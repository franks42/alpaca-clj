(ns alpaca.client
  "HTTP client for Alpaca REST API.
   Speaks JSON to Alpaca, returns EDN maps.
   This is the only namespace that touches JSON."
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [alpaca.config :as config]))

(defn- base-url
  "Resolve base URL from config and alpaca spec."
  [config alpaca-spec]
  (case (:base alpaca-spec)
    :trading (:trading-url config)
    :data    (:data-url config)))

(defn- interpolate-path
  "Replace :symbol etc. in path template with values from params.
   /v2/stocks/:symbol/quotes/latest + {:symbol \"AAPL\"}
   → /v2/stocks/AAPL/quotes/latest"
  [path params]
  (reduce-kv
   (fn [p k v]
     (let [placeholder (str ":" (name k))]
       (if (.contains p placeholder)
         (.replace p placeholder (str v))
         p)))
   path
   params))

(defn- path-params
  "Return the set of param keys that appear as :placeholders in the path."
  [path]
  (->> (re-seq #":(\w+)" path)
       (map (comp keyword second))
       set))

(defn- apply-param-map
  "Rename params according to :param-map in alpaca spec.
   E.g. {:symbol :symbols} renames :symbol to :symbols for the Alpaca API."
  [params param-map]
  (if (empty? param-map)
    params
    (reduce-kv
     (fn [m from to]
       (if (contains? m from)
         (let [v (get m from)]
           (-> m (dissoc from) (assoc to v)))
         m))
     params
     param-map)))

(defn- query-params
  "Return params that are NOT path interpolations — these go as query params.
   Applies :param-map renaming from alpaca spec."
  [params alpaca-spec]
  (let [path-keys (path-params (:path alpaca-spec))
        param-map (or (:param-map alpaca-spec) {})
        filtered  (->> params
                       (remove (fn [[k _]] (contains? path-keys k)))
                       (into {}))
        renamed   (apply-param-map filtered param-map)]
    (reduce-kv
     (fn [m k v] (assoc m (name k) (str v)))
     {}
     renamed)))

(defn- body-params
  "Return params that are NOT path interpolations — these go as JSON body.
   For POST/DELETE with body. Removes nil values."
  [params alpaca-spec]
  (let [path-keys (path-params (:path alpaca-spec))]
    (->> params
         (remove (fn [[k _]] (contains? path-keys k)))
         (remove (fn [[_ v]] (nil? v)))
         (into {}))))

(defn request!
  "Make a request to the Alpaca REST API.

   Arguments:
     alpaca-spec — {:method :get/:post/:delete, :base :trading/:data, :path \"/v2/...\"}
     params      — EDN map of request parameters
     config      — (optional) config map, defaults to (config/load-config)

   Returns: EDN map (parsed from Alpaca JSON response)
   Throws: ex-info on HTTP errors."
  ([alpaca-spec params]
   (request! alpaca-spec params (config/load-config)))
  ([alpaca-spec params config]
   (let [base    (base-url config alpaca-spec)
         path    (interpolate-path (:path alpaca-spec) params)
         url     (str base path)
         method  (:method alpaca-spec)
         headers {"APCA-API-KEY-ID"     (:api-key-id config)
                  "APCA-API-SECRET-KEY" (:api-secret-key config)
                  "Accept"              "application/json"}
         opts    (case method
                   :get
                   (let [qp (query-params params alpaca-spec)]
                     (cond-> {:headers headers}
                       (seq qp) (assoc :query-params qp)))

                   :post
                   (let [body (body-params params alpaca-spec)]
                     {:headers (assoc headers "Content-Type" "application/json")
                      :body    (json/generate-string body)})

                   :delete
                   (let [body (body-params params alpaca-spec)]
                     (cond-> {:headers headers}
                       (seq body) (assoc :headers (assoc headers "Content-Type" "application/json")
                                         :body (json/generate-string body)))))
         ;; Timeouts: 10s connect, 30s for reads, 10s for writes/deletes
         timeout-ms (if (= method :get) 30000 10000)
         resp    @(http/request (merge opts {:method method :url url
                                             :connect-timeout 10000
                                             :timeout timeout-ms}))]
     (if (<= 200 (:status resp) 299)
       (let [body-str (if (string? (:body resp))
                        (:body resp)
                        (some-> (:body resp) slurp))]
         (when (and body-str (not (empty? body-str)))
           (json/parse-string body-str keyword)))
       (let [body-str (if (string? (:body resp))
                        (:body resp)
                        (some-> (:body resp) slurp))]
         (throw (ex-info (str "Alpaca API error: " (:status resp))
                         {:status (:status resp)
                          :body   (try (json/parse-string body-str keyword)
                                       (catch Exception _ body-str))
                          :url    url})))))))
