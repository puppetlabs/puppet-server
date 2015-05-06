(ns puppetlabs.services.master.master-service
  (:require [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.services.master.master-core :as core]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]))

(defservice master-service
  [[:WebroutingService add-ring-handler get-route]
   [:PuppetServerConfigService get-config]
   [:RequestHandlerService handle-request]
   [:CaService initialize-master-ssl! retrieve-ca-cert!]
   [:StatusService register-status]]
  (init
   [this context]
   (core/validate-memory-requirements!)
   (let [path           (get-route this)
         config         (get-config)
         certname       (get-in config [:puppet-server :certname])
         localcacert    (get-in config [:puppet-server :localcacert])
         puppet-version (get-in config [:puppet-server :puppet-version])
         settings       (ca/config->master-settings config)]

     (retrieve-ca-cert! localcacert)
     (initialize-master-ssl! settings certname)

     (log/info "Master Service adding a ring handler")
     (add-ring-handler
       this
      (compojure/context path [] (core/build-ring-handler handle-request puppet-version)))
     (register-status "puppet-server"
                      (status-core/get-service-version "puppetlabs" "puppetserver")
                      1
                      core/v1-status-callback))
   context)
  (start
    [this context]
    (log/info "Puppet Server has successfully started and is now ready to handle requests")
    context))
