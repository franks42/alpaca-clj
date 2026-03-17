(ns alpaca.proxy.middleware
  "Ring middleware for the proxy server.
   Phase 1: simple token auth via PROXY_TOKEN env var.
   Phase 2: Stroopwafel capability token verification."
  (:require [alpaca.proxy.log :as log]
            [alpaca.schema :as schema]))

(defn wrap-edn-content-type
  "Set Content-Type: application/edn on all responses."
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (assoc-in resp [:headers "Content-Type"] "application/edn; charset=utf-8"))))

(defn wrap-simple-auth
  "Phase 1 auth: check Bearer token against PROXY_TOKEN env var.
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
