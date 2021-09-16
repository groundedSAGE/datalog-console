(ns datalog-console.chrome.extension.content-script.main
  {:no-doc true}
  (:require [goog.object :as gobj]
            [cljs.reader]
            [datalog-console.lib.messaging :as msg]))

(def background-conn (atom nil))

(def app-tab-conn ; e.g. datalog-console.integrations.datascript
  (msg/create-conn {:to js/window
                    :routes {:* (fn [conn msg] 
                                  ((msg/forward @background-conn) conn msg))}
                    :send-fn (fn [{:keys [to conn msg]}]
                               (.postMessage to (clj->js {(str ::msg/msg) (pr-str msg)
                                                          :conn-id (:id @conn)})))
                    :receive-fn (fn [cb conn]
                                  (.addEventListener (:to @conn) "message"
                                                     (fn [event]
                                                       (when (and (identical? (.-source event) js/window)
                                                                  (not= (:id @conn) (gobj/getValueByKeys event "data" "conn-id")))
                                                         (when-let [raw-msg (gobj/getValueByKeys event "data" (str ::msg/msg))]
                                                           (js/console.log "this is the active tab: " js/tabs)
                                                           (cb (cljs.reader/read-string raw-msg)))))))}))

(reset! background-conn
        (msg/create-conn {:to (js/chrome.runtime.connect #js {:name ":datalog-console.remote/content-script-port"})
                          :routes {:* (msg/forward app-tab-conn)}
                          :send-fn (fn [{:keys [to msg]}]
                                     (.postMessage to
                                                   (clj->js {(str ::msg/msg) (pr-str msg)})))
                          :receive-fn (fn [cb conn]
                                        (.addListener (gobj/get (:to @conn) "onMessage")
                                                      (fn [msg]
                                                        (when-let [raw-msg (gobj/get msg (str ::msg/msg))]
                                                          (js/console.log "raw msg: " raw-msg)
                                                          (cb (cljs.reader/read-string raw-msg))))))}))

(defn supports-datalog-console? []
  (js/document.documentElement.getAttribute "__datalog-console-remote-installed__"))

(defn detect-db! []
  (when (supports-datalog-console?)
    (msg/send {:conn @background-conn
               :type :datalog-console.remote/db-detected
               :data true})))

(defn init-detector!
  "Attempts to detect if the datalog console is supported in the current tab multiple times before giving up."
  []
  (detect-db!)
  (js/setTimeout detect-db! 1000)
  (js/setTimeout detect-db! 3000)
  (js/setTimeout detect-db! 10000)
  )

(init-detector!)
