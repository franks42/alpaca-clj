(ns alpaca.pep
  "Policy Enforcement Point — configurable pipeline.

   The PEP is a composable pipeline of plain functions:

     wire-request
       → canonicalize    (extract security-relevant facts from wire format)
       → extract-creds   (extract token + signature from wire format)
       → verify-sig      (verify request signature, return agent key or nil)
       → authorize       (evaluate token + canonical envelope against policy)
       → on-allow / on-deny

   The implementor provides `canonicalize` — this is the most security-critical
   function in the PEP. It defines the binding between the wire world and the
   policy world. If it's wrong, everything downstream is wrong.

   All steps are plain functions — portable across JVM, bb, CLJS."
  (:require [taoensso.trove :as log]))

;; ---------------------------------------------------------------------------
;; The canonical request envelope
;; ---------------------------------------------------------------------------
;;
;; The canonical envelope is the single data structure that connects:
;; 1. What arrived over the wire (the actual request)
;; 2. What the agent signed (the signed envelope in the header)
;; 3. What the Datalog evaluator checks (the authorization facts)
;;
;; It is produced by the `canonicalize` function and consumed by every
;; downstream step. If canonicalize produces the wrong envelope, the
;; signed envelope comparison will fail (good — integrity check) or
;; succeed when it shouldn't (bad — security hole).
;;
;; Shape:
;;   {:method  "get"              ;; HTTP method (string)
;;    :path    "/market/quote"    ;; route path (string)
;;    :body    {:symbol "AAPL"}   ;; parsed request body (EDN map, {} for GET)
;;    :effect  :read              ;; effect class from schema
;;    :domain  "market"           ;; authorization domain from schema
;;    :op      <schema-op-map>}   ;; full schema operation (for reference)

;; ---------------------------------------------------------------------------
;; Default implementations
;; ---------------------------------------------------------------------------

(defn default-on-deny
  "Default deny handler — returns EDN error response."
  [_canonical result]
  {:status (case (:reason-code result)
             :no-token       401
             :invalid-token  403
             :replay         403
             :stale          403
             :bad-signature  403
             :wrong-key      403
             :insufficient   403
             403)
   :body   (pr-str {:error  "Forbidden"
                    :reason (:reason result)})})

(defn default-on-allow
  "Default allow handler — passes request to the next ring handler.
   Re-attaches body string if it was consumed during canonicalization."
  [handler req canonical _result]
  (let [body-str (:_body-str canonical)
        req      (if (and body-str (not (string? (:body req))))
                   (assoc req :body body-str)
                   req)]
    (handler req)))

;; ---------------------------------------------------------------------------
;; PEP pipeline
;; ---------------------------------------------------------------------------

(defn create-pep
  "Create a PEP middleware from a pipeline configuration.

   Required:
     :canonicalize  (fn [wire-request op-lookup-fn] → canonical-envelope or nil)
                    The most security-critical function. Extracts security-relevant
                    facts from the wire request. Must return nil for requests that
                    should bypass auth (health, discovery).

   Optional (have sensible defaults):
     :extract-creds (fn [wire-request] → {:token-str :sig-metadata})
     :authorize     (fn [token-str public-key canonical sig-metadata body]
                      → {:authorized true/false :reason ...})
     :on-allow      (fn [handler wire-request canonical result] → ring-response)
     :on-deny       (fn [canonical result] → ring-response)
     :exempt?       (fn [wire-request] → boolean)  — skip auth entirely
     :public-key    — root public key for token verification

   Returns: Ring middleware function (fn [handler] → handler)."
  [{:keys [canonicalize extract-creds authorize
           on-allow on-deny exempt? public-key]}]
  (fn [handler]
    (fn [req]
      ;; 1. Check exemptions (health, discovery, etc.)
      (if (and exempt? (exempt? req))
        (handler req)

        ;; 2. Canonicalize: wire request → canonical envelope
        (let [canonical (canonicalize req)]
          (if (nil? canonical)
            ;; canonicalize returned nil — pass through (e.g., unknown route → 404)
            (handler req)

            ;; 3. Extract credentials from wire request
            (let [{:keys [token-str sig-metadata body-str]}
                  (extract-creds req)]

              ;; 4. Check token is present
              (if (nil? token-str)
                (default-on-deny canonical
                                 {:reason-code :no-token
                                  :reason "Missing Bearer token"})

                ;; 5. Authorize: verify token + signature + evaluate policy
                (let [result (authorize token-str public-key canonical
                                        sig-metadata (:body canonical))
                      ;; Re-attach body for downstream handlers if we consumed it
                      req   (if (and body-str (not (string? (:body req))))
                              (assoc req :body body-str)
                              req)]

                  (if (:authorized result)
                    ;; 6a. Allow
                    (do (log/log! {:level :debug :id ::request-authorized
                                   :msg   "Request authorized"
                                   :data  {:path   (:path canonical)
                                           :effect (:effect canonical)
                                           :domain (:domain canonical)
                                           :bound? (:requester-bound result)}})
                        ((or on-allow default-on-allow) handler req canonical result))

                    ;; 6b. Deny
                    (do (log/log! {:level :warn :id ::request-denied
                                   :msg   "Request denied"
                                   :data  {:path   (:path canonical)
                                           :effect (:effect canonical)
                                           :domain (:domain canonical)
                                           :reason (:reason result)}})
                        ((or on-deny default-on-deny) canonical result))))))))))))
