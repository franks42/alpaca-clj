(ns alpaca.ssh-test
  "Tests for SSH Ed25519 key import."
  (:require [clojure.test :refer [deftest is]]
            [alpaca.ssh :as ssh]
            [stroopwafel.core :as sw]
            [stroopwafel.request :as req]
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
  (let [kp     (ssh/load-ssh-keypair test-priv-path)
        signed (req/sign-request {:action :test} (:priv kp) (:pub kp))
        verified (req/verify-request signed)]
    (is (some? verified) "Signature should verify")))

(deftest ssh-keys-work-with-stroopwafel-tokens
  (let [ssh-kp    (ssh/load-ssh-keypair test-priv-path)
        root-kp   (sw/new-keypair)
        agent-pk  (crypto/encode-public-key (:pub ssh-kp))
        token     (sw/issue
                   {:facts [[:authorized-agent-key agent-pk]
                            [:effect :read]
                            [:domain "market"]]}
                   {:private-key (:priv root-kp)})
        signed    (req/sign-request {:action :test} (:priv ssh-kp) (:pub ssh-kp))
        verified  (req/verify-request signed)
        result    (sw/evaluate token
                               :authorizer
                               {:facts [[:request-verified-agent-key verified]]
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
