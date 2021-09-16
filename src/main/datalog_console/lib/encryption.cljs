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
        (.then (fn [keys] (reset! key-atom {:private (.-privateKey keys)
                                            :public (.-publicKey keys)
                                            ;; TODO: add the others
                                            ;; :object keys
                                            }))))
    key-atom))

(defn encode [data]
  (.encode (js/TextEncoder.) data))

(defn decode [data]
  (.decode (js/TextDecoder.) data))

(defn encrypt [{:keys [cb key-type keypair data]}]
    ;; might want to do encoding outside of this function
  (when @keypair
    (-> (.encrypt js/crypto.subtle
                  #js {:name "RSA-OAEP"} (key-type @keypair) (encode data))
        (.then (fn [s]
                 (cb s)))
        (.catch #(js/console.log %)))))

(defn decrypt [{:keys [cb key-type keypair data]}]
  ;; might want to do decoding outside of this function
  (when @keypair
    (-> (.decrypt js/crypto.subtle
                  #js {:name "RSA-OAEP"} (key-type @keypair) data)
        (.then (fn [s]
                 (cb (decode s))))
        (.catch #(js/console.log %)))))

