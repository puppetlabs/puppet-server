(ns puppetlabs.master.services.config.jvm-puppet-config-service-test
  (:require [clojure.test :refer :all]
            [puppetlabs.master.services.protocols.jvm-puppet-config :refer :all]
            [puppetlabs.master.services.config.jvm-puppet-config-service :refer :all]
            [puppetlabs.master.services.config.jvm-puppet-config-core :as core]
            [puppetlabs.master.services.jruby.jruby-puppet-service :refer [jruby-puppet-pooled-service]]
            [puppetlabs.master.services.jruby.jruby-puppet-core :as jruby-puppet-core]
            [puppetlabs.master.services.jruby.testutils :as jruby-testutils]
            [puppetlabs.master.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.master.services.jruby.testutils :as jruby-testutils]))

(def service-and-deps
  [jvm-puppet-config-service jruby-puppet-pooled-service jetty9-service
   profiler/puppet-profiler-service])

(def required-config
  {:jruby-puppet  (jruby-testutils/jruby-puppet-config-with-prod-env)
   :webserver     {:port 8081}})

(deftest config-service-functions
  (tk-testutils/with-app-with-config
    app
    service-and-deps
    (assoc required-config :my-config {:foo "bar"})
    (testing "Basic jvm-puppet config service function usage"

      (let [service (tk-app/get-service app :JvmPuppetConfigService)
            service-config (get-config service)]

        (is (= (:jruby-puppet service-config)
               (jruby-testutils/jruby-puppet-config-with-prod-env 1)))
        (is (= (:webserver service-config) {:port 8081}))
        (is (= (:my-config service-config) {:foo "bar"}))
        (is (= (set (keys (:jvm-puppet service-config)))
               (conj core/puppet-config-keys :puppet-version))
            (str "config not as expected: " service-config))

        (testing "The config service has puppet's version available."
          (is (string? (get-in-config service [:jvm-puppet :puppet-version]))))

        (testing "`get-in-config` functions"

          (testing "get a value from TK's config"
            (is (= "bar" (get-in-config service [:my-config :foo]))))

          (testing "get a value from JRuby's config"
            (is (= "localhost" (get-in-config service [:jvm-puppet :certname])))))

        (testing "Default values passed to `get-in-config` are handled correctly"
          (is (= "default" (get-in-config service [:bogus] "default")))

          (testing "get a value from TK's config (w/ default specified)"
            (is (= "bar"
                   (get-in-config service [:my-config :foo]
                                  "default value, should not be returned"))))

          (testing "get a value from JRuby's config (w/ default specified"
            (is (= "localhost"
                   (get-in-config service [:jvm-puppet :certname]
                                  "default value, should not be returned")))))))))

(deftest config-key-conflicts
  (testing (str
             "Providing config values that should be read from Puppet results "
             "in an error that mentions all offending config keys.")
    (with-redefs
      [jruby-puppet-core/create-jruby-instance
         jruby-testutils/create-mock-jruby-instance]
      (with-test-logging
        (is (thrown-with-msg?
              Exception
              #".*configuration.*conflict.*cacrl.*cacert"
              (tk-testutils/with-app-with-config
                app service-and-deps (assoc required-config :cacrl "bogus"
                                                            :cacert "meow")
                ; do nothing - bootstrap should throw the exception
                )))))))
