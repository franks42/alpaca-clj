(ns alpaca.router-test
  "Tests for fn-as-URL routing — the structural whitelist."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [alpaca.proxy.router :as router]
            [alpaca.proxy.middleware :as mw]
            [alpaca.config :as config]))

(def test-config (config/load-config))

(defn make-handler
  "Create a test handler stack (no auth, no logging)."
  []
  (-> (router/create-router test-config)
      (mw/wrap-edn-content-type)
      (mw/wrap-error-handler)))

(defn request
  "Make a ring request map."
  ([method uri]
   (request method uri nil))
  ([method uri body]
   (cond-> {:request-method method :uri uri :headers {}}
     body (assoc :body (pr-str body)
                 :headers {"content-type" "application/edn"}))))

(defn parse-body [resp]
  (edn/read-string (:body resp)))

;; ---------------------------------------------------------------------------
;; Route enforcement
;; ---------------------------------------------------------------------------

(deftest unknown-route-returns-404
  (let [handler (make-handler)
        resp    (handler (request :get "/bogus"))]
    (is (= 404 (:status resp)))
    (is (= "Not found" (:error (parse-body resp))))))

(deftest wrong-method-returns-405
  (let [handler (make-handler)]
    (testing "POST to a GET-only route"
      (let [resp (handler (request :post "/account/info"))]
        (is (= 405 (:status resp)))
        (is (= "get" (:allowed (parse-body resp))))))
    (testing "GET to a POST-only route"
      (let [resp (handler (request :get "/market/quote"))]
        (is (= 405 (:status resp)))
        (is (= "post" (:allowed (parse-body resp))))))))

(deftest health-endpoint-returns-200
  (let [handler (make-handler)
        resp    (handler (request :get "/health"))]
    (is (= 200 (:status resp)))
    (is (= "ok" (:status (parse-body resp))))))

(deftest api-discovery-returns-schema
  (let [handler (make-handler)
        resp    (handler (request :get "/api"))
        body    (parse-body resp)]
    (is (= 200 (:status resp)))
    (is (= "alpaca-clj" (:name body)))
    (is (vector? (:operations body)))
    (is (pos? (count (:operations body))))))

(deftest all-schema-routes-respond
  (let [handler (make-handler)]
    (testing "GET routes return non-404"
      (doseq [uri ["/account/info" "/market/clock" "/trade/positions"]]
        (let [resp (handler (request :get uri))]
          (is (not= 404 (:status resp)) (str uri " should not be 404")))))
    (testing "POST routes return non-404"
      (doseq [uri ["/market/quote" "/market/bars" "/trade/place-order"
                   "/trade/orders" "/trade/order"
                   "/trade/cancel-order" "/trade/close-position"]]
        (let [resp (handler (request :post uri {}))]
          (is (not= 404 (:status resp)) (str uri " should not be 404")))))))

(deftest missing-required-params-returns-400
  (let [handler (make-handler)
        resp    (handler (request :post "/market/quote" {}))]
    (is (= 400 (:status resp)))
    (is (.contains (:error (parse-body resp)) "Missing required"))))

(deftest edn-content-type-set
  (let [handler (make-handler)
        resp    (handler (request :get "/health"))]
    (is (.contains (get-in resp [:headers "Content-Type"]) "application/edn"))))
