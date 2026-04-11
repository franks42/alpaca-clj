(ns alpaca.ssh-test
  "Tests that signet.ssh Ed25519 key import works end-to-end with
   alpaca.auth tokens — an SSH-imported key can serve as the root
   keypair for issuing capability tokens."
  (:require [clojure.test :refer [deftest is]]
            [signet.ssh :as ssh]
            [signet.key :as key]
            [signet.sign :as sign]
            [alpaca.auth :as auth]))

(def ^:private test-key-dir
  (str (System/getProperty "java.io.tmpdir") "/alpaca-ssh-test"))

(defn- setup-test-keys! []
  (.mkdirs (java.io.File. test-key-dir))
  (let [priv-path (str test-key-dir "/id_ed25519")
        f         (java.io.File. priv-path)]
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
        pub-key  (ssh/read-public-key pub-line)]
    (is (= :signet/ed25519-public-key (:type pub-key)))
    (is (= 32 (count (:x pub-key))))))

(deftest load-keypair-round-trip
  (let [kp (ssh/load-keypair test-priv-path)]
    (is (= :signet/ed25519-keypair (:type kp)))
    (is (= 32 (count (:x kp))))
    (is (= 32 (count (:d kp))))))

(deftest ssh-key-signs-and-verifies
  (let [kp       (ssh/load-keypair test-priv-path)
        env      (sign/sign-edn kp {:action :test} {:ttl 60})
        verified (sign/verify-edn env)]
    (is (:valid? verified))
    (is (= {:action :test} (:message verified)))
    (is (= (key/kid kp) (:signer verified)))))

(deftest ssh-key-as-root-for-alpaca-token
  (let [ssh-kp   (ssh/load-keypair test-priv-path)
        ssh-kid  (key/kid ssh-kp)
        agent-kp (auth/generate-keypair)
        token    (auth/issue-token ssh-kp
                                   {:effects   #{:read}
                                    :domains   #{"market"}
                                    :agent-key (key/kid agent-kp)})
        sig      (auth/sign-request :get "/market/clock" {} agent-kp)
        result   (auth/verify-and-authorize
                  token ssh-kid
                  {:effect :read :domain "market"
                   :method :get :path "/market/clock"}
                  sig {})]
    (is (:authorized result))))

(deftest nonexistent-keypair-returns-nil
  (is (nil? (ssh/load-keypair "/nonexistent/path"))))
