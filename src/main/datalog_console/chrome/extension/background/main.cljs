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

(defonce key-manager (atom {}))
(defonce integration-configs (atom {}))
(defonce secured-connections (atom {}))

(defn random-code []
  (let [numbers (take 6 (repeatedly #(rand-int 10)))
        parts (map str/join (partition 3 numbers))]
     (str (first parts) " - " (second parts))))

(defonce code (random-code))

(defonce keypair (crypto/generate-key))


(defn popup-port? [port]
  (= ":datalog-console.remote/extension-popup" (gobj/get port "name")))

(defn get-browser-tab-id [port]
  (gobj/getValueByKeys port "sender" "tab" "id"))

(defn get-current-tab [cb]
  (.query js/chrome.tabs #js {:active true :currentWindow true}
          (fn [tabs]
            (let [current-tab (-> (js->clj tabs) first (get "id"))]
              (cb current-tab)))))

#_(defn real-time-popup-update [conn port]
  (when (popup-port? port)
    (async/go-loop []
      (when (:popup @port-conns)
        (msg/send {:conn conn
                   :type :datalog-console.extension/popup-update!
                   :data {:tools (keys (:tools @port-conns))
                          :remote (keys (:remote @port-conns))}}))
      (<! (async/timeout 250))
      (recur))))

(defn start-secure-integration-handshake! [tab-id]
  (js/console.log "start secure called for: " tab-id )
  (when-not (get-in @integration-configs [tab-id :handshake])
    (js/console.log "first handshake for: " tab-id)
    (let [get-port (fn [context] (get-in @port-conns [context tab-id]))]
      (swap! integration-configs assoc-in [tab-id :handshake] (chan))
      (js/console.log (get-in @integration-configs [tab-id :handshake]))
      (go
        (let [handshake-key-ch (get-in @integration-configs [tab-id :handshake])]
          (when (:secure? (get @integration-configs tab-id))

          ;; Send confirmation code to devtool
            (when-let [tools-conn (get-port :tools)]
              (msg/send {:conn tools-conn
                         :type :datalog-console.extension/secure-integration-handshake!
                         :data {:confirmation-code code}}))

           ;; Send confirmation code to popup
            (when-let [popup-conn (get-port :popup)]
              (msg/send {:conn popup-conn
                         :type :datalog-console.extension/secure-integration-handshake!
                         :data {:confirmation-code code}}))

          ;; Send confirmation code to application
            (msg/send {:conn (get-port :remote)
                       :type :datalog-console.extension/secure-integration-handshake!
                       :data {:confirmation-code code}
                       :encryption {:key (<! handshake-key-ch)
                                    :algorithm crypto/aes-key-algo}}))))

    ;; Start connection handshake
      (crypto/export {:format "jwk"
                      :key (:public @keypair)}
                     (fn [exported-key]
                       (msg/send {:conn (get-port :remote)
                                  :type :datalog-console.extension/secure-integration-handshake!
                                  :data {:init-key exported-key}}))))))

(defn handle-user-confirmation [tab-id confirmation]
  (case confirmation 
    true (swap! assoc integration-configs [])))



(js/chrome.runtime.onConnect.addListener
 (fn [port]
   (js/console.log "port connected: " (gobj/get port "name"))
   (msg/create-conn {:to port
                     :encryption (atom nil)
                     :send-fn (fn [{:keys [to msg]}]
                                (js/console.log "sending to: " (gobj/get to "name") msg)
                                (.postMessage to (clj->js {(str ::msg/msg) (pr-str msg)})))
                     :tab-id (atom (or nil (get-browser-tab-id port)))
                     :routes {:datalog-console.remote/integration-init!
                              (fn [conn msg]
                                (swap! integration-configs assoc @(:tab-id @conn) (:data msg))
                                (set-icon-and-popup @(:tab-id @conn)))

                              :datalog-console.client/init!
                              (fn [conn msg]
                                (swap! port-conns assoc-in [:tools @(:tab-id @conn)] conn)
                                (let [secure? (:secure? (get @integration-configs @(:tab-id @conn)))]
                                  (when secure? (start-secure-integration-handshake! @(:tab-id @conn)))))

                              :datalog-console.popup/init!
                              (fn [conn msg]
                                (swap! port-conns assoc-in [:popup @(:tab-id @conn)] conn)
                                (msg/send {:conn conn
                                           :type :datalog-console.popup/init-response!
                                           :data (into {:tools (keys (:tools @port-conns))
                                                        :remote (keys (:remote @port-conns))}
                                                       (get @integration-configs @(:tab-id @conn)))}))
                              
                              

                              :datalog-console.popup/connect!
                              (fn [conn msg]
                                (start-secure-integration-handshake! @(:tab-id @conn)))

                              :datalog-console.extension/secure-integration-handshake!
                              (fn [conn msg]
                                (let [tab-id @(:tab-id @conn)
                                      msg-data (:data msg)
                                      handshake-ch (get-in @integration-configs [tab-id :handshake])]

                                  (cond
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
                                                        (go (>! handshake-ch key))
                                                        (swap! key-manager assoc tab-id key)))

                                    (contains? msg-data :user-confirmation)
                                    (do
                                      (swap! integration-configs assoc-in [tab-id :user-confirmation] (:user-confirmation msg-data))
                                      (swap! integration-configs update-in [tab-id :confirmation-attempts] inc)
                                      (swap! integration-configs update tab-id dissoc :handshake)
                                      (js/console.log "This is the user confirmation step: " @integration-configs)

                                      (msg/send {:conn (get-in @port-conns [:popup tab-id])
                                                 :type :datalog-console.extension/secure-integration-handshake!
                                                 :data {:user-confirmation (:user-confirmation msg-data)}})
                                      ;; Send the user confirmation to the devtool. TODO: Turn into handle-user-confirmation to also send to other tool connections
                                      #_(msg/send {:conn (get-in @port-conns [:tools tab-id])
                                                   :type :datalog-console.extension/secure-integration-handshake!
                                                   :data {:user-confirmation (:user-confirmation msg-data)}})))))

                              :* (fn [conn msg]
                                     ;;TODO: handle wildcard when multi variety ports
                                   
                                   (when-not (popup-port? port)
                                     (js/console.log "this is the port before the env context" port)
                                     
                                     (let [env-context (case (gobj/get port "name")
                                                         ":datalog-console.remote/content-script-port" :tools
                                                         ":datalog-console.client/devtool-port" :remote)
                                           to (get-in @port-conns [env-context @(:tab-id @conn)])]
                                       ((msg/forward to) conn msg))))}

                     :receive-fn (fn [cb conn]
                                   #_(real-time-popup-update conn port)
                                   (let [conn-tab-id @(:tab-id @conn)]
                                     (when-let [tab-id (get-browser-tab-id port)]
                                       (swap! port-conns assoc-in [:remote tab-id] conn))
                                     (let [listener (fn [message port]
                                                      (when-let [msg-tab-id (gobj/get message "tab-id")]
                                                        (reset! (:tab-id @conn) msg-tab-id))
                                                      (let [raw-msg (gobj/get message (str ::msg/msg))
                                                            parsed-msg (cljs.reader/read-string raw-msg)]
                                                        (js/console.log "receive msg: " (:type parsed-msg))
                                                        (if (:encrypted? parsed-msg)
                                                          (crypto/decrypt {:key (get @key-manager conn-tab-id)
                                                                           :algorithm crypto/aes-key-algo
                                                                           :data (:data parsed-msg)}
                                                                          #(cb (assoc parsed-msg :data %)))
                                                          (cb parsed-msg))))]
                                       (.addListener (gobj/get port "onMessage") listener)
                                       (.addListener (gobj/get port "onDisconnect")
                                                     (fn [port]
                                                       (js/console.log "this port disconnected: " (gobj/get port "name"))
                                                       (when-let [msg (gobj/get port "onMessage")]
                                                         (.removeListener msg listener))
                                                       (cond
                                                         (get-browser-tab-id port)
                                                         (swap! port-conns update :remote dissoc conn-tab-id)

                                                         (popup-port? port)
                                                         (swap! port-conns update :popup dissoc conn-tab-id)

                                                         :else
                                                         (swap! port-conns update :tools dissoc conn-tab-id)))))))})))