(ns datalog-console.workspaces.chrome.extension-cards
  {:no-doc true}
  (:require [nubank.workspaces.core :as ws]
            [cljs.reader]
            [nubank.workspaces.card-types.react :as ct.react]
            [datalog-console.workspaces.workspace-db-conn :refer [conn]]
            [datalog-console.integrations.datascript :as integrations]))

;; an extra option for security
;; {:security {:level [none medium high]
;;             :salt "salt added by the application developer"}}

(integrations/enable! {:db-conn conn
                       :secure? true
                       :disable-write? false})

(defn element [name props & children]
  (apply js/React.createElement name (clj->js props) children))

(ws/defcard chrome-extension-card
  (ct.react/react-card
   (element "div" {}
            (element "div" {:className "font-black"} "Install the chrome extension and the open datalog panel. It should connect to the running datascript DB in this card."))))