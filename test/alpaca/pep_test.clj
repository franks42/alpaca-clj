(ns alpaca.pep-test
  "Tests for the PEP pipeline abstraction."
  (:require [clojure.test :refer [deftest is]]
            [alpaca.pep :as pep]
            [alpaca.pep.http-edn :as http-edn]))

;; ---------------------------------------------------------------------------
;; Canonicalization tests
;; ---------------------------------------------------------------------------

(defn ring-req
  "Build a minimal ring request."
  ([method uri] (ring-req method uri nil))
  ([method uri body]
   (cond-> {:request-method method :uri uri :headers {}}
     body (assoc :body (pr-str body)))))

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
;; Exemption tests
;; ---------------------------------------------------------------------------

(deftest exempt-health-and-api
  (is (true? (http-edn/exempt? (ring-req :get "/health"))))
  (is (true? (http-edn/exempt? (ring-req :get "/api"))))
  (is (not (http-edn/exempt? (ring-req :get "/market/clock")))))

;; ---------------------------------------------------------------------------
;; Credential extraction tests
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
               {:headers {"authorization" "Bearer tok"
                          "x-agent-signature" "{:sig :test}"}})]
    (is (some? (:sig-metadata creds)))))

;; ---------------------------------------------------------------------------
;; Pipeline composition test
;; ---------------------------------------------------------------------------

(deftest pipeline-exempt-passes-through
  (let [handler  (fn [_] {:status 200 :body "ok"})
        pep-fn   (pep/create-pep
                  {:canonicalize  (fn [_] {:method "get" :path "/test"})
                   :extract-creds (fn [_] {:token-str nil})
                   :authorize     (fn [& _] {:authorized false})
                   :exempt?       (fn [_] true)
                   :public-key    nil})
        wrapped  (pep-fn handler)
        resp     (wrapped (ring-req :get "/anything"))]
    (is (= 200 (:status resp)))
    (is (= "ok" (:body resp)))))

(deftest pipeline-no-token-returns-401
  (let [handler (fn [_] {:status 200 :body "ok"})
        pep-fn  (pep/create-pep
                 {:canonicalize  (fn [_] {:method "get" :path "/test"})
                  :extract-creds (fn [_] {:token-str nil})
                  :authorize     (fn [& _] {:authorized false})
                  :exempt?       (fn [_] false)
                  :public-key    nil})
        wrapped (pep-fn handler)
        resp    (wrapped (ring-req :get "/test"))]
    (is (= 401 (:status resp)))))

(deftest pipeline-deny-returns-403
  (let [handler (fn [_] {:status 200 :body "ok"})
        pep-fn  (pep/create-pep
                 {:canonicalize  (fn [_] {:method "get" :path "/test"
                                          :effect :read :domain "x"})
                  :extract-creds (fn [_] {:token-str "sometoken"})
                  :authorize     (fn [& _] {:authorized false
                                            :reason "nope"
                                            :reason-code :insufficient})
                  :exempt?       (fn [_] false)
                  :public-key    nil})
        wrapped (pep-fn handler)
        resp    (wrapped (ring-req :get "/test"))]
    (is (= 403 (:status resp)))))

(deftest pipeline-allow-passes-to-handler
  (let [handler (fn [_] {:status 200 :body "ok"})
        pep-fn  (pep/create-pep
                 {:canonicalize  (fn [_] {:method "get" :path "/test"
                                          :effect :read :domain "x"})
                  :extract-creds (fn [_] {:token-str "sometoken"})
                  :authorize     (fn [& _] {:authorized true})
                  :exempt?       (fn [_] false)
                  :public-key    nil})
        wrapped (pep-fn handler)
        resp    (wrapped (ring-req :get "/test"))]
    (is (= 200 (:status resp)))
    (is (= "ok" (:body resp)))))

(deftest pipeline-canonicalize-nil-passes-through
  (let [handler (fn [_] {:status 404 :body "not found"})
        pep-fn  (pep/create-pep
                 {:canonicalize  (fn [_] nil)
                  :extract-creds (fn [_] {:token-str nil})
                  :authorize     (fn [& _] {:authorized false})
                  :exempt?       (fn [_] false)
                  :public-key    nil})
        wrapped (pep-fn handler)
        resp    (wrapped (ring-req :get "/unknown"))]
    (is (= 404 (:status resp)))))
