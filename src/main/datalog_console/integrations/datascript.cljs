(ns datalog-console.integrations.datascript
  (:require [goog.object :as gobj]
            [cljs.reader]
            [datascript.core :as d]
            [datalog-console.lib.version :as dc]
            [datalog-console.lib.encryption :as crypto]
            [datalog-console.lib.messaging :as msg]
            [clojure.core.async :as async :refer [>! <! go chan]]
            ;; Currently using konserve for iteration speed. Removing dependency on core.async preferable?
            ;; Otherwise we go all in with core.async and leverage the core.async promise API provided for removing web crypto callbacks.
            [konserve.indexeddb :refer [new-indexeddb-store]]
            [konserve.core :as k]))

;; Security
(defonce connection (atom {:confirmed false
                           :attempts 0
                           :connected-at nil}))

;; TODO: Find a better way to do this?
;; We do this up front to allow time to generate the keys and have them available in the atoms
(defonce aes-key (crypto/generate-aes-key))
(defonce key-manager (atom {}))



(defn transact-from-devtool! [db-conn transact-str]
  (try
    (d/transact db-conn (cljs.reader/read-string transact-str))
    (catch js/Error e {:error (goog.object/get e "message")})))


(defn enable!
  "Takes a [datascript](https://github.com/tonsky/datascript) database connection atom. Adds message handlers for a remote datalog-console process to communicate with. E.g. the datalog-console browser [extension](https://chrome.google.com/webstore/detail/datalog-console/cfgbajnnabfanfdkhpdhndegpmepnlmb?hl=en)."
  [{:keys [db-conn disable-write? secure?]}]
  (try
    (js/document.documentElement.setAttribute "__datalog-console-remote-installed__" true)
    (let [integration-config (into {:integration-version dc/version
                                    :secure? secure?}
                                   (when-not secure? {:disable-write? disable-write?}))
          msg-conn (msg/create-conn {:to js/window
                                     :routes {:datalog-console.extension/secure-integration-handshake!
                                              (fn [msg-conn msg]
                                                (let [msg-data (:data msg)]
                                                  (cond
                                                    ;; Send the wrapped AES key
                                                    (:init-key msg-data)
                                                    (do
                                                      (swap! connection assoc :extension-init-key (:init-key msg-data))
                                                      (crypto/key-swap {:received-key (:init-key msg-data)
                                                                        :wrap-settings {:format "jwk"
                                                                                        :key @aes-key
                                                                                        :wrapAlgo (clj->js crypto/rsa-key-algo)}}
                                                                       #(msg/send {:conn msg-conn
                                                                                   :type :datalog-console.extension/secure-integration-handshake!
                                                                                   :data {:wrapped-key (crypto/buff->base64 %)}})))

                                                    ;; User confirmation for secure connection
                                                    (:confirmation-code msg-data)
                                                    (when (and (not (:confirmed @connection)) (< (:attempts @connection) 3))
                                                      
                                                      (let [user-confirmation (js/confirm (str "Confirm Datalog Console connection code: " (:confirmation-code msg-data))
                                                                                          #_(str "Datalog Console has requested a connection to this tab. Please ensure confirmation code is the same you see in console: " (:confirmation-code (:data msg))))]
                                                        (swap! connection update-in [:attempts] inc)
                                                        (msg/send {:conn msg-conn
                                                                   :type :datalog-console.extension/secure-integration-handshake!
                                                                   :data {:user-confirmation user-confirmation}})
                                                        (cond
                                                          user-confirmation
                                                          (do
                                                            (go
                                                              (let [idb (<! (new-indexeddb-store "datalog-console-integration"))]
                                                                (<! (k/assoc-in idb ["extension-key"] (pr-str @connection)))))
                                                            (swap! connection assoc :confirmed true :connected-at (js/Date.)))

                                                          (>= (:attempts @connection) 3)
                                                          (do
                                                            (msg/send {:conn msg-conn
                                                                       :type :datalog-console.extension/secure-integration-handshake!
                                                                       :data {:user-confirmation :failed}})
                                                            (js/alert "Too many attempts made to safely connect Datalog Console to this tab."))))))))


                                              :datalog-console.client/request-whole-database-as-string
                                              (fn [msg-conn _msg]
                                                (msg/send {:conn msg-conn
                                                           :type :datalog-console.remote/db-as-string
                                                           :data (pr-str @db-conn)
                                                           :encryption (when secure?
                                                                         {:key @aes-key
                                                                          :algorithm crypto/aes-key-algo})}))

                                              :datalog-console.client/transact!
                                              (fn [msg-conn msg]
                                                (when-not disable-write?
                                                  (let [transact-result (transact-from-devtool! db-conn (:data msg))]
                                                    (when (:error transact-result)
                                                      (msg/send {:conn msg-conn
                                                                 :type :datalog-console.client.response/transact!
                                                                 :data transact-result
                                                                 :encryption (when secure?
                                                                               {:key @aes-key
                                                                                :algorithm crypto/aes-key-algo})})))))}
                                     :send-fn (fn [{:keys [to conn msg]}]
                                                (when (or
                                                       (not secure?)
                                                       (= :datalog-console.remote/integration-init! (:type msg))
                                                       (:confirmed @connection)
                                                       (= :datalog-console.extension/secure-integration-handshake! (:type msg))
                                                       (= ::msg/ack (:type msg))
                                                       (not secure?))
                                                  (.postMessage to (clj->js {(str ::msg/msg) (pr-str msg)
                                                                             :conn-id (:id @conn)}))))
                                     :receive-fn (fn [cb msg-conn]
                                                   (.addEventListener (:to @msg-conn) "message"
                                                                      (fn [event]
                                                                        (when (and (identical? (.-source event) js/window)
                                                                                   (not= (:id @msg-conn) (gobj/getValueByKeys event "data" "conn-id")))
                                                                          (when-let [raw-msg (gobj/getValueByKeys event "data" (str ::msg/msg))]
                                                                            (let [parsed-msg (cljs.reader/read-string raw-msg)]
                                                                              ;;  (js/console.log "this is parsed msg: " parsed-msg)
                                                                              (if (:encrypted? parsed-msg)
                                                                                (crypto/decrypt {:key @aes-key
                                                                                                 :algorithm crypto/aes-key-algo
                                                                                                 :data (:data parsed-msg)}
                                                                                                #(cb (assoc parsed-msg :data (cljs.reader/read-string %))))
                                                                                (cb parsed-msg))))))))})]
      (msg/send {:conn msg-conn
                 :type :datalog-console.remote/integration-init!
                 :data integration-config})
      

      ;; Establish secure connection after tab refresh
      (when secure?
        (go
          (let [idb (<! (new-indexeddb-store "datalog-console-integration"))]
            (when-let [connection-data (<! (k/get-in idb ["extension-key"]))]
              ;; (js/console.log "re-establishing connection" (cljs.reader/read-string public-key))
              (reset! connection (cljs.reader/read-string connection-data))
              (js/console.log "established connection: " @connection)
              (crypto/key-swap {:received-key (:extension-init-key @connection)
                                :wrap-settings {:format "jwk"
                                                :key @aes-key
                                                :wrapAlgo (clj->js crypto/rsa-key-algo)}}
                               #(msg/send {:conn msg-conn
                                           :type :datalog-console.extension/secure-integration-handshake!
                                           :data {:refreshed-key (crypto/buff->base64 %)}}))))))



      (d/listen! db-conn (fn [x]
                           (let [tx-data (:tx-data x)]
                             (msg/send {:conn msg-conn
                                        :type :datalog-console.client.response/tx-data
                                        :data (pr-str tx-data)
                                        :encryption (when secure?
                                                      {:key @aes-key
                                                       :algorithm crypto/aes-key-algo})})))))


    (catch js/Error _e nil)))



