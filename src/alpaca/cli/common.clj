(ns alpaca.cli.common
  "Shared CLI utilities — parse args, call proxy, print EDN."
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

(defn call-proxy!
  "Call the proxy server and return the EDN response.

   Arguments:
     method — :get or :post
     path   — e.g. \"/market/quote\"
     body   — EDN map (for POST) or nil"
  [method path body]
  (let [url     (str (proxy-url) path)
        token   (or (System/getenv "STROOPWAFEL_TOKEN")
                    (System/getenv "PROXY_TOKEN"))
        headers (cond-> {"Accept" "application/edn"}
                  token (assoc "Authorization" (str "Bearer " token))
                  body  (assoc "Content-Type" "application/edn"))
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
