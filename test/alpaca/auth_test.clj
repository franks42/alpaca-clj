(ns alpaca.auth-test
  "Tests for alpaca.auth — capability tokens, bearer/bound/group modes,
   envelope binding, replay protection, and multi-root scoped trust."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string]
            [alpaca.auth :as auth]
            [signet.key :as key]))

;; ---------------------------------------------------------------------------
;; Test fixtures
;; ---------------------------------------------------------------------------

(def root-kp (auth/generate-keypair))
(def root-kid (key/kid root-kp))

(def agent-kp (auth/generate-keypair))
(def agent-kid (key/kid agent-kp))

(def rogue-kp (auth/generate-keypair))

(defn issue-bearer [effects domains]
  (auth/issue-token root-kp {:effects effects :domains domains}))

(defn issue-bound [effects domains]
  (auth/issue-token root-kp {:effects   effects
                             :domains   domains
                             :agent-key agent-kid}))

;; ---------------------------------------------------------------------------
;; Token serialization round-trip
;; ---------------------------------------------------------------------------

(deftest token-serialize-deserialize
  (let [token-str (issue-bearer #{:read} #{"market"})
        token     (auth/deserialize-token token-str)]
    (is (= :signet/chain (:type token)))
    (is (vector? (:blocks token)))
    (is (= 1 (count (:blocks token))))
    (is (true? (:sealed (:proof token))))))

;; ---------------------------------------------------------------------------
;; Bearer token authorization
;; ---------------------------------------------------------------------------

(deftest bearer-read-market-allowed
  (let [token  (issue-bearer #{:read} #{"market"})
        result (auth/verify-and-authorize token root-kid
                                          {:effect :read :domain "market"})]
    (is (:authorized result))))

(deftest bearer-wrong-effect-denied
  (let [token  (issue-bearer #{:read} #{"market"})
        result (auth/verify-and-authorize token root-kid
                                          {:effect :write :domain "market"})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "write"))))

(deftest bearer-wrong-domain-denied
  (let [token  (issue-bearer #{:read} #{"market"})
        result (auth/verify-and-authorize token root-kid
                                          {:effect :read :domain "account"})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "account"))))

(deftest bearer-multiple-effects-and-domains
  (let [token (issue-bearer #{:read :write :destroy} #{"market" "account" "trade"})]
    (testing "all combinations allowed"
      (doseq [effect [:read :write :destroy]
              domain ["market" "account" "trade"]]
        (let [result (auth/verify-and-authorize token root-kid
                                                {:effect effect :domain domain})]
          (is (:authorized result)
              (str (name effect) " on " domain " should be allowed")))))))

(deftest bearer-wrong-root-key-denied
  (let [token     (issue-bearer #{:read} #{"market"})
        wrong-kid (key/kid (auth/generate-keypair))
        result    (auth/verify-and-authorize token wrong-kid
                                             {:effect :read :domain "market"})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "not trusted"))))

(deftest bearer-tampered-token-denied
  (let [token-str (issue-bearer #{:read} #{"market"})
        tampered  (clojure.string/replace token-str "market" "hacked")
        result    (auth/verify-and-authorize tampered root-kid
                                             {:effect :read :domain "market"})]
    (is (not (:authorized result)))))

;; ---------------------------------------------------------------------------
;; Requester-bound tokens
;; ---------------------------------------------------------------------------

(deftest bound-token-without-signature-denied
  (let [token  (issue-bound #{:read} #{"market"})
        result (auth/verify-and-authorize token root-kid
                                          {:effect :read :domain "market"})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "signed request"))))

(deftest bound-token-with-valid-signature-allowed
  (let [token    (issue-bound #{:read} #{"market"})
        sig-meta (auth/sign-request :get "/market/clock" {} agent-kp)
        result   (auth/verify-and-authorize
                  token root-kid
                  {:effect :read :domain "market"
                   :method :get :path "/market/clock"}
                  sig-meta
                  {})]
    (is (:authorized result))
    (is (:requester-bound result))))

(deftest bound-token-wrong-agent-key-denied
  (let [token    (issue-bound #{:read} #{"market"})
        sig-meta (auth/sign-request :get "/market/clock" {} rogue-kp)
        result   (auth/verify-and-authorize
                  token root-kid
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
  (let [token    (issue-bound #{:read} #{"market"})
        sig-meta (auth/sign-request :get "/market/clock" {} agent-kp)
        result   (auth/verify-and-authorize
                  token root-kid
                  {:effect :read :domain "market"
                   :method :post :path "/market/clock"}
                  sig-meta
                  {})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "method"))))

(deftest envelope-path-mismatch-denied
  (let [token    (issue-bound #{:read} #{"market"})
        sig-meta (auth/sign-request :get "/market/clock" {} agent-kp)
        result   (auth/verify-and-authorize
                  token root-kid
                  {:effect :read :domain "market"
                   :method :get :path "/market/quote"}
                  sig-meta
                  {})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "path"))))

(deftest envelope-body-mismatch-denied
  (let [token    (issue-bound #{:read} #{"market"})
        sig-meta (auth/sign-request :post "/market/quote" {:symbol "AAPL"} agent-kp)
        result   (auth/verify-and-authorize
                  token root-kid
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
  (let [token    (issue-bound #{:read} #{"market"})
        sig-meta (auth/sign-request :get "/market/clock" {} agent-kp)
        req      {:effect :read :domain "market"
                  :method :get :path "/market/clock"}
        result1  (auth/verify-and-authorize token root-kid req sig-meta {})
        result2  (auth/verify-and-authorize token root-kid req sig-meta {})]
    (is (:authorized result1) "First request should pass")
    (is (not (:authorized result2)) "Replay should be denied")
    (is (.contains (:reason result2) "Replay"))))

(deftest fresh-request-ids-both-allowed
  (let [token   (issue-bound #{:read} #{"market"})
        req     {:effect :read :domain "market"
                 :method :get :path "/market/clock"}
        result1 (auth/verify-and-authorize
                 token root-kid req
                 (auth/sign-request :get "/market/clock" {} agent-kp) {})
        result2 (auth/verify-and-authorize
                 token root-kid req
                 (auth/sign-request :get "/market/clock" {} agent-kp) {})]
    (is (:authorized result1))
    (is (:authorized result2))))

;; ---------------------------------------------------------------------------
;; Audience binding
;; ---------------------------------------------------------------------------

(deftest audience-match-allowed
  (let [token    (issue-bound #{:read} #{"market"})
        sig-meta (auth/sign-request :get "/market/clock" {} agent-kp "proxy-a:8080")
        result   (auth/verify-and-authorize
                  token root-kid
                  {:effect :read :domain "market" :method :get :path "/market/clock"}
                  sig-meta {} {:proxy-identity "proxy-a:8080"})]
    (is (:authorized result))))

(deftest audience-mismatch-denied
  (let [token    (issue-bound #{:read} #{"market"})
        sig-meta (auth/sign-request :get "/market/clock" {} agent-kp "proxy-a:8080")
        result   (auth/verify-and-authorize
                  token root-kid
                  {:effect :read :domain "market" :method :get :path "/market/clock"}
                  sig-meta {} {:proxy-identity "proxy-b:9090"})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "Audience mismatch"))))

(deftest audience-not-set-passes
  (testing "No audience in envelope, no proxy-identity → passes"
    (let [token    (issue-bound #{:read} #{"market"})
          sig-meta (auth/sign-request :get "/market/clock" {} agent-kp)
          result   (auth/verify-and-authorize
                    token root-kid
                    {:effect :read :domain "market" :method :get :path "/market/clock"}
                    sig-meta {} {})]
      (is (:authorized result)))))

;; ---------------------------------------------------------------------------
;; Multi-root scoped trust
;; ---------------------------------------------------------------------------

(def authority-b-kp (auth/generate-keypair))
(def authority-b-kid (key/kid authority-b-kp))

(defn issue-bearer-with [kp effects domains]
  (auth/issue-token kp {:effects effects :domains domains}))

(defn multi-root-config []
  {root-kid          {:scoped-to {:effects #{:read} :domains #{"market"}}}
   authority-b-kid   {:scoped-to {:effects #{:write :destroy} :domains #{"trade"}}}})

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
(def agent2-kid (key/kid agent2-kp))

(def test-roster
  {"traders"  [agent-kid agent2-kid]
   "monitors" [agent-kid]})

(defn issue-group [rights]
  (auth/issue-group-token root-kp {:rights rights}))

(defn make-sig-meta [method path body kp]
  (auth/sign-request method path body kp))

(deftest sdsi-group-member-allowed
  (let [token  (issue-group [["traders" :read "market"]])
        req    {:effect :read :domain "market"
                :method :get :path "/market/clock"}
        sig    (make-sig-meta :get "/market/clock" {} agent-kp)
        result (auth/verify-and-authorize
                token root-kid req sig {} {:roster test-roster})]
    (is (:authorized result))
    (is (:sdsi-group result))))

(deftest sdsi-second-group-member-allowed
  (let [token  (issue-group [["traders" :read "market"]])
        req    {:effect :read :domain "market"
                :method :get :path "/market/clock"}
        sig    (make-sig-meta :get "/market/clock" {} agent2-kp)
        result (auth/verify-and-authorize
                token root-kid req sig {} {:roster test-roster})]
    (is (:authorized result))
    (is (:sdsi-group result))))

(deftest sdsi-non-member-denied
  (let [token  (issue-group [["traders" :read "market"]])
        req    {:effect :read :domain "market"
                :method :get :path "/market/clock"}
        sig    (make-sig-meta :get "/market/clock" {} rogue-kp)
        result (auth/verify-and-authorize
                token root-kid req sig {} {:roster test-roster})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "not in any group"))))

(deftest sdsi-wrong-effect-denied
  (let [token  (issue-group [["traders" :read "market"]])
        req    {:effect :write :domain "market"
                :method :post :path "/market/quote"}
        sig    (make-sig-meta :post "/market/quote" {:symbol "AAPL"} agent-kp)
        result (auth/verify-and-authorize
                token root-kid req sig {:symbol "AAPL"} {:roster test-roster})]
    (is (not (:authorized result)))))

(deftest sdsi-wrong-domain-denied
  (let [token  (issue-group [["traders" :read "market"]])
        req    {:effect :read :domain "account"
                :method :get :path "/account/info"}
        sig    (make-sig-meta :get "/account/info" {} agent-kp)
        result (auth/verify-and-authorize
                token root-kid req sig {} {:roster test-roster})]
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
                 token root-kid req1 sig {} {:roster test-roster})]
        (is (:authorized r))))
    (testing "write trade allowed"
      (let [sig (make-sig-meta :post "/trade/place-order"
                               {:symbol "AAPL" :side "buy"} agent-kp)
            r   (auth/verify-and-authorize
                 token root-kid req2 sig {:symbol "AAPL" :side "buy"}
                 {:roster test-roster})]
        (is (:authorized r))))))

(deftest sdsi-no-roster-returns-error
  (let [token  (issue-group [["traders" :read "market"]])
        req    {:effect :read :domain "market"
                :method :get :path "/market/clock"}
        sig    (make-sig-meta :get "/market/clock" {} agent-kp)
        result (auth/verify-and-authorize token root-kid req sig {})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "roster"))))

(deftest sdsi-no-signature-returns-error
  (let [token  (issue-group [["traders" :read "market"]])
        result (auth/verify-and-authorize
                token root-kid {:effect :read :domain "market"})]
    (is (not (:authorized result)))
    (is (.contains (:reason result) "signed request"))))

;; ---------------------------------------------------------------------------
;; SPKI/SDSI assertion-block mode (Phase 6)
;; ---------------------------------------------------------------------------

(defn- issue-spki-block
  [kp opts]
  (auth/issue-assertion-block kp opts))

(deftest spki-happy-path
  (let [block (issue-spki-block root-kp
                                {:subject   "alice"
                                 :agent-key agent-kid
                                 :effects   #{:read}
                                 :domains   #{"market"}})
        sig   (auth/sign-request :get "/market/clock" {} agent-kp)
        r     (auth/verify-and-authorize
               block root-kid
               {:effect :read :domain "market"
                :method :get :path "/market/clock"}
               sig {})]
    (is (:authorized r))
    (is (:spki r))
    (is (:requester-bound r))))

(deftest spki-requires-signed-request
  (let [block (issue-spki-block root-kp
                                {:subject "alice" :agent-key agent-kid
                                 :effects #{:read} :domains #{"market"}})
        r     (auth/verify-and-authorize
               block root-kid {:effect :read :domain "market"})]
    (is (not (:authorized r)))
    (is (.contains (:reason r) "signed request"))))

(deftest spki-rogue-agent-signature-denied
  (let [block (issue-spki-block root-kp
                                {:subject "alice" :agent-key agent-kid
                                 :effects #{:read} :domains #{"market"}})
        sig   (auth/sign-request :get "/market/clock" {} rogue-kp)
        r     (auth/verify-and-authorize
               block root-kid
               {:effect :read :domain "market"
                :method :get :path "/market/clock"}
               sig {})]
    (is (not (:authorized r)))))

(deftest spki-wrong-effect-denied
  (let [block (issue-spki-block root-kp
                                {:subject "alice" :agent-key agent-kid
                                 :effects #{:read} :domains #{"market"}})
        sig   (auth/sign-request :post "/market/quote" {} agent-kp)
        r     (auth/verify-and-authorize
               block root-kid
               {:effect :write :domain "market"
                :method :post :path "/market/quote"}
               sig {})]
    (is (not (:authorized r)))
    (is (.contains (:reason r) "write"))))

(deftest spki-wrong-domain-denied
  (let [block (issue-spki-block root-kp
                                {:subject "alice" :agent-key agent-kid
                                 :effects #{:read} :domains #{"market"}})
        sig   (auth/sign-request :get "/account/info" {} agent-kp)
        r     (auth/verify-and-authorize
               block root-kid
               {:effect :read :domain "account"
                :method :get :path "/account/info"}
               sig {})]
    (is (not (:authorized r)))
    (is (.contains (:reason r) "account"))))

(deftest spki-untrusted-root-denied
  (let [block     (issue-spki-block root-kp
                                    {:subject "alice" :agent-key agent-kid
                                     :effects #{:read} :domains #{"market"}})
        sig       (auth/sign-request :get "/market/clock" {} agent-kp)
        wrong-kid (key/kid (auth/generate-keypair))
        r         (auth/verify-and-authorize
                   block wrong-kid
                   {:effect :read :domain "market"
                    :method :get :path "/market/clock"}
                   sig {})]
    (is (not (:authorized r)))))

(deftest spki-multiple-effects-and-domains
  (testing "issue-assertion-block produces one capability per effect × domain"
    (let [block (issue-spki-block root-kp
                                  {:subject   "alice"
                                   :agent-key agent-kid
                                   :effects   #{:read :write}
                                   :domains   #{"market" "trade"}})]
      (doseq [effect [:read :write]
              domain ["market" "trade"]]
        (let [sig (auth/sign-request :post "/x" {} agent-kp)
              r   (auth/verify-and-authorize
                   block root-kid
                   {:effect effect :domain domain
                    :method :post :path "/x"}
                   sig {})]
          (is (:authorized r)
              (str (name effect) " on " domain " should be allowed")))))))

(deftest spki-tampered-block-denied
  (let [block     (issue-spki-block root-kp
                                    {:subject "alice" :agent-key agent-kid
                                     :effects #{:read} :domains #{"market"}})
        tampered  (clojure.string/replace block "market" "hacked")
        sig       (auth/sign-request :get "/market/clock" {} agent-kp)
        r         (auth/verify-and-authorize
                   tampered root-kid
                   {:effect :read :domain "market"
                    :method :get :path "/market/clock"}
                   sig {})]
    (is (not (:authorized r)))))

(deftest spki-replay-denied
  (let [block (issue-spki-block root-kp
                                {:subject "alice" :agent-key agent-kid
                                 :effects #{:read} :domains #{"market"}})
        sig   (auth/sign-request :get "/market/clock" {} agent-kp)
        req   {:effect :read :domain "market"
               :method :get :path "/market/clock"}
        r1    (auth/verify-and-authorize block root-kid req sig {})
        r2    (auth/verify-and-authorize block root-kid req sig {})]
    (is (:authorized r1) "First call passes")
    (is (not (:authorized r2)) "Replay is denied")
    (is (.contains (:reason r2) "Replay"))))

(deftest spki-envelope-binding-mismatch
  (testing "agent signs one path, server sees a different one → deny"
    (let [block (issue-spki-block root-kp
                                  {:subject "alice" :agent-key agent-kid
                                   :effects #{:read} :domains #{"market"}})
          sig   (auth/sign-request :get "/market/clock" {} agent-kp)
          r     (auth/verify-and-authorize
                 block root-kid
                 {:effect :read :domain "market"
                  :method :get :path "/market/bars"}   ; different path
                 sig {})]
      (is (not (:authorized r)))
      (is (.contains (:reason r) "path")))))

(deftest sdsi-monitors-group-separate
  (testing "agent1 is in monitors, agent2 is not"
    (let [token (issue-group [["monitors" :read "account"]])]
      (let [sig (make-sig-meta :get "/account/info" {} agent-kp)
            r   (auth/verify-and-authorize
                 token root-kid
                 {:effect :read :domain "account" :method :get :path "/account/info"}
                 sig {} {:roster test-roster})]
        (is (:authorized r)))
      (let [sig (make-sig-meta :get "/account/info" {} agent2-kp)
            r   (auth/verify-and-authorize
                 token root-kid
                 {:effect :read :domain "account" :method :get :path "/account/info"}
                 sig {} {:roster test-roster})]
        (is (not (:authorized r)))))))
