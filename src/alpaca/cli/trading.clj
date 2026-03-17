(ns alpaca.cli.trading
  "CLI: trading commands."
  (:require [alpaca.cli.common :as cli]))

(defn -main [& args]
  (case (first args)
    "positions" (cli/print-edn (cli/call-proxy! :get "/trading/positions" nil))
    (println "Usage: bb trading <positions>")))
