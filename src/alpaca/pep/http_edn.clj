(ns alpaca.pep.http-edn
  "HTTP + EDN canonicalization and credential extraction for the PEP.

   This is the template for alpaca-clj's wire format: Ring requests with
   EDN bodies, Bearer tokens in Authorization header, and optional
   X-Agent-Signature header for requester-bound tokens.

   Other PEP implementations would provide different canonicalize/extract
   functions for their wire format (JSON, gRPC, CLI, nREPL, etc.)."
  (:require [alpaca.auth :as auth]
            [alpaca.schema :as schema]
            [clojure.edn :as edn]))

(defn- read-body-string
  "Read request body as a string."
  [req]
  (when-let [body (:body req)]
    (let [s (if (string? body) body (slurp body))]
      (when-not (empty? s) s))))

(defn canonicalize
  "Canonicalize a Ring HTTP request into a security-relevant envelope.

   This is THE binding between the wire world and the policy world.
   Every field in the returned map is a fact that the authorization
   engine will check. If this function extracts the wrong method,
   path, or body, the authorization decision will be wrong.

   Returns nil for unknown routes (lets the router handle 404).

   Wire format assumptions:
   - Ring request map with :request-method, :uri, :body, :headers
   - Body is EDN (parsed via clojure.edn/read-string)
   - Schema lookup via alpaca.schema/by-route"
  [req]
  (let [uri (:uri req)
        op  (get schema/by-route uri)]
    (when op
      (let [body-str (read-body-string req)
            body-edn (if body-str (edn/read-string body-str) {})]
        {:method  (-> req :request-method name)
         :path    uri
         :body    body-edn
         :effect  (:effect op)
         :domain  (namespace (:name op))
         :op      op
         ;; Preserve body-str for re-attachment after consumption
         :_body-str body-str}))))

(defn extract-creds
  "Extract authentication credentials from a Ring HTTP request.

   Looks for:
   - Authorization: Bearer <token> → :token-str
   - X-Agent-Signature: <cedn> → :sig-metadata (deserialized)
   - Raw body string for re-attachment → :body-str"
  [req]
  (let [auth-header (get-in req [:headers "authorization"] "")
        token-str   (when (.startsWith auth-header "Bearer ")
                      (subs auth-header 7))
        sig-header  (get-in req [:headers "x-agent-signature"])
        sig-meta    (when sig-header
                      (auth/deserialize-sig-metadata sig-header))
        body-str    (when-let [body (:body req)]
                      (if (string? body) body nil))]
    {:token-str    token-str
     :sig-metadata sig-meta
     :body-str     body-str}))

(defn exempt?
  "Check if a request is exempt from auth (health, discovery)."
  [req]
  (let [uri (:uri req)]
    (or (= uri "/health")
        (= uri "/api"))))

(defn make-authorize
  "Create an authorize function with optional roster for SDSI group support.
   Returns (fn [token-str public-key canonical sig-metadata body] → result)."
  ([] (make-authorize nil))
  ([roster]
   (fn [token-str public-key canonical sig-metadata body]
     (auth/verify-and-authorize
      token-str public-key
      {:effect (:effect canonical)
       :domain (:domain canonical)
       :method (keyword (:method canonical))
       :path   (:path canonical)}
      sig-metadata
      body
      {:roster roster}))))
