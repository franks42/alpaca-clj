(ns alpaca.auth
  "Token authentication and authorization for the alpaca-clj proxy.

   Four authorization modes, dispatched by token shape:

   Chain-shaped tokens (:type :signet/chain):
     :bearer  — token carries [:effect ...] and [:domain ...] facts
     :bound   — token also carries [:authorized-agent-key <kid>]
                (requires a signed request envelope)
     :group   — token carries [:right <group> <effect> <domain>]
                (requires a signed envelope + a named-key roster)

   Assertion-block tokens (:type :signet/signed):
     :spki    — the token IS a signed assertion block containing
                :name-binding + :capability assertions (+ optional
                :delegation and :revocation). Authorization is done
                via Datalog join over the SDSI name resolution
                templates. Requires a signed request envelope.

   Chain modes use signet.chain + stroopwafel.pdp.
   :spki mode uses signet.sign + stroopwafel.pdp.spki.core."
  (:require [signet.chain :as chain]
            [signet.key :as key]
            [signet.sign :as sign]
            [stroopwafel.pdp.core :as pdp]
            [stroopwafel.pdp.trust :as trust]
            [stroopwafel.pdp.replay :as replay]
            [stroopwafel.pdp.spki.core :as spki]
            [stroopwafel.pdp.spki.assertion :as spki-a]
            [stroopwafel.pdp.spki.templates :as spki-t]
            [cedn.core :as cedn] ;; cedn/readers used at runtime
            [clojure.edn :as edn]
            [taoensso.trove :as log]))

;; ---------------------------------------------------------------------------
;; Keypair management
;; ---------------------------------------------------------------------------

(defn generate-keypair
  "Generate a new Ed25519 signing keypair (signet record)."
  []
  (key/signing-keypair))

