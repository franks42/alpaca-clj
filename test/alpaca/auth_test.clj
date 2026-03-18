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
    (is (.contains (:reason result) "not trusted"))))

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
;; Audience binding
;; ---------------------------------------------------------------------------

(deftest audience-match-allowed
  (let [token  (issue-bound #{:read} #{"market"})
        signed (auth/sign-request :get "/market/clock" {} agent-kp "proxy-a:8080")
        sig    {:agent-key (:agent-key signed) :sig (:sig signed)
                :timestamp (:timestamp signed) :body (:body signed)}
        result (auth/verify-and-authorize
                token root-pk
                {:effect :read :domain "market" :method :get :path "/market/clock"}
                sig {} {:proxy-identity "proxy-a:8080"})]
    (is (:authorized result))))

(deftest audience-mismatch-denied
  (let [token  (issue-bound #{:read} #{"market"})
        signed (auth/sign-request :get "/market/clock" {} agent-kp "proxy-a:8080")
        sig    {:agent-key (:agent-key signed) :sig (:sig signed)
                :timestamp (:timestamp signed) :body (:body signed)}
        result (auth/verify-and-authorize
                token root-pk
                {:effect :read :domain "market" :method :get :path "/market/clock"}
                sig {} {:proxy-identity "proxy-b:9090"})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "Audience mismatch"))))

(deftest audience-not-set-passes
  (testing "No audience in envelope, no proxy-identity → passes"
    (let [token  (issue-bound #{:read} #{"market"})
          signed (auth/sign-request :get "/market/clock" {} agent-kp)
          sig    {:agent-key (:agent-key signed) :sig (:sig signed)
                  :timestamp (:timestamp signed) :body (:body signed)}
          result (auth/verify-and-authorize
                  token root-pk
                  {:effect :read :domain "market" :method :get :path "/market/clock"}
                  sig {} {})]
      (is (:authorized result)))))

;; ---------------------------------------------------------------------------
;; Hex utilities
;; ---------------------------------------------------------------------------

(deftest hex-round-trip
  (let [original (byte-array [0 1 127 -128 -1 42])
        hex      (auth/bytes->hex original)
        back     (auth/hex->bytes hex)]
    (is (= (seq original) (seq back)))))

;; ---------------------------------------------------------------------------
;; Multi-root scoped trust
;; ---------------------------------------------------------------------------

(def authority-b-kp (auth/generate-keypair))
(def authority-b-pk-bytes (crypto/encode-public-key (:pub authority-b-kp)))

(defn issue-bearer-with [kp effects domains]
  (auth/issue-token kp {:effects effects :domains domains}))

(defn multi-root-config []
  {(crypto/encode-public-key (:pub root-kp))
   {:scoped-to {:effects #{:read} :domains #{"market"}}}
   authority-b-pk-bytes
   {:scoped-to {:effects #{:write :destroy} :domains #{"trade"}}}})

(deftest multi-root-authority-a-in-scope
  (let [token  (issue-bearer-with root-kp #{:read} #{"market"})
        result (auth/verify-and-authorize
                token (multi-root-config) {:effect :read :domain "market"})]
    (is (:authorized result))))

(deftest multi-root-authority-a-out-of-scope
  (testing "Authority A is only trusted for read on market, not write on trade"
    (let [token  (issue-bearer-with root-kp #{:write} #{"trade"})
          result (auth/verify-and-authorize
                  token (multi-root-config) {:effect :write :domain "trade"})]
      (is (not (:authorized result)))
      (is (.contains (:reason result) "not trusted")))))

(deftest multi-root-authority-b-in-scope
  (let [token  (issue-bearer-with authority-b-kp #{:write} #{"trade"})
        result (auth/verify-and-authorize
                token (multi-root-config) {:effect :write :domain "trade"})]
    (is (:authorized result))))

(deftest multi-root-authority-b-out-of-scope
  (testing "Authority B is only trusted for write/destroy on trade, not read on market"
    (let [token  (issue-bearer-with authority-b-kp #{:read} #{"market"})
          result (auth/verify-and-authorize
                  token (multi-root-config) {:effect :read :domain "market"})]
      (is (not (:authorized result))))))

(deftest multi-root-unknown-signer-denied
  (let [unknown-kp (auth/generate-keypair)
        token      (issue-bearer-with unknown-kp #{:read} #{"market"})
        result     (auth/verify-and-authorize
                    token (multi-root-config) {:effect :read :domain "market"})]
    (is (not (:authorized result)))))

;; ---------------------------------------------------------------------------
;; SDSI group authorization
;; ---------------------------------------------------------------------------

(def agent2-kp (auth/generate-keypair))
(def agent2-pk-bytes (crypto/encode-public-key (:pub agent2-kp)))

(def test-roster
  {"traders"  [(auth/bytes->hex agent-pk-bytes)
               (auth/bytes->hex agent2-pk-bytes)]
   "monitors" [(auth/bytes->hex agent-pk-bytes)]})

(defn issue-group [rights]
  (auth/issue-group-token root-kp {:rights rights}))

(defn make-sig-meta [method path body kp]
  (let [signed (auth/sign-request method path body kp)]
    {:agent-key (:agent-key signed)
     :sig       (:sig signed)
     :timestamp (:timestamp signed)
     :body      (:body signed)}))

(deftest sdsi-group-member-allowed
  (let [token  (issue-group [["traders" :read "market"]])
        req    {:effect :read :domain "market"
                :method :get :path "/market/clock"}
        sig    (make-sig-meta :get "/market/clock" {} agent-kp)
        result (auth/verify-and-authorize
                token root-pk req sig {} {:roster test-roster})]
    (is (:authorized result))
    (is (:sdsi-group result))))

(deftest sdsi-second-group-member-allowed
  (let [token  (issue-group [["traders" :read "market"]])
        req    {:effect :read :domain "market"
                :method :get :path "/market/clock"}
        sig    (make-sig-meta :get "/market/clock" {} agent2-kp)
        result (auth/verify-and-authorize
                token root-pk req sig {} {:roster test-roster})]
    (is (:authorized result))
    (is (:sdsi-group result))))

(deftest sdsi-non-member-denied
  (let [token  (issue-group [["traders" :read "market"]])
        req    {:effect :read :domain "market"
                :method :get :path "/market/clock"}
        sig    (make-sig-meta :get "/market/clock" {} rogue-kp)
        result (auth/verify-and-authorize
                token root-pk req sig {} {:roster test-roster})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "not in any group"))))

(deftest sdsi-wrong-effect-denied
  (let [token  (issue-group [["traders" :read "market"]])
        req    {:effect :write :domain "market"
                :method :post :path "/market/quote"}
        sig    (make-sig-meta :post "/market/quote" {:symbol "AAPL"} agent-kp)
        result (auth/verify-and-authorize
                token root-pk req sig {:symbol "AAPL"} {:roster test-roster})]
    (is (not (:authorized result)))))

(deftest sdsi-wrong-domain-denied
  (let [token  (issue-group [["traders" :read "market"]])
        req    {:effect :read :domain "account"
                :method :get :path "/account/info"}
        sig    (make-sig-meta :get "/account/info" {} agent-kp)
        result (auth/verify-and-authorize
                token root-pk req sig {} {:roster test-roster})]
    (is (not (:authorized result)))))

(deftest sdsi-multiple-rights
  (let [token  (issue-group [["traders" :read "market"]
                             ["traders" :write "trade"]])
        req1   {:effect :read :domain "market"
                :method :get :path "/market/clock"}
        req2   {:effect :write :domain "trade"
                :method :post :path "/trade/place-order"}]
    (testing "read market allowed"
      (let [sig (make-sig-meta :get "/market/clock" {} agent-kp)
            r   (auth/verify-and-authorize
                 token root-pk req1 sig {} {:roster test-roster})]
        (is (:authorized r))))
    (testing "write trade allowed"
      (let [sig (make-sig-meta :post "/trade/place-order"
                               {:symbol "AAPL" :side "buy"} agent-kp)
            r   (auth/verify-and-authorize
                 token root-pk req2 sig {:symbol "AAPL" :side "buy"}
                 {:roster test-roster})]
        (is (:authorized r))))))

(deftest sdsi-no-roster-returns-error
  (let [token  (issue-group [["traders" :read "market"]])
        req    {:effect :read :domain "market"
                :method :get :path "/market/clock"}
        sig    (make-sig-meta :get "/market/clock" {} agent-kp)
        result (auth/verify-and-authorize token root-pk req sig {})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "roster"))))

(deftest sdsi-no-signature-returns-error
  (let [token  (issue-group [["traders" :read "market"]])
        result (auth/verify-and-authorize
                token root-pk {:effect :read :domain "market"})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "signed request"))))

(deftest sdsi-monitors-group-separate
  (testing "agent1 is in monitors, agent2 is not"
    (let [token (issue-group [["monitors" :read "account"]])]
      ;; agent1 in monitors → allowed
      (let [sig (make-sig-meta :get "/account/info" {} agent-kp)
            r   (auth/verify-and-authorize
                 token root-pk
                 {:effect :read :domain "account" :method :get :path "/account/info"}
                 sig {} {:roster test-roster})]
        (is (:authorized r)))
      ;; agent2 NOT in monitors → denied
      (let [sig (make-sig-meta :get "/account/info" {} agent2-kp)
            r   (auth/verify-and-authorize
                 token root-pk
                 {:effect :read :domain "account" :method :get :path "/account/info"}
                 sig {} {:roster test-roster})]
        (is (not (:authorized r)))))))
