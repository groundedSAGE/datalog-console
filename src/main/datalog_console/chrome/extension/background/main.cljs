(ns datalog-console.chrome.extension.background.main
  {:no-doc true}
  (:require [goog.object :as gobj]
            [cljs.reader]
            [clojure.string :as str]
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

(defn random-code []
  (let [numbers (take 6 (repeatedly #(rand-int 10)))
        parts (map str/join (partition 3 numbers))]
     (str (first parts) " - " (second parts))))

(defonce code (random-code))

(defonce keypair (crypto/generate-key))

(js/chrome.runtime.onConnect.addListener
 (fn [port]
   (let [tab-id (atom (gobj/getValueByKeys port "sender" "tab" "id"))]
     (msg/create-conn {:to port
                       :encryption (atom nil)
                       :send-fn (fn [{:keys [to msg]}]
                                  (.postMessage to (clj->js {(str ::msg/msg) (pr-str msg)})))
                       :tab-id tab-id
                       :routes {:datalog-console.remote/secure-connection
                                (fn [conn msg]
                                  
                                  ;; Send public key to the integration
                                  (when (:secure? (:data msg))
                                    (crypto/export {:format "jwk"
                                                    :key (:public @keypair)}
                                                   (fn [exported-key]
                                                     (msg/send {:conn (get-in @port-conns [:remote @(:tab-id @conn)])
                                                                :type :datalog-console.background/secure-connection
                                                                :data {:initial-key exported-key}}))))
                                  
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
                                                        (swap! key-manager assoc @(:tab-id @conn) key)))))

                                :datalog-console.client/init! (fn [conn msg]
                                                                (let [tab-id @(:tab-id @conn)]
                                                                  (swap! port-conns assoc-in [:tools tab-id] conn)

                                                                  ;; Send confirmation code to devtool
                                                                  (msg/send {:conn (get-in @port-conns [:tools tab-id])
                                                                             :type :datalog-console.background/confirmation-code
                                                                             :data code})

                                                                  ;; Forward message to application
                                                                  (msg/send {:conn (get-in @port-conns [:remote tab-id])
                                                                             :type (:type msg)
                                                                             :data {:confirmation-code code}
                                                                             :encryption {:key (get @key-manager tab-id)
                                                                                          :algorithm crypto/aes-key-algo}})))
                                :datalog-console.remote/db-detected (fn [conn _msg]
                                                                     (msg/send {:conn (get-in @port-conns [:remote @(:tab-id @conn)])
                                                                                :type :datalog-console.background/secure-connection
                                                                                :data {}})
                                                                      (set-icon-and-popup @(:tab-id @conn)))
                                :* (fn [conn msg]
                                     (let [env-context (if (gobj/getValueByKeys (:to @conn) "sender" "tab" "id") :tools :remote)
                                           to (get-in @port-conns [env-context @(:tab-id @conn)])]
                                       ((msg/forward to) conn msg)))}
                       :receive-fn (fn [cb conn]
                                     (when-let [tab-id (gobj/getValueByKeys port "sender" "tab" "id")]
                                      ;; (js/console.log "the tab id: " tab-id)
                                       (swap! port-conns assoc-in [:remote tab-id] conn))
                                     (let [listener (fn [message _port]
                                                      (when-let [msg-tab-id (gobj/get message "tab-id")]
                                                        (reset! tab-id msg-tab-id))
                                                      (let [raw-msg (gobj/get message (str ::msg/msg))
                                                            parsed-msg (cljs.reader/read-string raw-msg)]
                                                        (if (:encrypted? parsed-msg)
                                                          (crypto/decrypt {:key (get @key-manager @(:tab-id @conn))
                                                                           :algorithm crypto/aes-key-algo
                                                                           :data (:data parsed-msg)}
                                                                          #(cb (assoc parsed-msg :data %)))
                                                          (cb parsed-msg))))]
                                       (.addListener (gobj/get port "onMessage") listener)
                                       (.addListener (gobj/get port "onDisconnect")
                                                     (fn [port]
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

