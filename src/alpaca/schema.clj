(ns alpaca.schema
  "Predicate schema — single source of truth for all operations.

   Each entry defines:
   - :name        — namespaced keyword (Datalog predicate, CLI name, route name)
   - :description — human/LLM-readable description of what the operation does
   - :effect      — :read, :write, or :destroy
   - :route       — URL path on the proxy
   - :method      — HTTP method on the proxy (:get or :post)
   - :params      — parameter spec (key → {:type :required :description :default})
   - :alpaca      — Alpaca REST mapping {:method :base :path :param-map}
   - :constraints — (optional) vector of declarative validation rules:
     {:enum {<key> [allowed-values]}}          — value must be one of the listed values
     {:when {<key> <value>} :require [<keys>]} — conditional required params
     {:mutex [<key1> <key2>]}                  — at most one may be present")

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

   {:name        :trade/positions
    :description "List all open positions: symbol, qty, entry price, P&L, market value."
    :effect      :read
    :route       "/trade/positions"
    :method      :get
    :params      {}
    :alpaca      {:method :get
                  :base   :trading
                  :path   "/v2/positions"}}

   ;; ── WRITE ──────────────────────────────────────────────────────────

   {:name        :trade/place-order
    :description "Place a stock order: market, limit, stop, or stop-limit."
    :effect      :write
    :route       "/trade/place-order"
    :method      :post
    :params      {:symbol        {:type :string :required true
                                  :description "Stock ticker symbol (e.g. AAPL)"}
                  :side          {:type :string :required true
                                  :description "Order side: buy or sell"}
                  :type          {:type :string :required true
                                  :description "Order type: market, limit, stop, stop_limit, trailing_stop"}
                  :qty           {:type :string :required true
                                  :description "Number of shares"}
                  :time_in_force {:type :string :required false :default "day"
                                  :description "Time in force: day, gtc, ioc, opg"}
                  :limit_price   {:type :string :required false
                                  :description "Limit price (required for limit/stop_limit)"}
                  :stop_price    {:type :string :required false
                                  :description "Stop price (required for stop/stop_limit)"}
                  :extended_hours {:type :boolean :required false
                                   :description "Allow extended hours trading"}
                  :client_order_id {:type :string :required false
                                    :description "Client-specified order ID"}}
    :constraints [{:enum {:side ["buy" "sell"]}}
                  {:enum {:type ["market" "limit" "stop" "stop_limit" "trailing_stop"]}}
                  {:enum {:time_in_force ["day" "gtc" "ioc" "opg"]}}
                  {:when {:type "limit"}      :require [:limit_price]}
                  {:when {:type "stop"}       :require [:stop_price]}
                  {:when {:type "stop_limit"} :require [:limit_price :stop_price]}]
    :alpaca      {:method :post
                  :base   :trading
                  :path   "/v2/orders"}}

   ;; ── READ (orders) ──────────────────────────────────────────────────

   {:name        :trade/orders
    :description "List orders, optionally filtered by status, symbols, side."
    :effect      :read
    :route       "/trade/orders"
    :method      :post
    :params      {:status    {:type :string :required false :default "open"
                              :description "Filter: open, closed, or all"}
                  :limit     {:type :int :required false :default 50
                              :description "Max orders to return (max 500)"}
                  :direction {:type :string :required false
                              :description "Sort direction: asc or desc"}
                  :symbols   {:type :string :required false
                              :description "Comma-separated symbol filter"}
                  :side      {:type :string :required false
                              :description "Filter by side: buy or sell"}}
    :constraints [{:enum {:status ["open" "closed" "all"]}}
                  {:enum {:direction ["asc" "desc"]}}
                  {:enum {:side ["buy" "sell"]}}]
    :alpaca      {:method :get
                  :base   :trading
                  :path   "/v2/orders"}}

   {:name        :trade/order
    :description "Get a specific order by ID."
    :effect      :read
    :route       "/trade/order"
    :method      :post
    :params      {:order_id {:type :string :required true
                             :description "Order ID (UUID)"}}
    :alpaca      {:method :get
                  :base   :trading
                  :path   "/v2/orders/:order_id"}}

   ;; ── DESTROY ────────────────────────────────────────────────────────

   {:name        :trade/cancel-order
    :description "Cancel an open order by ID."
    :effect      :destroy
    :route       "/trade/cancel-order"
    :method      :post
    :params      {:order_id {:type :string :required true
                             :description "Order ID to cancel (UUID)"}}
    :alpaca      {:method :delete
                  :base   :trading
                  :path   "/v2/orders/:order_id"}}

   {:name        :trade/close-position
    :description "Close an open position (fully or partially)."
    :effect      :destroy
    :route       "/trade/close-position"
    :method      :post
    :params      {:symbol     {:type :string :required true
                               :description "Symbol of position to close"}
                  :qty        {:type :string :required false
                               :description "Number of shares to close (omit for full close)"}
                  :percentage {:type :string :required false
                               :description "Percentage of position to close (e.g. 50)"}}
    :constraints [{:mutex [:qty :percentage]}]
    :alpaca      {:method :delete
                  :base   :trading
                  :path   "/v2/positions/:symbol"}}])

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
   :version "0.3.0"
   :description "Capability-gated proxy for Alpaca Markets trading API. All operations require going through this proxy — no direct Alpaca access."
   :operations
   (mapv (fn [op]
           (-> op
               (dissoc :alpaca)
               (update :method name)
               (update :effect name)
               (update :name #(subs (str %) 1))))
         operations)})
