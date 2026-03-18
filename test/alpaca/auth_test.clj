(ns alpaca.auth-test
  "Tests for Stroopwafel authentication and authorization.
   Covers bearer tokens, requester binding, replay protection,
   envelope integrity, and effect/domain scoping."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string]
            [alpaca.auth :as auth]
            [stroopwafel.crypto :as crypto]))

;; ---------------------------------------------------------------------------
;; Test fixtures
;; ---------------------------------------------------------------------------

(def root-kp (auth/generate-keypair))
(def root-pk (:pub root-kp))

(def agent-kp (auth/generate-keypair))
(def agent-pk-bytes (crypto/encode-public-key (:pub agent-kp)))

(def rogue-kp (auth/generate-keypair))

(defn issue-bearer [effects domains]
  (auth/issue-token root-kp {:effects effects :domains domains}))

(defn issue-bound [effects domains]
  (auth/issue-token root-kp {:effects effects :domains domains
                             :agent-key agent-pk-bytes}))

;; ---------------------------------------------------------------------------
;; Token serialization round-trip
;; ---------------------------------------------------------------------------

(deftest token-serialize-deserialize
  (let [token-str (issue-bearer #{:read} #{"market"})
        token     (auth/deserialize-token token-str)]
    (is (map? token))
    (is (vector? (:blocks token)))
    (is (= 1 (count (:blocks token))))
    (is (= :sealed (get-in token [:proof :type])))))

;; ---------------------------------------------------------------------------
;; Bearer token authorization
;; ---------------------------------------------------------------------------

(deftest bearer-read-market-allowed
  (let [token  (issue-bearer #{:read} #{"market"})
        result (auth/verify-and-authorize token root-pk
                                          {:effect :read :domain "market"})]
    (is (:authorized result))))

(deftest bearer-wrong-effect-denied
  (let [token  (issue-bearer #{:read} #{"market"})
        result (auth/verify-and-authorize token root-pk
                                          {:effect :write :domain "market"})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "write"))))

(deftest bearer-wrong-domain-denied
  (let [token  (issue-bearer #{:read} #{"market"})
        result (auth/verify-and-authorize token root-pk
                                          {:effect :read :domain "account"})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "account"))))

(deftest bearer-multiple-effects-and-domains
  (let [token (issue-bearer #{:read :write :destroy} #{"market" "account" "trade"})]
    (testing "all combinations allowed"
      (doseq [effect [:read :write :destroy]
              domain ["market" "account" "trade"]]
        (let [result (auth/verify-and-authorize token root-pk
                                                {:effect effect :domain domain})]
          (is (:authorized result)
              (str (name effect) " on " domain " should be allowed")))))))

(deftest bearer-wrong-root-key-denied
  (let [token    (issue-bearer #{:read} #{"market"})
        wrong-kp (auth/generate-keypair)
        result   (auth/verify-and-authorize token (:pub wrong-kp)
                                            {:effect :read :domain "market"})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "signature"))))

(deftest bearer-tampered-token-denied
  (let [token-str (issue-bearer #{:read} #{"market"})
        tampered  (clojure.string/replace token-str "market" "hacked")
        result    (auth/verify-and-authorize tampered root-pk
                                             {:effect :read :domain "market"})]
    (is (not (:authorized result)))))

;; ---------------------------------------------------------------------------
;; Requester-bound tokens
;; ---------------------------------------------------------------------------

(deftest bound-token-without-signature-denied
  (let [token  (issue-bound #{:read} #{"market"})
        result (auth/verify-and-authorize token root-pk
                                          {:effect :read :domain "market"})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "signed request"))))

(deftest bound-token-with-valid-signature-allowed
  (let [token   (issue-bound #{:read} #{"market"})
        signed  (auth/sign-request :get "/market/clock" {} agent-kp)
        sig-meta {:agent-key (:agent-key signed)
                  :sig       (:sig signed)
                  :timestamp (:timestamp signed)
                  :body      (:body signed)}
        result  (auth/verify-and-authorize
                 token root-pk
                 {:effect :read :domain "market"
                  :method :get :path "/market/clock"}
                 sig-meta
                 {})]
    (is (:authorized result))
    (is (:requester-bound result))))

(deftest bound-token-wrong-agent-key-denied
  (let [token   (issue-bound #{:read} #{"market"})
        signed  (auth/sign-request :get "/market/clock" {} rogue-kp)
        sig-meta {:agent-key (:agent-key signed)
                  :sig       (:sig signed)
                  :timestamp (:timestamp signed)
                  :body      (:body signed)}
        result  (auth/verify-and-authorize
                 token root-pk
                 {:effect :read :domain "market"
                  :method :get :path "/market/clock"}
                 sig-meta
                 {})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "not bound"))))

;; ---------------------------------------------------------------------------
;; Envelope binding
;; ---------------------------------------------------------------------------

(deftest envelope-method-mismatch-denied
  (let [token   (issue-bound #{:read} #{"market"})
        signed  (auth/sign-request :get "/market/clock" {} agent-kp)
        sig-meta {:agent-key (:agent-key signed)
                  :sig       (:sig signed)
                  :timestamp (:timestamp signed)
                  :body      (:body signed)}
        result  (auth/verify-and-authorize
                 token root-pk
                 {:effect :read :domain "market"
                  :method :post :path "/market/clock"}
                 sig-meta
                 {})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "method"))))

(deftest envelope-path-mismatch-denied
  (let [token   (issue-bound #{:read} #{"market"})
        signed  (auth/sign-request :get "/market/clock" {} agent-kp)
        sig-meta {:agent-key (:agent-key signed)
                  :sig       (:sig signed)
                  :timestamp (:timestamp signed)
                  :body      (:body signed)}
        result  (auth/verify-and-authorize
                 token root-pk
                 {:effect :read :domain "market"
                  :method :get :path "/market/quote"}
                 sig-meta
                 {})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "path"))))

(deftest envelope-body-mismatch-denied
  (let [token   (issue-bound #{:read} #{"market"})
        signed  (auth/sign-request :post "/market/quote" {:symbol "AAPL"} agent-kp)
        sig-meta {:agent-key (:agent-key signed)
                  :sig       (:sig signed)
                  :timestamp (:timestamp signed)
                  :body      (:body signed)}
        result  (auth/verify-and-authorize
                 token root-pk
                 {:effect :read :domain "market"
                  :method :post :path "/market/quote"}
                 sig-meta
                 {:symbol "MSFT"})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "body"))))

;; ---------------------------------------------------------------------------
;; Replay protection
;; ---------------------------------------------------------------------------

(deftest replay-same-request-id-denied
  (let [token   (issue-bound #{:read} #{"market"})
        signed  (auth/sign-request :get "/market/clock" {} agent-kp)
        sig-meta {:agent-key (:agent-key signed)
                  :sig       (:sig signed)
                  :timestamp (:timestamp signed)
                  :body      (:body signed)}
        req     {:effect :read :domain "market"
                 :method :get :path "/market/clock"}
        ;; First request — should pass
        result1 (auth/verify-and-authorize token root-pk req sig-meta {})
        ;; Replay — same sig-meta — should fail
        result2 (auth/verify-and-authorize token root-pk req sig-meta {})]
    (is (:authorized result1) "First request should pass")
    (is (not (:authorized result2)) "Replay should be denied")
    (is (.contains (:reason result2) "Replay"))))

(deftest fresh-request-ids-both-allowed
  (let [token (issue-bound #{:read} #{"market"})
        req   {:effect :read :domain "market"
               :method :get :path "/market/clock"}
        make-sig (fn []
                   (let [signed (auth/sign-request :get "/market/clock" {} agent-kp)]
                     {:agent-key (:agent-key signed)
                      :sig       (:sig signed)
                      :timestamp (:timestamp signed)
                      :body      (:body signed)}))
        result1 (auth/verify-and-authorize token root-pk req (make-sig) {})
        result2 (auth/verify-and-authorize token root-pk req (make-sig) {})]
    (is (:authorized result1))
    (is (:authorized result2))))

;; ---------------------------------------------------------------------------
;; Hex utilities
;; ---------------------------------------------------------------------------

(deftest hex-round-trip
  (let [original (byte-array [0 1 127 -128 -1 42])
        hex      (auth/bytes->hex original)
        back     (auth/hex->bytes hex)]
    (is (= (seq original) (seq back)))))
