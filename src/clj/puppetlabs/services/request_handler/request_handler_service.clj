(ns puppetlabs.services.request-handler.request-handler-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.protocols.request-handler :as handler]
            [puppetlabs.services.request-handler.request-handler-core :as core]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [clojure.tools.logging :as log]))

(defn- handle-request
  [request jruby-service config]
  (try
    (jruby/with-jruby-puppet jruby-puppet jruby-service
                             (core/handle-request request jruby-puppet config))
    (catch IllegalStateException e
      (let [message (.getMessage e)]
        (log/error message)
        {:status  503
         :body    message
         :headers {"Content-Type" "text/plain"}}))))

(tk/defservice request-handler-service
               handler/RequestHandlerService
               [[:PuppetServerConfigService get-config]]
               (handle-request
                 [this request]
                 (let [jruby-service (tk-services/get-service this :JRubyPuppetService)
                       config (core/config->request-handler-settings (get-config))]
                   (handle-request request jruby-service config))))
