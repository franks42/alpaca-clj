(ns alpaca.proxy.server
  "HTTP proxy server lifecycle.
   Wraps http-kit with start/stop and middleware composition."
  (:require [org.httpkit.server :as http]
            [taoensso.trove :as log]
            [clojure.string]
            [alpaca.telemetry :as telemetry]
            [alpaca.config :as config]
            [alpaca.schema :as schema]
            [alpaca.auth :as auth]
            [alpaca.proxy.router :as router]
            [alpaca.proxy.middleware :as mw]))

(defonce ^:private server-instance (atom nil))

(def ^:private pid-file ".alpaca-proxy.pid")

(defn- write-pid-file! [port]
  (let [pid (.pid (java.lang.ProcessHandle/current))]
    (spit pid-file (str pid "\n" port "\n"))
    (log/log! {:level :debug :id ::pid-written
               :msg "PID file written"
               :data {:file pid-file :pid pid :port port}})))

(defn- remove-pid-file! []
  (let [f (java.io.File. pid-file)]
    (when (.exists f) (.delete f))))

(defn- read-pid-file []
  (let [f (java.io.File. pid-file)]
    (when (.exists f)
      (let [[pid-str port-str] (clojure.string/split (slurp f) #"\n")]
        {:pid  (parse-long pid-str)
         :port (parse-long port-str)}))))

(defn start-server!
  "Start the proxy server.

   Arguments:
     config — optional config map (defaults to env-based config)

   Returns: server instance map with :stop!, :port, :host."
  [& [{:keys [port host] :as config-override}]]
  (when @server-instance
    (log/log! {:level :warn :id ::already-running
               :msg "Server already running, stopping first"})
    ((:stop! @server-instance)))

  (let [config     (merge (config/load-config) config-override)
        port       (or port (:proxy-port config))
        host       (or host (:proxy-host config))
        auth-mode  (cond
                     (:stroopwafel-root-key config) :stroopwafel
                     (:proxy-token config)          :token
                     :else                          :none)
        handler    (-> (router/create-router config)
                       (mw/wrap-edn-content-type)
                       (cond->
                        (= auth-mode :stroopwafel)
                         (mw/wrap-stroopwafel-auth
                          (auth/import-public-key (:stroopwafel-root-key config)))

                         (= auth-mode :token)
                         (mw/wrap-simple-auth (:proxy-token config)))
                       (mw/wrap-request-logging)
                       (mw/wrap-error-handler))
        http-server (http/run-server handler
                                     {:port port
                                      :ip   host
                                      :legacy-return-value? false})
        actual-port (http/server-port http-server)
        instance    {:stop! (fn []
                              (log/log! {:level :info :id ::server-stopping
                                         :msg "Stopping server"})
                              @(http/server-stop! http-server {:timeout 5000})
                              (remove-pid-file!)
                              (reset! server-instance nil)
                              (log/log! {:level :info :id ::server-stopped
                                         :msg "Server stopped"}))
                     :port  actual-port
                     :host  host}]
    (reset! server-instance instance)
    (write-pid-file! actual-port)
    (log/log! {:level :info :id ::server-started
               :msg   "alpaca-clj proxy started"
               :data  {:host  host
                       :port  actual-port
                       :paper (:paper? config)
                       :auth  auth-mode
                       :routes (mapv (fn [op]
                                       {:route  (:route op)
                                        :method (name (:method op))
                                        :effect (name (:effect op))})
                                     schema/operations)}})
    instance))

(defn stop-server!
  "Stop the running server."
  []
  (when-let [srv @server-instance]
    ((:stop! srv))))

(defn stop-remote!
  "Stop a proxy server running in another process via PID file."
  []
  (if-let [{:keys [pid port]} (read-pid-file)]
    (let [handle (java.lang.ProcessHandle/of pid)]
      (if (and (.isPresent handle) (.isAlive (.get handle)))
        (do (.destroy (.get handle))
            (remove-pid-file!)
            (log/log! {:level :info :id ::remote-stopped
                       :msg "Stopped proxy server"
                       :data {:pid pid :port port}})
            true)
        (do (remove-pid-file!)
            (log/log! {:level :warn :id ::pid-stale
                       :msg "PID file found but process not running"
                       :data {:pid pid}})
            false)))
    (do (log/log! {:level :warn :id ::no-pid-file
                   :msg "No PID file found — server not running?"})
        false)))

(defn server-running?
  "Check if a proxy server is running (via PID file)."
  []
  (when-let [{:keys [pid port]} (read-pid-file)]
    (let [handle (java.lang.ProcessHandle/of pid)]
      (when (and (.isPresent handle) (.isAlive (.get handle)))
        {:pid pid :port port}))))

(defn -main
  "Entry point. Supports: start [port], stop, restart [port], status."
  [& args]
  (telemetry/ensure-initialized!)
  (case (first args)
    "stop"
    (do (stop-remote!)
        (shutdown-agents))

    "restart"
    (do (stop-remote!)
        (Thread/sleep 500)
        (let [port (when-let [p (second args)] (parse-long p))]
          (start-server! (when port {:port port}))
          @(promise)))

    "status"
    (if-let [info (server-running?)]
      (println (pr-str {:status :running :pid (:pid info) :port (:port info)}))
      (println (pr-str {:status :stopped})))

    ;; default: start
    (let [port (when-let [p (first args)] (parse-long p))]
      (start-server! (when port {:port port}))
      @(promise))))
