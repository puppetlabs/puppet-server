(ns puppetlabs.services.ca.certificate-authority-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.services.ca.certificate-authority-core :as core]
            [puppetlabs.services.protocols.ca :refer [CaService]]
            [puppetlabs.comidi :as comidi]))

(tk/defservice certificate-authority-service
  CaService
  [[:PuppetServerConfigService get-config get-in-config]
   [:WebroutingService add-ring-handler get-route]
   [:AuthorizationService wrap-with-authorization-check]]
  (init
   [this context]
   (let [path           (get-route this)
         settings       (ca/config->ca-settings (get-config))
         puppet-version (get-in-config [:puppetserver :puppet-version])]
     (ca/validate-settings! settings)
     (ca/initialize! settings)
     (log/info "CA Service adding a ring handler")
     (add-ring-handler
       this
       (core/get-wrapped-handler
         (-> (core/web-routes settings)
             ((partial comidi/context path))
             comidi/routes->handler)
         settings
         path
         wrap-with-authorization-check
         puppet-version)))
   context)

  (initialize-master-ssl!
   [this master-settings certname]
   (let [settings (ca/config->ca-settings (get-config))]
     (ca/initialize-master-ssl! master-settings certname settings)))

  (retrieve-ca-cert!
    [this localcacert]
    (ca/retrieve-ca-cert! (get-in-config [:puppetserver :cacert])
                          localcacert))

  (retrieve-ca-crl!
    [this localcacrl]
    (ca/retrieve-ca-crl! (get-in-config [:puppetserver :cacrl])
                         localcacrl)))
