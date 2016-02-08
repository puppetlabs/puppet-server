(ns puppetlabs.services.master.master-core
  (:import (java.io FileInputStream)
           (clojure.lang IFn)
           (java.util Map))
  (:require [me.raynes.fs :as fs]
            [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.comidi :as comidi]
            [ring.middleware.params :as ring]
            [ring.util.response :as rr]
            [schema.core :as schema]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.puppetserver.jruby-request :as jruby-request]
            [puppetlabs.services.request-handler.request-handler-core :as request-core]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def puppet-API-version
  "v3")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(def EnvironmentClassesFileEntry
  "Schema for an individual file entry which is part of the return payload
  for an environment_classes request"
  {:path schema/Str
   :classes [Map]})

(def EnvironmentClassesInfo
  "Schema for the return payload an environment_classes request"
  {:name schema/Str
   :files [EnvironmentClassesFileEntry]})

(schema/defn ^:always-validate
  class-info-from-jruby->class-info-for-json :- EnvironmentClassesInfo
  "Convert a class info map received from the jruby service into an
  appropriate map for use in serializing an environment_classes response to
  JSON"
  [info-from-jruby :- Map
   environment :- schema/Str]
  (->> info-from-jruby
       (map #(hash-map :path (key %) :classes (val %)))
       (sort-by :path)
       (vec)
       (hash-map :name environment :files)))

(schema/defn ^:always-validate
  environment-class-info-fn :- IFn
  "Middleware function for constructing a Ring response from an incoming
  request for environment_classes information"
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)]
  (fn [request]
    (if-let [environment (jruby-request/get-environment-from-request request)]
      (if (re-matches #"^\w+$" environment)
        (if-let [class-info
                 (jruby-protocol/get-environment-class-info jruby-service
                                                            (:jruby-instance
                                                             request)
                                                            environment)]
          (-> class-info
              (class-info-from-jruby->class-info-for-json environment)
              (ringutils/json-response))
          (rr/not-found (str "Could not find environment '" environment "'")))
        (jruby-request/throw-bad-request!
         (str
          "The environment must be purely alphanumeric, not '"
          environment
          "'")))
      (jruby-request/throw-bad-request!
       "An environment parameter must be specified"))))

(schema/defn ^:always-validate
  environment-class-handler :- IFn
  "Handler for processing an incoming environment_classes Ring request"
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)]
  (->
   (environment-class-info-fn jruby-service)
   (jruby-request/wrap-with-jruby-instance jruby-service)
   jruby-request/wrap-with-error-handling
   ring/wrap-params))

