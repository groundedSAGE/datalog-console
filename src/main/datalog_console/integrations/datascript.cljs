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

(defonce aes-key (crypto/generate-aes-key))



(defn transact-from-devtool! [db-conn transact-str]
  (try
    (d/transact db-conn (cljs.reader/read-string transact-str))
    (catch js/Error e {:error (goog.object/get e "message")})))


(defn enable!
  "Takes a [datascript](https://github.com/tonsky/datascript) database connection atom. Adds message handlers for a remote datalog-console process to communicate with. E.g. the datalog-console browser [extension](https://chrome.google.com/webstore/detail/datalog-console/cfgbajnnabfanfdkhpdhndegpmepnlmb?hl=en)."
  [{db-conn :conn
    disable-write? :disable-write?
    secure? :secure?}]
  (try
    (js/document.documentElement.setAttribute "__datalog-console-remote-installed__" true)
    (let [msg-conn (msg/create-conn {:to js/window
                                     :routes {:datalog-console.background/secure-connection
                                              (fn [msg-conn msg]
                                                (if-let [initial-key (:initial-key (:data msg))]
                                                  (crypto/key-swap {:received-key initial-key
                                                                    :wrap-settings {:format "jwk"
                                                                                    :key @aes-key
                                                                                    :wrapAlgo (clj->js crypto/rsa-key-algo)}}
                                                                   #(msg/send {:conn msg-conn
                                                                               :type :datalog-console.remote/secure-connection
                                                                               :data {:wrapped-key (crypto/buff->base64 %)}}))
                                                  (msg/send {:conn msg-conn
                                                             :type :datalog-console.remote/secure-connection
                                                             :data {:secure? secure?}})))

                                              :datalog-console.client/init!
                                              (fn [msg-conn msg]
                                                (when-not (:confirmed @connection)
                                                  (let [user-confirmation (js/confirm (str "Datalog Console has requested a connection to this tab. Please ensure confirmation code is the same you see in console: " (:confirmation-code (:data msg))))]
                                                    (cond
                                                      (= user-confirmation true) (swap! connection assoc :confirmed true :connected-at (js/Date.))
                                                      (<= (:attempts @connection) 3) (swap! connection update-in [:attempts] inc)
                                                      :else (js/alert "Too many attempts made to safely connect Datalog Console to this tab."))))
                                                (msg/send {:conn msg-conn
                                                           :type :datalog-console.remote/init-config
                                                           :data {:integration-version dc/version
                                                                  :disable-write? disable-write?
                                                                  :secure? secure?}}))

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
                                                (when (or (:confirmed @connection)
                                                          (= :datalog-console.remote/secure-connection (:type msg))
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
                                                                              (if (:encrypted? parsed-msg)
                                                                                (crypto/decrypt {:key @aes-key
                                                                                                 :algorithm crypto/aes-key-algo
                                                                                                 :data (:data parsed-msg)}
                                                                                                #(cb (assoc parsed-msg :data (cljs.reader/read-string %))))
                                                                                (cb parsed-msg))))))))})]
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


  (def keypair (crypto/generate-key))

  (js/console.log @keypair)


  (crypto/encrypt {:key (:public @keypair)
                   :algorithm crypto/rsa-key-algo
                   :data "the text I am sending"}
                  (fn [s]
                    (js/console.log "s is: " s)
                    (crypto/decrypt {:key (:private @keypair)
                                     :algorithm crypto/rsa-key-algo
                                     :data s}
                                    (fn [s]
                                      (js/console.log "decrypt" s)))))




  (js/console.log @aes-key)


  (crypto/encrypt {:key @aes-key
                   :algorithm crypto/aes-key-algo
                   :data "the text I am sending"}
                      (fn [s]
                        (js/console.log "s is: " s)
                        #_(crypto/decrypt {:key aes-key
                                         :algorithm crypto/aes-key-algo
                                         :data s}
                                        (fn [s]
                                          (js/console.log "decrypted:" s)))))



  ;; format blocker
  )