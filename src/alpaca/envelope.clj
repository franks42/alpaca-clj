(ns alpaca.envelope
  "Delegates to stroopwafel.envelope.
   Kept for backward compatibility within alpaca-clj."
  (:require [stroopwafel.envelope :as env]))

(def sign env/sign)
(def verify env/verify)
(def serialize env/serialize)
(def deserialize env/deserialize)