(defn static-file-content-request-handler
  "Returns a function which is the main request handler for the
  /static_file_content endpoint, utilizing the provided implementation of
  `get-code-content`"
  [get-code-content]
  (fn [req]
    (let [environment (get-in req [:params "environment"])
          code-id (get-in req [:params "code_id"])
          file-path (get-in req [:params :rest])]
      (if (or (nil? environment)
              (nil? code-id)
              (or (nil? file-path) (= "" file-path)))
        {:status 400
         :body "Error: A /static_file_content request requires an environment, a code-id, and a file-path"}
        {:status 200
         :body   (get-code-content environment code-id file-path)}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Routing

(defn v3-routes
  "Creates the routes to handle the master's '/v3' routes, which
   includes '/environments' and the non-CA indirected routes. The CA-related
   endpoints are handled separately by the CA service."
  [request-handler jruby-service get-code-content-fn]
  (let [environment-class-handler (environment-class-handler jruby-service)
        static-file-content-handler (static-file-content-request-handler get-code-content-fn)]
    (comidi/routes
     (comidi/GET ["/node/" [#".*" :rest]] request
                 (request-handler request))
     (comidi/GET ["/file_content/" [#".*" :rest]] request
                 (request-handler request))
     (comidi/GET ["/file_metadatas/" [#".*" :rest]] request
                 (request-handler request))
     (comidi/GET ["/file_metadata/" [#".*" :rest]] request
                 (request-handler request))
     (comidi/GET ["/file_bucket_file/" [#".*" :rest]] request
                 (request-handler request))
     (comidi/PUT ["/file_bucket_file/" [#".*" :rest]] request
                 (request-handler request))
     (comidi/HEAD ["/file_bucket_file/" [#".*" :rest]] request
                  (request-handler request))

     (comidi/GET ["/catalog/" [#".*" :rest]] request
                 (request-handler (assoc request :include-code-id? true)))
     (comidi/POST ["/catalog/" [#".*" :rest]] request
                  (request-handler (assoc request :include-code-id? true)))
     (comidi/PUT ["/report/" [#".*" :rest]] request
                 (request-handler request))
     (comidi/GET ["/resource_type/" [#".*" :rest]] request
                 (request-handler request))
     (comidi/GET ["/resource_types/" [#".*" :rest]] request
                 (request-handler request))
     (comidi/GET ["/environment/" [#".*" :rest]] request
                 (request-handler request))
     (comidi/GET "/environments" request
                 (request-handler request))
     (comidi/GET ["/status/" [#".*" :rest]] request
                 (request-handler request))

     (comidi/GET ["/environment_classes" [#".*" :rest]] request
                 (environment-class-handler request))
     (comidi/GET ["/static_file_content/" [#".*" :rest]] request
                 (static-file-content-handler request)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Lifecycle Helper Functions

(defn meminfo-content
  "Read and return the contents of /proc/meminfo, if it exists.  Otherwise
  return nil."
  []
  (when (fs/exists? "/proc/meminfo")
    ; Due to OpenJDK Issue JDK-7132461
    ; (https://bugs.openjdk.java.net/browse/JDK-7132461),
    ; we have to open and slurp a FileInputStream object rather than
    ; slurping the file directly, since directly slurping the file
    ; causes a call to be made to FileInputStream.available().
    (with-open [mem-info-file (FileInputStream. "/proc/meminfo")]
      (slurp mem-info-file))))

; the current max java heap size (-Xmx) in kB defined for with-redefs in tests
(def max-heap-size (/ (.maxMemory (Runtime/getRuntime)) 1024))

(defn validate-memory-requirements!
  "On Linux Distributions, parses the /proc/meminfo file to determine
   the total amount of System RAM, and throws an exception if that
   is less than 1.1 times the maximum heap size of the JVM. This is done
   so that the JVM doesn't fail later due to an Out of Memory error."
  []
  (when-let [meminfo-file-content (meminfo-content)]
    (let [heap-size max-heap-size
          mem-size (Integer. (second (re-find #"MemTotal:\s+(\d+)\s+\S+"
                                               meminfo-file-content)))
          required-mem-size (* heap-size 1.1)]
      (when (< mem-size required-mem-size)
        (throw (Error.
                 (str "Not enough available RAM (" (int (/ mem-size 1024.0))
                      "MB) to safely accommodate the configured JVM heap "
                      "size of " (int (/ heap-size 1024.0)) "MB.  "
                      "Puppet Server requires at least "
                      (int (/ required-mem-size 1024.0))
                      "MB of available RAM given this heap size, computed as "
                      "1.1 * max heap (-Xmx).  Either increase available "
                      "memory or decrease the configured heap size by "
                      "reducing the -Xms and -Xmx values in JAVA_ARGS in "
                      "/etc/sysconfig/puppetserver on EL systems or "
                      "/etc/default/puppetserver on Debian systems.")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn root-routes
  "Creates all of the web routes for the master."
  [request-handler jruby-service get-code-content-fn]
  (comidi/routes
    (comidi/context "/v3"
                    (v3-routes request-handler
                               jruby-service
                               get-code-content-fn))))

(schema/defn ^:always-validate
  wrap-middleware :- IFn
  [handler :- IFn
   puppet-version :- schema/Str]
  (-> handler
      ringutils/wrap-exception-handling
      ringutils/wrap-request-logging
      ringutils/wrap-response-logging
      (ringutils/wrap-with-puppet-version-header puppet-version)))

(schema/defn ^:always-validate get-master-route-config
  "Get the webserver route configuration for the master service"
  [master-ns :- schema/Keyword
   config :- {schema/Keyword schema/Any}]
  (get-in config [:web-router-service master-ns]))

(schema/defn ^:always-validate
  get-master-mount :- schema/Str
  "Get the webserver mount point that the master service is rooted under"
  [master-ns :- schema/Keyword
   config-route]
  (cond
    ;; if the route config is a map, we need to determine whether it's the
    ;; new-style multi-server config (where there will be a `:route` key and a
    ;; `:server`, key), or the old style where there is a single key that is
    ;; assumed to be our hard-coded route id (`:master-routes`).
    ;; It should be possible to delete this hack (perhaps this entire function)
    ;; when we remove support for legacy routes.
    (and (map? config-route) (or (contains? config-route :route)
                                 (contains? config-route :master-routes)))
    (or (:route config-route)
        (:master-routes config-route))

    (string? config-route)
    config-route

    :else
    (throw (IllegalArgumentException.
             (str "Route not found for service " master-ns)))))

(schema/defn ^:always-validate
  get-wrapped-handler :- IFn
  [route-handler :- IFn
   authorization-fn :- IFn
   use-legacy-auth-conf :- schema/Bool
   puppet-version :- schema/Str]
  (let [handler-maybe-with-authorization (if use-legacy-auth-conf
                                           route-handler
                                           (authorization-fn route-handler))]
    (wrap-middleware handler-maybe-with-authorization puppet-version)))
