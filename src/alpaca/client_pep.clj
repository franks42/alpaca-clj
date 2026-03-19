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
   │  │  (A's key)    │                │  (B's key)    │              │
   │  └──────┬───────┘                └──────┬───────┘              │
   │         │ issues                        │ issues               │
   │         ▼                               ▼                      │
   │  ┌──────────────┐                ┌──────────────┐              │
   │  │  Outbound     │   request      │  Inbound      │              │
   │  │  Token        │                │  Token        │              │
   │  │              │                │              │              │
   │  │ destinations │                │ effects      │              │
   │  │ permissions  │                │ domains      │              │
   │  │ restrictions │                │ agent-key    │              │
   │  └──────┬───────┘                └──────┬───────┘              │
   │         │                               │                      │
   │         ▼                               ▼                      │
   │  ┌──────────────┐  ──────────►  ┌──────────────┐              │
   │  │  Client PEP   │   signed     │  Server PEP   │              │
   │  │              │   envelope   │              │              │
   │  │ 1. dest ok?  │   + audience │ 1. sig ok?   │              │
   │  │ 2. effect ok?│              │ 2. token ok? │              │
   │  │ 3. body clean│              │ 3. audience? │              │
   │  │              │              │ 4. replay?   │              │
   │  │ deny → stop  │              │ deny → 403   │              │
   │  └──────────────┘              └──────┬───────┘              │
   │                                       │                      │
   │                                       ▼                      │
   │                                  Alpaca Markets               │
   │                                                                 │
   └─────────────────────────────────────────────────────────────────┘

   Two trust roots. Two authorities. Two independent policy chains.
   Neither PEP trusts the other's enforcement."
  (:require [stroopwafel.core :as sw]
            [stroopwafel.crypto :as sw-crypto]
            [alpaca.auth :as auth]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Data restriction scanning
;; ---------------------------------------------------------------------------

(def ^:private pii-patterns
  "Regex patterns for common PII in string values."
  [#"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"     ;; email
   #"\b\d{3}[-.]?\d{2}[-.]?\d{4}\b"                        ;; SSN-like
   #"\b\d{3}[-.]?\d{3}[-.]?\d{4}\b"])                       ;; phone

(defn- collect-string-values
  "Recursively collect all string values from a nested data structure."
  [x]
  (cond
    (string? x) [x]
    (map? x)    (mapcat collect-string-values (vals x))
    (coll? x)   (mapcat collect-string-values x)
    :else       []))

(defn- check-pii
  "Check for PII patterns in all string values. Returns error or nil."
  [body]
  (let [strings (collect-string-values body)]
    (some (fn [s]
            (some (fn [pat]
                    (when (re-find pat s)
                      "PII detected in request body (matches pattern in value)"))
                  pii-patterns))
          strings)))

(defn- check-client-names
  "Check for client/customer name prefixes. Returns error or nil."
  [body]
  (let [strings (collect-string-values body)]
    (some (fn [s]
            (let [lower (str/lower-case s)]
              (when (or (str/starts-with? lower "client:")
                        (str/starts-with? lower "customer:"))
                "Client/customer name detected in request body")))
          strings)))

(defn- check-strategy-in-comments
  "Check for :comment or :notes keys in body. Returns error or nil."
  [body]
  (when (map? body)
    (when (or (contains? body :comment)
              (contains? body :notes)
              (contains? body "comment")
              (contains? body "notes"))
      "Strategy/comment field present in request body")))

(def ^:private restriction-checks
  "Map of restriction keyword → check function."
  {:no-pii-in-params         check-pii
   :no-client-names          check-client-names
   :no-strategy-in-comments  check-strategy-in-comments})

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
     authority-kp  — outbound authority keypair {:priv :pub}
     grants        — map:
       :destinations  — vector of approved destination strings
       :permissions   — vector of {:destination :effect :domain}
       :restrictions  — set of restriction keywords

   Returns: sealed token string (CEDN)."
  [authority-kp {:keys [destinations permissions restrictions]}]
  (let [signer-pk  (sw-crypto/encode-public-key (:pub authority-kp))
        dest-facts (mapv (fn [d] [:approved-destination d]) destinations)
        perm-facts (mapv (fn [{:keys [destination effect domain]}]
                           [:outbound-allow destination effect domain])
                         permissions)
        restr-facts (mapv (fn [r] [:data-restriction r]) restrictions)
        all-facts   (into [[:signer-key signer-pk]]
                          (concat dest-facts perm-facts restr-facts))
        token       (sw/issue {:facts all-facts}
                              {:private-key (:priv authority-kp) :public-key (:pub authority-kp)})
        sealed      (sw/seal token)]
    (auth/serialize-token sealed)))

;; ---------------------------------------------------------------------------
;; Outbound policy check
;; ---------------------------------------------------------------------------

(defn check-outbound
  "Evaluate outbound policy before signing a request.

   Verifies:
   1. Token signature (was this issued by our outbound authority?)
   2. Destination is approved
   3. Effect + domain are permitted for this destination
   4. Request body passes data restriction checks

   Arguments:
     outbound-token-str — sealed outbound token string (CEDN)
     authority-pub      — outbound authority public key (for signature verification)
     request            — {:destination :effect :domain :body}

   Returns: {:allowed true} or {:allowed false :reason \"...\"}"
  [outbound-token-str authority-pub {:keys [destination effect domain body]}]
  (try
    (let [token            (auth/deserialize-token outbound-token-str)
          facts            (get-in token [:blocks 0 :envelope :message :facts])
          signer-key-bytes (some (fn [f] (when (= :signer-key (first f)) (second f))) facts)
          signer-pk        (when signer-key-bytes
                             (sw-crypto/decode-public-key signer-key-bytes))]
      (cond
        (nil? signer-pk)
        {:allowed false :reason "Outbound token missing [:signer-key] fact"}

        (not (sw/verify token {:public-key signer-pk}))
        {:allowed false :reason "Outbound token signature verification failed"}

        ;; Verify signer is our trusted outbound authority
        (not (sw-crypto/bytes= (sw-crypto/encode-public-key signer-pk)
                               (sw-crypto/encode-public-key authority-pub)))
        {:allowed false :reason "Outbound token not signed by trusted authority"}

        :else
        (let [result (sw/evaluate token
                                  :authorizer
                                  {:facts [[:requested-destination destination]
                                           [:requested-effect effect]
                                           [:requested-domain domain]]
                                   :checks [{:id    :dest-approved
                                             :query [[:approved-destination destination]]}
                                            {:id    :outbound-allowed
                                             :query [[:outbound-allow destination
                                                      effect domain]]}]
                                   :policies [{:kind :allow
                                               :query [[:approved-destination destination]
                                                       [:outbound-allow destination
                                                        effect domain]]}]})]
          (if-not (:valid? result)
            {:allowed false
             :reason (str "Outbound policy denies " (name effect) " on "
                          domain " to " destination)}
            ;; Datalog passed — now check data restrictions
            (let [restrictions (->> facts
                                    (filter #(= :data-restriction (first %)))
                                    (map second)
                                    set)]
              (if-let [err (scan-body-restrictions body restrictions)]
                {:allowed false :reason err}
                {:allowed true}))))))
    (catch Exception e
      {:allowed false :reason (str "Outbound token error: " (.getMessage e))})))
