(ns alpaca.cli.common
  "Shared CLI utilities — parse args, call proxy, print EDN.
   Supports optional agent request signing for requester-bound tokens."
  (:require [org.httpkit.client :as http]
            [cedn.core :as cedn] ;; cedn/readers used at runtime
            [clojure.edn :as edn]
            [clojure.pprint]))

(defn parse-flags
  "Parse --key value pairs from command-line args into a map."
  [args]
  (loop [remaining args
         result   {}]
    (if (empty? remaining)
      result
      (let [[flag val & rest] remaining]
        (if (and flag (.startsWith flag "--"))
          (recur rest (assoc result (keyword (subs flag 2)) val))
          (recur (next remaining) result))))))

(defn proxy-url
  []
  (let [host (or (System/getenv "PROXY_HOST") "127.0.0.1")
        port (or (System/getenv "PROXY_PORT") "8080")]
    (str "http://" host ":" port)))

(defn- load-agent-keypair
  "Load agent keypair from .stroopwafel-agent.edn if it exists.
   Returns a signet Ed25519KeyPair or nil."
  []
  (let [f (java.io.File. ".stroopwafel-agent.edn")]
    (when (.exists f)
      (require 'signet.key)
      (let [sk           @(resolve 'signet.key/signing-keypair)
            {:keys [x d]} (edn/read-string {:readers cedn/readers} (slurp f))]
        (sk x d)))))

(defn- sign-and-add-header
  "If STROOPWAFEL_AGENT_SIGN=true and an agent keyfile exists,
   sign the request envelope and add the X-Agent-Signature header."
  [headers method path body]
  (if-not (= "true" (System/getenv "STROOPWAFEL_AGENT_SIGN"))
    headers
    (if-let [agent-kp (load-agent-keypair)]
      (do (require 'alpaca.auth)
          (let [sign-req      @(resolve 'alpaca.auth/sign-request)
                serialize-sig @(resolve 'alpaca.auth/serialize-signed-request)
                audience      (System/getenv "PROXY_IDENTITY")
                signed        (sign-req method path body agent-kp audience)
                sig-str       (serialize-sig signed)]
            (assoc headers "X-Agent-Signature" sig-str)))
      (do (binding [*out* *err*]
            (println "Warning: STROOPWAFEL_AGENT_SIGN=true but no .stroopwafel-agent.edn found"))
          headers))))

(defn call-proxy!
  "Call the proxy server and return the EDN response."
  [method path body]
  (let [url     (str (proxy-url) path)
        token   (or (System/getenv "STROOPWAFEL_TOKEN")
                    (System/getenv "PROXY_TOKEN"))
        headers (cond-> {"Accept" "application/edn"}
                  token (assoc "Authorization" (str "Bearer " token))
                  body  (assoc "Content-Type" "application/edn"))
        headers (sign-and-add-header headers method path body)
        opts    (cond-> {:method method :url url :headers headers :as :text}
                  body (assoc :body (pr-str body)))
        resp    @(http/request opts)]
    (if (:error resp)
      (do (println "Error connecting to proxy:" (:error resp))
          (System/exit 1))
      (let [edn-body (edn/read-string (:body resp))]
        (if (<= 200 (:status resp) 299)
          edn-body
          (do (println "Error:" (:status resp))
              (prn edn-body)
              (System/exit 1)))))))

(defn print-edn
  [v]
  (binding [*print-namespace-maps* false]
    (clojure.pprint/pprint v)))
