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
                                     :routes {:datalog-console.extension/integration-handshake!
                                              (fn [msg-conn msg]
                                                (let [msg-data (:data msg)]
                                                  (cond

                                                    ;; Send the init-config for handshake
                                                    (= :request-handshake msg-data)
                                                    (msg/send {:conn msg-conn
                                                               :type :datalog-console.extension/integration-handshake!
                                                               :data {:init-config {:integration-version dc/version
                                                                                    :secure? secure?}}})

                                                    ;; Send the wrapped AES key
                                                    (:init-key msg-data)
                                                    (crypto/key-swap {:received-key (:init-key msg-data)
                                                                      :wrap-settings {:format "jwk"
                                                                                      :key @aes-key
                                                                                      :wrapAlgo (clj->js crypto/rsa-key-algo)}}
                                                                     #(msg/send {:conn msg-conn
                                                                                 :type :datalog-console.extension/integration-handshake!
                                                                                 :data {:wrapped-key (crypto/buff->base64 %)}}))

                                                    ;; User confirmation for secure connection
                                                    (:confirmation-code msg-data)
                                                    (when-not (:confirmed @connection)
                                                      (let [user-confirmation (js/confirm (str "Datalog Console has requested a connection to this tab. Please ensure confirmation code is the same you see in console: " (:confirmation-code (:data msg))))]
                                                        (js/console.log "user confirmation: " user-confirmation)
                                                        (msg/send {:conn msg-conn
                                                                   :type :datalog-console.extension/integration-handshake!
                                                                   :data {:user-confirmation user-confirmation}})
                                                        (cond
                                                          (= user-confirmation true) 
                                                          (swap! connection assoc :confirmed true :connected-at (js/Date.))

                                                          (<= (:attempts @connection) 3) 
                                                          (swap! connection update-in [:attempts] inc)
                                                          
                                                          :else 
                                                          (js/alert "Too many attempts made to safely connect Datalog Console to this tab.")))))))

                                              :datalog-console.client/init!
                                              (fn [msg-conn msg]
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
                                                          (= :datalog-console.extension/integration-handshake! (:type msg))
                                                          (= ::msg/ack (:type msg))
                                                          (not secure?))
                                                  (js/console.log "sending from integration: " msg)
                                                  (.postMessage to (clj->js {(str ::msg/msg) (pr-str msg)
                                                                             :conn-id (:id @conn)}))))
                                     :receive-fn (fn [cb msg-conn]
                                                   (.addEventListener (:to @msg-conn) "message"
                                                                      (fn [event]
                                                                        (when (and (identical? (.-source event) js/window)
                                                                                   (not= (:id @msg-conn) (gobj/getValueByKeys event "data" "conn-id")))
                                                                          (when-let [raw-msg (gobj/getValueByKeys event "data" (str ::msg/msg))]
                                                                            (let [parsed-msg (cljs.reader/read-string raw-msg)]
                                                                               (js/console.log "this is parsed msg: " parsed-msg)
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