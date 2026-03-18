(ns alpaca.envelope-test
  "Tests for the generic signed envelope."
  (:require [clojure.test :refer [deftest is testing]]
            [alpaca.envelope :as envelope]
            [stroopwafel.crypto :as crypto]))

;; ---------------------------------------------------------------------------
;; Test fixtures
;; ---------------------------------------------------------------------------

(def kp (let [raw (crypto/generate-keypair "Ed25519")]
          {:priv (.getPrivate raw) :pub (.getPublic raw)}))

(def kp2 (let [raw (crypto/generate-keypair "Ed25519")]
           {:priv (.getPrivate raw) :pub (.getPublic raw)}))

;; ---------------------------------------------------------------------------
;; Sign / verify round-trip
;; ---------------------------------------------------------------------------

(deftest sign-produces-correct-structure
  (let [outer (envelope/sign {:action :test} (:priv kp) (:pub kp))]
    (is (map? (:envelope outer)))
    (is (some? (:signature outer)))
    (let [inner (:envelope outer)]
      (is (= {:action :test} (:message inner)))
      (is (some? (:signer-key inner)))
      (is (string? (:request-id inner)))
      (is (number? (:expires inner))))))

(deftest verify-valid-envelope
  (let [outer  (envelope/sign {:action :test} (:priv kp) (:pub kp))
        result (envelope/verify outer)]
    (is (:valid? result))
    (is (= {:action :test} (:message result)))
    (is (some? (:signer-key result)))
    (is (string? (:request-id result)))
    (is (number? (:timestamp result)))
    (is (number? (:expires result)))
    (is (false? (:expired? result)))
    (is (number? (:age-ms result)))
    (is (< (:age-ms result) 5000))))

(deftest verify-tampered-message
  (let [outer    (envelope/sign {:action :test} (:priv kp) (:pub kp))
        tampered (assoc-in outer [:envelope :message] {:action :hacked})
        result   (envelope/verify tampered)]
    (is (not (:valid? result)))))

(deftest verify-tampered-signature
  (let [outer    (envelope/sign {:action :test} (:priv kp) (:pub kp))
        bad-sig  (byte-array (repeat 64 0))
        tampered (assoc outer :signature bad-sig)
        result   (envelope/verify tampered)]
    (is (not (:valid? result)))))

(deftest verify-wrong-key-in-envelope
  (testing "Swap signer-key to a different key — signature won't match"
    (let [outer    (envelope/sign {:action :test} (:priv kp) (:pub kp))
          other-pk (crypto/encode-public-key (:pub kp2))
          tampered (assoc-in outer [:envelope :signer-key] other-pk)
          result   (envelope/verify tampered)]
      (is (not (:valid? result))))))

;; ---------------------------------------------------------------------------
;; Expiry
;; ---------------------------------------------------------------------------

(deftest expires-computed-from-ttl
  (let [before (System/currentTimeMillis)
        outer  (envelope/sign {:x 1} (:priv kp) (:pub kp) 60)
        after  (System/currentTimeMillis)
        exp    (get-in outer [:envelope :expires])]
    (is (>= exp (+ before 60000)))
    (is (<= exp (+ after 60000)))))

(deftest expired-envelope-reported
  (let [outer  (envelope/sign {:x 1} (:priv kp) (:pub kp) 0)
        _      (Thread/sleep 2)
        result (envelope/verify outer)]
    (is (:valid? result) "Signature is still valid")
    (is (:expired? result) "But envelope should be expired")))

(deftest age-ms-computed
  (let [outer  (envelope/sign {:x 1} (:priv kp) (:pub kp))
        result (envelope/verify outer)]
    (is (some? (:age-ms result)))
    (is (< (:age-ms result) 5000))))

;; ---------------------------------------------------------------------------
;; Request-id
;; ---------------------------------------------------------------------------

(deftest request-id-is-uuidv7
  (let [outer (envelope/sign {:x 1} (:priv kp) (:pub kp))
        rid   (get-in outer [:envelope :request-id])]
    (is (some? (parse-uuid rid)) "Should be a valid UUID")
    (let [uuid (parse-uuid rid)]
      (is (= 7 (.version uuid)) "Should be version 7"))))

;; ---------------------------------------------------------------------------
;; Message opacity
;; ---------------------------------------------------------------------------

(deftest message-is-opaque
  (testing "Various EDN types as message"
    (doseq [msg [{:a 1} [:x :y] "hello" 42 nil]]
      (let [outer  (envelope/sign msg (:priv kp) (:pub kp))
            result (envelope/verify outer)]
        (is (:valid? result) (str "Valid for " (pr-str msg)))
        (is (= msg (:message result)) (str "Round-trip for " (pr-str msg)))))))

;; ---------------------------------------------------------------------------
;; Serialize / deserialize
;; ---------------------------------------------------------------------------

(deftest serialize-deserialize-round-trip
  (let [outer  (envelope/sign {:action :test} (:priv kp) (:pub kp))
        s      (envelope/serialize outer)
        back   (envelope/deserialize s)
        result (envelope/verify back)]
    (is (string? s))
    (is (:valid? result))
    (is (= {:action :test} (:message result)))))

;; ---------------------------------------------------------------------------
;; Different messages produce different signatures
;; ---------------------------------------------------------------------------

(deftest digest-returned
  (testing "Digest is 32-byte SHA-256"
    (let [v (envelope/verify (envelope/sign {:intent "buy"} (:priv kp) (:pub kp)))]
      (is (some? (:digest v)))
      (is (= 32 (count (:digest v))))))
  (testing "Different messages produce different digests"
    (let [v1 (envelope/verify (envelope/sign {:a 1} (:priv kp) (:pub kp)))
          v2 (envelope/verify (envelope/sign {:a 2} (:priv kp) (:pub kp)))]
      (is (not (crypto/bytes= (:digest v1) (:digest v2)))))))

(deftest message-digest-for-quorum
  (testing "Same message, different signers → same message-digest"
    (let [msg {:intent "buy 100 AAPL"}
          v1  (envelope/verify (envelope/sign msg (:priv kp) (:pub kp)))
          v2  (envelope/verify (envelope/sign msg (:priv kp2) (:pub kp2)))]
      (is (crypto/bytes= (:message-digest v1) (:message-digest v2)))
      (is (not (crypto/bytes= (:digest v1) (:digest v2)))
          "Full digests differ (different signer-key, request-id, expires)")))
  (testing "Different messages → different message-digest"
    (let [v1 (envelope/verify (envelope/sign {:a 1} (:priv kp) (:pub kp)))
          v2 (envelope/verify (envelope/sign {:a 2} (:priv kp) (:pub kp)))]
      (is (not (crypto/bytes= (:message-digest v1) (:message-digest v2)))))))

(deftest different-messages-different-signatures
  (let [o1 (envelope/sign {:a 1} (:priv kp) (:pub kp))
        o2 (envelope/sign {:a 2} (:priv kp) (:pub kp))]
    (is (not= (seq (:signature o1)) (seq (:signature o2))))))

(deftest different-signs-different-request-ids
  (let [o1 (envelope/sign {:a 1} (:priv kp) (:pub kp))
        o2 (envelope/sign {:a 1} (:priv kp) (:pub kp))]
    (is (not= (get-in o1 [:envelope :request-id])
              (get-in o2 [:envelope :request-id])))))
