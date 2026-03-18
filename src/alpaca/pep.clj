(ns alpaca.pep
  "Delegates to stroopwafel.pep with taoensso/trove logging.
   Kept for backward compatibility within alpaca-clj."
  (:require [stroopwafel.pep :as pep]
            [taoensso.trove :as log]))

(def default-on-deny pep/default-on-deny)
(def default-on-allow pep/default-on-allow)

(defn create-pep
  "Create a PEP middleware, wiring trove logging by default.
   Accepts same options as stroopwafel.pep/create-pep.
   If no :log-fn is provided, uses taoensso/trove."
  [opts]
  (pep/create-pep
   (if (:log-fn opts)
     opts
     (assoc opts :log-fn
            (fn [level data]
              (log/log! {:level level
                         :id    (if (= level :warn)
                                  ::request-denied
                                  ::request-authorized)
                         :msg   (if (= level :warn) "Request denied" "Request authorized")
                         :data  data}))))))
