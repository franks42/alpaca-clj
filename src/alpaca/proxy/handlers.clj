(ns alpaca.proxy.handlers
  "Request handlers for proxy endpoints.
   Each handler: parse EDN body → validate → check constraints → call Alpaca → return EDN."
  (:require [alpaca.client :as client]
            [alpaca.keys :as keys]
            [clojure.edn :as edn]
            [clojure.string :as str]))

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

(defn- coerce-param
  "Coerce a parameter value to its declared type. Returns [value nil] or [nil error]."
  [k v declared-type]
  (case declared-type
    :string  (if (string? v) [v nil]
                 [(str v) nil])
    :int     (cond
               (int? v) [v nil]
               (string? v) (if-let [n (parse-long v)]
                             [n nil]
                             [nil (str k " must be an integer, got: " (pr-str v))])
               :else [nil (str k " must be an integer, got: " (pr-str v))])
    :boolean (cond
               (boolean? v) [v nil]
               (= "true" v) [true nil]
               (= "false" v) [false nil]
               :else [nil (str k " must be a boolean, got: " (pr-str v))])
    ;; No type declared — pass through
    [v nil]))

(defn- validate-and-coerce
  "Validate param types and coerce values according to schema.
   Returns [coerced-params nil] or [nil error-string]."
  [params schema-params]
  (reduce-kv
   (fn [[acc _] k v]
     (if-let [spec (get schema-params k)]
       (let [[coerced err] (coerce-param k v (:type spec))]
         (if err
           (reduced [nil err])
           [(assoc acc k coerced) nil]))
       ;; Unknown parameter — reject (closed schema)
       (reduced [nil (str "Unknown parameter: " (pr-str k))])))
   [{} nil]
   params))

;; ---------------------------------------------------------------------------
;; Constraint evaluation
;; ---------------------------------------------------------------------------

(defn- check-enum
  "Check {:enum {:key [\"val1\" \"val2\"]}} — value must be in the allowed set."
  [params {:keys [enum]}]
  (reduce-kv
   (fn [_ k allowed]
     (when-let [v (get params k)]
       (when-not (some #{v} allowed)
         (reduced (str (name k) " must be one of "
                       (str/join ", " allowed)
                       ", got: " (pr-str v))))))
   nil
   enum))

(defn- check-when-require
  "Check {:when {:key \"val\"} :require [:k1 :k2]} — conditional required params."
  [params {:keys [when require]}]
  (let [[cond-key cond-val] (first when)]
    (clojure.core/when (= (get params cond-key) cond-val)
      (let [missing (filter #(nil? (get params %)) require)]
        (clojure.core/when (seq missing)
          (str "When " (name cond-key) " is " (pr-str cond-val)
               ", the following are required: "
               (str/join ", " (map name missing))))))))

(defn- check-mutex
  "Check {:mutex [:k1 :k2]} — at most one may be present."
  [params {:keys [mutex]}]
  (let [present (filter #(contains? params %) mutex)]
    (when (> (count present) 1)
      (str "Only one of " (str/join ", " (map name mutex))
           " may be specified, got: " (str/join ", " (map name present))))))

(defn- validate-constraints
  "Evaluate all constraints for an operation. Returns nil or first error string."
  [params constraints]
  (when (seq constraints)
    (reduce
     (fn [_ constraint]
       (let [err (cond
                   (:enum constraint)    (check-enum params constraint)
                   (:when constraint)    (check-when-require params constraint)
                   (:mutex constraint)   (check-mutex params constraint)
                   :else                 nil)]
         (when err (reduced err))))
     nil
     constraints)))

;; ---------------------------------------------------------------------------
;; Main handler
;; ---------------------------------------------------------------------------

(defn handle-request
  "Generic handler for any schema-defined operation.

   Validates:
   1. Required parameters present
   2. Parameter types match schema (coerces where possible)
   3. No unknown parameters (closed schema)
   4. Business-rule constraints (enums, conditional requires, mutex)

   Arguments:
     op     — operation schema map (from alpaca.schema)
     config — alpaca config map
     req    — ring request

   Returns: ring response with EDN body."
  [op config req]
  (let [params        (read-edn-body req)
        schema-params (:params op)
        ;; 1. Required params
        req-err       (validate-required params schema-params)
        _             (when req-err (throw (ex-info req-err {:status 400})))
        ;; 2. Defaults
        params        (merge-defaults params schema-params)
        ;; 3. Type validation + coercion
        [params type-err] (validate-and-coerce params schema-params)
        _             (when type-err (throw (ex-info type-err {:status 400})))
        ;; 4. Business-rule constraints
        cons-err      (validate-constraints params (:constraints op))
        _             (when cons-err (throw (ex-info cons-err {:status 400})))
        ;; 5. Forward to Alpaca
        result        (client/request! (:alpaca op) params config)
        result        (keys/expand-response (:name op) result)]
    {:status 200
     :body   (pr-str result)}))
