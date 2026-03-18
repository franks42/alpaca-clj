(ns alpaca.proxy.middleware
  "Ring middleware for the proxy server.

   Auth modes:
   - Simple: PROXY_TOKEN env var (development)
   - Stroopwafel: PEP pipeline with configurable canonicalization"
  (:require [alpaca.proxy.log :as log]
            [alpaca.schema :as schema]
            [alpaca.pep :as pep]
            [alpaca.pep.http-edn :as http-edn]))

(defn wrap-edn-content-type
  "Set Content-Type: application/edn on all responses."
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (assoc-in resp [:headers "Content-Type"] "application/edn; charset=utf-8"))))

(defn wrap-simple-auth
  "Simple auth: check Bearer token against PROXY_TOKEN env var.
   If PROXY_TOKEN is not set, auth is disabled (development mode)."
  [handler proxy-token]
  (if (nil? proxy-token)
    handler
    (fn [req]
      (let [auth-header (get-in req [:headers "authorization"] "")
            token       (when (.startsWith auth-header "Bearer ")
                          (subs auth-header 7))]
        (if (= token proxy-token)
          (handler req)
          {:status 401
           :body   (pr-str {:error "Unauthorized"
                            :message "Invalid or missing Bearer token"})})))))

(defn wrap-stroopwafel-auth
  "Stroopwafel capability token auth via the PEP pipeline.

   Uses the HTTP+EDN canonicalization template:
   - Canonicalize: Ring request → canonical envelope (method, path, body, effect, domain)
   - Extract: Bearer token + X-Agent-Signature header
   - Authorize: verify token + signature + Datalog policy
   - Exempt: /health and /api bypass auth

   The canonicalization step is explicit and auditable — it defines the
   binding between the wire format and the policy evaluation."
  [handler public-key]
  ((pep/create-pep
    {:canonicalize  http-edn/canonicalize
     :extract-creds http-edn/extract-creds
     :authorize     http-edn/authorize
     :exempt?       http-edn/exempt?
     :public-key    public-key})
   handler))

(defn wrap-error-handler
  "Catch exceptions and return EDN error responses."
  [handler]
  (fn [req]
    (try
      (handler req)
      (catch Exception e
        (let [data (ex-data e)]
          {:status (or (:status data) 500)
           :body   (pr-str {:error   (or (.getMessage e) "Internal server error")
                            :details (dissoc data :status)})})))))

(defn wrap-request-logging
  "Log every request with operation, status, and timing."
  [handler]
  (fn [req]
    (let [start (System/currentTimeMillis)
          op    (get schema/by-route (:uri req))]
      (try
        (let [resp     (handler req)
              duration (- (System/currentTimeMillis) start)]
          (log/log-request req op (:status resp) duration nil)
          resp)
        (catch Exception e
          (let [duration (- (System/currentTimeMillis) start)]
            (log/log-request req op 500 duration (.getMessage e))
            (throw e)))))))
