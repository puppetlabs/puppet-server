(ns puppetlabs.services.ca.certificate-authority-core
  (:import  [java.io InputStream])
  (:require [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.puppetserver.liberator-utils :as utils]
            [slingshot.slingshot :as sling]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [schema.core :as schema]
            [cheshire.core :as cheshire]
            [compojure.core :as compojure :refer [GET ANY PUT]]
            [liberator.core :as liberator]
            [liberator.representation :as representation]
            [liberator.dev :as liberator-dev]
            [ring.util.response :as rr]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; 'handler' functions for HTTP endpoints

(defn handle-get-certificate
  [subject {:keys [cacert signeddir]}]
  (-> (if-let [certificate (ca/get-certificate subject cacert signeddir)]
        (rr/response certificate)
        (rr/not-found (str "Could not find certificate " subject)))
      (rr/content-type "text/plain")))

(defn handle-get-certificate-request
  [subject {:keys [csrdir]}]
  (-> (if-let [certificate-request (ca/get-certificate-request subject csrdir)]
        (rr/response certificate-request)
        (rr/not-found (str "Could not find certificate_request " subject)))
      (rr/content-type "text/plain")))

(schema/defn handle-put-certificate-request!
  [subject :- String
   certificate-request :- InputStream
   ca-settings :- ca/CaSettings]
  (sling/try+
    (ca/process-csr-submission! subject certificate-request ca-settings)
    (rr/content-type (rr/response nil) "text/plain")
    (catch ca/csr-validation-failure? {:keys [message]}
      (log/error message)
      ;; Respond to all CSR validation faliures with a 400
      (-> (rr/response message)
          (rr/status 400)
          (rr/content-type "text/plain")))))

(defn handle-get-certificate-revocation-list
  [{:keys [cacrl]}]
  (-> (ca/get-certificate-revocation-list cacrl)
      (rr/response)
      (rr/content-type "text/plain")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Compojure app

(defn try-to-parse
  [body]
  (try
    (cheshire/parse-stream (io/reader body) true)
    (catch Exception e
      (log/debug e))))

(defn malformed
  "Returns a value indicating to liberator that the request is malformed,
  with the given error message assoc'ed into the context."
  [message]
  [true {::malformed message}])

(defn conflict
  "Returns a value indicating to liberator that the request is is conflict
  with the server, with the given error message assoc'ed into the context."
  [message]
  [true {::conflict message}])

(defn get-desired-state
  [context]
  (keyword (get-in context [::json-body :desired_state])))

(defn invalid-state-requested?
  [context]
  (when (= :put (get-in context [:request :request-method]))
    (when-let [desired-state (get-desired-state context)]
      (not (contains? #{:signed :revoked} desired-state)))))

(liberator/defresource certificate-status
  [subject settings]
  :allowed-methods [:get :put :delete]

  :available-media-types ["application/json"]

  :can-put-to-missing? false

  :conflict?
  (fn [context]
    (let [desired-state (get-desired-state context)]
      (case desired-state
        :revoked
        ;; A signed cert must exist if we are to revoke it.
        (when-not (ca/certificate-exists? settings subject)
          (conflict (str "Cannot revoke certificate for host " subject
                         " - no certificate exists on disk.")))

        :signed
        (or
          ;; A CSR must exist if we are to sign it.
          (when-not (ca/csr-exists? settings subject)
            (conflict (str "Cannot sign certificate for host " subject
                           " - no certificate signing request exists on disk.")))

          ;; And the CSR must be valid.
          (when-let [error-message (ca/csr-invalid? settings subject)]
            (conflict error-message))))))

  :delete!
  (fn [context]
    (ca/delete-certificate! settings subject))

  :exists?
  (fn [context]
    (or
      (ca/certificate-exists? settings subject)
      (ca/csr-exists? settings subject)))

  :handle-conflict
  (fn [context]
    (::conflict context))

  :handle-exception utils/exception-handler

  :handle-not-implemented
  (fn [context]
    (when (= :put (get-in context [:request :request-method]))
      ; We've landed here because :exists? returned false, and we have set
      ; `:can-put-to-missing? false` above.  This happens when
      ; a PUT request comes in with an invalid hostname/subject specified in
      ; in the URL; liberator is pushing us towards a 501 here, but instead
      ; we want to return a 404.  There seems to be some disagreement as to
      ; which makes the most sense in general - see
      ; https://github.com/clojure-liberator/liberator/pull/120
      ; ... but in our case, a 404 definitely makes more sense.
      (-> "Invalid certificate subject."
          (representation/as-response context)
          (assoc :status 404)
          (representation/ring-response))))

  :handle-ok
  (fn [context]
    (ca/get-certificate-status settings subject))

  :malformed?
  (fn [context]
    (when (= :put (get-in context [:request :request-method]))
      (if-let [body (get-in context [:request :body])]
        (if-let [json-body (try-to-parse body)]
          (let [desired-state (keyword (:desired_state json-body))]
            (if (schema/check ca/DesiredCertificateState desired-state)
              (malformed
                (format
                  "State %s invalid; Must specify desired state of 'signed' or 'revoked' for host %s."
                  (name desired-state) subject))
              [false {::json-body json-body}]))
          (malformed "Request body is not JSON."))
        (malformed "Empty request body."))))

  :handle-malformed
  (fn [context]
    (if-let [message (::malformed context)]
      message
      "Bad Request."))

  ;; Never return a 201, we're not creating a new cert or anything like that.
  :new? false

  :put!
  (fn [context]
    (let [desired-state (get-desired-state context)]
      (ca/set-certificate-status! settings subject desired-state))))

(liberator/defresource certificate-statuses
  [settings]
  :allowed-methods [:get]

  :available-media-types ["application/json"]

  :handle-exception utils/exception-handler

  :handle-ok
  (fn [context]
    (ca/get-certificate-statuses settings)))

(schema/defn routes
  [ca-settings :- ca/CaSettings]
  (compojure/context "/:environment" [environment]
    (compojure/routes
      (ANY "/certificate_status/:subject" [subject]
        (certificate-status subject ca-settings))
      (ANY "/certificate_statuses/:ignored-but-required" [do-not-use]
        (certificate-statuses ca-settings)))
      (GET "/certificate/:subject" [subject]
        (handle-get-certificate subject ca-settings))
      (compojure/context "/certificate_request/:subject" [subject]
        (GET "/" []
          (handle-get-certificate-request subject ca-settings))
        (PUT "/" {body :body}
          (handle-put-certificate-request! subject body ca-settings)))
      (GET "/certificate_revocation_list/:ignored-node-name" []
        (handle-get-certificate-revocation-list ca-settings))))

(defn wrap-with-puppet-version-header
  "Function that returns a middleware that adds an
  X-Puppet-Version header to the response."
  [handler version]
  (fn [request]
    (let [response (handler request)]
      ; Our compojure app returns nil responses sometimes.
      ; In that case, don't add the header.
      (when response
        (rr/header response "X-Puppet-Version" version)))))

(schema/defn ^:always-validate
  compojure-app
  [ca-settings :- ca/CaSettings
   puppet-version :- schema/Str]
  (-> (routes ca-settings)
      ;(liberator-dev/wrap-trace :header)           ; very useful for debugging!
      (wrap-with-puppet-version-header puppet-version)
      (ringutils/wrap-response-logging)))
