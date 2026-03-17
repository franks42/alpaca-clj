(ns alpaca.schema
  "Predicate schema — single source of truth for all operations.

   Each entry defines:
   - :name        — namespaced keyword (Datalog predicate, CLI name, route name)
   - :description — human/LLM-readable description of what the operation does
   - :effect      — :read, :write, or :destroy
   - :route       — URL path on the proxy
   - :method      — HTTP method on the proxy (:get or :post)
   - :params      — parameter spec (key → {:type :required :description :default})
   - :alpaca      — Alpaca REST mapping {:method :base :path :param-map}")

(def operations
  "All operations the proxy can perform.
   The router, CLI tasks, discovery endpoint, and authorization predicates
   are all derived from this single definition."
  [{:name        :account/info
    :description "Get account details: balances, buying power, status, margin info."
    :effect      :read
    :route       "/account/info"
    :method      :get
    :params      {}
    :alpaca      {:method :get
                  :base   :trading
                  :path   "/v2/account"}}

   {:name        :market/clock
    :description "Get market clock: whether market is open, next open/close times."
    :effect      :read
    :route       "/market/clock"
    :method      :get
    :params      {}
    :alpaca      {:method :get
                  :base   :trading
                  :path   "/v2/clock"}}

   {:name        :market/quote
    :description "Get latest quote for a stock: bid/ask price, size, exchange, timestamp."
    :effect      :read
    :route       "/market/quote"
    :method      :post
    :params      {:symbol {:type :string :required true
                           :description "Stock ticker symbol (e.g. AAPL, FIGR, SPY)"}}
    :alpaca      {:method :get
                  :base   :data
                  :path   "/v2/stocks/:symbol/quotes/latest"}}

   {:name        :market/bars
    :description "Get historical price bars (OHLCV candles) for a stock."
    :effect      :read
    :route       "/market/bars"
    :method      :post
    :params      {:symbol    {:type :string :required true
                              :description "Stock ticker symbol"}
                  :timeframe {:type :string :required false :default "1Day"
                              :description "Bar timeframe: 1Min, 5Min, 15Min, 1Hour, 1Day, 1Week, 1Month"}
                  :limit     {:type :int :required false :default 10
                              :description "Max number of bars to return"}
                  :start     {:type :string :required false
                              :description "Start date/time (ISO 8601, e.g. 2026-03-01)"}
                  :end       {:type :string :required false
                              :description "End date/time (ISO 8601)"}}
    :alpaca      {:method :get
                  :base   :data
                  :path   "/v2/stocks/bars"
                  :param-map {:symbol :symbols}}}

   {:name        :trading/positions
    :description "List all open positions: symbol, qty, entry price, P&L, market value."
    :effect      :read
    :route       "/trading/positions"
    :method      :get
    :params      {}
    :alpaca      {:method :get
                  :base   :trading
                  :path   "/v2/positions"}}])

;; --- Derived indexes ---

(def by-route
  "Map from route path to operation schema."
  (into {} (map (juxt :route identity)) operations))

(def by-name
  "Map from operation name keyword to operation schema."
  (into {} (map (juxt :name identity)) operations))

;; --- API discovery ---

(defn api-listing
  "Return the public API description — suitable for /api discovery endpoint.
   Strips internal :alpaca mapping (callers don't need to know the backend)."
  []
  {:name    "alpaca-clj"
   :version "0.1.0"
   :description "Capability-gated proxy for Alpaca Markets trading API. All operations require going through this proxy — no direct Alpaca access."
   :operations
   (mapv (fn [op]
           (-> op
               (dissoc :alpaca)
               (update :method name)
               (update :effect name)
               (update :name #(subs (str %) 1))))
         operations)})
