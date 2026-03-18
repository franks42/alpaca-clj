(ns alpaca.auth
  "Stroopwafel token authentication for the proxy.
   Handles token serialization, verification, and authorization.

   Two auth modes:
   - Bearer-only: token in Authorization header (current)
   - Requester-bound: token + signed request (agent key in token,
     agent signs each request, Datalog join verifies key match)"
  (:require [stroopwafel.core :as sw]
            [stroopwafel.crypto :as sw-crypto]
            [stroopwafel.request :as sw-req]
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
       :domains     — set of allowed domains #{\"market\" \"account\" \"trading\"}
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

;; ---------------------------------------------------------------------------
;; Request signing (agent side)
;; ---------------------------------------------------------------------------

(defn sign-request
  "Sign a request body with the agent's private key.
   Returns a signed request map with :body :agent-key :sig :timestamp."
  [body agent-kp]
  (sw-req/sign-request body (:priv agent-kp) (:pub agent-kp)))

(defn serialize-signed-request
  "Serialize the signature metadata (agent-key, sig, timestamp) as CEDN.
   The body is already in the HTTP request — only sig metadata goes in the header."
  [signed-req]
  (cedn/canonical-str (select-keys signed-req [:agent-key :sig :timestamp])))

(defn deserialize-sig-metadata
  "Deserialize signature metadata from header value."
  [s]
  (edn/read-string {:readers cedn/readers} s))

;; ---------------------------------------------------------------------------
;; Token verification and authorization
;; ---------------------------------------------------------------------------

(defn- check-bearer-only
  "Evaluate a bearer-only token (no requester binding)."
  [token effect domain]
  (let [result (sw/evaluate token
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
                    " access to " domain)})))

(defn- check-requester-bound
  "Evaluate a requester-bound token with signed request verification."
  [token effect domain sig-metadata request-body]
  (let [;; Reconstruct the signed request for verification
        signed-req (assoc sig-metadata :body request-body)
        verified-key (sw-req/verify-request signed-req)]
    (if-not verified-key
      {:authorized false :reason "Request signature verification failed"}
      (let [result (sw/evaluate token
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
          {:authorized true :requester-bound true}
          {:authorized false
           :reason "Token not bound to this agent key"})))))

(defn verify-and-authorize
  "Verify token signature and evaluate authorization for a request.

   Arguments:
     token-str    — serialized token string (from Bearer header)
     public-key   — root public key
     request      — map with :effect and :domain from the schema
     sig-metadata — (optional) deserialized signature metadata from X-Agent-Signature header
     request-body — (optional) the EDN request body (for signature verification)

   Returns:
     {:authorized true} or {:authorized false :reason \"...\"}."
  ([token-str public-key request]
   (verify-and-authorize token-str public-key request nil nil))
  ([token-str public-key {:keys [effect domain]} sig-metadata request-body]
   (try
     (let [token (deserialize-token token-str)]
       ;; 1. Verify cryptographic integrity
       (if-not (sw/verify token {:public-key public-key})
         {:authorized false :reason "Token signature verification failed"}

         ;; 2. Check if token has agent binding
         (let [has-agent-key? (some #(= :authorized-agent-key (first %))
                                    (get-in token [:blocks 0 :facts]))]
           (cond
             ;; Token is requester-bound but no signature provided
             (and has-agent-key? (nil? sig-metadata))
             {:authorized false
              :reason "Token requires signed request (X-Agent-Signature header missing)"}

             ;; Token is requester-bound and signature provided
             (and has-agent-key? sig-metadata)
             (check-requester-bound token effect domain sig-metadata request-body)

             ;; Bearer-only token
             :else
             (check-bearer-only token effect domain)))))
     (catch Exception e
       (log/log! {:level :warn :id ::auth-error
                  :msg "Token verification error"
                  :data {:error (.getMessage e)}})
       {:authorized false :reason (str "Token error: " (.getMessage e))}))))
