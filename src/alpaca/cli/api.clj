(ns alpaca.cli.api
  "Unified CLI for the alpaca-clj proxy.
   All calls go through the proxy — no direct Alpaca access.

   Usage:
     bb api account/info
     bb api market/quote --symbol AAPL
     bb api market/bars --symbol AAPL --limit 5
     bb api market/clock
     bb api trading/positions
     bb api help"
  (:require [alpaca.cli.common :as cli]
            [alpaca.schema :as schema]
            [clojure.string]))

(defn- print-help
  "Print help from local schema (works without proxy running)."
  []
  (println "alpaca-clj proxy CLI")
  (println "")
  (println "Usage: bb api <operation> [--key value ...]")
  (println "       bb api                              (discover from running proxy)")
  (println "")
  (println "Operations:")
  (doseq [op schema/operations]
    (let [params (:params op)
          flags  (->> params
                      (map (fn [[k v]]
                             (str "--" (name k)
                                  (when (:required v) " *"))))
                      (clojure.string/join " "))]
      (println (format "  %-24s %-6s %s"
                       (subs (str (:name op)) 1)
                       (name (:effect op))
                       (:description op "")))
      (when (seq flags)
        (println (format "  %-24s        %s" "" flags)))))
  (println "")
  (println "  * = required")
  (println "")
  (println "Environment:")
  (println "  PROXY_HOST  proxy host (default: 127.0.0.1)")
  (println "  PROXY_PORT  proxy port (default: 8080)")
  (println "  PROXY_TOKEN auth token (optional)"))

(defn -main [& args]
  (let [[op-name & rest-args] args]
    (cond
      (or (= op-name "help") (= op-name "--help"))
      (print-help)

      (nil? op-name)
      (cli/print-edn (cli/call-proxy! :get "/api" nil))

      :else
      (let [op-kw (keyword op-name)
            op    (get schema/by-name op-kw)]
        (if (nil? op)
          (do (println (str "Unknown operation: " op-name))
              (println "Run 'bb api help' for available operations.")
              (System/exit 1))
          (let [flags  (cli/parse-flags rest-args)
                method (:method op)
                route  (:route op)
                body   (when (= method :post)
                         (reduce-kv
                          (fn [m k v]
                            (let [ptype (get-in (:params op) [k :type])]
                              (assoc m k (case ptype
                                           :int (parse-long v)
                                           v))))
                          {}
                          flags))]
            (cli/print-edn (cli/call-proxy! method route body))))))))
