(ns alpaca.proxy.log
  "Structured request logging for the proxy using taoensso.trove."
  (:require [taoensso.trove :as log]))

(defn log-request
  "Log a completed proxy request.
   Arguments:
     req    — ring request
     op     — schema operation (or nil for non-schema routes)
     status — HTTP response status
     ms     — elapsed time in milliseconds
     error  — error message (or nil)"
  [req op status ms error]
  (let [data (cond-> {:method (-> req :request-method name .toUpperCase)
                      :uri    (:uri req)
                      :status status
                      :duration-ms ms}
               op    (assoc :operation (subs (str (:name op)) 1)
                            :effect    (name (:effect op))))]
    (if error
      (log/log! {:level :error
                 :id    ::request-failed
                 :msg   "Request failed"
                 :data  (assoc data :error-msg error)})
      (log/log! {:level :info
                 :id    ::request-completed
                 :msg   "Request completed"
                 :data  data}))))
