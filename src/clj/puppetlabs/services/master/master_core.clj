(ns puppetlabs.services.master.master-core
  (:import (java.io FileInputStream))
  (:require [compojure.core :as compojure]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetserver.ringutils :as ringutils]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Routing

(defn v2_0-routes
  "Creates the compojure routes to handle the master's '/v2.0' routes."
  [request-handler]
  (compojure/routes
    (compojure/GET "/environments" request
                   (request-handler request))))

(defn legacy-routes
  "Creates the compojure routes to handle the master's 'legacy' routes
   - ie, any route without a version in its path (eg, /v2.0/whatever) - but
   excluding the CA-related endpoints, which are handled separately by the
   CA service."
  [request-handler]
  (compojure/routes
    (compojure/GET "/node/*" request
                   (request-handler request))
    (compojure/GET "/facts/*" request
                   (request-handler request))
    (compojure/GET "/file_content/*" request
                   (request-handler request))
    (compojure/GET "/file_metadatas/*" request
                   (request-handler request))
    (compojure/GET "/file_metadata/*" request
                   (request-handler request))
    (compojure/GET "/file_bucket_file/*" request
                   (request-handler request))

    ;; TODO: file_bucket_file request PUTs from Puppet agents currently use a
    ;; Content-Type of 'text/plain', which, per HTTP specification, would imply
    ;; a default character encoding of ISO-8859-1 or US-ASCII be used to decode
    ;; the data.  This would be incorrect to do in this case, however, because
    ;; the actual payload is "binary".  Coercing this to
    ;; "application/octet-stream" for now as this is synonymous with "binary".
    ;; This should be removed when/if Puppet agents start using an appropriate
    ;; Content-Type to describe the input payload - see PUP-3812 for the core
    ;; Puppet work and SERVER-294 for the related Puppet Server work that
    ;; would be done.
    (compojure/PUT "/file_bucket_file/*" request
                   (request-handler (assoc request
                                           :content-type
                                           "application/octet-stream")))

    (compojure/HEAD "/file_bucket_file/*" request
                   (request-handler request))
    (compojure/GET "/catalog/*" request
                   (request-handler request))
    (compojure/POST "/catalog/*" request
                    (request-handler request))
    (compojure/PUT "/report/*" request
                   (request-handler request))
    (compojure/GET "/resource_type/*" request
                   (request-handler request))
    (compojure/GET "/resource_types/*" request
                   (request-handler request))

    ;; TODO: when we get rid of the legacy dashboard after 3.4, we should remove
    ;; this endpoint as well.  It makes more sense for this type of query to be
    ;; directed to PuppetDB.
    (compojure/GET "/facts_search/*" request
                   (request-handler request))))

(defn root-routes
  "Creates all of the compojure routes for the master."
  [request-handler]
  (compojure/routes
    (compojure/context "/v2.0" request
                       (v2_0-routes request-handler))
    (compojure/context "/:environment" [environment]
                       (legacy-routes request-handler))))

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

(defn build-ring-handler
  "Creates the entire compojure application (all routes and middleware)."
  [request-handler]
  {:pre [(fn? request-handler)]}
  (-> (root-routes request-handler)
      ringutils/wrap-exception-handling
      ringutils/wrap-request-logging
      ringutils/wrap-response-logging))
