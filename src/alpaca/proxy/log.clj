(ns alpaca.proxy.log
  "Structured request logging for the proxy using taoensso.trove.
   Includes security audit fields: token fingerprint, agent key, auth result."
  (:require [taoensso.trove :as log]))

(defn- token-fingerprint
  "Extract a short fingerprint from the Bearer token for audit logging.
   Uses first 16 chars of the token string — enough to correlate logs
   without exposing the full token."
  [req]
  (let [auth (get-in req [:headers "authorization"] "")]
    (when (.startsWith auth "Bearer ")
      (let [token (subs auth 7)]
        (when (> (count token) 16)
          (subs token 0 16))))))

(defn- agent-sig?
  "Check if the request has an agent signature header."
  [req]
  (some? (get-in req [:headers "x-agent-signature"])))

(defn log-request
  "Log a completed proxy request with security audit fields.

   Arguments:
     req    — ring request
     op     — schema operation (or nil for non-schema routes)
     status — HTTP response status
     ms     — elapsed time in milliseconds
     error  — error message (or nil)"
  [req op status ms error]
  (let [data (cond-> {:method      (-> req :request-method name .toUpperCase)
                      :uri         (:uri req)
                      :status      status
                      :duration-ms ms}
               op               (assoc :operation (subs (str (:name op)) 1)
                                       :effect    (name (:effect op)))
               (token-fingerprint req) (assoc :token-fp (token-fingerprint req))
               (agent-sig? req)        (assoc :agent-signed true))]
    (if error
      (log/log! {:level :error
                 :id    ::request-failed
                 :msg   "Request failed"
                 :data  (assoc data :error-msg error)})
      (log/log! {:level :info
                 :id    ::request-completed
                 :msg   "Request completed"
                 :data  data}))))