(comment
  ;; repl code 

  (.query js/chrome.tabs #js {:active true :currentWindow true}
          (fn [tabs]
            (let [current-tab (-> (js->clj tabs) first (get "id"))]
              (js/console.log current-tab))))

  (println "hello")

  (println integration-id)

  (go (def my-db (<! (new-indexeddb-store "konserve"))))

  (js/console.log my-db)

  (go (println "get:" (<! (k/get-in my-db ["test" :a]))))

  (go (doseq [i (range 10)] (<! (k/assoc-in my-db [i] i))))

  (go (doseq [i (range 10)] (println (<! (k/get-in my-db [i])))))

  (go (println (<! (k/assoc-in my-db ["test"] {:a 1 :b 4.2}))))

  (go (println (<! (k/update-in my-db ["test" :a] inc))))

  (go (println "get:" (<! (k/get-in my-db ["test"]))))

  (let [my-div (.createElement js/document "div")
        _ (set! (.-background (.-style my-div)) "red")
        text-node (.createTextNode js/document "Hello world")]
    (.prepend js/document.body (.appendChild my-div text-node)))

  (let [elem (.createElement js/document "div")]
    (set! (.. elem -style -backgroundColor) "red")
    (set! (.. elem -style -height) "100px")
    (set! (.. elem -style -width) "100%")
    (set! (.. elem -style -margin) "0")
    (.prepend js/document.body elem))


  (let [elem (.createElement js/document "div")
        content (.createTextNode js/document "Hello world")]
    (.appendChild elem content)
    (set! (.. elem -style -font) "blue")
    (set! (.. elem -style -backgroundColor) "red")
    (set! (.. elem -style -height) "100px")
    (set! (.. elem -style -width) "100vw")
    (set! (.. elem -style -marginTop) "-8px")
    (set! (.. elem -style -marginLeft) "-8px")
    (set! (.. elem -style -marginRight) "-8px")
    (.insertBefore js/document.body elem js/document.body.firstChild))

  ;; (set! (.. js/document -body -style -backgroundColor) "red")

  ;; format blocker
  )