;; ---------------------------------------------------------------------------
;; Token serialization (CEDN with #bytes support)
;; ---------------------------------------------------------------------------

(defn serialize-token
  "Serialize a sealed chain token to a CEDN string for transport."
  [token]
  (cedn/canonical-str token))

(defn deserialize-token
  "Deserialize a CEDN string back to a chain token map."
  [s]
  (edn/read-string {:readers cedn/readers} s))

;; ---------------------------------------------------------------------------
;; Token issuance
;; ---------------------------------------------------------------------------

(defn issue-token
  "Issue a new capability token.

   Arguments:
     root-kp — signet Ed25519 keypair (the trust anchor)
     grants  — map:
       :effects   — set of allowed effects #{:read :write :destroy}
       :domains   — set of allowed domains #{\"market\" \"account\" \"trade\"}
       :agent-key — (optional) agent's kid URN string for requester binding

   Returns: sealed chain token serialized as a CEDN string."
  [root-kp {:keys [effects domains agent-key]}]
  (let [effect-facts (mapv (fn [e] [:effect e]) effects)
        domain-facts (mapv (fn [d] [:domain d]) domains)
        facts        (cond-> (vec (concat effect-facts domain-facts))
                       agent-key (conj [:authorized-agent-key agent-key]))
        token        (-> (chain/extend root-kp {:facts facts})
                         (chain/close))]
    (serialize-token token)))

(defn issue-assertion-block
  "Issue an SPKI/SDSI-style assertion block (Phase 6 dogfood).

   Produces a single signed block containing:
     - one :name-binding assertion:  [:name-binding agent-kid subject-name]
     - N :capability assertions:     [:capability subject-name effect domain]
       (one per effect × domain pair)

   Arguments:
     root-kp — signet Ed25519 keypair (the trust anchor)
     grants  — map:
       :subject     — string name for the subject (e.g. \"alice\")
       :agent-key   — kid URN of the agent who'll sign requests
       :effects     — set of effects #{:read :write :destroy}
       :domains     — set of domains #{\"market\" \"account\" \"trade\"}
       :not-after   — optional epoch-ms or :forever (default infinity)

   Returns: sealed signed block serialized as a CEDN string, suitable
   for use as a Bearer token."
  [root-kp {:keys [subject agent-key effects domains not-after]}]
  (when-not (and subject agent-key)
    (throw (ex-info "issue-assertion-block requires :subject and :agent-key"
                    {:got {:subject subject :agent-key agent-key}})))
  (let [binding     (spki-a/assertion :name-binding [agent-key subject])
        capabilities (for [e effects d domains]
                       (spki-a/assertion :capability [subject e d]))
        block       (spki-a/sign-block
                     root-kp
                     (cond-> {:assertions (into [binding] capabilities)}
                       not-after (assoc :not-after not-after)))]
    (serialize-token block)))

(defn issue-group-token
  "Issue an SDSI group-based token.

   Arguments:
     root-kp — signet Ed25519 keypair
     grants  — map:
       :rights — vector of [group-name effect domain] triples

   Returns: sealed chain token serialized as a CEDN string."
  [root-kp {:keys [rights]}]
  (let [facts (mapv (fn [[g e d]] [:right g e d]) rights)
        token (-> (chain/extend root-kp {:facts facts})
                  (chain/close))]
    (serialize-token token)))

;; ---------------------------------------------------------------------------
;; Request envelope signing (agent side)
;; ---------------------------------------------------------------------------

(defn sign-request
  "Sign a request envelope with the agent's keypair.

   Message carries method/path/body and an optional audience string.
   signet.sign adds signer kid, request-id (UUIDv7), and :expires."
  ([method path body agent-kp]
   (sign-request method path body agent-kp nil))
  ([method path body agent-kp audience]
   (sign-request method path body agent-kp audience 120))
  ([method path body agent-kp audience ttl-seconds]
   (let [message (cond-> {:method (name method)
                          :path   path
                          :body   (or body {})}
                   audience (assoc :audience audience))]
     (sign/sign-edn agent-kp message {:ttl ttl-seconds}))))

(defn serialize-signed-request
  "Serialize a signed envelope as CEDN for the X-Agent-Signature header."
  [envelope]
  (cedn/canonical-str envelope))

(defn deserialize-sig-metadata
  "Deserialize signature metadata from a header value."
  [s]
  (edn/read-string {:readers cedn/readers} s))

;; ---------------------------------------------------------------------------
;; Replay protection (module-level guard, 120s freshness window)
;; ---------------------------------------------------------------------------

(def ^:private replay-guard
  (replay/create-replay-guard))

;; ---------------------------------------------------------------------------
;; Envelope binding check (method/path/body/audience + replay)
;;
;; PDP's add-signed-envelope only verifies the signature. The agent-binding
;; integrity check (did the signer actually endorse THIS method/path/body
;; on THIS proxy?) is alpaca-specific and lives here.
;; ---------------------------------------------------------------------------

(defn- check-envelope-binding
  "Verify a signed envelope's claimed fields match the actual request
   and pass replay protection. Returns nil if OK, or an error string."
  [sig-meta actual-method actual-path actual-body proxy-identity]
  (let [verified (sign/verify-edn sig-meta)]
    (if-not (:valid? verified)
      "Request signature verification failed"
      (let [{:keys [message request-id]} verified
            {env-method :method env-path :path env-body :body
             env-audience :audience} message]
        (cond
          (not= env-method (name actual-method))
          (str "Signed method '" env-method
               "' does not match actual method '" (name actual-method) "'")

          (not= env-path actual-path)
          (str "Signed path '" env-path
               "' does not match actual path '" actual-path "'")

          (not= env-body (or actual-body {}))
          "Signed body does not match actual request body"

          (and env-audience proxy-identity (not= env-audience proxy-identity))
          (str "Audience mismatch: request signed for '" env-audience
               "' but this proxy is '" proxy-identity "'")

          :else
          (replay/check replay-guard request-id))))))

;; ---------------------------------------------------------------------------
;; Token shape inspection (pre-verification, dispatch only)
;; ---------------------------------------------------------------------------

(defn- token-facts
  "Extract all facts from a chain token's blocks.
   UNVERIFIED — use only to determine auth mode, never as authorization data."
  [token]
  (vec (mapcat (fn [block]
                 (get-in block [:envelope :message :data :facts]))
               (:blocks token))))

(defn- token-mode
  [facts]
  (cond
    (some #(= :right (first %)) facts)                :group
    (some #(= :authorized-agent-key (first %)) facts) :bound
    :else                                             :bearer))

(defn- token-kind
  "Distinguish a chain-shaped token from an SPKI assertion block."
  [token]
  (case (:type token)
    :signet/chain  :chain
    :signet/signed :spki
    :unknown))

(defn- extract-primordial-kids
  "Normalize alpaca's trust-roots value (string / scoped map / set)
   into the flat set of kid URNs that spki.core/add-trust-roots
   accepts as primordial trust. Scoping metadata is dropped — SPKI
   expresses scope inside assertions rather than in trust roots."
  [trust-roots]
  (cond
    (string? trust-roots) #{trust-roots}
    (map? trust-roots)    (set (keys trust-roots))
    (set? trust-roots)    trust-roots
    (sequential? trust-roots) (set trust-roots)
    :else #{}))

;; ---------------------------------------------------------------------------
;; Authorizer ingredients
;; ---------------------------------------------------------------------------

(defn- roster-facts
  "Convert roster map {group-name [kid-urn ...]} to [:named-key group kid] facts."
  [roster]
  (when roster
    (into []
          (mapcat (fn [[group kids]]
                    (map (fn [k] [:named-key group k]) kids)))
          roster)))

(defn- trust-ok-rules
  "Rules that define [:trusted-ok] for a given effect/domain pair.
   Two rules cover unscoped (:any) and scoped trust roots."
  [effect domain]
  [{:id   :trusted-any
    :head [:trusted-ok]
    :body [[:chain-root '?k] [:trusted-root '?k :any :any]]}
   {:id   :trusted-scoped
    :head [:trusted-ok]
    :body [[:chain-root '?k] [:trusted-root '?k effect domain]]}])

(defn- agent-fingerprint
  "Short kid fingerprint for audit logging (tail 16 chars of base64url)."
  [kid-str]
  (when kid-str
    (let [n (count kid-str)]
      (subs kid-str (max 0 (- n 16))))))

;; ---------------------------------------------------------------------------
;; Mode-specific authorize helpers
;;
;; Each builds a PDP context, injects local facts (chain-root, request-
;; verified-signer, trust-root facts, roster facts), and runs one policy.
;; ---------------------------------------------------------------------------

(defn- authorize-bearer
  [token trust-roots effect domain]
  (let [tr-facts (trust/trust-root-facts trust-roots)
        ctx      (-> (pdp/context)
                     (pdp/add-chain token {})
                     (pdp/add-facts (into [[:chain-root (:root token)]] tr-facts)))
        result   (pdp/decide ctx
                             :rules (trust-ok-rules effect domain)
                             :policies [{:kind  :allow
                                         :query [[:effect effect]
                                                 [:domain domain]
                                                 [:trusted-ok]]}])]
    (if (:allowed? result)
      {:authorized true}
      {:authorized false
       :reason (str "Token does not grant " (name effect)
                    " access to " domain
                    " (or signer not trusted for this scope)")})))

(defn- authorize-bound
  [token trust-roots effect domain sig-meta method path body proxy-identity]
  (if-let [err (check-envelope-binding sig-meta method path body proxy-identity)]
    {:authorized false :reason err}
    (let [verified (sign/verify-edn sig-meta)
          signer   (:signer verified)
          tr-facts (trust/trust-root-facts trust-roots)
          ctx      (-> (pdp/context)
                       (pdp/add-chain token {})
                       (pdp/add-facts (into [[:chain-root (:root token)]
                                             [:request-verified-signer signer]]
                                            tr-facts)))
          result   (pdp/decide ctx
                               :rules (trust-ok-rules effect domain)
                               :policies [{:kind  :allow
                                           :query [[:effect effect]
                                                   [:domain domain]
                                                   [:authorized-agent-key '?k]
                                                   [:request-verified-signer '?k]
                                                   [:trusted-ok]]}])]
      (if (:allowed? result)
        {:authorized      true
         :requester-bound true
         :request-id      (:request-id verified)
         :agent-key-fp    (agent-fingerprint signer)}
        {:authorized false
         :reason     "Token not bound to this agent key"}))))

(defn- authorize-group
  [token trust-roots effect domain sig-meta method path body roster proxy-identity]
  (if-let [err (check-envelope-binding sig-meta method path body proxy-identity)]
    {:authorized false :reason err}
    (let [verified (sign/verify-edn sig-meta)
          signer   (:signer verified)
          tr-facts (trust/trust-root-facts trust-roots)
          rl-facts (roster-facts roster)
          ctx      (-> (pdp/context)
                       (pdp/add-chain token {})
                       (pdp/add-facts (into [[:chain-root (:root token)]
                                             [:request-verified-signer signer]]
                                            (into tr-facts rl-facts))))
          result   (pdp/decide ctx
                               :rules (trust-ok-rules effect domain)
                               :policies [{:kind  :allow
                                           :query [[:right '?name effect domain]
                                                   [:named-key '?name '?k]
                                                   [:request-verified-signer '?k]
                                                   [:trusted-ok]]}])]
      (if (:allowed? result)
        {:authorized      true
         :requester-bound true
         :sdsi-group      true
         :request-id      (:request-id verified)
         :agent-key-fp    (agent-fingerprint signer)}
        {:authorized false
         :reason     (str "Agent not in any group with " (name effect)
                          " access to " domain)}))))

(defn- authorize-spki
  "Authorize an SPKI/SDSI assertion-block token.

   The block is fed directly into stroopwafel.pdp.spki/add-assertions.
   A user rule expresses SDSI name resolution: the request signer's
   kid is joined to a :name-binding assertion for the subject name,
   which is joined to a :capability assertion granting the requested
   effect+domain.

   Trust policy in one rule:
     [:request-verified-signer ?signer]   ← injected from envelope
     ∧ [:named ?signer ?subject]          ← from :name-binding template
     ∧ [:can ?subject effect domain]      ← from :capability template
     → [:authorized-via-name ?signer]

   All verification (block signatures, trust closure, temporal,
   revocation) is done inside spki.core/decide. This function's only
   responsibility is envelope binding + building the context."
  [block trust-roots effect domain sig-meta method path body proxy-identity]
  (if-let [err (check-envelope-binding sig-meta method path body proxy-identity)]
    {:authorized false :reason err}
    (let [verified (sign/verify-edn sig-meta)
          signer   (:signer verified)
          kids     (extract-primordial-kids trust-roots)
          rule     '{:id   :authorized-via-name
                     :head [:authorized-via-name ?signer]
                     :body [[:request-verified-signer ?signer]
                            [:named ?signer ?subject]
                            [:can ?subject ?e ?d]]}
          ctx      (-> (spki/context)
                       (spki/add-trust-roots kids)
                       (spki/with-templates spki-t/standard)
                       (spki/add-assertions block)
                       (spki/add-facts [[:request-verified-signer signer]])
                       (spki/add-rules [rule]))
          result   (spki/decide
                    ctx
                    :policies [{:kind :allow
                                :query [[:authorized-via-name signer]
                                        [:can '?subject effect domain]
                                        [:named signer '?subject]]}])]
      (if (:allowed? result)
        {:authorized      true
         :requester-bound true
         :spki            true
         :request-id      (:request-id verified)
         :agent-key-fp    (agent-fingerprint signer)}
        {:authorized false
         :reason     (str "SPKI: agent not granted " (name effect)
                          " access to " domain)}))))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn verify-and-authorize
  "Verify a capability token and authorize the request.

   Arguments:
     token-str   — serialized sealed chain (CEDN string)
     trust-roots — kid URN string (single root)
                   OR {kid → {:scoped-to {:effects #{} :domains #{}}}} (multi-root)
     request     — {:effect :domain :method :path}
     sig-meta    — (optional) signed envelope for bound/group modes
     body        — (optional) request body
     opts        — (optional) {:roster :proxy-identity}

   Returns: {:authorized true/false ...}"
  ([token-str trust-roots request]
   (verify-and-authorize token-str trust-roots request nil nil nil))
  ([token-str trust-roots request sig-meta body]
   (verify-and-authorize token-str trust-roots request sig-meta body nil))
  ([token-str trust-roots {:keys [effect domain method path]} sig-meta body
    {:keys [roster proxy-identity]}]
   (try
     (let [token (deserialize-token token-str)
           kind  (token-kind token)]
       (case kind
         ;; ── SPKI assertion block ──────────────────────────────────
         :spki
         (cond
           (nil? sig-meta)
           {:authorized false
            :reason "SPKI block requires signed request (X-Agent-Signature header missing)"}
           :else
           (do (replay/evict-expired! replay-guard)
               (authorize-spki token trust-roots effect domain
                               sig-meta method path body proxy-identity)))

         ;; ── Chain-shaped token (bearer / bound / group) ───────────
         :chain
         (let [facts (token-facts token)
               mode  (token-mode facts)]
           (cond
             (and (#{:bound :group} mode) (nil? sig-meta))
             {:authorized false
              :reason (str (case mode :group "Group" :bound "Bound")
                           " token requires signed request"
                           " (X-Agent-Signature header missing)")}

             (and (= mode :group) (nil? roster))
             {:authorized false
              :reason "Group token requires roster configuration (STROOPWAFEL_ROSTER)"}

             :else
             (do (replay/evict-expired! replay-guard)
                 (case mode
                   :bearer (authorize-bearer token trust-roots effect domain)
                   :bound  (authorize-bound token trust-roots effect domain
                                            sig-meta method path body proxy-identity)
                   :group  (authorize-group token trust-roots effect domain
                                            sig-meta method path body roster
                                            proxy-identity)))))

         :unknown
         {:authorized false :reason "Unrecognized token format"}))
     (catch Exception e
       (log/log! {:level :warn :id ::auth-error
                  :msg  "Token verification error"
                  :data {:error (.getMessage e)}})
       {:authorized false :reason (str "Token error: " (.getMessage e))}))))
