(ns alpaca.test-runner
  "Test runner for bb. Discovers and runs all test namespaces."
  (:require [clojure.test :as t]))

(defn -main [& _args]
  (let [test-nses '[alpaca.router-test
                    alpaca.auth-test
                    alpaca.pep-test
                    alpaca.keys-test
                    alpaca.ssh-test]]
    (doseq [ns test-nses]
      (require ns))
    (let [results (apply t/run-tests test-nses)]
      (when (or (pos? (:fail results 0))
                (pos? (:error results 0)))
        (System/exit 1)))))
