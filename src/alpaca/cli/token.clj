(ns alpaca.cli.token
  "CLI for Stroopwafel token management.

   Usage:
     bb token generate-keys                — generate root keypair
     bb token generate-agent-keys          — generate agent keypair
     bb token generate-outbound-keys       — generate outbound authority keypair
     bb token issue-read-only              — issue bearer read-only token
     bb token issue --effects read,write --domains market,trading
     bb token issue --effects read --domains market --agent-key <hex>
     bb token issue-outbound --destinations host:port --effects read --domains market
     bb token inspect <token-string>       — show token contents"
  (:require [alpaca.auth :as auth]
            [alpaca.client-pep :as cpep]
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
(defn- load-outbound-keypair [] (load-keypair-file ".stroopwafel-outbound.edn"))

(defn- cmd-issue [{:keys [effects domains agent-key]}]
  (let [kp    (load-root-keypair)
        agent-key-bytes (when agent-key (auth/hex->bytes agent-key))
        token (auth/issue-token kp {:effects effects
                                    :domains domains
                                    :agent-key agent-key-bytes})]
    (println token)))

(defn- cmd-issue-group [{:keys [group effects domains]}]
  (let [kp     (load-root-keypair)
        rights (for [e effects d domains] [group e d])
        token  (auth/issue-group-token kp {:rights (vec rights)})]
    (println token)))

(defn- cmd-generate-outbound-keys []
  (let [kp  (auth/generate-keypair)
        hex (auth/export-public-key kp)]
    (println "# Outbound authority keypair for client-side PEP")
    (println "# The agent uses this public key to verify outbound policy tokens")
    (println (str "# Outbound authority public key: " hex))
    (println)
    (println "# Add to agent's environment:")
    (println (str "export STROOPWAFEL_OUTBOUND_KEY=\"" hex "\""))
    (println)
    (spit ".stroopwafel-outbound.edn"
          (cedn/canonical-str {:priv (-> kp :priv .getEncoded)
                               :pub  (-> kp :pub .getEncoded)}))
    (println "Written to .stroopwafel-outbound.edn")))

(defn- cmd-issue-outbound [{:keys [destinations effects domains restrictions]}]
  (let [kp          (load-outbound-keypair)
        permissions (for [d destinations e effects dom domains]
                      {:destination d :effect e :domain dom})
        token       (cpep/issue-outbound-token
                     kp
                     {:destinations (vec destinations)
                      :permissions  (vec permissions)
                      :restrictions (or restrictions #{})})]
    (println token)))

(defn- cmd-inspect [token-str]
  (try
    (let [token (auth/deserialize-token token-str)]
      (println "Blocks:" (count (:blocks token)))
      (println "Sealed:" (= :sealed (get-in token [:proof :type])))
      (doseq [[i block] (map-indexed vector (:blocks token))]
        (let [content (get-in block [:envelope :message])]
          (println (str "\nBlock " i ":"))
          (println "  Type:" (:type block))
          (println "  Signer:" (auth/bytes->hex (get-in block [:envelope :signer-key])))
          (println "  Facts:" (pr-str (:facts content)))
          (when (seq (:rules content))
            (println "  Rules:" (pr-str (:rules content))))
          (when (seq (:checks content))
            (println "  Checks:" (pr-str (:checks content))))))
      ;; Check for agent binding
      (let [agent-fact (some #(when (= :authorized-agent-key (first %)) %)
                             (get-in token [:blocks 0 :envelope :message :facts]))]
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

      "issue-group"
      (let [group   (or (:group flags) "traders")
            effects (if (:effects flags)
                      (set (map keyword (str/split (:effects flags) #",")))
                      #{:read})
            domains (if (:domains flags)
                      (set (str/split (:domains flags) #","))
                      #{"market" "account" "trade"})]
        (cmd-issue-group {:group group :effects effects :domains domains}))

      "generate-outbound-keys"
      (cmd-generate-outbound-keys)

      "issue-outbound"
      (let [destinations (if (:destinations flags)
                           (set (str/split (:destinations flags) #","))
                           (do (println "Error: --destinations required")
                               (System/exit 1)))
            effects      (if (:effects flags)
                           (set (map keyword (str/split (:effects flags) #",")))
                           #{:read})
            domains      (if (:domains flags)
                           (set (str/split (:domains flags) #","))
                           #{"market"})
            restrictions (if (:restrictions flags)
                           (set (map keyword (str/split (:restrictions flags) #",")))
                           #{})]
        (cmd-issue-outbound {:destinations destinations
                             :effects effects
                             :domains domains
                             :restrictions restrictions}))

      "inspect"
      (if-let [token-str (first rest-args)]
        (cmd-inspect token-str)
        (println "Usage: bb token inspect <token-string>"))

      (do (println "Usage: bb token <command>")
          (println "")
          (println "  Inbound (server-side) tokens:")
          (println "  generate-keys          Generate root keypair")
          (println "  generate-agent-keys    Generate agent keypair")
          (println "  issue-read-only        Issue bearer read-only token (all domains)")
          (println "  issue                  Issue token with --effects, --domains, --agent-key")
          (println "  issue-group            Issue SDSI group token --group --effects --domains")
          (println "")
          (println "  Outbound (client-side) tokens:")
          (println "  generate-outbound-keys Generate outbound authority keypair")
          (println "  issue-outbound         Issue outbound policy token:")
          (println "                         --destinations host:port[,host:port,...]")
          (println "                         --effects read[,write,destroy]")
          (println "                         --domains market[,trade,account]")
          (println "                         --restrictions no-pii-in-params[,no-client-names,...]")
          (println "")
          (println "  inspect                Show token contents")))))
