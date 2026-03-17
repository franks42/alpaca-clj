(ns alpaca.cli.account
  "CLI: account commands."
  (:require [alpaca.cli.common :as cli]))

(defn -main [& args]
  (case (first args)
    "info" (cli/print-edn (cli/call-proxy! :get "/account/info" nil))
    (println "Usage: bb account <info>")))
