(ns alpaca.dual-pep-test
  "Integration tests for the dual-PEP architecture.

   Demonstrates two independent trust chains working together:

   Company A (outbound)              Company B (inbound)
   ════════════════════              ═══════════════════
   ┌──────────────┐                 ┌──────────────┐
   │ A's Outbound │                 │ B's Inbound  │
   │ Authority    │                 │ Authority    │
   └──────┬───────┘                 └──────┬───────┘
          │ issues                         │ issues
          ▼                                ▼
   ┌──────────────┐                 ┌──────────────┐
   │ Outbound     │                 │ Inbound      │
   │ Token        │                 │ Token        │
   │              │                 │              │
   │ destinations │                 │ effects      │
   │ permissions  │                 │ domains      │
   │ restrictions │                 │ agent-key    │
   └──────┬───────┘                 └──────┬───────┘
          │                                │
          ▼              signed            ▼
   ┌──────────────┐     envelope    ┌──────────────┐
   │ Client PEP   │ ────────────►  │ Server PEP   │
   │ (agent-side) │  + audience    │ (proxy-side) │
   │              │                │              │
   │ deny → stop  │                │ deny → 403   │
   └──────────────┘                └──────┬───────┘
                                          │
                                          ▼
                                     Alpaca Markets

   Every test creates BOTH tokens, then exercises the full chain.
   No server required — all pure function calls."
  (:require [clojure.test :refer [deftest is testing]]
            [alpaca.client-pep :as cpep]
            [alpaca.auth :as auth]
            [stroopwafel.crypto :as crypto]))

;; ---------------------------------------------------------------------------
;; Two companies, two authorities, one agent
;; ---------------------------------------------------------------------------

;; Company A — the agent's organization (outbound policy)
(def company-a-kp (auth/generate-keypair))
(def company-a-pub (:pub company-a-kp))

;; Company B — the resource owner (inbound policy)
(def company-b-kp (auth/generate-keypair))
(def company-b-pub (:pub company-b-kp))

