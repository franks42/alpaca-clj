(ns alpaca.cli.token
  "CLI for Stroopwafel token management.

   Usage:
     bb token generate-keys                — generate root keypair
     bb token generate-agent-keys          — generate agent keypair
     bb token issue-read-only              — issue bearer read-only token
     bb token issue --effects read,write --domains market,trading
     bb token issue --effects read --domains market --agent-key <hex>
     bb token inspect <token-string>       — show token contents"
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

(defn- cmd-generate-agent-keys []
  (let [kp (auth/generate-keypair)
        hex (auth/export-public-key kp)]
    (println "# Agent keypair for requester-bound tokens")
    (println "# The public key hex goes in the token (--agent-key flag)")
    (println (str "# Agent public key: " hex))
    (println)
    (spit ".stroopwafel-agent.edn"
          (cedn/canonical-str {:priv (-> kp :priv .getEncoded)
                               :pub  (-> kp :pub .getEncoded)}))
    (println "Written to .stroopwafel-agent.edn")
    (println)
    (println "To issue a requester-bound token:")
    (println (str "  bb token issue --effects read --domains market --agent-key " hex))))

(defn- load-keypair-file [filename]
  (let [f (java.io.File. filename)]
    (if (.exists f)
      (let [data (edn/read-string {:readers cedn/readers} (slurp f))
            priv-key (java.security.KeyFactory/getInstance "Ed25519")
            priv (.generatePrivate priv-key
                                   (java.security.spec.PKCS8EncodedKeySpec. (:priv data)))
            pub (auth/import-public-key (auth/bytes->hex (:pub data)))]
        {:priv priv :pub pub})
      (do (println (str "No " filename " found. Run the appropriate generate command."))
          (System/exit 1)))))

(defn- load-root-keypair [] (load-keypair-file ".stroopwafel-root.edn"))

(defn- cmd-issue [{:keys [effects domains agent-key]}]
  (let [kp    (load-root-keypair)
        agent-key-bytes (when agent-key (auth/hex->bytes agent-key))
        token (auth/issue-token kp {:effects effects
                                    :domains domains
                                    :agent-key agent-key-bytes})]
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
          (println "  Checks:" (pr-str (:checks block)))))
      ;; Check for agent binding
      (let [agent-fact (some #(when (= :authorized-agent-key (first %)) %)
                             (get-in token [:blocks 0 :facts]))]
        (when agent-fact
          (println "\nRequester-bound: yes")
          (println "  Agent key:" (auth/bytes->hex (second agent-fact))))))
    (catch Exception e
      (println "Error parsing token:" (.getMessage e)))))

(defn -main [& args]
  (telemetry/ensure-initialized!)
  (let [[cmd & rest-args] args
        flags (cli/parse-flags rest-args)]
    (case cmd
      "generate-keys"
      (cmd-generate-keys)

      "generate-agent-keys"
      (cmd-generate-agent-keys)

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
        (cmd-issue {:effects effects
                    :domains domains
                    :agent-key (:agent-key flags)}))

      "inspect"
      (if-let [token-str (first rest-args)]
        (cmd-inspect token-str)
        (println "Usage: bb token inspect <token-string>"))

      (do (println "Usage: bb token <command>")
          (println "")
          (println "  generate-keys        Generate root keypair")
          (println "  generate-agent-keys  Generate agent keypair")
          (println "  issue-read-only      Issue bearer read-only token (all domains)")
          (println "  issue                Issue token with --effects, --domains, --agent-key")
          (println "  inspect              Show token contents")))))
