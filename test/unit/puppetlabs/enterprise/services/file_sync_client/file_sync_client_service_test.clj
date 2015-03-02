(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-service-test
  (:import (javax.net.ssl SSLHandshakeException)
           (java.net URL)
           (org.eclipse.jgit.transport HttpTransport)
           (org.eclipse.jgit.transport.http JDKHttpConnectionFactory)
           (com.puppetlabs.enterprise HttpClientConnection))
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-utils :as client-utils]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core :as core]
            [puppetlabs.http.client.common :as http-client]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]))

(def file-sync-client-ssl-config
  {:poll-interval    1
   :server-url       "https://localhost:10080/"
   :repos            {:fake "fake"}
   :server-api-path  helpers/default-api-path-prefix
   :server-repo-path helpers/default-repo-path-prefix
   :ssl-ca-cert      "./dev-resources/ssl/ca.pem"
   :ssl-cert         "./dev-resources/ssl/cert.pem"
   :ssl-key          "./dev-resources/ssl/key.pem"})

; As it stands right now, there's no way to directly access the worker loop for the client service
; from outside the code, so the only way to test it right now is to use with-redefs to redefine
; a function that it calls. However, if the service fails before it calls that function (say, if
; the configuration is invalid), no test failures or errors will occur. This atom is here
; to ensure that test failures occur if the client service never calls the functions being
; redefined below.
(def polling-test-success
  (atom {}))

(defn mock-process-repos-for-updates
  [client request-url _ _]
  (let [response (http-client/get client request-url {:as :text})]
    (is (= 200 (:status response)))
    (is (= "Successful connection over SSL" (:body response)))
    (swap! polling-test-success assoc :poll-success true)))

(defn mock-process-repos-for-updates-SSL-failure
  [client request-url _ _]
  (is (thrown? SSLHandshakeException
               (http-client/get client request-url)))
  (swap! polling-test-success assoc :poll-fail true))

(defn ring-handler
  [_]
  {:status 200
   :body "Successful connection over SSL"})

(deftest polling-client-ssl-test
  (testing "polling client will use SSL when configured"
    (logging/with-test-logging
      (with-redefs
        [core/process-repos-for-updates mock-process-repos-for-updates]
        (client-utils/with-boostrapped-file-sync-client-and-webserver
          (helpers/webserver-ssl-config)
          ring-handler
          file-sync-client-ssl-config
          (Thread/sleep 500)
          (is (:poll-success @polling-test-success))))))

  (testing "polling client fails to use SSL when not configured"
    (logging/with-test-logging
      (with-redefs
        [core/process-repos-for-updates mock-process-repos-for-updates-SSL-failure]
        (client-utils/with-boostrapped-file-sync-client-and-webserver
          (helpers/webserver-ssl-config)
          ring-handler
          (dissoc file-sync-client-ssl-config :ssl-ca-cert :ssl-cert :ssl-key)
          (Thread/sleep 500)
          (is (:poll-fail @polling-test-success))))))

  (testing "SSL configuration fails when not all options are provided"
    (logging/with-test-logging
      (is (thrown? IllegalArgumentException
                   (client-utils/with-boostrapped-file-sync-client-and-webserver
                     (helpers/webserver-ssl-config)
                     ring-handler
                     (dissoc file-sync-client-ssl-config :ssl-ca-cert)))))))

(deftest jgit-client-ssl-configuration-test
  (testing "client service configures a connection factory that produces the proper type of connection"
    (HttpTransport/setConnectionFactory (JDKHttpConnectionFactory.))
    (client-utils/with-boostrapped-file-sync-client-and-webserver
      (helpers/webserver-ssl-config)
      ring-handler
      file-sync-client-ssl-config
      (let [connection-factory (HttpTransport/getConnectionFactory)
            connection (.create connection-factory (URL. "https://localhost:10080"))]
        (is (instance? HttpClientConnection connection))))))
