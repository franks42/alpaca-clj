(ns alpaca.client-pep
  "Client-side Policy Enforcement Point — outbound request gating.

   Enforces the agent's own company policy BEFORE a request is signed
   and sent. Independent of the server-side PEP. Different authority,
   different trust root, different policy domain.

   ┌─────────────────────────────────────────────────────────────────┐
   │                                                                 │
   │  Company A (agent's org)          Company B (resource owner)    │
   │  ════════════════════             ════════════════════════      │
   │                                                                 │
   │  ┌──────────────┐                ┌──────────────┐              │
   │  │  Outbound     │                │  Inbound      │              │
   │  │  Authority    │                │  Authority    │              │
   │  └──────┬───────┘                └──────┬───────┘              │
   │         │ issues                        │ issues               │
   │         ▼                               ▼                      │
   │  ┌──────────────┐                ┌──────────────┐              │
   │  │  Outbound     │   request      │  Inbound      │              │
   │  │  Token        │                │  Token        │              │
   │  └──────┬───────┘                └──────┬───────┘              │
   │         │                               │                      │
   │         ▼                               ▼                      │
   │  ┌──────────────┐  ──────────►  ┌──────────────┐              │
   │  │  Client PEP   │              │  Server PEP   │              │
   │  │ 1. dest ok?  │              │ 1. sig ok?   │              │
   │  │ 2. effect ok?│              │ 2. token ok? │              │
   │  │ 3. body clean│              │ 3. audience? │              │
   │  │              │              │ 4. replay?   │              │
   │  └──────────────┘              └──────┬───────┘              │
   │                                       │                      │
   │                                       ▼                      │
   │                                  Alpaca Markets               │
   │                                                                 │
   └─────────────────────────────────────────────────────────────────┘

   Two trust roots. Two authorities. Two independent policy chains.
   Neither PEP trusts the other's enforcement."
  (:require [signet.chain :as chain]
            [stroopwafel.pdp.core :as pdp]
            [alpaca.auth :as auth]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Data restriction scanning
;; ---------------------------------------------------------------------------

(def ^:private pii-patterns
  [#"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"     ;; email
   #"\b\d{3}[-.]?\d{2}[-.]?\d{4}\b"                        ;; SSN-like
   #"\b\d{3}[-.]?\d{3}[-.]?\d{4}\b"])                       ;; phone

(defn- collect-string-values
  [x]
  (cond
    (string? x) [x]
    (map? x)    (mapcat collect-string-values (vals x))
    (coll? x)   (mapcat collect-string-values x)
    :else       []))

(defn- check-pii
  [body]
  (let [strings (collect-string-values body)]
    (some (fn [s]
            (some (fn [pat]
                    (when (re-find pat s)
                      "PII detected in request body (matches pattern in value)"))
                  pii-patterns))
          strings)))

(defn- check-client-names
  [body]
  (let [strings (collect-string-values body)]
    (some (fn [s]
            (let [lower (str/lower-case s)]
              (when (or (str/starts-with? lower "client:")
                        (str/starts-with? lower "customer:"))
                "Client/customer name detected in request body")))
          strings)))

(defn- check-strategy-in-comments
  [body]
  (when (map? body)
    (when (or (contains? body :comment)
              (contains? body :notes)
              (contains? body "comment")
              (contains? body "notes"))
      "Strategy/comment field present in request body")))

(def ^:private restriction-checks
  {:no-pii-in-params        check-pii
   :no-client-names         check-client-names
   :no-strategy-in-comments check-strategy-in-comments})

(defn- scan-body-restrictions
  "Apply data restriction checks to request body.
   Returns nil (OK) or error string."
  [body restrictions]
  (some (fn [r]
          (when-let [check-fn (get restriction-checks r)]
            (check-fn body)))
        restrictions))

;; ---------------------------------------------------------------------------
;; Outbound token issuance
;; ---------------------------------------------------------------------------

(defn issue-outbound-token
  "Issue an outbound capability token from the company's outbound authority.

   Arguments:
     authority-kp — signet Ed25519 keypair
     grants       — map:
       :destinations — vector of approved destination strings
       :permissions  — vector of {:destination :effect :domain}
       :restrictions — set of restriction keywords

   Returns: sealed token string (CEDN)."
  [authority-kp {:keys [destinations permissions restrictions]}]
  (let [dest-facts  (mapv (fn [d] [:approved-destination d]) destinations)
        perm-facts  (mapv (fn [{:keys [destination effect domain]}]
                            [:outbound-allow destination effect domain])
                          permissions)
        restr-facts (mapv (fn [r] [:data-restriction r]) restrictions)
        facts       (vec (concat dest-facts perm-facts restr-facts))
        token       (-> (chain/extend authority-kp {:facts facts})
                        (chain/close))]
    (auth/serialize-token token)))

;; ---------------------------------------------------------------------------
;; Outbound policy check
;; ---------------------------------------------------------------------------

(defn- extract-restrictions
  "Extract [:data-restriction r] values from an unverified token.
   Used after Datalog policy allows the request, to scan the request body."
  [token]
  (->> (mapcat (fn [b] (get-in b [:envelope :message :data :facts]))
               (:blocks token))
       (keep (fn [f] (when (= :data-restriction (first f)) (second f))))
       set))

(defn check-outbound
  "Evaluate outbound policy before signing a request.

   Verifies:
   1. Token chain integrity and signatures
   2. Token was issued by the configured authority
   3. Destination is approved for this effect+domain
   4. Request body passes data restriction checks

   Arguments:
     outbound-token-str — sealed outbound token string (CEDN)
     authority-kid      — kid URN of the outbound authority (trust root)
     request            — {:destination :effect :domain :body}

   Returns: {:allowed true} or {:allowed false :reason \"...\"}"
  [outbound-token-str authority-kid {:keys [destination effect domain body]}]
  (try
    (let [token  (auth/deserialize-token outbound-token-str)
          ctx    (-> (pdp/context)
                     (pdp/add-chain token {:trust-root authority-kid}))
          result (pdp/decide
                  ctx
                  :checks [{:id    :dest-approved
                            :query [[:approved-destination destination]]}
                           {:id    :outbound-allowed
                            :query [[:outbound-allow destination effect domain]]}]
                  :policies [{:kind  :allow
                              :query [[:approved-destination destination]
                                      [:outbound-allow destination effect domain]]}])]
      (cond
        ;; PDP errors short-circuit (invalid chain or untrusted root)
        (seq (:errors result))
        (let [err (first (:errors result))]
          {:allowed false
           :reason  (case (:reason err)
                      :invalid-chain   "Outbound token chain verification failed"
                      :untrusted-root  "Outbound token not signed by trusted authority"
                      (str "Outbound token error: " (:reason err)))})

        (not (:allowed? result))
        {:allowed false
         :reason  (str "Outbound policy denies " (name effect) " on "
                       domain " to " destination)}

        :else
        (if-let [err (scan-body-restrictions body (extract-restrictions token))]
          {:allowed false :reason err}
          {:allowed true})))
    (catch Exception e
      {:allowed false :reason (str "Outbound token error: " (.getMessage e))})))
