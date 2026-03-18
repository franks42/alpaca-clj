(ns alpaca.cli.common
  "Shared CLI utilities — parse args, call proxy, print EDN.
   Supports optional agent request signing for requester-bound tokens."
  (:require [org.httpkit.client :as http]
            [clojure.edn :as edn]
            [clojure.pprint]))

(defn parse-flags
  "Parse --key value pairs from command-line args into a map.
   Returns map with keyword keys."
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
  "Build proxy URL from env or defaults."
  []
  (let [host (or (System/getenv "PROXY_HOST") "127.0.0.1")
        port (or (System/getenv "PROXY_PORT") "8080")]
    (str "http://" host ":" port)))

(defn- load-agent-keypair
  "Load agent keypair from .stroopwafel-agent.edn if it exists."
  []
  (let [f (java.io.File. ".stroopwafel-agent.edn")]
    (when (.exists f)
      ;; Lazy-require to avoid loading stroopwafel for non-signed requests
      (require 'alpaca.auth)
      (require 'cedn.core)
      (let [readers    @(resolve 'cedn.core/readers)
            data       (edn/read-string {:readers readers} (slurp f))
            bytes->hex @(resolve 'alpaca.auth/bytes->hex)
            import-pk  @(resolve 'alpaca.auth/import-public-key)
            kf         (java.security.KeyFactory/getInstance "Ed25519")
            priv       (.generatePrivate kf (java.security.spec.PKCS8EncodedKeySpec. (:priv data)))
            pub        (import-pk (bytes->hex (:pub data)))]
        {:priv priv :pub pub}))))

(defn- sign-and-add-header
  "If STROOPWAFEL_AGENT_SIGN=true and agent keypair exists,
   sign the request body and add X-Agent-Signature header."
  [headers body]
  (if-not (= "true" (System/getenv "STROOPWAFEL_AGENT_SIGN"))
    headers
    (if-let [agent-kp (load-agent-keypair)]
      (do (require 'alpaca.auth)
          (let [sign-req       @(resolve 'alpaca.auth/sign-request)
                serialize-sig  @(resolve 'alpaca.auth/serialize-signed-request)
                signed         (sign-req (or body {}) agent-kp)
                sig-str        (serialize-sig signed)]
            (assoc headers "X-Agent-Signature" sig-str)))
      (do (binding [*out* *err*]
            (println "Warning: STROOPWAFEL_AGENT_SIGN=true but no .stroopwafel-agent.edn found"))
          headers))))

(defn call-proxy!
  "Call the proxy server and return the EDN response.

   Arguments:
     method — :get or :post
     path   — e.g. \"/market/quote\"
     body   — EDN map (for POST) or nil

   When STROOPWAFEL_AGENT_SIGN=true, signs the request body with
   the agent key from .stroopwafel-agent.edn and adds the
   X-Agent-Signature header."
  [method path body]
  (let [url     (str (proxy-url) path)
        token   (or (System/getenv "STROOPWAFEL_TOKEN")
                    (System/getenv "PROXY_TOKEN"))
        headers (cond-> {"Accept" "application/edn"}
                  token (assoc "Authorization" (str "Bearer " token))
                  body  (assoc "Content-Type" "application/edn"))
        headers (sign-and-add-header headers body)
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
  "Pretty-print an EDN value."
  [v]
  (binding [*print-namespace-maps* false]
    (clojure.pprint/pprint v)))
