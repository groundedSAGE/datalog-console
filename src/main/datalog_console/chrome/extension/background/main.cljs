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
                           :popup nil}))



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

(defn random-code []
  (let [numbers (take 6 (repeatedly #(rand-int 10)))
        parts (map str/join (partition 3 numbers))]
     (str (first parts) " - " (second parts))))

(defonce code (random-code))

(defonce keypair (crypto/generate-key))


(defonce popup-open? (atom false))
(defn popup-port? [port]
  (= ":datalog-console.remote/extension-popup" (gobj/get port "name")))

(defn get-current-tab [cb]
  (.query js/chrome.tabs #js {:active true :currentWindow true} (fn [tabs]
                                                                  (let [current-tab (-> (js->clj tabs) first (get "id"))]
                                                                    (cb current-tab)))))

(defn real-time-popup-update [conn port]
  (when (popup-port? port)
    (when-not @popup-open? (reset! popup-open? true))
    #_(get-current-tab (fn [tab]
                       (msg/send {:conn (get-in @port-conns [:remote tab])
                                  :type :datalog-console/init-handshake!})))

    (async/go-loop []
      (when @popup-open?
        (get-current-tab (fn [tab]
                             (msg/send {:conn conn
                                        :type :datalog-console.background/status-update
                                        :data {:current-tab tab}}))))
        (<! (async/timeout 3000))
        (recur))))

(js/chrome.runtime.onConnect.addListener
 (fn [port]
   (let [tab-id (atom (or (gobj/getValueByKeys port "sender" "tab" "id") nil))]
     (msg/create-conn {:to port
                       :id (gobj/get port "name")
                       :encryption (atom nil)
                       :send-fn (fn [{:keys [to msg]}]
                                  (.postMessage to (clj->js {(str ::msg/msg) (pr-str msg)})))
                       :tab-id tab-id
                       :routes {:datalog-console.extension/integration-handshake!
                                (fn [conn msg]
                                  (let [tab-id @(:tab-id @conn)
                                        handshake-ch (get-in @integration-configs [tab-id :initial])]
                                    ;; Store the initial config data for the integration
                                    (when-let [init-config (:init-config (:data msg))]
                                      (go (>! handshake-ch init-config)))

                                    ;; Send public key to the integration
                                    (when (:secure? (:init-config (:data msg)))
                                      (crypto/export {:format "jwk"
                                                      :key (:public @keypair)}
                                                     (fn [exported-key]
                                                       (msg/send {:conn (get-in @port-conns [:remote tab-id])
                                                                  :type :datalog-console.extension/integration-handshake!
                                                                  :data {:init-key exported-key}}))))
                                    ;; Receive wrapped AES key from integration
                                    (when-let [encrypted-key (:wrapped-key (:data msg))]
                                      (crypto/unwrapKey {:format "jwk"
                                                         :wrappedKey (crypto/base64->buff encrypted-key)
                                                         :unwrappingKey (:private @keypair)
                                                         :unwrapAlgo (clj->js crypto/rsa-key-algo)
                                                         :unwrappedKeyAlgo (clj->js crypto/aes-key-algo)
                                                         :extractable true
                                                         :keyUsages ["encrypt" "decrypt"]}
                                                        (fn [key]
                                                          (go (>! handshake-ch key))
                                                          (swap! key-manager assoc tab-id key))))))


                                :datalog-console.client/init!
                                (fn [conn msg]
                                  (let [tab-id @(:tab-id @conn)]

                                    ;; Add the port for the devtool to the port-conns
                                    (swap! port-conns assoc-in [:tools tab-id] conn)
                                    (swap! integration-configs assoc-in [tab-id :initial] (chan))
                                    (go
                                      (let [handshake-data (get-in @integration-configs [tab-id :initial])]
                                        (when (:secure? (<! handshake-data))

                                        ;; Send confirmation code to devtool
                                          (msg/send {:conn (get-in @port-conns [:tools tab-id])
                                                     :type :datalog-console.background/confirmation-code
                                                     :data code})

                                        ;; Send confirmation code to application
                                          (msg/send {:conn (get-in @port-conns [:remote tab-id])
                                                     :type (:type msg)
                                                     :data {:confirmation-code code}
                                                     :encryption {:key (<! handshake-data)
                                                                  :algorithm crypto/aes-key-algo}}))))

                                    ;; Start connection handshake
                                    (msg/send {:conn (get-in @port-conns [:remote tab-id])
                                               :type :datalog-console.extension/integration-handshake!})))
                                

                                :datalog-console.remote/db-detected
                                (fn [conn _msg]
                                  (set-icon-and-popup @(:tab-id @conn)))

                                :* (fn [conn msg]
                                     (let [env-context (if (gobj/getValueByKeys (:to @conn) "sender" "tab" "id") :tools :remote)
                                           to (get-in @port-conns [env-context @(:tab-id @conn)])]
                                       ((msg/forward to) conn msg)))}
                       :receive-fn (fn [cb conn]
                                     (when-let [tab-id (gobj/getValueByKeys port "sender" "tab" "id")]
                                       (js/console.log "the tab id: " tab-id)
                                       (swap! port-conns assoc-in [:remote tab-id] conn))
                                     (js/console.log "connected to:" (:id @conn))
                                     (let [listener (fn [message _port]
                                                      (when-let [msg-tab-id (gobj/get message "tab-id")]
                                                        (reset! tab-id msg-tab-id))
                                                      (let [raw-msg (gobj/get message (str ::msg/msg))
                                                            parsed-msg (cljs.reader/read-string raw-msg)
                                                            _ (js/console.log "received: " parsed-msg)]
                                                        (if (:encrypted? parsed-msg)
                                                          (crypto/decrypt {:key (get @key-manager @(:tab-id @conn))
                                                                           :algorithm crypto/aes-key-algo
                                                                           :data (:data parsed-msg)}
                                                                          #(cb (assoc parsed-msg :data %)))
                                                          (cb parsed-msg))))]
                                       (real-time-popup-update conn port)
                                       (.addListener (gobj/get port "onMessage") listener)
                                       (.addListener (gobj/get port "onDisconnect")
                                                     (fn [port]
                                                       (js/console.log "the disconnected port: " (gobj/get port "name"))
                                                       (when (popup-port? port)
                                                         (js/console.log "this is it" @popup-open?)
                                                         (reset! popup-open? false)
                                                         (js/console.log "this is it after" @popup-open?))
                                                       (when-let [msg (gobj/get port "onMessage")]
                                                         (.removeListener msg listener))
                                                       ;; conn-context may not work with other connection types: eg native application, cross-extension connections
                                                       (let [conn-context (if (gobj/getValueByKeys port "sender" "tab" "id")
                                                                            :remote
                                                                            :tools)]
                                                         (when-let [port-key (->> (conn-context @port-conns)
                                                                                  (keep (fn [[k v]] (when (= v port) k)))
                                                                                  (first))]
                                                           (swap! port-conns update conn-context dissoc port-key)))))))}))))

