(ns alpaca.proxy.handlers
  "Request handlers for proxy endpoints.
   Each handler: parse EDN body → call Alpaca client → return EDN response."
  (:require [alpaca.client :as client]
            [alpaca.keys :as keys]
            [clojure.edn :as edn]))

(defn- read-edn-body
  "Read EDN from request body. Returns empty map for GET requests."
  [req]
  (if-let [body (:body req)]
    (let [s (if (string? body) body (slurp body))]
      (if (empty? s) {} (edn/read-string s)))
    {}))

(defn- merge-defaults
  "Apply default values from schema params where request value is missing."
  [params schema-params]
  (reduce-kv
   (fn [m k {:keys [default]}]
     (if (and default (not (contains? m k)))
       (assoc m k default)
       m))
   params
   schema-params))

(defn- validate-required
  "Check that all required params are present. Returns nil or error string."
  [params schema-params]
  (let [missing (->> schema-params
                     (filter (fn [[_ v]] (:required v)))
                     (map first)
                     (remove #(contains? params %)))]
    (when (seq missing)
      (str "Missing required parameters: " (pr-str (vec missing))))))

(defn handle-request
  "Generic handler for any schema-defined operation.

   Arguments:
     op     — operation schema map (from alpaca.schema)
     config — alpaca config map
     req    — ring request

   Returns: ring response with EDN body."
  [op config req]
  (let [params        (read-edn-body req)
        schema-params (:params op)
        err           (validate-required params schema-params)
        _             (when err (throw (ex-info err {:status 400})))
        params        (merge-defaults params schema-params)
        result        (client/request! (:alpaca op) params config)
        result        (keys/expand-response (:name op) result)]
    {:status 200
     :body   (pr-str result)}))
