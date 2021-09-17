(ns datalog-console.lib.encryption
  (:refer-clojure :exclude [import]))

;;TODO: do more with catch in all the promises

;; Constants

(defonce rsa-key-algo {:name "RSA-OAEP"
                       :modulusLength 4096
                       :publicExponent (js/Uint8Array. [1 0 1])
                       :hash "SHA-256"})

(defonce aes-key-algo {:name "AES-GCM"
                       :length 256
                       :iv (js/Uint8Array. 12)})


;; Utils

(defn encode [data]
  (.encode (js/TextEncoder.) data))

(defn decode [data]
  (.decode (js/TextDecoder.) data))

(defn buff->base64 [buff]
  (js/btoa (.apply js/String.fromCharCode nil (js/Uint8Array. buff))))

(defn base64->buff [b64]
  (.from js/Uint8Array. (js/atob b64)
         (fn [c] (.charCodeAt c nil))))


(defn generate-key []
  (let [key-atom (atom nil)]
    (-> (.generateKey js/crypto.subtle
                      (clj->js rsa-key-algo)
                      true
                      ["encrypt" "decrypt" "wrapKey" "unwrapKey"])
        (.then (fn [keys] (reset! key-atom {:private (.-privateKey keys)
                                            :public (.-publicKey keys)
                                            ;; TODO: add the others
                                            :object keys
                                            }))))
    key-atom))

(defn generate-aes-key []
  (let [key-atom (atom nil)]
    (-> (.generateKey js/crypto.subtle 
                      (clj->js aes-key-algo)
                      true 
                      ["encrypt" "decrypt"])
        (.then (fn [keys] (reset! key-atom keys
                                  #_{:public (.-publicKey keys)
                                            ;; TODO: add the others
                                            ;; :object keys
                                     }))))
    key-atom))

(defn import [{:keys [format keyData algorithm extractable keyUsages]} cb]
 (-> (.importKey js/crypto.subtle
                  format
                  keyData
                  algorithm
                  extractable
                  keyUsages)
      (.then (fn [result]
               (cb result)))
      (.catch #(js/console.log %))))

(defn export [{:keys [format key]} cb]
  ;; consider formats available. jwk produces JSON object and others produce ArrayBuffer
  ;; raw: Raw format.
  ;; pkcs8: PKCS #8 format.
  ;; spki: SubjectPublicKeyInfo format.
  ;; jwk: JSON Web Key format.
  (-> (.exportKey js/crypto.subtle
                  format
                  key)
      (.then (fn [result]
               (cb result)))
      (.catch #(js/console.log %))))

(defn wrapKey [{:keys [format key wrappingKey wrapAlgo]} cb]
  (-> (.wrapKey js/crypto.subtle
                  format
                  key
                  wrappingKey 
                  wrapAlgo)
      (.then (fn [result]
               (cb result)))
      (.catch #(js/console.log %))))

(defn unwrapKey [{:keys [format wrappedKey unwrappingKey unwrapAlgo unwrappedKeyAlgo extractable keyUsages]} cb]
  (-> (.unwrapKey js/crypto.subtle
                  format
                  wrappedKey
                  unwrappingKey
                  unwrapAlgo
                  unwrappedKeyAlgo
                  extractable
                  keyUsages)
      (.then (fn [result]
               (cb result)))
      (.catch #(js/console.log %))))

(defn encrypt [{:keys [algorithm key data]} cb]
    ;; might want to do encoding outside of this function
  (-> (.encrypt js/crypto.subtle
                (clj->js algorithm)
                key
                (encode data))
      (.then (fn [s]
               (cb (buff->base64 s))))
      (.catch #(js/console.log %))))


(defn decrypt [{:keys [algorithm key data]} cb]
  ;; might want to do decoding outside of this function
  (when key
    (-> (.decrypt js/crypto.subtle
                  (clj->js algorithm)
                  key
                  (base64->buff data))
        (.then (fn [s]
                 (cb (decode s))))
        (.catch #(js/console.log %)))))



;; Application specific code

(defn import-jwk [jwk cb]
  (import {:format "jwk"
           :keyData jwk
           :algorithm (clj->js rsa-key-algo)
           :extractable true
           :keyUsages ["encrypt" "wrapKey"]}
          cb))

(defn key-swap [{:keys [received-key wrap-settings]} cb]
  (import-jwk received-key
   (fn [imported-key]
     (wrapKey (assoc wrap-settings :wrappingKey imported-key)
              #(cb %)))))
