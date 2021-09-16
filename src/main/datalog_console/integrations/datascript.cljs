(ns datalog-console.integrations.datascript
  (:require [goog.object :as gobj]
            [cljs.reader]
            [datascript.core :as d]
            [datalog-console.lib.version :as dc]
            [datalog-console.lib.encryption :as crypto]
            [datalog-console.lib.messaging :as msg]))

;; Security
(defonce confirmation-code (atom nil))
(defonce keypair (crypto/generate-key))
(defonce aes-key (crypto/generate-aes-key))

(def exported-key-atom (atom nil))


(defonce initial-public-key (atom nil))


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
                                                                     (js/console.log "wrapped-key: " (crypto/ab2str wrapped-key))
                                                                     #_(crypto/unwrapKey {:format "jwk"
                                                                                        :wrappedKey wrapped-key
                                                                                        :unwrappingKey (:private @keypair)
                                                                                        :unwrapAlgo (clj->js crypto/rsa-key-algo)
                                                                                        :unwrappedKeyAlgo (clj->js crypto/aes-key-algo)
                                                                                        :extractable true
                                                                                        :keyUsages ["encrypt" "decrypt"]}
                                                                                       #(js/console.log "the unwrapped: " %))
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
                                                #_(when-not @confirmation-code
                                                    #_(reset! confirmation-code (js/prompt "Please enter confirmation code")))
                                                (msg/send {:conn msg-conn
                                                           :type :datalog-console.remote/init-config
                                                           :data {:integration-version dc/version
                                                                  :disable-write? disable-write?}}))

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



  (crypto/encrypt {:key-type :public
                   :keypair keypair
                   :data "the text I am sending"}
                  (fn [s]
                    (js/console.log "the s" s)
                    (crypto/decrypt {:keypair keypair
                                     :key-type :private
                                     :data s}
                                    (fn [s]
                                      (js/console.log "decrypt" s)))))



  ;; format blocker
  )