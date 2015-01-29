(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-service
  (:import (org.eclipse.jgit.http.server GitServlet))
  (:require [clojure.tools.logging :as log]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core
              :as core]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :as tk-services]))

(tk/defservice file-sync-client-service
               [[:ConfigService get-in-config]
                [:ShutdownService shutdown-on-error]]

  (start [this context]
    (log/info "Starting file sync client service")
    (let [config               (get-in-config
                                 [:file-sync-client])
          shutdown-requested?  (promise)]
      (future
        (shutdown-on-error
          (tk-services/service-id this)
          #(core/start-worker config shutdown-requested?)))
      (assoc context :file-sync-client-shutdown-requested?
                     shutdown-requested?)))

  (stop [this context]
    (log/info "Stopping file sync client service")
    (if-let [shutdown-requested?
             (:file-sync-client-shutdown-requested? context)]
      (core/stop-worker shutdown-requested?))
    context))