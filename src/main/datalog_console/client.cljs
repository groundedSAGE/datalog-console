(ns datalog-console.client
  {:no-doc true}
  (:require [reagent.dom :as rdom]
            [reagent.core :as r]
            [reagent.ratom]
            [datalog-console.components.schema :as c.schema]
            [datalog-console.components.entity :as c.entity]
            [datalog-console.components.entities :as c.entities]
            [datalog-console.components.query :as c.query]
            [datalog-console.components.transact :as c.transact]
            [datascript.core :as d]
            [goog.object :as gobj]
            [clojure.string :as str]
            [datalog-console.lib.messaging :as msg]
            [datalog-console.components.feature-flag :as feature-flag]
            [homebase.reagent :as hbr]
            [cljs.reader]
            ["crypto-js" :as crypto]))

(def r-db-conn (r/atom nil))
(def r-error (r/atom nil))
(def entity-lookup-ratom (r/atom ""))
(def remote-config (r/atom {}))
(def security-code (r/atom {}))


;; Security

(defn encrypt [msg secret]
  (.toString (.encrypt (.-AES crypto) msg secret)))

(defn decrypt [msg secret]
  (.toString (.decrypt (.-AES crypto) msg secret) (.-Utf8 (.-enc crypto))))

(def confirmation-code "secret" #_(clojure.string/join (unique-random-numbers 6)))

(try
  ;; Inside of a try for cases when there is no browser environment
  (def background-conn (msg/create-conn {:to (js/chrome.runtime.connect #js {:name ":datalog-console.client/devtool-port"})
                                         :routes {:datalog-console.background/confirmation-code
                                                  (fn [_msg-conn msg]
                                                    (reset! security-code (:data msg)))

                                                  :datalog-console.remote/init-config
                                                  (fn [_msg-conn msg]
                                                    (reset! remote-config (:data msg)))

                                                  :datalog-console.client.response/tx-data
                                                  (fn [_msg-conn msg]
                                                    (doseq [datom (cljs.reader/read-string (decrypt (:data msg) confirmation-code))]
                                                      (let [{:keys [e a v _tx added]} datom]
                                                        (d/transact! @r-db-conn [[(if added :db/add :db/retract) e a v]]))))

                                                  :datalog-console.remote/db-as-string
                                                  (fn [_msg-conn msg]
                                                    (when @r-db-conn (hbr/disconnect! @r-db-conn))
                                                    (reset! r-db-conn (d/conn-from-db (cljs.reader/read-string (decrypt (:data msg) confirmation-code))))
                                                    (hbr/connect! @r-db-conn))

                                                  :datalog-console.client.response/transact!
                                                  (fn [_msg-conn msg] (when (:error (:data msg))
                                                                        (reset! r-error (:error (:data msg)))))}
                                         :tab-id js/chrome.devtools.inspectedWindow.tabId
                                         :send-fn (fn [{:keys [tab-id to msg]}]
                                                    (.postMessage to
                                                                  (clj->js {(str ::msg/msg) (pr-str msg)
                                                                            :tab-id tab-id})))
                                         :receive-fn (fn [cb msg-conn]
                                                       (.addListener (gobj/get (:to @msg-conn) "onMessage")
                                                                     (fn [msg]
                                                                       (when-let [raw-msg (gobj/get msg (str ::msg/msg))]
                                                                         (js/console.log  "raw msg: " raw-msg)
                                                                         (cb (cljs.reader/read-string raw-msg))))))}))

  (msg/send {:conn background-conn
             :type :datalog-console.client/init!})

  (catch js/Error _e nil))


(defn transaction-permission-check [comp]
  (if-not (:disable-write? @remote-config)
    comp
    [:div {:class "relative h-full"}
     [:div {:class "opacity-50"} comp]
     [:div {:class "absolute top-0 w-full h-full bg-gray-500 bg-opacity-80 flex justify-center items-center"}
      [:div {:class "mx-4 p-4 bg-gray-100 rounded"}
       [:span {:class "block"}
        "Transactions are disabled for this database"]]]]))

(defn tabs []
  (let [active-tab (r/atom "Entity")
        tabs ["Entity" "Query" "Transact"]
        on-tx-submit (fn [tx-str]
                       (msg/send {:conn background-conn
                                  :type :datalog-console.client/transact!
                                  :data tx-str}))]
    @(r/track! #(do @entity-lookup-ratom
                    (reset! active-tab "Entity")))
    (fn [r-db-conn entity-lookup-ratom]
      [:div {:class "flex flex-col overflow-hidden col-span-2"}
       [:ul {:class "text-xl border-b flex flex-row"}
        (doall (for [tab-name tabs]
                 ^{:key (str tab-name)}
                 [:li {:class (str (when (= tab-name @active-tab) "border-b-4 border-blue-400 ") "px-2 pt-2 cursor-pointer hover:bg-blue-100 focus:bg-blue-100")
                       :on-click #(reset! active-tab tab-name)}
                  [:h2 tab-name]]))]
              (when @r-db-conn
                (case @active-tab
                  "Entity" [:div {:class "overflow-auto h-full w-full mt-2"}
                            [c.entity/entity @r-db-conn entity-lookup-ratom]]
                  "Query"  [:div {:class "overflow-auto h-full w-full mt-2"}
                            [c.query/query @r-db-conn]]
                  "Transact" [feature-flag/version-check
                              {:title "Transact"
                               :required-version "0.3.1"
                               :current-version (:integration-version @remote-config)}
                              (transaction-permission-check
                               [:div {:class "overflow-auto h-full w-full mt-2"}
                                [c.transact/transact on-tx-submit r-error]])]))])))



(defn root []
  (let [loaded-db? (r/atom false)]
    (fn []
      [:div {:class "relative text-xs h-full w-full grid grid-cols-4"}
       [:div {:class "flex flex-col border-r pt-2 overflow-hidden col-span-1 "}
        [:h2 {:class "pl-1 text-xl border-b flex center"} "Schema"]
        (when @r-db-conn
          [:div {:class "overflow-auto h-full w-full"}
           [c.schema/schema @r-db-conn]])]

       [:div {:class "flex flex-col border-r overflow-hidden col-span-1 "}
        [:h2 {:class "px-1 text-xl border-b pt-2"} "Entities"]
        (when @r-db-conn
          [:div {:class "overflow-auto h-full w-full"}
           [c.entities/entities @r-db-conn entity-lookup-ratom]])]
       [tabs r-db-conn entity-lookup-ratom]
       [:div {:class "absolute top-2 right-1 flex items-center"}
        [:p {:class "px-2"} [:b @security-code]]
        [:button
         {:class "py-1 px-2 rounded bg-gray-200 border"
          :on-click (fn []
                      (when-not @loaded-db? (reset! loaded-db? true))
                      (js/console.log "the confirmation code: " (type confirmation-code))
                      (msg/send {:conn background-conn
                                 :type :datalog-console.client/request-whole-database-as-string}))}
         (if @loaded-db? "Refresh database" "Load database")]]])))
