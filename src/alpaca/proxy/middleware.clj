(ns alpaca.proxy.middleware
  "Ring middleware for the proxy server.

   Auth modes:
   - Simple: PROXY_TOKEN env var (development)
   - Stroopwafel: capability token auth with HTTP+EDN canonicalization"
  (:require [alpaca.auth :as auth]
            [alpaca.pep.http-edn :as http-edn]
            [alpaca.proxy.log :as log]
            [alpaca.schema :as schema]
            [taoensso.trove :as tlog]))

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

(defn- deny-response
  "Build a 401/403 EDN error response for denied requests."
  [reason status]
  {:status status
   :body   (pr-str {:error  (case status 401 "Unauthorized" "Forbidden")
                    :reason reason})})

(defn wrap-stroopwafel-auth
  "Stroopwafel capability token auth middleware.

   Pipeline:
     1. /health and /api bypass auth (exempt)
     2. Canonicalize: Ring request → {:method :path :body :effect :domain}
        (nil → unknown route, pass through to 404 handling)
     3. Extract Bearer token + X-Agent-Signature header
     4. Missing token → 401
     5. verify-and-authorize → allowed: pass to handler, denied: 403

   Optional roster for SDSI group-based authorization.
   Optional proxy-identity for audience binding."
  ([handler trust-roots]
   (wrap-stroopwafel-auth handler trust-roots nil))
  ([handler trust-roots roster]
   (wrap-stroopwafel-auth handler trust-roots roster nil))
  ([handler trust-roots roster proxy-identity]
   (fn [req]
     (if (http-edn/exempt? req)
       (handler req)
       (let [canonical (http-edn/canonicalize req)]
         (if (nil? canonical)
           (handler req)                    ;; unknown route → 404 downstream
           (let [{:keys [token-str sig-metadata body-str]} (http-edn/extract-creds req)]
             (if (nil? token-str)
               (deny-response "Missing Bearer token" 401)
               (let [result (auth/verify-and-authorize
                             token-str trust-roots
                             {:effect (:effect canonical)
                              :domain (:domain canonical)
                              :method (keyword (:method canonical))
                              :path   (:path canonical)}
                             sig-metadata
                             (:body canonical)
                             {:roster roster :proxy-identity proxy-identity})
                     req    (if (and body-str (not (string? (:body req))))
                              (assoc req :body body-str)
                              req)]
                 (if (:authorized result)
                   (do (tlog/log!
                        {:level :debug :id ::request-authorized
                         :msg   "Request authorized"
                         :data  (cond-> {:path   (:path canonical)
                                         :effect (:effect canonical)
                                         :domain (:domain canonical)}
                                  (:requester-bound result)
                                  (assoc :bound? true
                                         :agent-key-fp (:agent-key-fp result))
                                  (:request-id result)
                                  (assoc :request-id (:request-id result)))})
                       (handler req))
                   (do (tlog/log!
                        {:level :warn :id ::request-denied
                         :msg   "Request denied"
                         :data  {:path   (:path canonical)
                                 :effect (:effect canonical)
                                 :domain (:domain canonical)
                                 :reason (:reason result)}})
                       (deny-response (:reason result) 403))))))))))))

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
