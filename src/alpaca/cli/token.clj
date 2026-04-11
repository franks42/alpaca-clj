(ns alpaca.cli.token
  "CLI for capability token management.

   Usage:
     bb token generate-keys                — generate root keypair
     bb token generate-agent-keys          — generate agent keypair
     bb token generate-outbound-keys       — generate outbound authority keypair
     bb token issue-read-only              — issue bearer read-only token
     bb token issue --effects read,write --domains market,trading
     bb token issue --effects read --domains market --agent-key <kid>
     bb token issue-group --group traders --effects read --domains market
     bb token issue-outbound --destinations host:port --effects read --domains market
     bb token inspect <token-string>       — show token contents"
  (:require [alpaca.auth :as auth]
            [alpaca.client-pep :as cpep]
            [alpaca.telemetry :as telemetry]
            [alpaca.cli.common :as cli]
            [signet.key :as key]
            [signet.chain :as chain]
            [cedn.core :as cedn] ;; cedn/readers used at runtime
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Keyfile I/O — CEDN with #bytes for raw :x and :d
;; ---------------------------------------------------------------------------

(defn- write-keypair-file!
  "Write a signet Ed25519KeyPair to a file as CEDN.
   Keyfile format: {:x #bytes ... :d #bytes ...}"
  [path kp]
  (spit path (cedn/canonical-str {:x (:x kp) :d (:d kp)})))

(defn- load-keypair-file
  "Load a signet Ed25519KeyPair from a CEDN keyfile.
   Exits with an error message if the file doesn't exist."
  [filename]
  (let [f (java.io.File. filename)]
    (if (.exists f)
      (let [{:keys [x d]} (edn/read-string {:readers cedn/readers} (slurp f))]
        (key/signing-keypair x d))
      (do (println (str "No " filename " found. Run the appropriate generate command."))
          (System/exit 1)))))

(defn- load-root-keypair [] (load-keypair-file ".stroopwafel-root.edn"))
(defn- load-outbound-keypair [] (load-keypair-file ".stroopwafel-outbound.edn"))

;; ---------------------------------------------------------------------------
;; Key generation commands
;; ---------------------------------------------------------------------------

(defn- cmd-generate-keys []
  (let [kp (auth/generate-keypair)]
    (println "# Root keypair for alpaca-clj proxy")
    (println "# Add to .env.paper or export in shell:")
    (println (str "export STROOPWAFEL_ROOT_KEY=\"" (key/kid kp) "\""))
    (println)
    (println "# Private key (keep secret — needed for token issuance):")
    (println "# Save to .stroopwafel-root.edn (gitignored)")
    (write-keypair-file! ".stroopwafel-root.edn" kp)
    (println "Written to .stroopwafel-root.edn")))

(defn- cmd-generate-agent-keys []
  (let [kp  (auth/generate-keypair)
        kid (key/kid kp)]
    (println "# Agent keypair for requester-bound tokens")
    (println (str "# Agent kid: " kid))
    (println)
    (write-keypair-file! ".stroopwafel-agent.edn" kp)
    (println "Written to .stroopwafel-agent.edn")
    (println)
    (println "To issue a requester-bound token:")
    (println (str "  bb token issue --effects read --domains market --agent-key " kid))))

(defn- cmd-generate-outbound-keys []
  (let [kp  (auth/generate-keypair)
        kid (key/kid kp)]
    (println "# Outbound authority keypair for client-side PEP")
    (println (str "# Outbound authority kid: " kid))
    (println)
    (println "# Add to agent's environment:")
    (println (str "export STROOPWAFEL_OUTBOUND_KEY=\"" kid "\""))
    (println)
    (write-keypair-file! ".stroopwafel-outbound.edn" kp)
    (println "Written to .stroopwafel-outbound.edn")))

;; ---------------------------------------------------------------------------
;; Token issuance commands
;; ---------------------------------------------------------------------------

(defn- cmd-issue [{:keys [effects domains agent-key]}]
  (let [kp    (load-root-keypair)
        token (auth/issue-token kp {:effects   effects
                                    :domains   domains
                                    :agent-key agent-key})]
    (println token)))

(defn- cmd-issue-group [{:keys [group effects domains]}]
  (let [kp     (load-root-keypair)
        rights (vec (for [e effects d domains] [group e d]))
        token  (auth/issue-group-token kp {:rights rights})]
    (println token)))

(defn- cmd-issue-outbound [{:keys [destinations effects domains restrictions]}]
  (let [kp          (load-outbound-keypair)
        permissions (vec (for [d destinations e effects dom domains]
                           {:destination d :effect e :domain dom}))
        token       (cpep/issue-outbound-token
                     kp
                     {:destinations (vec destinations)
                      :permissions  permissions
                      :restrictions (or restrictions #{})})]
    (println token)))

;; ---------------------------------------------------------------------------
;; Token inspection
;; ---------------------------------------------------------------------------

(defn- cmd-inspect [token-str]
  (try
    (let [token  (auth/deserialize-token token-str)
          result (chain/verify token)]
      (println "Type:    " (:type token))
      (println "Root:    " (:root token))
      (println "Sealed:  " (:sealed? result))
      (println "Valid:   " (:valid? result))
      (when-let [err (:error result)]
        (println "Error:   " err))
      (println "Blocks:  " (count (:blocks result)))
      (doseq [[i block-msg] (map-indexed vector (:blocks result))]
        (let [data (:data block-msg)]
          (println (str "\nBlock " i ":"))
          (println "  Facts: " (pr-str (:facts data)))
          (when (seq (:rules data))
            (println "  Rules: " (pr-str (:rules data))))
          (when (seq (:checks data))
            (println "  Checks:" (pr-str (:checks data)))))))
    (catch Exception e
      (println "Error parsing token:" (.getMessage e)))))

;; ---------------------------------------------------------------------------
;; CLI dispatch
;; ---------------------------------------------------------------------------

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
        (cmd-issue {:effects   effects
                    :domains   domains
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
                             :effects      effects
                             :domains      domains
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
