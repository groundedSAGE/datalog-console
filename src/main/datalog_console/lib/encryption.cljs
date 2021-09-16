(ns datalog-console.lib.encryption)


;; consider promise core async interop. But we don't want this to go into the integrations and make people depend on core.async
(defn generate-key []
  (let [key-atom (atom nil)]
    (-> (.generateKey js/crypto.subtle
                      (clj->js {:name "RSA-OAEP"
                                :modulusLength 4096
                                :publicExponent (js/Uint8Array. [1 0 1])
                                :hash "SHA-256"})
                      true
                      ["encrypt" "decrypt" "wrapKey" "unwrapKey"])
        (.then (fn [keys] (reset! key-atom keys))))
    key-atom))

(defn encode [data]
  (.encode (js/TextEncoder.) data))

(defn decode [data]
  (.decode (js/TextDecoder.) data))

(defn encrypt [{:keys [cb keypair data]}]
    ;; might want to do encoding outside of this function
  (-> (.encrypt js/crypto.subtle
                #js {:name "RSA-OAEP"} (.-publicKey @keypair) (encode data))
      (.then (fn [s]
               (cb s)))))

(defn decrypt [{:keys [cb keypair data]}]
  ;; might want to do decoding outside of this function
  (-> (.decrypt js/crypto.subtle
                #js {:name "RSA-OAEP"} (.-privateKey @keypair) data)
      (.then (fn [s]
               (cb (decode s))))
      (.catch #(js/console.log %))))

