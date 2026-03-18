(ns alpaca.auth
  "Stroopwafel token authentication for the proxy.

   Two auth modes:
   - Bearer-only: token in Authorization header
   - Requester-bound: token + signed request envelope

   Signed request envelopes include method, path, body, and a UUIDv7
   request-id that serves as both timestamp and nonce for replay protection."
  (:require [stroopwafel.core :as sw]
            [stroopwafel.crypto :as sw-crypto]
            [stroopwafel.request :as sw-req]
            [com.github.franks42.uuidv7.core :as uuidv7]
            [cedn.core :as cedn] ;; cedn/readers used at runtime
            [clojure.edn :as edn]
            [taoensso.trove :as log]))

;; ---------------------------------------------------------------------------
;; Keypair management
;; ---------------------------------------------------------------------------

(defn generate-keypair
  "Generate a new Ed25519 keypair.
   Returns {:priv PrivateKey :pub PublicKey}."
  []
  (sw/new-keypair))

(defn export-public-key
  "Export public key as hex string for configuration/display."
  [kp]
  (let [bytes (sw-crypto/encode-public-key (:pub kp))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn public-key-bytes
  "Get the encoded public key bytes for embedding in token facts."
  [kp]
  (sw-crypto/encode-public-key (:pub kp)))

(defn import-public-key
  "Import public key from hex string."
  [hex]
  (let [bytes (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16))
                               (partition 2 hex)))]
    (sw-crypto/decode-public-key bytes)))

(defn hex->bytes
  "Convert hex string to byte array."
  [hex]
  (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16))
                   (partition 2 hex))))

