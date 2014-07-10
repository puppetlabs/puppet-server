(ns puppetlabs.master.services.ca.certificate-authority-core-test
  (:require [puppetlabs.master.services.ca.certificate-authority-core :refer :all]
            [puppetlabs.master.certificate-authority :as ca]
            [puppetlabs.kitchensink.core :as ks]
            [me.raynes.fs :as fs]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

(deftest crl-endpoint-test
  (testing "implementation of the CRL endpoint"
    (let [response (handle-get-certificate-revocation-list
                     {:cacrl "./dev-resources/config/master/conf/ssl/crl.pem"})]
      (is (map? response))
      (is (= 200 (:status response)))
      (is (= "text/plain" (get-in response [:headers "Content-Type"])))
      (is (string? (:body response))))))

(deftest handle-put-certificate-request!-test
  (let [cadir     "./dev-resources/config/master/conf/ssl/ca"
        signeddir (str cadir "/signed")
        csrdir    (str cadir "/requests")
        serial-number-file (str "/tmp/serial-number" (ks/uuid))
        settings  {:ca-name   "some CA"
                   :cacert    (str cadir "/ca_crt.pem")
                   :cacrl     (str cadir "/ca_crl.pem")
                   :cakey     (str cadir "/ca_key.pem")
                   :capub     (str cadir "/ca_pub.pem")
                   :signeddir signeddir
                   :csrdir    csrdir
                   :ca-ttl    100
                   :serial    serial-number-file
                   :load-path ["ruby/puppet/lib" "ruby/facter/lib"]}
        csr-path  (ca/path-to-cert-request csrdir "test-agent")]

    (testing "when autosign results in true"
      (doseq [value [true
                     "dev-resources/config/master/conf/ruby-autosign-executable"
                     "dev-resources/config/master/conf/autosign-whitelist.conf"]]
        (let [settings      (assoc settings :autosign value)
              csr-stream    (io/input-stream csr-path)
              expected-path (ca/path-to-cert signeddir "test-agent")]

          (testing "it signs the CSR, writes the certificate to disk, and
                    returns a 200 response with empty plaintext body"
            (try
              (is (false? (fs/exists? expected-path)))
              (let [response (handle-put-certificate-request! "test-agent" csr-stream settings)]
                (is (true? (fs/exists? expected-path)))
                (is (= 200 (:status response)))
                (is (= "text/plain" (get-in response [:headers "Content-Type"])))
                (is (nil? (:body response))))
              (finally
                (fs/delete serial-number-file)
                (fs/delete expected-path)))))))

    (testing "when autosign results in false"
      (doseq [value [false
                     "dev-resources/config/master/conf/ruby-autosign-executable"
                     "dev-resources/config/master/conf/autosign-whitelist.conf"]]
        (let [settings      (assoc settings :autosign value)
              csr-stream    (io/input-stream csr-path)
              expected-path (ca/path-to-cert-request csrdir "foo-agent")]

          (testing "it writes the CSR to disk and returns a
                    200 response with empty plaintext body"
            (try
              (is (false? (fs/exists? expected-path)))
              (let [response (handle-put-certificate-request! "foo-agent" csr-stream settings)]
                (is (true? (fs/exists? expected-path)))
                (is (false? (fs/exists? (ca/path-to-cert signeddir "foo-agent"))))
                (is (= 200 (:status response)))
                (is (= "text/plain" (get-in response [:headers "Content-Type"])))
                (is (nil? (:body response))))
              (finally
                (fs/delete serial-number-file)
                (fs/delete expected-path)))))))))
