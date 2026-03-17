(ns alpaca.telemetry
  "Telemetry bootstrap — initializes trove/timbre, routes logs to stderr.
   Call (ensure-initialized!) at every entry point (server, CLI tasks).
   Idempotent — subsequent calls are cheap no-ops.

   Pattern copied from bb-mcp-server/telemetry.clj."
  (:require [clojure.string :as str]
            [taoensso.trove :as log]
            [taoensso.trove.timbre :as backend]
            [taoensso.timbre :as timbre]))

(defonce ^:private initialized? (atom false))

(defn- parse-level [value]
  (cond
    (keyword? value) value
    (string? value)  (-> value str/trim str/lower-case keyword)
    :else            nil))

(defn- effective-level [level]
  (or (parse-level level)
      (parse-level (System/getenv "LOG_LEVEL"))
      :info))

(defn- stderr-appender
  "Timbre appender that forces output to stderr.
   Keeps stdout clean for CLI output and stdio transport."
  []
  {:enabled? true
   :fn (fn [data]
         (let [{:keys [output_]} data]
           (binding [*out* *err*]
             (println (force output_))
             (flush))))})

(defn- configure-timbre! [{:keys [level stderr?]
                           :or   {stderr? true}}]
  (let [resolved-level (effective-level level)]
    (timbre/merge-config!
     (cond-> {:min-level resolved-level}
       stderr? (assoc-in [:appenders :println] (stderr-appender))))
    resolved-level))

(defn- start! [opts]
  (let [resolved-level (configure-timbre! opts)]
    (log/set-log-fn! (backend/get-log-fn))
    (log/log! {:level :info
               :id    ::initialized
               :msg   "Telemetry initialized"
               :data  {:level      resolved-level
                       :bb-version (System/getProperty "babashka.version")}})
    resolved-level))

(defn ensure-initialized!
  "Idempotent initializer. Call at every entry point.
   Options:
     :level   — explicit log level (overrides LOG_LEVEL env var, default :info)
     :stderr? — route logs to stderr (default true)"
  ([] (ensure-initialized! {}))
  ([opts]
   (when (compare-and-set! initialized? false true)
     (start! opts)
     :initialized)))
