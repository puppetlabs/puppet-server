(ns puppetlabs.master.services.ca.certificate-authority-core
  (:require [puppetlabs.master.certificate-authority :as ca]
            [puppetlabs.master.ringutils :as ringutils]
            [schema.core :as schema]
            [compojure.core :as compojure]
            [ring.util.response :as rr]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; 'Handler' functions for HTTP endpoints

(defn handle-get-certificate
  [subject {:keys [cacert certdir]}]
  (-> (if-let [certificate (ca/get-certificate subject cacert certdir)]
        (rr/response certificate)
        (rr/not-found (str "Could not find certificate " subject)))
      (rr/content-type "text/plain")))

(defn handle-get-certificate-request
  [subject {:keys [csrdir]}]
  (-> (if-let [certificate-request (ca/get-certificate-request subject csrdir)]
        (rr/response certificate-request)
        (rr/not-found (str "Could not find certificate_request " subject)))
      (rr/content-type "text/plain")))

(defn signed-csr-response-body
  [expiration-date subject]
  ;; TODO return something proper (PE-3178)
  (str "---\n"
       "  - !ruby/object:Puppet::SSL::CertificateRequest\n"
       "name: " subject "\n"
       "content: !ruby/object:OpenSSL::X509::Request {}\n"
       "expiration: " expiration-date))

(schema/defn handle-put-certificate-request!
  [subject
   certificate-request
   ca-settings :- ca/CaSettings]
  (if (ca/autosign-csr? (:autosign ca-settings))
    (-> (ca/autosign-certificate-request! subject certificate-request ca-settings)
        (signed-csr-response-body subject)
        (rr/response)
        (rr/content-type "text/yaml"))
    (do (ca/save-certificate-request! subject certificate-request (:csrdir ca-settings))
        (rr/content-type (rr/response nil) "text/plain"))))

(defn handle-get-certificate-revocation-list
  [{:keys [cacrl]}]
  (-> (ca/get-certificate-revocation-list cacrl)
      (rr/response)
      (rr/content-type "text/plain")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Compojure app

(schema/defn routes
  [ca-settings :- ca/CaSettings]
  (compojure/context "/:environment" [environment]
    (compojure/routes
      (compojure/GET "/certificate/:subject" [subject]
        (handle-get-certificate subject ca-settings))
      (compojure/context "/certificate_request/:subject" [subject]
        (compojure/GET "/" []
          (handle-get-certificate-request subject ca-settings))
        (compojure/PUT "/" {body :body}
          (handle-put-certificate-request! subject body ca-settings)))
      (compojure/GET "/certificate_revocation_list/:ignored-node-name" []
        (handle-get-certificate-revocation-list ca-settings)))))

(schema/defn ^:always-validate
  compojure-app
  [ca-settings :- ca/CaSettings]
  (-> (routes ca-settings)
      (ringutils/wrap-response-logging)))
