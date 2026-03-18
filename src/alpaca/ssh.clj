(ns alpaca.ssh
  "Delegates to stroopwafel.ssh.
   Kept for backward compatibility within alpaca-clj."
  (:require [stroopwafel.ssh :as ssh]))

(def read-ssh-public-key ssh/read-ssh-public-key)
(def read-ssh-private-key ssh/read-ssh-private-key)
(def load-ssh-keypair ssh/load-ssh-keypair)
