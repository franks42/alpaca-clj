(ns alpaca.cli.market
  "CLI: market data commands."
  (:require [alpaca.cli.common :as cli]))

(defn -main [& args]
  (let [[cmd & rest-args] args
        flags (cli/parse-flags rest-args)]
    (case cmd
      "clock"
      (cli/print-edn (cli/call-proxy! :get "/market/clock" nil))

      "quote"
      (if (:symbol flags)
        (cli/print-edn (cli/call-proxy! :post "/market/quote" {:symbol (:symbol flags)}))
        (println "Usage: bb market quote --symbol AAPL"))

      "bars"
      (if (:symbol flags)
        (cli/print-edn
         (cli/call-proxy! :post "/market/bars"
                          (cond-> {:symbol (:symbol flags)}
                            (:timeframe flags) (assoc :timeframe (:timeframe flags))
                            (:limit flags)     (assoc :limit (parse-long (:limit flags)))
                            (:start flags)     (assoc :start (:start flags))
                            (:end flags)       (assoc :end (:end flags)))))
        (println "Usage: bb market bars --symbol AAPL [--timeframe 1Day] [--limit 10]"))

      (println "Usage: bb market <clock|quote|bars>"))))
