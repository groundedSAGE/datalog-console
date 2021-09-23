(ns datalog-console.chrome.extension.popup
  (:require [reagent.dom :as rdom]
            [reagent.core :as r]
            [reagent.ratom]
            [datalog-console.lib.messaging :as msg]
            [goog.object :as gobj]
            [cljs.reader]))

(defonce counter (r/atom 1))
(defonce status (r/atom {:current-tab nil}))

(defonce conn (msg/create-conn {:to (js/chrome.runtime.connect #js {:name ":datalog-console.remote/extension-popup"})
                                :routes {:datalog-console.background/status-update
                                         (fn [_conn msg]
                                           (swap! counter inc)
                                           (js/console.log "made it to status update: " (:current-tab (:data msg)))
                                           (swap! status assoc :current-tab (:current-tab (:data msg))))}
                                :send-fn (fn [{:keys [to msg]}]
                                           (.postMessage to
                                                         (clj->js {(str ::msg/msg) (pr-str msg)})))
                                :receive-fn (fn [cb conn]
                                              (.addListener (gobj/get (:to @conn) "onMessage")
                                                            (fn [msg]
                                                              (when-let [raw-msg (gobj/get msg (str ::msg/msg))]
                                                                (js/console.log "this is raw msg: " raw-msg)
                                                                (cb (cljs.reader/read-string raw-msg))))))}))






(defn root []
  (fn []
    [:div {:class "flex flex-col"}
     [:span "Current tab id: " [:b (:current-tab @status)]]
     [:button {:on-click #(msg/send {:conn conn
                                     :type :datalog-console/init-handshake!})}
      "Connect"]]))


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