;; The agent (works for Company A, accesses Company B's proxy)
(def agent-kp (auth/generate-keypair))
(def agent-pk-bytes (crypto/encode-public-key (:pub agent-kp)))

(def proxy-b-identity "proxy-b:8080")

;; ---------------------------------------------------------------------------
;; Token fixtures
;; ---------------------------------------------------------------------------

(def outbound-token
  "Company A says: agent may read market data from proxy-b, no PII."
  (cpep/issue-outbound-token
   company-a-kp
   {:destinations [proxy-b-identity]
    :permissions  [{:destination proxy-b-identity :effect :read :domain "market"}]
    :restrictions #{:no-pii-in-params}}))

(def inbound-token
  "Company B says: this agent may read market data (bound to agent key)."
  (auth/issue-token
   company-b-kp
   {:effects   #{:read}
    :domains   #{"market"}
    :agent-key agent-pk-bytes}))

;; ---------------------------------------------------------------------------
;; Helper: full dual-PEP flow
;; ---------------------------------------------------------------------------

(defn dual-pep-flow
  "Simulate the full dual-PEP flow:
   1. Client PEP checks outbound policy
   2. If allowed, agent signs request with audience
   3. Server PEP verifies inbound policy
   Returns {:client-result ... :server-result ...}"
  [{:keys [destination effect domain method path body audience]}]
  (let [client-result (cpep/check-outbound
                       outbound-token company-a-pub
                       {:destination destination
                        :effect effect :domain domain
                        :body body})]
    (if-not (:allowed client-result)
      {:client-result client-result :server-result nil}
      (let [sig-meta      (auth/sign-request method path body agent-kp audience)
            server-result (auth/verify-and-authorize
                           inbound-token company-b-pub
                           {:effect effect :domain domain
                            :method method :path path}
                           sig-meta body
                           {:proxy-identity proxy-b-identity})]
        {:client-result client-result
         :server-result server-result}))))

;; ---------------------------------------------------------------------------
;; Integration scenarios
;; ---------------------------------------------------------------------------

(deftest happy-path-dual-pep-allows
  (testing "Both PEPs allow: right destination, right effect, clean body"
    (let [{:keys [client-result server-result]}
          (dual-pep-flow {:destination proxy-b-identity
                          :effect :read :domain "market"
                          :method :post :path "/market/quote"
                          :body {:symbol "AAPL"}
                          :audience proxy-b-identity})]
      (is (:allowed client-result))
      (is (:authorized server-result))
      (is (:requester-bound server-result)))))

(deftest client-pep-blocks-wrong-destination
  (testing "Client PEP denies: unapproved destination"
    (let [{:keys [client-result server-result]}
          (dual-pep-flow {:destination "proxy-live:8080"
                          :effect :read :domain "market"
                          :method :post :path "/market/quote"
                          :body {:symbol "AAPL"}
                          :audience "proxy-live:8080"})]
      (is (not (:allowed client-result)))
      (is (nil? server-result) "Request should never reach server PEP"))))

(deftest client-pep-blocks-wrong-effect
  (testing "Client PEP denies: write not permitted to proxy-b"
    (let [{:keys [client-result server-result]}
          (dual-pep-flow {:destination proxy-b-identity
                          :effect :write :domain "trade"
                          :method :post :path "/trade/place-order"
                          :body {:symbol "AAPL" :side "buy" :qty 100}
                          :audience proxy-b-identity})]
      (is (not (:allowed client-result)))
      (is (nil? server-result)))))

(deftest client-pep-blocks-pii-in-body
  (testing "Client PEP denies: PII in request body"
    (let [{:keys [client-result server-result]}
          (dual-pep-flow {:destination proxy-b-identity
                          :effect :read :domain "market"
                          :method :post :path "/market/quote"
                          :body {:symbol "AAPL" :comment "user@example.com"}
                          :audience proxy-b-identity})]
      (is (not (:allowed client-result)))
      (is (.contains (:reason client-result) "PII"))
      (is (nil? server-result)))))

(deftest server-pep-blocks-wrong-effect
  (testing "Server PEP denies even if client PEP is bypassed"
    (let [;; Agent bypasses client PEP and signs a write request directly
          sig-meta      (auth/sign-request :post "/trade/place-order"
                                           {:symbol "AAPL" :side "buy"}
                                           agent-kp proxy-b-identity)
          server-result (auth/verify-and-authorize
                         inbound-token company-b-pub
                         {:effect :write :domain "trade"
                          :method :post :path "/trade/place-order"}
                         sig-meta {:symbol "AAPL" :side "buy"}
                         {:proxy-identity proxy-b-identity})]
      (is (not (:authorized server-result)))
      (is (.contains (:reason server-result) "not bound")))))

(deftest server-pep-blocks-audience-mismatch
  (testing "Request signed for proxy-b presented to proxy-a"
    (let [sig-meta      (auth/sign-request :post "/market/quote"
                                           {:symbol "AAPL"}
                                           agent-kp proxy-b-identity)
          server-result (auth/verify-and-authorize
                         inbound-token company-b-pub
                         {:effect :read :domain "market"
                          :method :post :path "/market/quote"}
                         sig-meta {:symbol "AAPL"}
                         {:proxy-identity "proxy-a:9090"})]
      (is (not (:authorized server-result)))
      (is (.contains (:reason server-result) "Audience mismatch")))))

(deftest independent-authorities-cannot-forge
  (testing "Swapping authority keys causes both PEPs to reject"
    ;; Outbound token verified with B's key (wrong) → fails
    (let [result (cpep/check-outbound
                  outbound-token company-b-pub
                  {:destination proxy-b-identity
                   :effect :read :domain "market"
                   :body {:symbol "AAPL"}})]
      (is (not (:allowed result)))
      (is (.contains (:reason result) "not signed by trusted")))

    ;; Bearer inbound token verified with A's key (wrong) → fails
    ;; (trust root mismatch — B signed it, A's key presented as trust root)
    (let [bearer-token (auth/issue-token company-b-kp
                                         {:effects #{:read} :domains #{"market"}})
          result       (auth/verify-and-authorize
                        bearer-token company-a-pub
                        {:effect :read :domain "market"})]
      (is (not (:authorized result)))
      (is (.contains (:reason result) "not trusted")))))
