(ns alpaca.config
  "Alpaca API configuration.
   All credentials come from environment variables — never hardcoded.")

(defn load-config
  "Load configuration from environment variables.
   Returns a config map used by the client and proxy.

   Auth modes (checked in order):
   1. STROOPWAFEL_ROOT_KEY — hex-encoded public key → Stroopwafel auth
   2. PROXY_TOKEN — shared secret → simple Bearer token auth
   3. Neither set → auth disabled (development mode)"
  []
  (let [paper? (not= (System/getenv "APCA_PAPER") "false")]
    {:api-key-id          (System/getenv "APCA_API_KEY_ID")
     :api-secret-key      (System/getenv "APCA_API_SECRET_KEY")
     :paper?              paper?
     :trading-url         (if paper?
                            "https://paper-api.alpaca.markets"
                            "https://api.alpaca.markets")
     :data-url            "https://data.alpaca.markets"
     :proxy-token         (System/getenv "PROXY_TOKEN")
     :stroopwafel-root-key (System/getenv "STROOPWAFEL_ROOT_KEY")
     :proxy-port          (parse-long (or (System/getenv "PROXY_PORT") "8080"))
     :proxy-host          (or (System/getenv "PROXY_HOST") "127.0.0.1")}))
