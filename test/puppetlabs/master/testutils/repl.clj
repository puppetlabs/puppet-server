(ns puppetlabs.master.testutils.repl
  (:require [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.master.services.master.master-service :refer [master-service]]
            [puppetlabs.master.services.handler.request-handler-service :refer [request-handler-service]]
            [puppetlabs.master.services.jruby.jruby-puppet-service :refer [jruby-puppet-pooled-service]]
            [puppetlabs.master.services.config.jvm-puppet-config-service :refer [jvm-puppet-config-service]]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tka]
            [clojure.tools.namespace.repl :refer (refresh)]))

(def system nil)

(defn init []
  (alter-var-root #'system
    (fn [_] (let [conf-dir "./scratch/master/conf"
                   app (tk/build-app
                        [jetty9-service
                         master-service
                         jruby-puppet-pooled-service
                         request-handler-service
                         jvm-puppet-config-service]
                        {:global {:logging-config "./test-resources/logback-dev.xml"}
                         :jruby-puppet { :jruby-pools  [{:environment "production"
                                                         :size 1}
                                                        {:environment "test"
                                                         :size 1}]
                                         :load-path    ["./ruby/puppet/lib"
                                                        "./ruby/facter/lib"]
                                         :master-conf-dir conf-dir}
                         :webserver {:client-auth "want"
                                     :ssl-host    "localhost"
                                     :ssl-port    8140}})]
              (tka/init app)))))

(defn start []
  (alter-var-root #'system tka/start))

(defn stop []
  (alter-var-root #'system
                  (fn [s] (when s (tka/stop s)))))

(defn go []
  (init)
  (start))

(defn context []
  @(tka/app-context system))

(defn print-context []
  (clojure.pprint/pprint (context)))

(defn reset []
  (stop)
  (refresh :after 'puppetlabs.enterprise.master.testutils.repl/go))