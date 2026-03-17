(ns alpaca.keys
  "Expand terse Alpaca market data keys to readable names.
   Applied at the proxy boundary — Alpaca wire format stays compact,
   EDN responses to clients use full names.")

;; ---------------------------------------------------------------------------
;; Key mappings — Alpaca abbreviated → readable
;; ---------------------------------------------------------------------------

(def quote-keys
  "Quote fields (latest quote, historical quotes)."
  {:bp :bid-price
   :bs :bid-size
   :bx :bid-exchange
   :ap :ask-price
   :as :ask-size
   :ax :ask-exchange
   :t  :timestamp
   :c  :conditions
   :z  :tape})

(def bar-keys
  "Bar/candle fields (historical bars, latest bar)."
  {:o  :open
   :h  :high
   :l  :low
   :c  :close
   :v  :volume
   :n  :trade-count
   :t  :timestamp
   :vw :vwap})

(def trade-keys
  "Trade fields (latest trade, historical trades)."
  {:p  :price
   :s  :size
   :x  :exchange
   :t  :timestamp
   :i  :id
   :c  :conditions
   :z  :tape})

;; ---------------------------------------------------------------------------
;; Transform functions
;; ---------------------------------------------------------------------------

(defn expand-keys
  "Rename keys in a map using a key mapping.
   Keys not in the mapping are kept as-is."
  [m key-map]
  (when m
    (reduce-kv
     (fn [acc k v]
       (assoc acc (get key-map k k) v))
     {}
     m)))

(defn expand-nested-keys
  "Expand keys in nested structures.
   Handles: single map, vector of maps, map of symbol → vec-of-maps."
  [data key-map]
  (cond
    (map? data)        (if (every? keyword? (keys data))
                         ;; Could be {symbol → [bars...]} or a single record
                         (if (some vector? (vals data))
                           ;; Map of symbol → vector of records
                           (reduce-kv
                            (fn [m k v]
                              (assoc m k (if (vector? v)
                                           (mapv #(expand-keys % key-map) v)
                                           v)))
                            {}
                            data)
                           ;; Single record map
                           (expand-keys data key-map))
                         data)
    (vector? data)     (mapv #(expand-keys % key-map) data)
    (sequential? data) (mapv #(expand-keys % key-map) data)
    :else              data))

;; ---------------------------------------------------------------------------
;; Response transformers by operation name
;; ---------------------------------------------------------------------------

(defmulti expand-response
  "Expand terse keys in an Alpaca response based on the operation name.
   Default: return response unchanged."
  (fn [op-name _response] op-name))

(defmethod expand-response :default [_ response] response)

(defmethod expand-response :market/quote
  [_ response]
  (-> response
      (update :quote expand-keys quote-keys)))

(defmethod expand-response :market/bars
  [_ response]
  (-> response
      (update :bars expand-nested-keys bar-keys)))
