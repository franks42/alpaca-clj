(ns alpaca.cli.token
  "CLI for Stroopwafel token management.

   Usage:
     bb token generate-keys              — generate root keypair, print to stdout
     bb token issue-read-only            — issue read-only token for all domains
     bb token issue --effects read,write --domains market,account,trading
     bb token inspect <token-string>     — show token contents"
  (:require [alpaca.auth :as auth]
            [alpaca.telemetry :as telemetry]
            [alpaca.cli.common :as cli]
            [cedn.core :as cedn] ;; cedn/readers used at runtime
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- cmd-generate-keys []
  (let [kp (auth/generate-keypair)]
    (println "# Root keypair for alpaca-clj proxy")
    (println "# Add to .env.paper or export in shell:")
    (println (str "export STROOPWAFEL_ROOT_KEY=\"" (auth/export-public-key kp) "\""))
    (println)
    (println "# Private key (keep secret — needed for token issuance):")
    (println "# Save to .stroopwafel-root.edn (gitignored)")
    (spit ".stroopwafel-root.edn"
          (cedn/canonical-str {:priv (-> kp :priv .getEncoded)
                               :pub  (-> kp :pub .getEncoded)}))
    (println "Written to .stroopwafel-root.edn")))

(defn- load-root-keypair []
  (let [f (java.io.File. ".stroopwafel-root.edn")]
    (if (.exists f)
      (let [data (edn/read-string {:readers cedn/readers} (slurp f))
            priv-key (java.security.KeyFactory/getInstance "Ed25519")
            priv (.generatePrivate priv-key
                                   (java.security.spec.PKCS8EncodedKeySpec. (:priv data)))
            pub (auth/import-public-key
                 (apply str (map #(format "%02x" (bit-and % 0xff)) (:pub data))))]
        {:priv priv :pub pub})
      (do (println "No .stroopwafel-root.edn found. Run: bb token generate-keys")
          (System/exit 1)))))

(defn- cmd-issue [{:keys [effects domains]}]
  (let [kp    (load-root-keypair)
        token (auth/issue-token kp {:effects effects :domains domains})]
    (println token)))

(defn- cmd-inspect [token-str]
  (try
    (let [token (auth/deserialize-token token-str)]
      (println "Blocks:" (count (:blocks token)))
      (println "Sealed:" (= :sealed (get-in token [:proof :type])))
      (doseq [[i block] (map-indexed vector (:blocks token))]
        (println (str "\nBlock " i ":"))
        (println "  Facts:" (pr-str (:facts block)))
        (when (seq (:rules block))
          (println "  Rules:" (pr-str (:rules block))))
        (when (seq (:checks block))
          (println "  Checks:" (pr-str (:checks block))))))
    (catch Exception e
      (println "Error parsing token:" (.getMessage e)))))

(defn -main [& args]
  (telemetry/ensure-initialized!)
  (let [[cmd & rest-args] args
        flags (cli/parse-flags rest-args)]
    (case cmd
      "generate-keys"
      (cmd-generate-keys)

      "issue-read-only"
      (cmd-issue {:effects #{:read}
                  :domains #{"account" "market" "trading"}})

      "issue"
      (let [effects (if (:effects flags)
                      (set (map keyword (str/split (:effects flags) #",")))
                      #{:read})
            domains (if (:domains flags)
                      (set (str/split (:domains flags) #","))
                      #{"account" "market" "trading"})]
        (cmd-issue {:effects effects :domains domains}))

      "inspect"
      (if-let [token-str (first rest-args)]
        (cmd-inspect token-str)
        (println "Usage: bb token inspect <token-string>"))

      (do (println "Usage: bb token <generate-keys|issue-read-only|issue|inspect>")
          (println "")
          (println "  generate-keys   Generate root keypair")
          (println "  issue-read-only Issue read-only token for all domains")
          (println "  issue           Issue token with --effects and --domains")
          (println "  inspect         Show token contents")))))
