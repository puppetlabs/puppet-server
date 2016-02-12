(ns puppetlabs.services.jruby.jruby-interpreter-test
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.jruby-puppet-internal :as jruby-internal]
            [puppetlabs.puppetserver.testutils :as testutils]
            [puppetlabs.kitchensink.core :as ks]))

(use-fixtures :once
              (testutils/with-puppet-conf
                "./dev-resources/puppetlabs/services/jruby/jruby_interpreter_test/puppet.conf"))

(deftest create-jruby-instance-test

  (testing "Var dir is not required (it will be read from puppet.conf)"
    (let [vardir (-> (jruby-testutils/jruby-puppet-config)
                     (assoc :master-var-dir nil)
                     (jruby-testutils/create-pool-instance)
                     (:jruby-puppet)
                     (.getSetting "vardir"))]
      (is (= (ks/absolute-path "target/master-var-jruby-int-test") vardir))))

  (testing "Directories can be configured programatically
            (and take precedence over puppet.conf)"
    (let [puppet (-> (jruby-testutils/jruby-puppet-config
                       {:ruby-load-path  jruby-testutils/ruby-load-path
                        :gem-home        jruby-testutils/gem-home
                        :master-conf-dir jruby-testutils/conf-dir
                        :master-code-dir jruby-testutils/code-dir
                        :master-var-dir  jruby-testutils/var-dir
                        :master-run-dir  jruby-testutils/run-dir
                        :master-log-dir  jruby-testutils/log-dir})
                     (jruby-testutils/create-pool-instance)
                     (:jruby-puppet))]
      (are [setting expected] (= (-> expected
                                     (ks/normalized-path)
                                     (ks/absolute-path))
                                 (.getSetting puppet setting))
           "confdir" jruby-testutils/conf-dir
           "codedir" jruby-testutils/code-dir
           "vardir" jruby-testutils/var-dir
           "rundir" jruby-testutils/run-dir
           "logdir" jruby-testutils/log-dir)))

  (testing "Settings from Ruby Puppet are available"
    (let [jruby-puppet (-> (jruby-testutils/jruby-puppet-config)
                           (jruby-testutils/create-pool-instance)
                           (:jruby-puppet))]
      (testing "Various data types"
        (is (= "0.0.0.0" (.getSetting jruby-puppet "bindaddress")))
        (is (= 8140 (.getSetting jruby-puppet "masterport")))
        (is (= false (.getSetting jruby-puppet "onetime")))))))

(deftest jruby-env-vars
  (testing "the environment used by the JRuby interpreters"
    (let [jruby-interpreter (jruby-internal/create-scripting-container
                              jruby-testutils/ruby-load-path
                              jruby-testutils/gem-home
                              jruby-testutils/compile-mode)
          jruby-env (.runScriptlet jruby-interpreter "ENV")]

      ; $HOME and $PATH are left in by `jruby-puppet-env`
      (is (= #{"HOME" "PATH" "GEM_HOME" "JARS_NO_REQUIRE" "JARS_REQUIRE"}
            (set (keys jruby-env)))))))
