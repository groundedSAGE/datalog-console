(ns datalog-console.chrome.extension.content-script.main
  {:no-doc true}
  (:require [goog.object :as gobj]
            [reagent.dom :as rdom]
            [reagent.core :as r]
            [clojure.core.async :as async :refer [>! <! go chan]]
            [cljs.reader]
            [datalog-console.lib.messaging :as msg]
            [konserve.indexeddb :refer [new-indexeddb-store]]
            [konserve.core :as k]))




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





;; Manipulate the DOM
(defn root []
  (fn []
    [:div {:class "flex w-screen h-100 bg-red-400 p-8"}
     [:h1 "Welcome to Datalog Console"]]))




;; Create DOM element
(when-not (.getElementById js/document "test-id")
  (let [elem (.createElement js/document "div")]
    (set! (.. elem -id) "test-id")
    (.insertBefore js/document.body elem js/document.body.firstChild)))


(go (def my-db (<! (new-indexeddb-store "rawr"))))
(js/console.log "the db: " my-db)



;; (go (println (<! (k/assoc-in my-db ["test"] {:a 1 :b 4.2}))))
;; (go (println "get:" (<! (k/get-in my-db ["test" :a]))))



(defn mount! []
  (rdom/render [root] (js/document.getElementById "test-id")))

(defn init! []
  (mount!))

(defn ^:dev/after-load remount!
  "Remounts the whole UI on every save. Def state you want to persist between remounts with defonce."
  []
  (mount!))

(mount!)