(defn bytes->hex
  "Convert byte array to hex string."
  [bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

;; ---------------------------------------------------------------------------
;; Token serialization (CEDN with #bytes support)
;; ---------------------------------------------------------------------------

(defn serialize-token
  "Serialize a sealed token to a CEDN string for transport."
  [token]
  (cedn/canonical-str token))

(defn deserialize-token
  "Deserialize a CEDN string back to a token map."
  [s]
  (edn/read-string {:readers cedn/readers} s))

;; ---------------------------------------------------------------------------
;; Token issuance
;; ---------------------------------------------------------------------------

(defn issue-token
  "Issue a new capability token for proxy access.

   Arguments:
     root-kp      — root keypair {:priv :pub}
     grants       — map of grants:
       :effects     — set of allowed effect classes #{:read :write :destroy}
       :domains     — set of allowed domains #{\"market\" \"account\" \"trade\"}
       :agent-key   — (optional) agent public key bytes for requester binding

   Returns: sealed token string (CEDN) ready for Bearer header."
  [root-kp {:keys [effects domains agent-key]}]
  (let [effect-facts (mapv (fn [e] [:effect e]) effects)
        domain-facts (mapv (fn [d] [:domain d]) domains)
        all-facts    (cond-> (into effect-facts domain-facts)
                       agent-key (conj [:authorized-agent-key agent-key]))
        token        (sw/issue
                      {:facts all-facts}
                      {:private-key (:priv root-kp)})
        sealed       (sw/seal token)]
    (serialize-token sealed)))

(defn issue-group-token
  "Issue a capability token for SDSI group-based authorization.

   Arguments:
     root-kp — root keypair {:priv :pub}
     grants  — map of grants:
       :rights — vector of [group-name effect domain] triples

   Example:
     (issue-group-token root-kp
       {:rights [[\"traders\" :read \"market\"]
                 [\"traders\" :write \"trade\"]]})

   Returns: sealed token string (CEDN) ready for Bearer header."
  [root-kp {:keys [rights]}]
  (let [right-facts (mapv (fn [[group effect domain]]
                            [:right group effect domain])
                          rights)
        token       (sw/issue
                     {:facts right-facts}
                     {:private-key (:priv root-kp)})
        sealed      (sw/seal token)]
    (serialize-token sealed)))

;; ---------------------------------------------------------------------------
;; Request envelope signing (agent side)
;; ---------------------------------------------------------------------------

(defn sign-request
  "Sign a request envelope with the agent's private key.

   The envelope includes method, path, body, UUIDv7 request-id, and
   an optional audience (proxy identity). The audience prevents
   cross-proxy replay — a request signed for proxy-A is rejected by proxy-B.

   Arguments:
     method   — HTTP method keyword (:get, :post)
     path     — route path string (\"/market/quote\")
     body     — EDN map (request body, or {} for GET)
     agent-kp — agent keypair {:priv :pub}
     audience — (optional) proxy identity string (e.g. host:port)

   Returns: signed request map from stroopwafel.request."
  ([method path body agent-kp]
   (sign-request method path body agent-kp nil))
  ([method path body agent-kp audience]
   (let [envelope (cond-> {:method     (name method)
                           :path       path
                           :body       (or body {})
                           :request-id (str (uuidv7/uuidv7))}
                    audience (assoc :audience audience))]
     (sw-req/sign-request envelope (:priv agent-kp) (:pub agent-kp)))))

(defn serialize-signed-request
  "Serialize the signature metadata (agent-key, sig, timestamp, and the
   full envelope) as CEDN for the X-Agent-Signature header."
  [signed-req]
  (cedn/canonical-str (select-keys signed-req [:agent-key :sig :timestamp :body])))

(defn deserialize-sig-metadata
  "Deserialize signature metadata from header value."
  [s]
  (edn/read-string {:readers cedn/readers} s))

;; ---------------------------------------------------------------------------
;; Replay protection
;; ---------------------------------------------------------------------------

(def ^:private replay-cache
  "Cache of recently seen request-ids. Keyed by request-id string.
   Entries expire after the freshness window."
  (atom {}))

(def ^:private freshness-window-ms
  "Maximum age of a signed request before it's rejected (2 minutes)."
  120000)

(defn- check-freshness
  "Check that the request-id's embedded timestamp is within the freshness window.
   Returns nil if OK, or error string if stale."
  [request-id-str]
  (try
    (let [request-id (parse-uuid request-id-str)]
      (if-not (uuidv7/uuidv7? request-id)
        "request-id is not a valid UUIDv7"
        (let [ts  (uuidv7/extract-ts request-id)
              now (System/currentTimeMillis)
              age (- now ts)]
          (cond
            (> age freshness-window-ms)
            (str "Request too old: " age "ms (max " freshness-window-ms "ms)")

            (< age -5000)
            (str "Request timestamp is in the future: " (- age) "ms")

            :else nil))))
    (catch Exception e
      (str "Invalid request-id: " (.getMessage e)))))

(defn- check-replay
  "Check that the request-id has not been seen before.
   Returns nil if OK, or error string if replay detected."
  [request-id-str]
  (if (contains? @replay-cache request-id-str)
    "Replay detected: request-id already seen"
    (do
      (swap! replay-cache assoc request-id-str (System/currentTimeMillis))
      nil)))

(defn- evict-expired-cache-entries!
  "Remove replay cache entries older than the freshness window."
  []
  (let [cutoff (- (System/currentTimeMillis) freshness-window-ms)]
    (swap! replay-cache
           (fn [cache]
             (into {} (filter (fn [[_ ts]] (> ts cutoff)) cache))))))

;; ---------------------------------------------------------------------------
;; Token verification and authorization
;; ---------------------------------------------------------------------------

(defn- agent-key-fingerprint
  "Short hex fingerprint of an agent public key (first 16 hex chars)."
  [key-bytes]
  (when key-bytes
    (subs (bytes->hex key-bytes) 0 (min 16 (* 2 (count key-bytes))))))

(defn- check-bearer-only
  "Evaluate a bearer-only token (no requester binding)."
  [token effect domain]
  (let [result (sw/evaluate token
                            :explain? true
                            :authorizer
                            {:facts [[:requested-effect effect]
                                     [:requested-domain domain]]
                             :checks [{:id    :check-effect
                                       :query [[:effect effect]]}
                                      {:id    :check-domain
                                       :query [[:domain domain]]}]})]
    (if (:valid? result)
      {:authorized true}
      {:authorized false
       :reason (str "Token does not grant " (name effect)
                    " access to " domain)
       :explain (:explain result)})))

(defn- verify-envelope
  "Verify the signed envelope matches the actual request and passes
   freshness/replay checks. Returns nil if OK, or error map if not.
   Also checks audience if present in the envelope."
  [signed-envelope actual-method actual-path actual-body proxy-identity]
  (let [env-method (:method signed-envelope)
        env-path   (:path signed-envelope)
        env-body   (:body signed-envelope)
        env-req-id (:request-id signed-envelope)
        env-audience (:audience signed-envelope)]
    (cond
      (not= env-method (name actual-method))
      {:authorized false
       :reason (str "Signed method '" env-method
                    "' does not match actual method '" (name actual-method) "'")}

      (not= env-path actual-path)
      {:authorized false
       :reason (str "Signed path '" env-path
                    "' does not match actual path '" actual-path "'")}

      (not= env-body (or actual-body {}))
      {:authorized false
       :reason "Signed body does not match actual request body"}

      ;; Audience check: if the envelope has an audience, it must match the proxy
      (and env-audience proxy-identity (not= env-audience proxy-identity))
      {:authorized false
       :reason (str "Audience mismatch: request signed for '" env-audience
                    "' but this proxy is '" proxy-identity "'")}

      :else
      (if-let [err (check-freshness env-req-id)]
        {:authorized false :reason err}
        (if-let [err (check-replay env-req-id)]
          {:authorized false :reason err}
          nil)))))

(defn- roster-facts
  "Convert a roster map into [:named-key group pk-bytes] Datalog facts.
   Roster format: {\"traders\" [\"hex1\" \"hex2\"], \"monitors\" [\"hex3\"]}"
  [roster]
  (when roster
    (into []
          (mapcat (fn [[group-name key-hexes]]
                    (map (fn [hex] [:named-key group-name (hex->bytes hex)])
                         key-hexes)))
          roster)))

(defn- check-sdsi-group
  "Evaluate a token with SDSI group-based authorization.
   Token carries [:right group-name :effect domain].
   Roster provides [:named-key group-name pk-bytes].
   Datalog resolves: name→key→verified-key→authenticated-as→right."
  [token effect domain sig-metadata actual-method actual-path actual-body roster
   proxy-identity]
  (let [signed-envelope (:body sig-metadata)
        signed-req      (assoc sig-metadata :body signed-envelope)
        verified-key    (sw-req/verify-request signed-req)]
    (if-not verified-key
      {:authorized false :reason "Request signature verification failed"}
      (or (verify-envelope signed-envelope actual-method actual-path actual-body
                           proxy-identity)
          (let [name-facts (roster-facts roster)
                result (sw/evaluate token
                                    :explain? true
                                    :authorizer
                                    {:facts (into [[:request-verified-agent-key verified-key]]
                                                  name-facts)
                                     :rules [{:id   :resolve-name
                                              :head [:authenticated-as '?name]
                                              :body [[:named-key '?name '?k]
                                                     [:request-verified-agent-key '?k]]}]
                                     :policies [{:kind :allow
                                                 :query [[:authenticated-as '?name]
                                                         [:right '?name effect domain]]}]})]
            (if (:valid? result)
              {:authorized true
               :requester-bound true
               :sdsi-group true
               :request-id (:request-id signed-envelope)
               :agent-key-fp (agent-key-fingerprint verified-key)}
              {:authorized false
               :reason (str "Agent not in any group with " (name effect)
                            " access to " domain)
               :explain (:explain result)}))))))

(defn- check-requester-bound
  "Evaluate a requester-bound token with signed request verification.
   Verifies: signature, envelope contents match actual request,
   freshness, and replay protection."
  [token effect domain sig-metadata actual-method actual-path actual-body
   proxy-identity]
  (let [signed-envelope (:body sig-metadata)
        signed-req      (assoc sig-metadata :body signed-envelope)
        verified-key    (sw-req/verify-request signed-req)]
    (if-not verified-key
      {:authorized false :reason "Request signature verification failed"}
      (or (verify-envelope signed-envelope actual-method actual-path actual-body
                           proxy-identity)
          (let [result (sw/evaluate token
                                    :explain? true
                                    :authorizer
                                    {:facts [[:requested-effect effect]
                                             [:requested-domain domain]
                                             [:request-verified-agent-key verified-key]]
                                     :rules [{:id   :agent-bound
                                              :head [:agent-can-act '?k]
                                              :body [[:authorized-agent-key '?k]
                                                     [:request-verified-agent-key '?k]]}]
                                     :checks [{:id    :check-effect
                                               :query [[:effect effect]]}
                                              {:id    :check-domain
                                               :query [[:domain domain]]}]
                                     :policies [{:kind :allow
                                                 :query [[:agent-can-act '?k]]}]})]
            (if (:valid? result)
              {:authorized true
               :requester-bound true
               :request-id (:request-id signed-envelope)
               :agent-key-fp (agent-key-fingerprint verified-key)}
              {:authorized false
               :reason "Token not bound to this agent key"
               :explain (:explain result)}))))))

(defn verify-and-authorize
  "Verify token signature and evaluate authorization for a request.

   Arguments:
     token-str    — serialized token string (from Bearer header)
     public-key   — root public key
     request      — map with :effect, :domain, :method, :path from schema
     sig-metadata — (optional) deserialized signature metadata from X-Agent-Signature header
     request-body — (optional) the EDN request body (for envelope verification)

   Options map keys:
     :roster         — (optional) group roster for SDSI-style authorization
     :proxy-identity — (optional) proxy identity string for audience verification

   Returns:
     {:authorized true} or {:authorized false :reason \"...\"}."
  ([token-str public-key request]
   (verify-and-authorize token-str public-key request nil nil nil))
  ([token-str public-key request sig-metadata request-body]
   (verify-and-authorize token-str public-key request sig-metadata request-body nil))
  ([token-str public-key {:keys [effect domain method path]} sig-metadata request-body
    {:keys [roster proxy-identity] :as _opts}]
   (try
     (let [token (deserialize-token token-str)
           facts (get-in token [:blocks 0 :facts])]
       ;; 1. Verify cryptographic integrity
       (if-not (sw/verify token {:public-key public-key})
         {:authorized false :reason "Token signature verification failed"}

         ;; 2. Detect token type from facts
         (let [has-agent-key? (some #(= :authorized-agent-key (first %)) facts)
               has-right?     (some #(= :right (first %)) facts)]
           (cond
             ;; SDSI group token — has [:right ...] facts, needs roster + signature
             (and has-right? sig-metadata roster)
             (do (evict-expired-cache-entries!)
                 (check-sdsi-group token effect domain sig-metadata
                                   method path request-body roster
                                   proxy-identity))

             ;; SDSI group token but missing signature
             (and has-right? (nil? sig-metadata))
             {:authorized false
              :reason "Group token requires signed request (X-Agent-Signature header missing)"}

             ;; SDSI group token but no roster configured
             (and has-right? (nil? roster))
             {:authorized false
              :reason "Group token requires roster configuration (STROOPWAFEL_ROSTER)"}

             ;; SPKI requester-bound — has [:authorized-agent-key ...], needs signature
             (and has-agent-key? (nil? sig-metadata))
             {:authorized false
              :reason "Token requires signed request (X-Agent-Signature header missing)"}

             (and has-agent-key? sig-metadata)
             (do (evict-expired-cache-entries!)
                 (check-requester-bound token effect domain sig-metadata
                                        method path request-body
                                        proxy-identity))

             ;; Bearer-only token
             :else
             (check-bearer-only token effect domain)))))
     (catch Exception e
       (log/log! {:level :warn :id ::auth-error
                  :msg "Token verification error"
                  :data {:error (.getMessage e)}})
       {:authorized false :reason (str "Token error: " (.getMessage e))}))))
