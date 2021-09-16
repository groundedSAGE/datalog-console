(ns datalog-console.integrations.datascript
  (:require [goog.object :as gobj]
            [cljs.reader]
            [datascript.core :as d]
            [datalog-console.lib.version :as dc]
            [datalog-console.lib.encryption :as crypto]
            [datalog-console.lib.messaging :as msg]))


(defn transact-from-devtool! [db-conn transact-str]
  (try
    (d/transact db-conn (cljs.reader/read-string transact-str))
    (catch js/Error e {:error (goog.object/get e "message")})))


(defonce confirmation-code (atom nil))

(defn enable!
  "Takes a [datascript](https://github.com/tonsky/datascript) database connection atom. Adds message handlers for a remote datalog-console process to communicate with. E.g. the datalog-console browser [extension](https://chrome.google.com/webstore/detail/datalog-console/cfgbajnnabfanfdkhpdhndegpmepnlmb?hl=en)."
  [{:keys [conn disable-write?]}]
  (let [db-conn conn]
    (try
      (js/document.documentElement.setAttribute "__datalog-console-remote-installed__" true)
      (let [msg-conn (msg/create-conn {:to js/window
                                       :routes {:datalog-console.client/init!
                                                (fn [msg-conn msg]
                                                  ;;  (when-not @confirmation-code
                                                  ;;   (reset! confirmation-code (js/prompt "Please enter confirmation code")))
                                                  (msg/send {:conn msg-conn
                                                             :type :datalog-console.remote/init-config
                                                             :data {:integration-version dc/version
                                                                    :disable-write? disable-write?}}))

                                                :datalog-console.client/request-whole-database-as-string
                                                (fn [msg-conn _msg]
                                                  (js/console.log "this is the active tab: " js/tabs)
                                                  ;; (js/console.log js/Crypto)
                                                  ;; (js/console.log js/SubtleCrypto)
                                                  ;; (js/console.log "the int array: " random-code)
                                                  ;; (js/console.log (.getRandomValues js/crypto ))
                                                  #_(js/console.log "generate key"
                                                                  (.generateKey js/crypto.subtle
                                                                                (clj->js {:name "AES-GCM"
                                                                                          :length 256})
                                                                                true
                                                                                (clj->js ["encrypt" "decrypt"])))
                                                  (msg/send {:conn msg-conn
                                                             :type :datalog-console.remote/db-as-string
                                                             :data (pr-str @db-conn)}))

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
                                                  (.postMessage to (clj->js {(str ::msg/msg) (pr-str msg)
                                                                             :conn-id (:id @conn)})))
                                       :receive-fn (fn [cb msg-conn]
                                                     (.addEventListener (:to @msg-conn) "message"
                                                                        (fn [event]
                                                                          (when (and (identical? (.-source event) js/window)
                                                                                     (not= (:id @msg-conn) (gobj/getValueByKeys event "data" "conn-id")))
                                                                            (when-let [raw-msg (gobj/getValueByKeys event "data" (str ::msg/msg))]
                                                                              ;; (js/console.log "this is the raw message: " raw-msg)
                                                                              (cb (cljs.reader/read-string raw-msg)))))))})]
        (d/listen! db-conn (fn [x]
                             (let [tx-data (:tx-data x)]
                               (msg/send {:conn msg-conn
                                          :type :datalog-console.client.response/tx-data
                                          :data (pr-str tx-data)})))))


      (catch js/Error _e nil))))



(comment
  ;; repl code

  ;;;;;;;;;;;;;;;;;
  ;; Generate keys
  ;;;;;;;;;;;;;;;;


  (def keypair (crypto/generate-key))



  (crypto/encrypt {:cb #(js/console.log (crypto/decrypt {:cb (fn [s] (js/console.log s)) 
                                                          :keypair keypair 
                                                          :data %}))
                    :keypair keypair
                    :data "the text I am sending"})






  ;; format blocker
  )