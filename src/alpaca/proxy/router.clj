(ns alpaca.proxy.router
  "fn-as-URL routing — the structural whitelist.
   Routes are derived from the schema. Unknown paths → 404 before any logic."
  (:require [alpaca.schema :as schema]
            [alpaca.proxy.handlers :as handlers]))

(defn- method-matches?
  "Check if ring request method matches schema method."
  [req-method schema-method]
  (= req-method (name schema-method)))

(defn create-router
  "Create a Ring handler from the schema.
   Each schema entry becomes a route. Unknown routes → 404.

   Arguments:
     config — alpaca config map (passed to handlers)"
  [config]
  (fn [req]
    (let [uri    (:uri req)
          method (name (:request-method req))]
      (cond
        ;; Health check
        (= uri "/health")
        {:status 200
         :body   (pr-str {:status "ok"})}

        ;; API discovery — list all available operations
        (= uri "/api")
        {:status 200
         :body   (pr-str (schema/api-listing))}

        ;; Schema-defined routes
        :else
        (if-let [op (get schema/by-route uri)]
          (if (method-matches? method (:method op))
            (handlers/handle-request op config req)
            {:status 405
             :body   (pr-str {:error "Method not allowed"
                              :allowed (name (:method op))})})
          {:status 404
           :body   (pr-str {:error "Not found"
                            :message (str "Unknown operation: " uri)})})))))
