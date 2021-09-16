(ns datalog-console.chrome.extension.popup
  (:require [reagent.dom :as rdom]
            [reagent.core :as r]
            [reagent.ratom]
            [datalog-console.lib.messaging :as msg]
            [goog.object :as gobj]
            [cljs.reader]))


(defn root []
  (let [counter (r/atom 1)]
    (fn []
      (let [conn (msg/create-conn {:to (js/chrome.runtime.connect #js {:name ":datalog-console.remote/extension-popup"})
                                   :send-fn (fn [{:keys [to msg]}]
                                              (.postMessage to
                                                            (clj->js {(str ::msg/msg) (pr-str msg)})))
                                   :receive-fn (fn [cb conn]
                                                 (.addListener (gobj/get (:to @conn) "onMessage")
                                                               (fn [msg]
                                                                 (when-let [raw-msg (gobj/get msg (str ::msg/msg))]
                                                                   (js/console.log "this is raw msg: " raw-msg)
                                                                   (cb (cljs.reader/read-string raw-msg))))))})]

        [:div
         [:span "The counter" @counter]
         [:button {:on-click (fn []
                               (swap! counter inc)
                               (js/console.log "the tab: " js/tabs.Tab)
                               (msg/send {:conn conn
                                          :type :datalog-console.popup
                                          :data true})
                               (js/console.log "the conn" conn))}
          "Increment"]]))))


(defn mount! []
  (rdom/render [root] (js/document.getElementById "root")))

(defn init! []
  (mount!))

(defn ^:dev/after-load remount!
  "Remounts the whole UI on every save. Def state you want to persist between remounts with defonce."
  []
  (mount!))

(mount!)





;; (defn mount! []
;;   (rdom/render [root] (js/document.getElementById "root")))

;; (defn init! []
;;   (mount!))

;; (defn ^:dev/after-load remount!
;;   "Remounts the whole UI on every save. Def state you want to persist between remounts with defonce."
;;   []
;;   (mount!))