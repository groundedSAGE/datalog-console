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

(defn buff-to-base64 [buff]
  (js/btoa (.apply js/String.fromCharCode nil (js/Uint8Array. buff))))

(defn base64-to-buff [buff]
  (.from js/Uint8Array.
         (js/atob (js/btoa (.apply js/String.fromCharCode nil (js/Uint8Array. buff))))
         (fn [c] (.charCodeAt c nil))))

(defn encrypt [{:keys [key-type keypair data]} cb]
    ;; might want to do encoding outside of this function
  (when @keypair
    (-> (.encrypt js/crypto.subtle
                  #js {:name "RSA-OAEP"} (key-type @keypair) (encode data))
        (.then (fn [s]
                 (cb (buff-to-base64 s))))
        (.catch #(js/console.log %)))))

(defn decrypt [{:keys [key-type keypair data]} cb]
  ;; might want to do decoding outside of this function
  (when @keypair
    (-> (.decrypt js/crypto.subtle
                  #js {:name "RSA-OAEP"} (key-type @keypair) (base64-to-buff data))
        (.then (fn [s]
                 (js/console.log)
                 (cb (decode s))))
        (.catch #(js/console.log %)))))

