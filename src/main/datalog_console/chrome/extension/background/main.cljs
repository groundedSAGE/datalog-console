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
(defonce secured-connections (atom {:waiting nil
                                    :secured nil 
                                    :failed nil}))

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

(defn start-integration-handshake! [tab-id]
  (js/console.log "starting integration handshake: " tab-id)
  (let [get-port (fn [context] (get-in @port-conns [context tab-id]))]
                                      ;; Handle connection handshake
                                    ;; TODO: Review
                                    ;; Currently this process is concretely tied to the sequential way we put data on the handshake-data-ch.
                                    ;; Perhaps less resilient to future changes with this implementation
    (swap! integration-configs assoc-in [tab-id :handshake] (chan))
    (go
      (let [handshake-data-ch (get-in @integration-configs [tab-id :handshake])]
        
        (let [first-handshake (<! handshake-data-ch)]
          (js/console.log "first-handshake: " first-handshake)
          (when (:secure? first-handshake)
            (js/console.log "SECURE CONNECTION!!")

            (js/console.log "the integration-handshake ports: " @port-conns)
          ;; Send confirmation code to devtool
            (when-let [tools-conn (get-port :tools)]
              (js/console.log "sending confirmation to tools")
              (msg/send {:conn tools-conn
                         :type :datalog-console.extension/integration-handshake!
                         :data {:confirmation-code code}}))

           ;; Send confirmation code to popup
            (when-let [popup-conn (get-port :popup)]
              (js/console.log "sending confirmation to pop")
              (msg/send {:conn popup-conn
                         :type :datalog-console.extension/integration-handshake!
                         :data {:confirmation-code code}}))

            (js/console.log "after sending confirmation to devtool: " @integration-configs)

          ;; Send confirmation code to application
            (msg/send {:conn (get-port :remote)
                       :type :datalog-console.extension/integration-handshake!
                       :data {:confirmation-code code}
                       :encryption {:key (<! handshake-data-ch)
                                    :algorithm crypto/aes-key-algo}})

            (js/console.log "after sending confirmation to application: " @integration-configs)))))

    ;; Start connection handshake
    (msg/send {:conn (get-port :remote)
               :type :datalog-console.extension/integration-handshake!
               :data :request-handshake})))

(defn handle-user-confirmation [tab-id confirmation]
  (case confirmation 
    true (swap! assoc integration-configs [])))



(js/chrome.runtime.onConnect.addListener
 (fn [port]
   (js/console.log "port connected: " (gobj/get port "name"))
   (msg/create-conn {:to port
                     :encryption (atom nil)
                     :send-fn (fn [{:keys [to msg]}]
                                (js/console.log "sending: " msg)
                                (.postMessage to (clj->js {(str ::msg/msg) (pr-str msg)})))
                     :tab-id (atom (or nil (get-browser-tab-id port)))
                     :routes {:datalog-console.remote/integration-init!
                              (fn [conn msg]
                                (js/console.log "the integration init! " (:data msg))
                                (swap! integration-configs assoc @(:tab-id @conn) (:data msg))
                                (set-icon-and-popup @(:tab-id @conn)))

                              :datalog-console.client/init!
                              (fn [conn msg]
                                (swap! port-conns assoc-in [:tools @(:tab-id @conn)] conn)
                                (js/console.log "this is the " @integration-configs)
                                #_(start-integration-handshake! @(:tab-id @conn)))

                              :datalog-console.popup/init!
                              (fn [conn msg]
                                (swap! port-conns assoc-in [:popup @(:tab-id @conn)] conn)
                                (msg/send {:conn conn
                                           :type :datalog-console.popup/init-response!
                                           :data (get @integration-configs @(:tab-id @conn))}))

                              :datalog-console.popup/connect!
                              (fn [conn msg]
                                (start-integration-handshake! @(:tab-id @conn)))

                              :datalog-console.extension/integration-handshake!
                              (fn [conn msg]
                                (let [tab-id @(:tab-id @conn)
                                      msg-data (:data msg)
                                      handshake-ch (get-in @integration-configs [tab-id :handshake])]

                                  (cond
                                    (:init-config msg-data)
                                    (go
                                      (js/console.log "integration configs: " @integration-configs)
                                      (>! handshake-ch (:init-config msg-data))
                                        ;; Send public key to the integration when :secure? flag is true
                                      (when (:secure? (:init-config msg-data))
                                        (crypto/export {:format "jwk"
                                                        :key (:public @keypair)}
                                                       (fn [exported-key]
                                                         (msg/send {:conn (get-in @port-conns [:remote tab-id])
                                                                    :type :datalog-console.extension/integration-handshake!
                                                                    :data {:init-key exported-key}})))))

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
                                      (swap! integration-configs assoc-in [tab-id :confirmation] (:user-confirmation msg-data))
                                      (swap! integration-configs update tab-id dissoc :handshake)
                                      (js/console.log "This is the user confirmation step: " @integration-configs)

                                      (msg/send {:conn (get-in @port-conns [:popup tab-id])
                                                 :type :datalog-console.extension/integration-handshake!
                                                 :data {:user-confirmation (:user-confirmation msg-data)}})
                                      ;; Send the user confirmation to the devtool. TODO: Turn into handle-user-confirmation to also send to other tool connections
                                      (msg/send {:conn (get-in @port-conns [:tools tab-id])
                                                 :type :datalog-console.extension/integration-handshake!
                                                 :data {:user-confirmation (:user-confirmation msg-data)}})))))






                              :* (fn [conn msg]
                                     ;;TODO: handle wildcard when multi variety ports
                                   (js/console.log "forwarding the message: " msg)
                                   (when-not (popup-port? port)
                                     (let [env-context (if (get-browser-tab-id (:to @conn)) :tools :remote)
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
                                                        (js/console.log "this is the message: " (:type parsed-msg))
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