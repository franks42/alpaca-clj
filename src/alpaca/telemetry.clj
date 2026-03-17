(ns alpaca.telemetry
  "Telemetry bootstrap — initializes trove/timbre, routes logs to stderr.
   Call (ensure-initialized!) at every entry point (server, CLI tasks).
   Idempotent — subsequent calls are cheap no-ops."
  (:require [taoensso.timbre :as timbre]
            [taoensso.trove :as log]))

(defonce ^:private initialized? (atom false))

(defn- parse-level [x]
  (when x
    (let [s (-> (str x) .toLowerCase .trim)]
      (case s
        "trace" :trace
        "debug" :debug
        "info"  :info
        "warn"  :warn
        "error" :error
        "fatal" :fatal
        nil))))

(defn- effective-level [level]
  (or (parse-level level)
      (parse-level (System/getenv "LOG_LEVEL"))
      :info))

(defn- stderr-appender
  "Timbre appender that routes to stderr.
   Keeps stdout clean for any future stdio transport."
  []
  {:enabled? true
   :fn (fn [data]
         (let [{:keys [output_]} data]
           (binding [*out* *err*]
             (println (force output_))
             (flush))))})

(defn- configure-timbre! [{:keys [level]}]
  (let [resolved-level (effective-level level)]
    (timbre/merge-config!
     {:min-level resolved-level
      :appenders {:println (stderr-appender)}})
    resolved-level))

(defn ensure-initialized!
  "Idempotent initializer. Call at every entry point.
   Options:
     :level — explicit log level (overrides LOG_LEVEL env var, default :info)"
  ([] (ensure-initialized! {}))
  ([opts]
   (when (compare-and-set! initialized? false true)
     (let [level (configure-timbre! opts)]
       (log/log! {:level :info :id ::initialized
                  :msg   "Telemetry initialized"
                  :data  {:level level
                          :bb-version (System/getProperty "babashka.version")}})
       :initialized))))
