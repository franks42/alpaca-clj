(ns alpaca.client-pep-test
  "Tests for client-side PEP — outbound policy enforcement."
  (:require [clojure.test :refer [deftest is testing]]
            [alpaca.client-pep :as cpep]
            [alpaca.auth :as auth]))

;; ---------------------------------------------------------------------------
;; Test fixtures
;; ---------------------------------------------------------------------------

(def outbound-authority-kp (auth/generate-keypair))
(def outbound-authority-pub (:pub outbound-authority-kp))

(def other-authority-kp (auth/generate-keypair))

(defn issue-outbound [grants]
  (cpep/issue-outbound-token outbound-authority-kp grants))

(def standard-grants
  {:destinations ["proxy-paper:8080" "vendor-b:443"]
   :permissions  [{:destination "proxy-paper:8080" :effect :read  :domain "market"}
                  {:destination "proxy-paper:8080" :effect :write :domain "trade"}
                  {:destination "vendor-b:443"     :effect :read  :domain "market"}]
   :restrictions #{:no-pii-in-params :no-client-names}})

;; ---------------------------------------------------------------------------
;; Token issuance
;; ---------------------------------------------------------------------------

(deftest issue-outbound-token-round-trip
  (let [token-str (issue-outbound standard-grants)
        token     (auth/deserialize-token token-str)]
    (is (map? token))
    (is (vector? (:blocks token)))
    (is (= :sealed (get-in token [:proof :type])))))

;; ---------------------------------------------------------------------------
;; Destination + effect + domain checks
;; ---------------------------------------------------------------------------

(deftest approved-destination-and-effect-passes
  (let [token (issue-outbound standard-grants)
        result (cpep/check-outbound
                token outbound-authority-pub
                {:destination "proxy-paper:8080"
                 :effect :read :domain "market"
                 :body {:symbol "AAPL"}})]
    (is (:allowed result))))

(deftest unapproved-destination-denied
  (let [token (issue-outbound standard-grants)
        result (cpep/check-outbound
                token outbound-authority-pub
                {:destination "proxy-live:8080"
                 :effect :read :domain "market"
                 :body {:symbol "AAPL"}})]
    (is (not (:allowed result)))
    (is (.contains (:reason result) "proxy-live:8080"))))

(deftest wrong-effect-denied
  (let [token (issue-outbound standard-grants)
        result (cpep/check-outbound
                token outbound-authority-pub
                {:destination "vendor-b:443"
                 :effect :write :domain "trade"
                 :body {:symbol "AAPL"}})]
    (is (not (:allowed result)))
    (is (.contains (:reason result) "write"))))

(deftest wrong-domain-denied
  (let [token (issue-outbound standard-grants)
        result (cpep/check-outbound
                token outbound-authority-pub
                {:destination "proxy-paper:8080"
                 :effect :read :domain "account"
                 :body {}})]
    (is (not (:allowed result)))
    (is (.contains (:reason result) "account"))))

(deftest bad-authority-key-denied
  (let [token (issue-outbound standard-grants)
        result (cpep/check-outbound
                token (:pub other-authority-kp)
                {:destination "proxy-paper:8080"
                 :effect :read :domain "market"
                 :body {:symbol "AAPL"}})]
    (is (not (:allowed result)))
    (is (.contains (:reason result) "not signed by trusted"))))

;; ---------------------------------------------------------------------------
;; Data restriction checks
;; ---------------------------------------------------------------------------

(deftest pii-email-detected
  (let [token (issue-outbound standard-grants)
        result (cpep/check-outbound
                token outbound-authority-pub
                {:destination "proxy-paper:8080"
                 :effect :read :domain "market"
                 :body {:symbol "AAPL" :comment "contact user@example.com"}})]
    (is (not (:allowed result)))
    (is (.contains (:reason result) "PII"))))

(deftest pii-phone-detected
  (let [token (issue-outbound standard-grants)
        result (cpep/check-outbound
                token outbound-authority-pub
                {:destination "proxy-paper:8080"
                 :effect :read :domain "market"
                 :body {:symbol "AAPL" :note "call 555-123-4567"}})]
    (is (not (:allowed result)))
    (is (.contains (:reason result) "PII"))))

(deftest clean-body-passes-pii-check
  (let [token (issue-outbound standard-grants)
        result (cpep/check-outbound
                token outbound-authority-pub
                {:destination "proxy-paper:8080"
                 :effect :read :domain "market"
                 :body {:symbol "AAPL"}})]
    (is (:allowed result))))

(deftest client-name-caught
  (let [token (issue-outbound standard-grants)
        result (cpep/check-outbound
                token outbound-authority-pub
                {:destination "proxy-paper:8080"
                 :effect :read :domain "market"
                 :body {:symbol "AAPL" :tag "client: Acme Corp"}})]
    (is (not (:allowed result)))
    (is (.contains (:reason result) "Client"))))

(deftest strategy-in-comments-caught
  (let [token (issue-outbound
               (assoc standard-grants
                      :restrictions #{:no-strategy-in-comments}))
        result (cpep/check-outbound
                token outbound-authority-pub
                {:destination "proxy-paper:8080"
                 :effect :read :domain "market"
                 :body {:symbol "AAPL" :comment "part of Project Phoenix"}})]
    (is (not (:allowed result)))
    (is (.contains (:reason result) "comment"))))

(deftest no-restrictions-allows-anything
  (let [token (issue-outbound (dissoc standard-grants :restrictions))
        result (cpep/check-outbound
                token outbound-authority-pub
                {:destination "proxy-paper:8080"
                 :effect :read :domain "market"
                 :body {:symbol "AAPL" :comment "user@example.com client: Acme"}})]
    (is (:allowed result))))

(deftest nested-pii-detected
  (testing "PII in nested data structures is still caught"
    (let [token (issue-outbound standard-grants)
          result (cpep/check-outbound
                  token outbound-authority-pub
                  {:destination "proxy-paper:8080"
                   :effect :read :domain "market"
                   :body {:symbol "AAPL"
                          :metadata {:contact "user@example.com"}}})]
      (is (not (:allowed result))))))
