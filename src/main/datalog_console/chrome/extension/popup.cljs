(ns datalog-console.chrome.extension.popup
  (:require [reagent.dom :as rdom]
            [reagent.core :as r]
            [reagent.ratom]
            [clojure.core.async :as async :refer [>! <! go chan]]
            [datalog-console.lib.messaging :as msg]
            [goog.object :as gobj]
            [cljs.reader]))

(defonce status (r/atom {:current-tab-id nil
                         :confirmation-code nil
                         :user-confirmation false
                         :tools nil
                         :remote nil}))

(defn get-current-tab [cb]
  (.query js/chrome.tabs #js {:active true :currentWindow true}
          (fn [tabs]
            (let [current-tab (-> (js->clj tabs) first (get "id"))]
              (cb current-tab)))))

(go
  (let [tab-id-ch (chan)
        _ (get-current-tab #(go (>! tab-id-ch %)))
        tab-id (<! tab-id-ch)]
    (defonce conn
      (msg/create-conn {:to (js/chrome.runtime.connect #js {:name ":datalog-console.remote/extension-popup"})
                        :tab-id tab-id
                        :routes {:datalog-console.popup/init-response!
                                 (fn [_conn msg]
                                   (swap! status conj (:data msg)))
                                 
                                 :datalog-console.extension/popup-update!
                                 (fn [_conn msg]
                                   (swap! status conj (:data msg)))

                                 :datalog-console.extension/secure-integration-handshake!
                                 (fn [_conn msg]
                                   (let [msg-data (:data msg)]
                                     (js/console.log "msg-data: " msg-data)
                                     (cond
                                       (contains? msg-data :confirmation-code)
                                       (swap! status conj msg-data)


                                       (contains? msg-data :user-confirmation)
                                       (swap! status conj msg-data))))}
                        

                        :send-fn (fn [{:keys [tab-id to msg]}]
                                   (.postMessage to
                                                 (clj->js {(str ::msg/msg) (pr-str msg)
                                                           :tab-id tab-id})))
                        :receive-fn (fn [cb conn]
                                      (.addListener (gobj/get (:to @conn) "onMessage")
                                                    (fn [msg]
                                                      (when-let [raw-msg (gobj/get msg (str ::msg/msg))]
                                                        (js/console.log "this is raw msg: " raw-msg)
                                                        (cb (cljs.reader/read-string raw-msg))))))}))
    (swap! status assoc :current-tab-id tab-id)
    (msg/send {:conn conn
           :type :datalog-console.popup/init!})))





(defn console-connections []
  [:div {:class "px-4 flex flex-col"}
   #_[:p (str @status)]
   [:p {:class "border-b text-xl flex justify-between"}
    [:span "Tab Id:"]
    [:b (:current-tab-id @status)]]
   (when (:secure? @status)
     (let [connection-status (:user-confirmation @status)]
       (case connection-status
         true [:span {:class "text-xl text-gray-300 self-center"} "Connected"]
         false [:button {:class "mt-4 py-1 px-2 rounded bg-gray-200 border"
                         :on-click
                         (fn []
                           (when (not= :failed connection-status)
                             (msg/send {:conn conn
                                        :type :datalog-console.popup/connect!})
                             (js/console.log "connection status: " connection-status)

                             (swap! status assoc :user-confirmation :waiting)))}
                "Connect"]
         :waiting [:div {:class "mt-4 p-2 rounded bg-red-200 flex flex-col items-center"}
                   [:p "Connection confirmation code"]
                   [:p {:class "text-xl"} (:confirmation-code @status)]]
         :failed [:div {:class "mt-4 p-2 rounded bg-red-200 flex flex-col items-center"}
                  [:p "Secure connection failed"]])))
    
   
   [:div {:class "mt-8"}
    [:p {:class "mt-4 border-b flex justify-between"}
     [:span "Tabs:"]
     [:b (or (count (:remote @status)) 0)]]
    [:p {:class "mt-4 border-b flex justify-between"}
     [:span "Databases:"]
     [:b (or (:tools-connected @status) 0)]]
    [:p {:class "mt-4 border-b flex justify-between"}
     [:span "Tools:"]
     [:b (or (:tools-connected @status) 0)]]]])



(defn root []
  (fn []
    [:div {:class "flex flex-col p-8"}
     [console-connections]]))

(defn mount! []
  (rdom/render [root] (js/document.getElementById "root")))

(defn init! []
  (mount!))

(defn ^:dev/after-load remount!
  "Remounts the whole UI on every save. Def state you want to persist between remounts with defonce."
  []
  (mount!))

(mount!)
