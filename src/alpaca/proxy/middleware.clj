(ns alpaca.proxy.middleware
  "Ring middleware for the proxy server.
   Supports three auth modes:
   - Simple: PROXY_TOKEN env var (development)
   - Stroopwafel bearer: capability token (production)
   - Stroopwafel requester-bound: token + signed request (highest security)"
  (:require [alpaca.proxy.log :as log]
            [alpaca.schema :as schema]
            [alpaca.auth :as auth]
            [clojure.edn :as edn]))

(defn wrap-edn-content-type
  "Set Content-Type: application/edn on all responses."
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (assoc-in resp [:headers "Content-Type"] "application/edn; charset=utf-8"))))

(defn- extract-bearer-token
  "Extract Bearer token from Authorization header."
  [req]
  (let [auth-header (get-in req [:headers "authorization"] "")]
    (when (.startsWith auth-header "Bearer ")
      (subs auth-header 7))))

(defn- read-body-string
  "Read request body as a string, preserving it for downstream handlers."
  [req]
  (when-let [body (:body req)]
    (let [s (if (string? body) body (slurp body))]
      (when-not (empty? s) s))))

(defn wrap-simple-auth
  "Simple auth: check Bearer token against PROXY_TOKEN env var.
   If PROXY_TOKEN is not set, auth is disabled (development mode)."
  [handler proxy-token]
  (if (nil? proxy-token)
    handler
    (fn [req]
      (let [token (extract-bearer-token req)]
        (if (= token proxy-token)
          (handler req)
          {:status 401
           :body   (pr-str {:error "Unauthorized"
                            :message "Invalid or missing Bearer token"})})))))

(defn wrap-stroopwafel-auth
  "Stroopwafel capability token auth.
   Verifies token signature and checks effect class + domain grants.

   Supports two modes:
   - Bearer-only: just Authorization header (token is bearer)
   - Requester-bound: Authorization + X-Agent-Signature headers
     (token bound to agent key, agent signs each request)

   Skips auth for /health and /api endpoints."
  [handler public-key]
  (fn [req]
    (let [uri (:uri req)]
      ;; Skip auth for discovery and health endpoints
      (if (or (= uri "/health") (= uri "/api"))
        (handler req)
        (let [token-str (extract-bearer-token req)
              op        (get schema/by-route uri)]
          (cond
            (nil? token-str)
            {:status 401
             :body (pr-str {:error "Unauthorized"
                            :message "Missing Bearer token"})}

            (nil? op)
            ;; Let the router handle unknown routes (404)
            (handler req)

            :else
            (let [domain       (namespace (:name op))
                  effect       (:effect op)
                  sig-header   (get-in req [:headers "x-agent-signature"])
                  sig-metadata (when sig-header
                                 (auth/deserialize-sig-metadata sig-header))
                  body-str     (when sig-metadata (read-body-string req))
                  body-edn     (if body-str (edn/read-string body-str) {})
                  result       (auth/verify-and-authorize
                                token-str public-key
                                {:effect effect :domain domain}
                                sig-metadata
                                body-edn)
                  ;; Re-attach body string for downstream handlers
                  req          (if (and body-str (not (string? (:body req))))
                                 (assoc req :body body-str)
                                 req)]
              (if (:authorized result)
                (handler req)
                {:status 403
                 :body (pr-str {:error "Forbidden"
                                :reason (:reason result)})}))))))))

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
