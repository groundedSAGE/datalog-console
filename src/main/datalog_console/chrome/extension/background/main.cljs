(ns datalog-console.chrome.extension.background.main
  {:no-doc true}
  (:require [goog.object :as gobj]
            [cljs.reader]
            [clojure.string :as str]
            [clojure.core.async :as async :refer [>! <! go chan]]
            [datalog-console.lib.encryption :as crypto]
            [datalog-console.lib.messaging :as msg]))

(defonce port-conns (atom {:tools {}
                           :remote {}
                           :popup {}}))

(defn set-icon-and-popup [tab-id]
  (js/chrome.browserAction.setIcon
   #js {:tabId tab-id
        :path  #js {"16"  "images/active/icon-16.png"
                    "32"  "images/active/icon-16.png"
                    "48"  "images/active/icon-16.png"
                    "128" "images/active/icon-16.png"}})
  (js/chrome.browserAction.setPopup
   #js {:tabId tab-id
        :popup "popups/enabled.html"}))

;;;;;;;;;;;;
;; security
;;;;;;;;;;;;

(defonce keypair (crypto/generate-key))
(defonce key-manager (atom {}))
(defonce integration-configs (atom {}))
(defonce secured-connections (atom {}))

(defn random-code []
  (let [numbers (take 6 (repeatedly #(rand-int 10)))
        parts (map str/join (partition 3 numbers))]
     (str (first parts) " - " (second parts))))

(defonce code (random-code))

(defn popup-port? [port]
  (= ":datalog-console.remote/extension-popup" (gobj/get port "name")))

(defn get-browser-tab-id [port]
  (gobj/getValueByKeys port "sender" "tab" "id"))

(defonce popup-updates
  (let [publish-ch (chan (async/sliding-buffer 1))
        kill-switch (chan)
        updating? (atom false)]
    (add-watch port-conns :popup-watcher (fn [& args]
                                           (if (seq (:popup (last args)))
                                             (when-not (= @updating? true)
                                               (async/go-loop []
                                                 (let [[result _] (async/alts! [kill-switch (async/timeout 250)] :priority true)]
                                                   (when-not (= result :kill)
                                                     (async/put! publish-ch {:tools (keys (:tools @port-conns))
                                                                             :remote (keys (:remote @port-conns))})
                                                     (recur))))
                                               (reset! updating? true))
                                             (do
                                               (when @updating? (go (>! kill-switch :kill)))
                                               (reset! updating? false)))))
    (atom
     {:subscribe (async/mult publish-ch)
      :publish publish-ch})))

(defn real-time-popup-update [conn]
  (let [update-chan (chan (async/sliding-buffer 1))
        kill-switch (chan)]
    (swap! conn assoc :kill-switch kill-switch)
    (async/tap (:subscribe @popup-updates) update-chan)
    (async/go-loop []
      (let [[result _] (async/alts! [kill-switch update-chan] :priority true)]
        (when-not (= result :kill)
          (msg/send {:conn conn
                     :type :datalog-console.extension/popup-update!
                     :data result})
          (recur))))))

(defn connection-security? [conn]
  (:secure? (get @integration-configs @(:tab-id @conn))))

(defn start-secure-integration-handshake! [tab-id]
  (when-not (or (get-in @integration-configs [tab-id :handshake])
                (= true (get-in @integration-configs [tab-id :user-confirmation])))
    (let [get-port (fn [context] (get-in @port-conns [context tab-id]))
          handshake-key-ch (chan)]
      (swap! integration-configs update-in [tab-id] conj {:handshake handshake-key-ch
                                                          :user-confirmation :waiting
                                                          :confirmation-code code})


      ;; Send confirmation code to devtool
      (when-let [tools-conn (get-port :tools)]
        (msg/send {:conn tools-conn
                   :type :datalog-console.extension/secure-integration-handshake!
                   :data {:confirmation-code code
                          :user-confirmation :waiting}}))

      ;; Send confirmation code to popup
      (when-let [popup-conn (get-port :popup)]
        (msg/send {:conn popup-conn
                   :type :datalog-console.extension/secure-integration-handshake!
                   :data {:confirmation-code code
                          :user-confirmation :waiting}}))

      (go
        ;; Send confirmation code to application
        (msg/send {:conn (get-port :remote)
                   :type :datalog-console.extension/secure-integration-handshake!
                   :data {:confirmation-code code}
                   :encryption {:key (<! handshake-key-ch)
                                :algorithm crypto/aes-key-algo}}))

    ;; Start connection handshake
      (crypto/export {:format "jwk"
                      :key (:public @keypair)}
                     (fn [exported-key]
                       (msg/send {:conn (get-port :remote)
                                  :type :datalog-console.extension/secure-integration-handshake!
                                  :data {:init-key exported-key}}))))))

(js/chrome.runtime.onConnect.addListener
 (fn [port]
  ;;  (js/console.log "port connected: " (gobj/get port "name"))
   (msg/create-conn {:to port
                     :encryption (atom nil)
                     :send-fn (fn [{:keys [to msg]}]
                                ;; (js/console.log "sending to: " (gobj/get to "name") msg)
                                (.postMessage to (clj->js {(str ::msg/msg) (pr-str msg)})))
                     :tab-id (atom (or nil (get-browser-tab-id port)))
                     :routes {:datalog-console.remote/integration-init!
                              (fn [conn msg]
                                (swap! integration-configs assoc @(:tab-id @conn) (:data msg))
                                (set-icon-and-popup @(:tab-id @conn)))

                              :datalog-console.client/init!
                              (fn [conn msg]
                                (swap! port-conns assoc-in [:tools @(:tab-id @conn)] conn)
                                (when (connection-security? conn)
                                  (start-secure-integration-handshake! @(:tab-id @conn))))

                              :datalog-console.popup/init!
                              (fn [conn msg]
                                (js/console.log "popup init!")
                                (swap! port-conns assoc-in [:popup @(:tab-id @conn)] conn)
                                (real-time-popup-update conn)

                                ;; (js/console.log "integration configs: " @integration-configs)
                                ;; (js/console.log "integration configs data: " (get @integration-configs @(:tab-id @conn)))
                                (msg/send {:conn conn
                                           :type :datalog-console.popup/init-response!
                                           :data (into {:tools (keys (:tools @port-conns))
                                                        :remote (keys (:remote @port-conns))}
                                                       (dissoc (get @integration-configs @(:tab-id @conn))
                                                               :handshake))}))
                              
                              :datalog-console.popup/connect!
                              (fn [conn msg]
                                (when (connection-security? conn)
                                  (start-secure-integration-handshake! @(:tab-id @conn))))

                              :datalog-console.extension/secure-integration-handshake!
                              (fn [conn msg]
                                (let [tab-id @(:tab-id @conn)
                                      msg-data (:data msg)
                                      handshake-key-ch (get-in @integration-configs [tab-id :handshake])]
                                  (js/console.log "the message for secure handshake! " msg)

                                  (cond

                                    ;; Handle new wrapped AES key from a tab refresh
                                    (:refreshed-key msg-data)
                                    (crypto/unwrapKey {:format "jwk"
                                                       :wrappedKey (crypto/base64->buff (:refreshed-key msg-data))
                                                       :unwrappingKey (:private @keypair)
                                                       :unwrapAlgo (clj->js crypto/rsa-key-algo)
                                                       :unwrappedKeyAlgo (clj->js crypto/aes-key-algo)
                                                       :extractable true
                                                       :keyUsages ["encrypt" "decrypt"]}
                                                      (fn [key]
                                                        (js/console.log "refreshed key received:")
                                                        (swap! key-manager assoc-in [tab-id :symmetric] {:key key
                                                                                                         :received (js/Date.)})))
                                    ;; Receive wrapped AES key from integration
                                    (:wrapped-key msg-data)
                                    (crypto/unwrapKey {:format "jwk"
                                                       :wrappedKey (crypto/base64->buff (:wrapped-key msg-data))
                                                       :unwrappingKey (:private @keypair)
                                                       :unwrapAlgo (clj->js crypto/rsa-key-algo)
                                                       :unwrappedKeyAlgo (clj->js crypto/aes-key-algo)
                                                       :extractable true
                                                       :keyUsages ["encrypt" "decrypt"]}
                                                      (fn [key]
                                                        (go (>! handshake-key-ch key))
                                                        (swap! key-manager assoc-in [tab-id :symmetric] {:key key
                                                                                                         :received (js/Date.)})))

                                    ;; Handle User Confirmation
                                    (contains? msg-data :user-confirmation)
                                    (do
                                      (swap! integration-configs assoc-in [tab-id :user-confirmation] (:user-confirmation msg-data))
                                      (swap! integration-configs update-in [tab-id :confirmation-attempts] inc)
                                      (swap! integration-configs update tab-id dissoc :handshake)
                                      (js/console.log "This is the user confirmation step: " @integration-configs)

                                      (js/console.log "this is the user confirmation: " (:user-confirmation msg-data))

                                      ;; Not necessary. Can just store initial public key..
                                      #_(when (:user-confirmation msg-data)
                                          (crypto/generate-key-cb (fn [keypair]
                                                                    (swap! key-manager assoc-in [tab-id :asymmetric] {:keypair keypair
                                                                                                                      :created (js/Date.)})
                                                                    (crypto/export {:format "jwk"
                                                                                    :key (:public keypair)}
                                                                                   (fn [exported-key]
                                                                                     (crypto/encrypt {:key (get-in @key-manager [tab-id :symmetric :key])
                                                                                                      :data (pr-str exported-key)
                                                                                                      :algorithm crypto/aes-key-algo}
                                                                                                     (fn [encrypted-]
                                                                                                       (msg/send {:conn msg-conn
                                                                                                                  :type :datalog-console.extension/secure-integration-handshake!
                                                                                                                  :data {:restart-key encrypted-priv-key}})))))
                                                                    (msg/send {:conn conn
                                                                               :type :datalog-console.extension/secure-integration-handshake!
                                                                               :data {:refresh-key "(:public keys)"}}))))


                                      (when-let [popup-conn (get-in @port-conns [:popup tab-id])]
                                        ;; (js/console.log "this is the popup-conn:" popup-conn)
                                        (msg/send {:conn popup-conn
                                                   :type :datalog-console.extension/secure-integration-handshake!
                                                   :data {:user-confirmation (:user-confirmation msg-data)}}))
                                      ;; Send the user confirmation to the devtool. TODO: Turn into handle-user-confirmation to also send to other tool connections
                                      #_(msg/send {:conn (get-in @port-conns [:tools tab-id])
                                                   :type :datalog-console.extension/secure-integration-handshake!
                                                   :data {:user-confirmation (:user-confirmation msg-data)}}))

                                    (contains? msg-data :restart-key)
                                    (do
                                      (swap! integration-configs assoc @(:tab-id @conn) msg-data)
                                      (crypto/decrypt
                                       {:key (get-in @key-manager [tab-id :symmetric :key])
                                        :data (:restart-key msg-data)
                                        :algorithm crypto/aes-key-algo}
                                       (fn [encrypted-key-str]
                                         (cljs.reader/read-string encrypted-key-str)))))))

                              :* (fn [conn msg]
                                     ;;TODO: handle wildcard when multi variety ports
                                   
                                   (when-not (popup-port? port)
                                    ;;  (js/console.log "this is the port before the env context" port)
                                     
                                     (let [env-context (case (gobj/get port "name")
                                                         ":datalog-console.remote/content-script-port" :tools
                                                         ":datalog-console.client/devtool-port" :remote)
                                           to (get-in @port-conns [env-context @(:tab-id @conn)])]
                                       ((msg/forward to) conn msg))))}

                     :receive-fn (fn [cb conn]
                                   (let [conn-tab-id @(:tab-id @conn)]
                                     (when-let [tab-id (get-browser-tab-id port)]
                                       (swap! port-conns assoc-in [:remote tab-id] conn))
                                     (let [listener (fn [message port]
                                                      (when-let [msg-tab-id (gobj/get message "tab-id")]
                                                        (reset! (:tab-id @conn) msg-tab-id))
                                                      (let [raw-msg (gobj/get message (str ::msg/msg))
                                                            parsed-msg (cljs.reader/read-string raw-msg)]
                                                        #_(js/console.log "receive msg: " (:type parsed-msg))
                                                        (if (:encrypted? parsed-msg)
                                                          (crypto/decrypt {:key (get @key-manager conn-tab-id)
                                                                           :algorithm crypto/aes-key-algo
                                                                           :data (:data parsed-msg)}
                                                                          #(cb (assoc parsed-msg :data %)))
                                                          (cb parsed-msg))))]
                                       (.addListener (gobj/get port "onMessage") listener)
                                       (.addListener (gobj/get port "onDisconnect")
                                                     (fn [port]
                                                      ;;  (js/console.log "this port disconnected: " (gobj/get port "name"))
                                                      ;;  (js/console.log @port-conns)
                                                       (when-let [msg (gobj/get port "onMessage")]
                                                         (.removeListener msg listener))
                                                       (cond
                                                         ;; TODO: handle when the page is refreshed and the connection is lost.

                                                         ;; Store the way to verify the message is from background
                                                         ;; If the public key has come from background. Generate a new AES key, wrap and send back.
                                                         ;; New secure connection established without user confirmation


                                                         (get-browser-tab-id port)
                                                         (swap! port-conns update-in [:remote] dissoc @(:tab-id @conn))

                                                         (popup-port? port)
                                                         (do
                                                           (go (>! (:kill-switch @conn) :kill))
                                                           (swap! port-conns update-in [:popup] dissoc @(:tab-id @conn)))

                                                         (gobj/get port ":datalog-console.client/devtool-port")
                                                         (swap! port-conns dissoc :tools conn-tab-id)))))))})))



