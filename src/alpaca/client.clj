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

(defn request!
  "Make a request to the Alpaca REST API.

   Arguments:
     alpaca-spec — {:method :get/:post, :base :trading/:data, :path \"/v2/...\"}
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
         qparams (when (= method :get)
                   (query-params params alpaca-spec))
         headers {"APCA-API-KEY-ID"     (:api-key-id config)
                  "APCA-API-SECRET-KEY" (:api-secret-key config)
                  "Accept"              "application/json"}
         opts    (cond-> {:headers headers}
                   (seq qparams) (assoc :query-params qparams))
         resp    @(http/request (merge opts {:method method :url url}))]
     (if (<= 200 (:status resp) 299)
       (when-let [body (:body resp)]
         (json/parse-string body keyword))
       (throw (ex-info (str "Alpaca API error: " (:status resp))
                       {:status (:status resp)
                        :body   (try (json/parse-string (:body resp) keyword)
                                     (catch Exception _ (:body resp)))
                        :url    url}))))))
