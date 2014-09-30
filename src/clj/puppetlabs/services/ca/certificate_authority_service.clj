(ns puppetlabs.services.ca.certificate-authority-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.services.ca.certificate-authority-core :as core]
            [puppetlabs.services.protocols.ca :refer [CaService]]
            [compojure.core :as compojure]
            [me.raynes.fs :as fs]))

(tk/defservice certificate-authority-service
  CaService
  [[:PuppetServerConfigService get-config get-in-config]
   [:WebserverService add-ring-handler]]
  (init
   [this context]
   (let [path     ""
         settings (ca/config->ca-settings (get-config))
         puppet-version (get-in-config [:puppet-server :puppet-version])]
     (log/info "CA Service adding a ring handler")
     (add-ring-handler
      (compojure/context path [] (core/compojure-app settings puppet-version))
      path))
   context))
