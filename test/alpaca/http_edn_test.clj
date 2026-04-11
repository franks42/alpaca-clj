(ns alpaca.http-edn-test
  "Tests for HTTP+EDN canonicalization and credential extraction —
   the security-critical wire → policy binding."
  (:require [clojure.test :refer [deftest is]]
            [alpaca.pep.http-edn :as http-edn]))

(defn ring-req
  ([method uri] (ring-req method uri nil))
  ([method uri body]
   (cond-> {:request-method method :uri uri :headers {}}
     body (assoc :body (pr-str body)))))

;; ---------------------------------------------------------------------------
;; Canonicalization
;; ---------------------------------------------------------------------------

(deftest canonicalize-known-route
  (let [canonical (http-edn/canonicalize (ring-req :get "/market/clock"))]
    (is (some? canonical))
    (is (= "get" (:method canonical)))
    (is (= "/market/clock" (:path canonical)))
    (is (= :read (:effect canonical)))
    (is (= "market" (:domain canonical)))
    (is (= {} (:body canonical)))))

(deftest canonicalize-post-with-body
  (let [canonical (http-edn/canonicalize
                   (ring-req :post "/market/quote" {:symbol "AAPL"}))]
    (is (some? canonical))
    (is (= "post" (:method canonical)))
    (is (= {:symbol "AAPL"} (:body canonical)))
    (is (= :read (:effect canonical)))))

(deftest canonicalize-write-effect
  (let [canonical (http-edn/canonicalize
                   (ring-req :post "/trade/place-order" {:symbol "AAPL" :side "buy"}))]
    (is (= :write (:effect canonical)))
    (is (= "trade" (:domain canonical)))))

(deftest canonicalize-destroy-effect
  (let [canonical (http-edn/canonicalize
                   (ring-req :post "/trade/cancel-order" {:order_id "abc"}))]
    (is (= :destroy (:effect canonical)))
    (is (= "trade" (:domain canonical)))))

(deftest canonicalize-unknown-route-returns-nil
  (is (nil? (http-edn/canonicalize (ring-req :get "/bogus")))))

;; ---------------------------------------------------------------------------
;; Exemption
;; ---------------------------------------------------------------------------

(deftest exempt-health-and-api
  (is (true? (http-edn/exempt? (ring-req :get "/health"))))
  (is (true? (http-edn/exempt? (ring-req :get "/api"))))
  (is (not (http-edn/exempt? (ring-req :get "/market/clock")))))

;; ---------------------------------------------------------------------------
;; Credential extraction
;; ---------------------------------------------------------------------------

(deftest extract-bearer-token
  (let [creds (http-edn/extract-creds
               {:headers {"authorization" "Bearer mytoken123"}})]
    (is (= "mytoken123" (:token-str creds)))))

(deftest extract-no-token
  (let [creds (http-edn/extract-creds {:headers {}})]
    (is (nil? (:token-str creds)))))

(deftest extract-sig-header
  (let [creds (http-edn/extract-creds
               {:headers {"authorization"     "Bearer tok"
                          "x-agent-signature" "{:sig :test}"}})]
    (is (some? (:sig-metadata creds)))))
