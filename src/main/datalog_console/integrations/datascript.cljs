(ns datalog-console.integrations.datascript
  (:require [goog.object :as gobj]
            [cljs.reader]
            [datascript.core :as d]
            [datalog-console.lib.version :as dc]
            [datalog-console.lib.encryption :as crypto]
            [datalog-console.lib.messaging :as msg]))

;; Security
(defonce connection (atom {:confirmed false
                           :attempts 0
                           :connected-at nil}))

(defonce keypair (crypto/generate-key))
(defonce aes-key (crypto/generate-aes-key))



(defn transact-from-devtool! [db-conn transact-str]
  (try
    (d/transact db-conn (cljs.reader/read-string transact-str))
    (catch js/Error e {:error (goog.object/get e "message")})))


(defn enable!
  "Takes a [datascript](https://github.com/tonsky/datascript) database connection atom. Adds message handlers for a remote datalog-console process to communicate with. E.g. the datalog-console browser [extension](https://chrome.google.com/webstore/detail/datalog-console/cfgbajnnabfanfdkhpdhndegpmepnlmb?hl=en)."
  [{db-conn :conn
    disable-write? :disable-write?}]
  (try
    (js/document.documentElement.setAttribute "__datalog-console-remote-installed__" true)
    (let [msg-conn (msg/create-conn {:to js/window
                                     :routes {:datalog-console.background/secure-connection
                                              (fn [msg-conn msg]
                                                (let [send-wrapped (fn [wrapped-key]
                                                                     (msg/send {:conn msg-conn
                                                                                :type :datalog-console.remote/secure-connection
                                                                                :data {:wrapped-key (crypto/buff->base64 wrapped-key)}}))
                                                      wrap-key (fn [imported-key]
                                                                 (crypto/wrapKey {:format "jwk"
                                                                                  :key @aes-key
                                                                                  :wrappingKey imported-key
                                                                                  :wrapAlgo (clj->js crypto/rsa-key-algo)}
                                                                                 send-wrapped))]
                                                  (crypto/import-jwk (:initial-key (:data msg)) wrap-key)))

                                              :datalog-console.client/init!
                                              (fn [msg-conn msg]
                                                (when-not (:confirmed @connection)
                                                  (let [user-confirmation (js/confirm (str "Datalog Console has requested a connection to this tab. Please ensure confirmation code is the same you see in console: " (:confirmation-code (:data msg))))]
                                                    (cond
                                                      (= user-confirmation true) (swap! connection assoc :confirmed true :connected-at (js/Date.))
                                                      (<= (:attempts @connection) 3) (swap! connection update-in [:attempts] inc)
                                                      :else (js/alert "Too many attempts made to safely connect Datalog Console to this tab."))))
                                                (when (:confirmed @connection)
                                                  (msg/send {:conn msg-conn
                                                             :type :datalog-console.remote/init-config
                                                             :data {:integration-version dc/version
                                                                    :disable-write? disable-write?}})))

                                              :datalog-console.client/request-whole-database-as-string
                                              (fn [msg-conn _msg]
                                                (crypto/encrypt {:key-type :public
                                                                 :keypair keypair
                                                                 :data "test data " #_(pr-str @db-conn)}
                                                                (fn [data]
                                                                  (js/console.log "THIS IS is the encrypted data: " data)
                                                                  ;; (crypto/decrypt )
                                                                  (msg/send {:conn msg-conn
                                                                             :type :datalog-console.remote/db-as-string
                                                                             :data data}))))

                                              :datalog-console.client/transact!
                                              (fn [msg-conn msg]
                                                (when-not disable-write?
                                                  (let [transact-result (transact-from-devtool! db-conn (:data msg))]
                                                    (when (:error transact-result)
                                                      (msg/send {:conn msg-conn
                                                                 :type :datalog-console.client.response/transact!
                                                                 :data transact-result})))))

                                                ;; Keep this around for legacy purposes
                                              :datalog-console.client/request-integration-version
                                              (fn [msg-conn _msg]
                                                (msg/send {:conn msg-conn
                                                           :type :datalog-console.remote/version
                                                           :data dc/version}))}
                                     :send-fn (fn [{:keys [to conn msg]}]
                                                 (js/console.log "sending message from integration: " msg)
                                                (.postMessage to (clj->js {(str ::msg/msg) (pr-str msg)
                                                                           :conn-id (:id @conn)})))
                                     :receive-fn (fn [cb msg-conn]
                                                   (.addEventListener (:to @msg-conn) "message"
                                                                      (fn [event]
                                                                        (when (and (identical? (.-source event) js/window)
                                                                                   (not= (:id @msg-conn) (gobj/getValueByKeys event "data" "conn-id")))
                                                                          (when-let [raw-msg (gobj/getValueByKeys event "data" (str ::msg/msg))]
                                                                            ;; (js/console.log "integration received: " raw-msg)
                                                                            (cb (cljs.reader/read-string raw-msg)))))))})]
      (d/listen! db-conn (fn [x]
                           (let [tx-data (:tx-data x)]
                             (msg/send {:conn msg-conn
                                        :type :datalog-console.client.response/tx-data
                                        :data (pr-str tx-data)})))))


    (catch js/Error _e nil)))



(comment


  (def keypair (crypto/generate-key))

  (js/console.log @keypair)


  (crypto/encrypt {:key-type :public
                   :keypair keypair
                   :data "the text I am sending"}
                  (fn [s]
                    (js/console.log "s is: " (crypto/base64-to-buff s))
                    (crypto/decrypt {:keypair keypair
                                     :key-type :private
                                     :data s}
                                    (fn [s]
                                      (js/console.log "decrypt" s)))))



  ;; format blocker
  )