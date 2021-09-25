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
                                                          ;;  (js/console.log "APP-tab-conn: " raw-msg)
                                                           (cb (cljs.reader/read-string raw-msg)))))))}))

(reset! background-conn
        (msg/create-conn {:to (js/chrome.runtime.connect #js {:name ":datalog-console.remote/content-script-port"})
                          :routes {:* (msg/forward app-tab-conn)}
                          :id "background-conn"
                          :send-fn (fn [{:keys [to msg]}]
                                     (.postMessage to
                                                   (clj->js {(str ::msg/msg) (pr-str msg)})))
                          :receive-fn (fn [cb conn]
                                        (.addListener (gobj/get (:to @conn) "onMessage")
                                                      (fn [msg]
                                                        (when-let [raw-msg (gobj/get msg (str ::msg/msg))]
                                                          ;; (js/console.log "BG-conn: " raw-msg)
                                                          (cb (cljs.reader/read-string raw-msg))))))}))
