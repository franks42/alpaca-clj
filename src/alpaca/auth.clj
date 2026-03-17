(ns alpaca.auth
  "Stroopwafel token authentication for the proxy.
   Handles token serialization, verification, and authorization."
  (:require [stroopwafel.core :as sw]
            [stroopwafel.crypto :as sw-crypto]
            [cedn.core :as cedn] ;; cedn/readers used at runtime
            [clojure.edn :as edn]
            [taoensso.trove :as log]))

;; ---------------------------------------------------------------------------
;; Root keypair management
;; ---------------------------------------------------------------------------

(defn generate-keypair
  "Generate a new Ed25519 keypair for token signing.
   Returns {:priv PrivateKey :pub PublicKey}."
  []
  (sw/new-keypair))

(defn export-public-key
  "Export public key as hex string for configuration."
  [kp]
  (let [bytes (sw-crypto/encode-public-key (:pub kp))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn import-public-key
  "Import public key from hex string."
  [hex]
  (let [bytes (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16))
                               (partition 2 hex)))]
    (sw-crypto/decode-public-key bytes)))

;; ---------------------------------------------------------------------------
;; Token serialization (CEDN with #bytes support)
;; ---------------------------------------------------------------------------

(defn serialize-token
  "Serialize a sealed token to a CEDN string for transport.
   Token MUST be sealed before serialization."
  [token]
  (cedn/canonical-str token))

(defn deserialize-token
  "Deserialize a CEDN string back to a token map.
   Uses cedn/readers for #bytes tagged literal support."
  [s]
  (edn/read-string {:readers cedn/readers} s))

;; ---------------------------------------------------------------------------
;; Token issuance
;; ---------------------------------------------------------------------------

(defn issue-token
  "Issue a new capability token for proxy access.

   Arguments:
     root-kp   — root keypair {:priv :pub}
     grants    — map of grants:
       :effects  — set of allowed effect classes #{:read :write :destroy}
       :domains  — set of allowed domains #{\"market\" \"account\" \"trading\"}

   Returns: sealed token string (CEDN) ready for Bearer header."
  [root-kp {:keys [effects domains]}]
  (let [effect-facts (mapv (fn [e] [:effect e]) effects)
        domain-facts (mapv (fn [d] [:domain d]) domains)
        token (sw/issue
               {:facts (into effect-facts domain-facts)}
               {:private-key (:priv root-kp)})
        sealed (sw/seal token)]
    (serialize-token sealed)))

;; ---------------------------------------------------------------------------
;; Token verification and authorization
;; ---------------------------------------------------------------------------

(defn verify-and-authorize
  "Verify token signature and evaluate authorization for a request.

   Arguments:
     token-str  — serialized token string (from Bearer header)
     public-key — root public key
     request    — map with :effect and :domain from the schema

   Returns:
     {:authorized true} or {:authorized false :reason \"...\"}."
  [token-str public-key {:keys [effect domain]}]
  (try
    (let [token (deserialize-token token-str)]
      ;; 1. Verify cryptographic integrity
      (if-not (sw/verify token {:public-key public-key})
        {:authorized false :reason "Token signature verification failed"}

        ;; 2. Evaluate authorization
        ;; Inject request facts, use policies to check token grants
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
                          " access to " domain)}))))
    (catch Exception e
      (log/log! {:level :warn :id ::auth-error
                 :msg "Token verification error"
                 :data {:error (.getMessage e)}})
      {:authorized false :reason (str "Token error: " (.getMessage e))})))
