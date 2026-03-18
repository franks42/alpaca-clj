(ns alpaca.keys-test
  "Tests for Alpaca market data key expansion."
  (:require [clojure.test :refer [deftest is]]
            [alpaca.keys :as keys]))

(deftest expand-quote-keys
  (let [input    {:quote {:bp 184.92 :bs 100 :bx "V"
                          :ap 184.95 :as 100 :ax "V"
                          :t "2026-03-17T14:00:00Z"
                          :c ["R"] :z "C"}
                  :symbol "AAPL"}
        result   (keys/expand-response :market/quote input)]
    (is (= 184.92 (get-in result [:quote :bid-price])))
    (is (= 184.95 (get-in result [:quote :ask-price])))
    (is (= 100 (get-in result [:quote :bid-size])))
    (is (= "V" (get-in result [:quote :bid-exchange])))
    (is (= "2026-03-17T14:00:00Z" (get-in result [:quote :timestamp])))
    (is (= "AAPL" (:symbol result)))))

(deftest expand-bar-keys
  (let [input  {:bars {:AAPL [{:o 252.0 :h 255.0 :l 251.0 :c 254.0
                               :v 30000000 :n 480000
                               :t "2026-03-17T04:00:00Z"
                               :vw 254.16}]}
                :next_page_token nil}
        result (keys/expand-response :market/bars input)
        bar    (first (get-in result [:bars :AAPL]))]
    (is (= 252.0 (:open bar)))
    (is (= 255.0 (:high bar)))
    (is (= 251.0 (:low bar)))
    (is (= 254.0 (:close bar)))
    (is (= 30000000 (:volume bar)))
    (is (= 480000 (:trade-count bar)))
    (is (= 254.16 (:vwap bar)))
    (is (= "2026-03-17T04:00:00Z" (:timestamp bar)))))

(deftest unknown-operation-passes-through
  (let [input  {:foo "bar" :baz 42}
        result (keys/expand-response :unknown/op input)]
    (is (= input result))))

(deftest nil-input-handled
  (is (nil? (keys/expand-keys nil keys/quote-keys))))

(deftest extra-keys-preserved
  (let [input  {:bp 100 :custom-field "kept"}
        result (keys/expand-keys input keys/quote-keys)]
    (is (= 100 (:bid-price result)))
    (is (= "kept" (:custom-field result)))))
