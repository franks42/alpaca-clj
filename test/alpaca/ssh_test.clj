(ns alpaca.ssh-test
  "Tests for SSH Ed25519 key import."
  (:require [clojure.test :refer [deftest is]]
            [alpaca.ssh :as ssh]
            [alpaca.envelope :as envelope]
            [stroopwafel.core :as sw]
            [stroopwafel.crypto :as crypto]))

;; Generate a temp SSH keypair for testing
(def ^:private test-key-dir (str (System/getProperty "java.io.tmpdir") "/alpaca-ssh-test"))

(defn- setup-test-keys! []
  (.mkdirs (java.io.File. test-key-dir))
  (let [priv-path (str test-key-dir "/id_ed25519")
        f (java.io.File. priv-path)]
    (when-not (.exists f)
      (let [proc (-> (ProcessBuilder.
                      ["ssh-keygen" "-t" "ed25519" "-f" priv-path "-N" "" "-q"])
                     (.redirectErrorStream true)
                     (.start))]
        (.waitFor proc))))
  (str test-key-dir "/id_ed25519"))

(def ^:private test-priv-path (setup-test-keys!))

(deftest read-ssh-public-key
  (let [pub-line (slurp (str test-priv-path ".pub"))
        pub-key  (ssh/read-ssh-public-key pub-line)]
    (is (some? pub-key))
    (is (= "EdDSA" (.getAlgorithm pub-key)))))

(deftest read-ssh-private-key
  (let [pem      (slurp test-priv-path)
        priv-key (ssh/read-ssh-private-key pem)]
    (is (some? priv-key))
    (is (= "EdDSA" (.getAlgorithm priv-key)))))

(deftest load-ssh-keypair-round-trip
  (let [kp (ssh/load-ssh-keypair test-priv-path)]
    (is (some? kp))
    (is (some? (:priv kp)))
    (is (some? (:pub kp)))))

(deftest ssh-keys-sign-and-verify
  (let [kp       (ssh/load-ssh-keypair test-priv-path)
        outer    (envelope/sign {:action :test} (:priv kp) (:pub kp) 60)
        verified (envelope/verify outer)]
    (is (:valid? verified) "Signature should verify")
    (is (= {:action :test} (:message verified)))))

(deftest ssh-keys-work-with-stroopwafel-tokens
  (let [ssh-kp    (ssh/load-ssh-keypair test-priv-path)
        root-kp   (sw/new-keypair)
        agent-pk  (crypto/encode-public-key (:pub ssh-kp))
        token     (sw/issue
                   {:facts [[:authorized-agent-key agent-pk]
                            [:effect :read]
                            [:domain "market"]]}
                   {:private-key (:priv root-kp)})
        outer     (envelope/sign {:action :test} (:priv ssh-kp) (:pub ssh-kp) 60)
        verified  (envelope/verify outer)
        result    (sw/evaluate token
                               :authorizer
                               {:facts [[:request-verified-agent-key (:signer-key verified)]]
                                :rules [{:id   :ab
                                         :head [:ok '?k]
                                         :body [[:authorized-agent-key '?k]
                                                [:request-verified-agent-key '?k]]}]
                                :checks [{:id :e :query [[:effect :read]]}
                                         {:id :d :query [[:domain "market"]]}]
                                :policies [{:kind :allow :query [[:ok '?k]]}]})]
    (is (:valid? result) "SSH key should work with stroopwafel token")))

(deftest nonexistent-keypair-returns-nil
  (is (nil? (ssh/load-ssh-keypair "/nonexistent/path"))))
