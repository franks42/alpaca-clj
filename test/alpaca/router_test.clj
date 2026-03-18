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

;; ---------------------------------------------------------------------------
;; Schema validation
;; ---------------------------------------------------------------------------

(deftest wrong-type-returns-400
  (let [handler (make-handler)]
    (testing "limit must be integer"
      (let [resp (handler (request :post "/market/bars"
                                   {:symbol "AAPL" :limit "not-a-number"}))]
        (is (= 400 (:status resp)))
        (is (.contains (:error (parse-body resp)) "integer"))))
    (testing "string coerced from number"
      (let [resp (handler (request :post "/market/quote" {:symbol 123}))]
        ;; symbol is :string type — numbers should coerce to string
        (is (not= 400 (:status resp)))))))

(deftest unknown-param-returns-400
  (let [handler (make-handler)
        resp    (handler (request :post "/market/quote"
                                  {:symbol "AAPL" :bogus "value"}))]
    (is (= 400 (:status resp)))
    (is (.contains (:error (parse-body resp)) "Unknown parameter"))))

(deftest integer-coercion-from-string
  (let [handler (make-handler)
        resp    (handler (request :post "/market/bars"
                                  {:symbol "AAPL" :limit "5"}))]
    ;; "5" should coerce to 5 — not rejected
    (is (not= 400 (:status resp)))))

;; ---------------------------------------------------------------------------
;; Business-rule constraints
;; ---------------------------------------------------------------------------

(deftest enum-validation-side
  (let [handler (make-handler)
        base    {:symbol "AAPL" :type "market" :qty "1"}]
    (testing "valid side"
      (let [resp (handler (request :post "/trade/place-order"
                                   (assoc base :side "buy")))]
        (is (not= 400 (:status resp)))))
    (testing "invalid side"
      (let [resp (handler (request :post "/trade/place-order"
                                   (assoc base :side "short")))]
        (is (= 400 (:status resp)))
        (is (.contains (:error (parse-body resp)) "side must be one of"))))))

(deftest enum-validation-order-type
  (let [handler (make-handler)
        resp    (handler (request :post "/trade/place-order"
                                  {:symbol "AAPL" :side "buy" :type "banana" :qty "1"}))]
    (is (= 400 (:status resp)))
    (is (.contains (:error (parse-body resp)) "type must be one of"))))

(deftest conditional-require-limit-price
  (let [handler (make-handler)]
    (testing "limit order without limit_price → 400"
      (let [resp (handler (request :post "/trade/place-order"
                                   {:symbol "AAPL" :side "buy" :type "limit" :qty "1"}))]
        (is (= 400 (:status resp)))
        (is (.contains (:error (parse-body resp)) "limit_price"))))
    (testing "limit order with limit_price → ok"
      (let [resp (handler (request :post "/trade/place-order"
                                   {:symbol "AAPL" :side "buy" :type "limit"
                                    :qty "1" :limit_price "200"}))]
        (is (not= 400 (:status resp)))))))

(deftest conditional-require-stop-price
  (let [handler (make-handler)
        resp    (handler (request :post "/trade/place-order"
                                  {:symbol "AAPL" :side "buy" :type "stop" :qty "1"}))]
    (is (= 400 (:status resp)))
    (is (.contains (:error (parse-body resp)) "stop_price"))))

(deftest conditional-require-stop-limit
  (let [handler (make-handler)]
    (testing "stop_limit needs both prices"
      (let [resp (handler (request :post "/trade/place-order"
                                   {:symbol "AAPL" :side "buy" :type "stop_limit"
                                    :qty "1" :limit_price "200"}))]
        (is (= 400 (:status resp)))
        (is (.contains (:error (parse-body resp)) "stop_price"))))
    (testing "stop_limit with both prices → ok"
      (let [resp (handler (request :post "/trade/place-order"
                                   {:symbol "AAPL" :side "buy" :type "stop_limit"
                                    :qty "1" :limit_price "200" :stop_price "195"}))]
        (is (not= 400 (:status resp)))))))

(deftest market-order-no-extra-prices-needed
  (let [handler (make-handler)
        resp    (handler (request :post "/trade/place-order"
                                  {:symbol "AAPL" :side "buy" :type "market" :qty "1"}))]
    (is (not= 400 (:status resp)))))

(deftest mutex-qty-percentage
  (let [handler (make-handler)]
    (testing "both qty and percentage → 400"
      (let [resp (handler (request :post "/trade/close-position"
                                   {:symbol "AAPL" :qty "10" :percentage "50"}))]
        (is (= 400 (:status resp)))
        (is (.contains (:error (parse-body resp)) "Only one of"))))
    (testing "just qty → ok"
      (let [resp (handler (request :post "/trade/close-position"
                                   {:symbol "AAPL" :qty "10"}))]
        (is (not= 400 (:status resp)))))
    (testing "neither → ok (full close)"
      (let [resp (handler (request :post "/trade/close-position"
                                   {:symbol "AAPL"}))]
        (is (not= 400 (:status resp)))))))
