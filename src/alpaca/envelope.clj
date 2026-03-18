(ns alpaca.envelope
  "Generic signed envelope — sign and verify any EDN message.

   The envelope handles cryptographic signing, verification, timestamps,
   and expiry. It does NOT handle trust, replay, audience, or policy —
   those belong to the enforcement layer above.

   Designed for migration to stroopwafel.envelope."
  (:require [stroopwafel.crypto :as crypto]
            [cedn.core :as cedn]
            [com.github.franks42.uuidv7.core :as uuidv7]
            [clojure.edn :as edn]))

(defn sign
  "Sign a message with the agent's private key.

   Arguments:
     message     — any CEDN-serializable EDN value (opaque to envelope)
     private-key — Ed25519 private key
     public-key  — Ed25519 public key
     ttl-seconds — how long the envelope is valid (default 120)

   Returns:
     {:envelope  {:message <message> :signer-key <pk-bytes>
                  :request-id <UUIDv7-string> :expires <epoch-ms>}
      :signature <bytes>}"
  ([message private-key public-key]
   (sign message private-key public-key 120))
  ([message private-key public-key ttl-seconds]
   (let [now        (System/currentTimeMillis)
         expires    (+ now (* ttl-seconds 1000))
         pk-bytes   (crypto/encode-public-key public-key)
         request-id (str (uuidv7/uuidv7))
         inner      {:message    message
                     :signer-key pk-bytes
                     :request-id request-id
                     :expires    expires}
         sig-bytes  (-> inner crypto/encode-block crypto/sha256
                        (->> (crypto/sign private-key)))]
     {:envelope  inner
      :signature sig-bytes})))

(defn verify
  "Verify a signed envelope.

   Arguments:
     outer — {:envelope <inner-map> :signature <bytes>}

   Returns:
     {:valid?     true/false
      :message    <the payload>
      :signer-key <pk-bytes>
      :request-id <UUIDv7-string>
      :timestamp  <epoch-ms from UUIDv7>
      :expires    <epoch-ms>
      :expired?   true/false
      :age-ms         <long>
      :digest         <bytes>    ;; SHA-256 of CEDN(inner) — unique per envelope
      :message-digest <bytes>}   ;; SHA-256 of CEDN(message) — same across signers

   Does NOT reject expired envelopes — reports :expired?, caller decides."
  [outer]
  (let [{:keys [envelope signature]} outer
        {:keys [message signer-key request-id expires]} envelope
        now      (System/currentTimeMillis)
        pub      (crypto/decode-public-key signer-key)
        hash     (crypto/sha256 (crypto/encode-block envelope))
        msg-hash (crypto/sha256 (crypto/encode-block message))
        valid?   (crypto/verify pub hash signature)
        ts       (try
                   (uuidv7/extract-ts (parse-uuid request-id))
                   (catch Exception _ nil))
        age-ms   (when ts (- now ts))]
    {:valid?     valid?
     :message    message
     :signer-key signer-key
     :request-id request-id
     :timestamp  ts
     :expires    expires
     :expired?   (> now expires)
     :age-ms         age-ms
     :digest         hash
     :message-digest msg-hash}))

(defn serialize
  "Serialize an outer envelope to a CEDN string for transport."
  [outer]
  (cedn/canonical-str outer))

(defn deserialize
  "Deserialize a CEDN string back to an outer envelope."
  [s]
  (edn/read-string {:readers cedn/readers} s))
