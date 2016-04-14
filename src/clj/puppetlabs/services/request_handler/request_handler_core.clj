(ns puppetlabs.services.request-handler.request-handler-core
  (:import (java.util HashMap)
           (java.io StringReader)
           (com.puppetlabs.puppetserver JRubyPuppetResponse))
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [puppetlabs.puppetserver.common :as ps-common]
            [ring.util.codec :as ring-codec]
            [puppetlabs.trapperkeeper.authorization.ring :as ring-auth]
            [puppetlabs.puppetserver.ring.middleware.params :as pl-ring-params]
            [puppetlabs.puppetserver.jruby-request :as jruby-request]
            [schema.core :as schema]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(def header-client-cert-name
  "Name of the HTTP header through which a client certificate can be passed
  for a request"
  "x-client-cert")

(defn unmunge-http-header-name
  [setting]
  "Given the value of a Puppet setting which contains a munged HTTP header name,
  convert it to the actual header name in all lower-case."
  (->> (string/split setting #"_")
       rest
       (string/join "-")
       string/lower-case))

(defn config->request-handler-settings
  "Given an entire Puppet Server configuration map, return only those keys
  which are required by the request handler service."
  [{:keys [puppetserver master]}]
  {:allow-header-cert-info   (true? (:allow-header-cert-info master))
   :ssl-client-verify-header (unmunge-http-header-name
                               (:ssl-client-verify-header puppetserver))
   :ssl-client-header        (unmunge-http-header-name
                               (:ssl-client-header puppetserver))})

(defn response->map
  "Converts a JRubyPuppetResponse instance to a map."
  [response]
  { :pre [(instance? JRubyPuppetResponse response)]
    :post [(map? %)] }
    { :status  (.getStatus response)
      :body    (.getBody response)
      :headers {"Content-Type"     (.getContentType response)
                "X-Puppet-Version" (.getPuppetVersion response)}})

(defn body-for-jruby
  "Converts the body from a request into a String if it is a non-binary
   content type.  Otherwise, just returns back the same body InputStream.
   Non-binary request bodies are coerced per the appropriate encoding at
   the Clojure layer.  Binary request bodies, however, need to be preserved
   in the originating InputStream so that they can be converted at the Ruby
   layer, where the raw bytes within the stream can be converted losslessly
   to a Ruby ASCII-8BIT encoded String.  Java has no equivalent to ASCII-8BIT
   for its Strings."
  [request]
  (let [body         (:body request)
        content-type (if-let [raw-type (:content-type request)]
                       (string/lower-case raw-type))]
    (case content-type
      (nil "" "application/octet-stream") body
      ; Treatment of the *default* encoding arguably should be much more
      ; intelligent than just choosing UTF-8.  Basing the default on the
      ; Content-Type would be an improvement although even this could lead to
      ; some ambiguities.  For "text/*" Content-Types, for example,
      ; different RFCs specified that either US-ASCII or ISO-8859-1 could
      ; be applied - see https://tools.ietf.org/html/rfc6657.  Ideally, this
      ; should be filled in with a broader list of the different Content-Types
      ; that Puppet recognizes and the default encodings to use when typical
      ; Puppet requests do not specify a corresponding charset.
      (slurp body :encoding (or (:character-encoding request)
                                "UTF-8")))))

(defn wrap-params-for-jruby
  "Pull parameters from the URL query string and/or urlencoded form POST
   body into the ring request map.  Includes some special processing for
   a request destined for JRubyPuppet."
  [request]
  (let [body-for-jruby (body-for-jruby request)]
    (-> request
        (assoc :body body-for-jruby)
        pl-ring-params/params-request)))

(def unauthenticated-client-info
  "Return a map with default info for an unauthenticated client"
  {:client-cert-cn nil
   :authenticated  false})

(defn header-auth-info
  "Return a map with authentication info based on header content"
  [header-dn-name header-dn-val header-auth-name header-auth-val]
  (if (ssl-utils/valid-x500-name? header-dn-val)
    (do
      (let [cn (ssl-utils/x500-name->CN header-dn-val)
            authenticated (= "SUCCESS" header-auth-val)]
        (log/debugf "CN '%s' provided by HTTP header '%s'"
                    cn header-dn-val)
        (log/debugf (str "Verification of client '%s' provided by HTTP "
                         "header '%s': '%s'.  Authenticated: %s.")
                    cn
                    header-auth-name
                    header-auth-val
                    authenticated)
        {:client-cert-cn cn
         :authenticated authenticated}))
    (do
      (if (nil? header-dn-val)
        (log/debugf (str "No DN provided by the HTTP header '%s'.  Treating "
                         "client as unauthenticated.") header-dn-name)
        (log/errorf (str "DN '%s' provided by the HTTP header '%s' is "
                         "malformed.  Treating client as unauthenticated.")
                    header-dn-val header-dn-name))
      unauthenticated-client-info)))

(defn header-cert->pem
  "Convert the header cert value into a PEM string"
  [header-cert]
  (try
    (ring-codec/url-decode header-cert)
    (catch Exception e
      (jruby-request/throw-bad-request!
        (str "Unable to URL decode the "
             header-client-cert-name
             " header: "
             (.getMessage e))))))

(defn pem->certs
  "Convert a pem string into certificate objects"
  [pem]
  (with-open [reader (StringReader. pem)]
    (try
      (ssl-utils/pem->certs reader)
      (catch Exception e
        (jruby-request/throw-bad-request!
          (str "Unable to parse "
               header-client-cert-name
               " into certificate: "
               (.getMessage e)))))))

(defn header-cert
  "Return an X509Certificate or nil from a string encoded for transmission
  in an HTTP header."
  [header-cert-val]
  (if header-cert-val
    (let [pem        (header-cert->pem header-cert-val)
          certs      (pem->certs pem)
          cert-count (count certs)]
      (condp = cert-count
        0 (jruby-request/throw-bad-request!
            (str "No certs found in PEM read from " header-client-cert-name))
        1 (first certs)
        (jruby-request/throw-bad-request!
          (str "Only 1 PEM should be supplied for "
               header-client-cert-name
               " but "
               cert-count
               " found"))))))

(defn ssl-auth-info
  "Get map of client authentication info from the supplied
   `java.security.cert.X509Certificate` object.  If the supplied object is nil,
   the information returned would represent an 'unauthenticated' client."
  [ssl-client-cert]
  (if ssl-client-cert
    (let [cn (ssl-utils/get-cn-from-x509-certificate ssl-client-cert)
          authenticated (not (empty? cn))]
      (log/debugf "CN '%s' provided by SSL certificate.  Authenticated: %s."
                  cn authenticated)
      {:client-cert-cn cn
       :authenticated  authenticated})
    (do
      (log/debugf "No SSL client certificate provided. "
                  "Treating client as unauthenticated.")
      unauthenticated-client-info)))

(defn client-auth-info
  "Get map of client authentication info for the client.  Map has the following
  keys:

  * :client-cert - A `java.security.cert.X509Certificate` object or nil
  * :client-cert-cn - The CN (Common Name) of the client, typically associated
                      with the CN attribute from the Distinguished Name
                      in an X.509 certificate's Subject.
  * :authenticated - A boolean representing whether or not the client is
                     considered to have been successfully authenticated.

  Parameters:

  * config - Map of configuration data
  * request - Ring request containing client data"
  [config request]
  (if-let [authorization (:authorization request)]
    {:client-cert    (ring-auth/authorized-certificate request)
     :client-cert-cn (ring-auth/authorized-name request)
     :authenticated  (true? (ring-auth/authorized-authenticated request))}
    (let [headers (:headers request)
          header-dn-name (:ssl-client-header config)
          header-dn-val (get headers header-dn-name)
          header-auth-name (:ssl-client-verify-header config)
          header-auth-val (get headers header-auth-name)
          header-cert-val (get headers header-client-cert-name)]
      (if (:allow-header-cert-info config)
        (-> (header-auth-info header-dn-name
                              header-dn-val
                              header-auth-name
                              header-auth-val)
            (assoc :client-cert (header-cert header-cert-val)))
        (do
          (doseq [[header-name header-val]
                  {header-dn-name header-dn-val
                   header-auth-name header-auth-val
                   header-client-cert-name header-cert-val}
                  :when header-val]
            (log/warn "The HTTP header" header-name "was specified,"
                      "but the master config option allow-header-cert-info"
                      "was either not set, or was set to false."
                      "This header will be ignored."))
          (let [ssl-cert (:ssl-client-cert request)]
            (-> (ssl-auth-info ssl-cert)
                (assoc :client-cert ssl-cert))))))))

(defn as-jruby-request
  "Given a ring HTTP request, return a new map that contains all of the data
   needed by the ruby HTTP layer to process it.  This function does a couple
   things that are a bit weird:
      * It reads the entire request body into memory.  This is not ideal for
        performance and memory usage, but we have to ship this thing over to
        JRuby, so I don't think there's any way around this.
      * It also extracts the client DN and certificate and includes that
        in the map it returns, because it's needed by the ruby layer.  It is
        possible that the HTTPS termination has happened external to Puppet
        Server.  If so, then the DN, authentication status, and, optionally, the
        certificate will be provided by HTTP headers."
  [config request]
  (merge
    {:uri            (:uri request)
     :params         (:params request)
     :remote-addr    (:remote-addr request)
     :headers        (:headers request)
     :body           (:body request)
     :request-method (-> (:request-method request)
                         name
                         string/upper-case)}
    (client-auth-info config request)))

(defn make-request-mutable
  [request]
  "Make the request mutable.  This is required by the ruby layer."
  (HashMap. request))

(defn with-code-id
  "Wraps the given request with the current-code-id, if it contains a
  :include-code-id? key with a truthy value.  current-code-id is passed the
  environment from the request from it is invoked."
  [current-code-id request]
  (if (:include-code-id? request)
    (let [env (jruby-request/get-environment-from-request request)]
      (when-not (nil? (schema/check ps-common/Environment env))
        (jruby-request/throw-bad-request! (ps-common/environment-validation-error-msg env)))
      (when-not env
        (jruby-request/throw-bad-request! "Environment is required in a catalog request."))
      (assoc-in request [:params "code_id"] (current-code-id env)))
    request))

(defn jruby-request-handler
  "Build a request handler fn that processes a request using a JRubyPuppet instance"
  [config current-code-id]
  (fn [request]
    (->> request
         wrap-params-for-jruby
         (with-code-id current-code-id)
         (as-jruby-request config)
         clojure.walk/stringify-keys
         make-request-mutable
         (.handleRequest (:jruby-instance request))
         response->map)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn build-request-handler
  "Build the main request handler fn for JRuby requests."
  [jruby-service config current-code-id]
  (-> (jruby-request-handler config current-code-id)
      (jruby-request/wrap-with-jruby-instance jruby-service)
      jruby-request/wrap-with-error-handling))
