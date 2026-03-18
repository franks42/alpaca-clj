# alpaca-clj

Capability-gated proxy trading server for [Alpaca Markets](https://alpaca.markets).

Babashka runtime. EDN end-to-end. [Stroopwafel](https://github.com/franks42/stroopwafel) authorization with requester-bound tokens.

---

## Four-Party Security Architecture

The system enforces strict separation between four parties. No party holds more authority than it needs. No party can escalate beyond what was explicitly granted.

```
┌─────────────────────────────────────────────────────────────┐
│  1. Policy Authority (human operator / AI judge panel)      │
│                                                             │
│  Holds: root Ed25519 private key                            │
│  Decides: what each AI agent is allowed to do               │
│  Produces: signed capability tokens (Stroopwafel)           │
│                                                             │
│  "Agent X may read market data and place limit buy orders   │
│   for AAPL and SPY, up to 100 shares per order."            │
└──────────────────────────┬──────────────────────────────────┘
                           │ signed token (sealed, non-expandable)
                           │ bound to agent's public key
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  2. AI Agent / LLM Client                                   │
│                                                             │
│  Holds: capability token + own Ed25519 private key          │
│  Cannot: talk to Alpaca directly, expand token authority,   │
│          forge signatures, act without capability grant      │
│  Does: sign each request with its private key,              │
│         send token + signed request to proxy                │
└──────────────────────────┬──────────────────────────────────┘
                           │ Authorization: Bearer <token>
                           │ X-Agent-Signature: <signed-request>
                           │ Content-Type: application/edn
                           │ Body: {:symbol "AAPL"}
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  3. alpaca-clj Proxy (this project)                         │
│                                                             │
│  Holds: root public key (verifies tokens),                  │
│         Alpaca API key+secret (authenticates to Alpaca)      │
│  Does:                                                      │
│    a. Verify token signature chain (root key → token)       │
│    b. Verify request signature (agent key → request)        │
│    c. Datalog join: token's agent key = request's agent key │
│    d. Check effect class (:read/:write/:destroy)            │
│    e. Check domain ("market", "account", "trade")           │
│    f. If all pass → forward to Alpaca, return EDN           │
│    g. Log everything                                        │
└──────────────────────────┬──────────────────────────────────┘
                           │ APCA-API-KEY-ID + APCA-API-SECRET-KEY
                           │ (never visible to AI agent)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  4. Alpaca Markets                                          │
│                                                             │
│  Sees only: the proxy's API credentials                     │
│  Knows nothing about: tokens, agents, capabilities, Datalog │
│  Provides: trading execution, market data, account state    │
└─────────────────────────────────────────────────────────────┘
```

**Why this matters:** The AI agent never holds Alpaca credentials. Even if the agent's runtime is fully compromised, the attacker gets a capability token that is:
- **Scoped** — limited to specific effect classes and domains
- **Bound** — useless without the agent's private key (requester binding)
- **Sealed** — cannot be expanded to grant more authority
- **Auditable** — every request is logged with token and agent identity

---

## Security Enforcement Stack

Requests pass through layered structural enforcement. Each layer is inexpressible, not just denied — you can't even construct a request that bypasses it.

| Layer | Check | Failure |
|---|---|---|
| **Router** | Route exists in schema? | 404 Not Found |
| **Router** | HTTP method matches? | 405 Method Not Allowed |
| **Auth** | Token present? | 401 Unauthorized |
| **Auth** | Token signature valid (root key)? | 403 Forbidden |
| **Auth** | Request signed (if requester-bound)? | 403 "requires signed request" |
| **Auth** | Agent key matches token binding? | 403 "not bound to this agent key" |
| **Auth** | Token grants required effect class? | 403 "does not grant write" |
| **Auth** | Token grants required domain? | 403 "does not grant access to trade" |
| **Proxy** | Forward to Alpaca | 200 + EDN response |

---

## Quick Start

```bash
# 1. Set up Alpaca paper trading credentials
source .env.paper

# 2. Start proxy (no auth — development mode)
bb server:start &

# 3. Discover available operations
bb api help

# 4. Query market data
bb api market/clock
bb api market/quote --symbol AAPL
bb api market/bars --symbol SPY --limit 5

# 5. Check account
bb api account/info
bb api trading/positions

# 6. Place and manage orders (paper trading)
bb api trade/place-order --symbol AAPL --side buy --type limit --qty 1 --limit_price 200
bb api trade/orders
bb api trade/cancel-order --order_id <uuid>

# 7. Stop
bb server:stop
```

---

## Authorization Examples

### Bearer Token (simple capability grant)

```bash
# Policy authority generates root keypair (one-time)
bb token generate-keys

# Start proxy with auth
export STROOPWAFEL_ROOT_KEY="302a300506032b6570..."
bb server:start &

# Issue a read-only token for an AI agent
TOKEN=$(bb token issue-read-only 2>/dev/null)
export STROOPWAFEL_TOKEN="$TOKEN"

bb api market/quote --symbol AAPL    # 200 — token grants read + market
bb api trade/place-order ...         # 403 — "does not grant write access"
```

### Scoped Token (restricted capabilities)

```bash
# Market data only — no account access, no trading
TOKEN=$(bb token issue --effects read --domains market 2>/dev/null)

bb api market/quote --symbol AAPL    # 200
bb api account/info                  # 403 — "does not grant read access to account"

# Read + write for trading only
TOKEN=$(bb token issue --effects read,write --domains trade 2>/dev/null)

bb api trade/place-order ...         # 200
bb api trade/cancel-order ...        # 403 — "does not grant destroy access"
```

### Requester-Bound Token (proof-of-possession)

```bash
# Agent generates its own Ed25519 keypair
bb token generate-agent-keys
# → writes .stroopwafel-agent.edn (agent's private key)
# → prints agent public key hex

# Policy authority issues token bound to this agent
TOKEN=$(bb token issue --effects read --domains market \
  --agent-key 302a300506032b6570... 2>/dev/null)

# Agent must sign every request
export STROOPWAFEL_TOKEN="$TOKEN"
export STROOPWAFEL_AGENT_SIGN=true
bb api market/quote --symbol AAPL    # 200 — signed + bound

# Without signing → rejected
unset STROOPWAFEL_AGENT_SIGN
bb api market/quote --symbol AAPL    # 403 — "requires signed request"

# Different agent with stolen token → rejected
# (even with a valid signature, wrong key doesn't match token)
#                                      403 — "not bound to this agent key"
```

### Auth Modes

The proxy auto-detects auth mode from environment:

| Env var | Mode | Description |
|---|---|---|
| `STROOPWAFEL_ROOT_KEY` | Stroopwafel | Capability token auth (bearer or requester-bound) |
| `PROXY_TOKEN` | Simple | Shared secret (development) |
| Neither | None | No auth (development only) |

---

## Available Operations (v0.3.1)

| Operation | Route | Effect | Description |
|---|---|---|---|
| `account/info` | `GET /account/info` | read | Account balances, buying power, status |
| `market/clock` | `GET /market/clock` | read | Market open/closed, next open/close times |
| `market/quote` | `POST /market/quote` | read | Latest bid/ask for a stock |
| `market/bars` | `POST /market/bars` | read | Historical OHLCV price bars |
| `trading/positions` | `GET /trading/positions` | read | Open positions |
| `trade/place-order` | `POST /trade/place-order` | write | Place market/limit/stop order |
| `trade/orders` | `POST /trade/orders` | read | List orders (open/closed/all) |
| `trade/order` | `POST /trade/order` | read | Get order by ID |
| `trade/cancel-order` | `POST /trade/cancel-order` | destroy | Cancel an open order |
| `trade/close-position` | `POST /trade/close-position` | destroy | Close a position |

### API Discovery

```bash
bb api help                 # offline — from local schema
bb api                      # live — from running proxy (GET /api)
curl http://localhost:8080/api   # returns full schema as EDN
```

The `/api` endpoint returns the complete operation schema as EDN — an LLM can read it to discover what operations are available, what parameters each takes, which are required, and what they mean.

---

## Server Lifecycle

```bash
bb server:start              # start (foreground, default port 8080)
bb server:start &            # start in background
bb server:start 9090         # custom port
bb server:stop               # stop via PID file
bb server:restart            # stop + start
bb server:status             # check if running
```

---

## Trust Bootstrap (Production Deployment)

In production, each party runs under a separate OS account. File permissions enforce key isolation.

```
operator account          agent-trader account         proxy account
─────────────────         ──────────────────────       ──────────────────
.stroopwafel-root.edn     ~/.stroopwafel/              ~/.alpaca/
  (root private key)        agent.edn     (0600)         credentials (0600)
                            public-key.hex (0644)       STROOPWAFEL_ROOT_KEY
                            token.cedn    (0644)          (root public key)
```

**Bootstrap flow:**

1. **Agent** starts → generates Ed25519 keypair → writes public key to `~/.stroopwafel/public-key.hex`
2. **Operator** reads agent's public key, decides capabilities, mints bound token
3. **Operator** writes token to agent's `~/.stroopwafel/token.cedn`
4. **Agent** reads own private key + token → ready to make signed requests
5. **Proxy** runs separately — has root public key (verifies tokens) + Alpaca credentials (forwards to Alpaca)

The agent's private key **never leaves the agent's home directory**. The operator **never sees the private key** — only reads the public key and writes back a sealed token.

**Compromise analysis:**

| Compromised party | Attacker gets | Cannot do |
|---|---|---|
| Agent | Bound token (useless without private key) | Mint new tokens, access Alpaca directly |
| Proxy | Alpaca API keys | Mint tokens (no root private key) |
| Operator | Root private key (can mint tokens) | Use tokens without agent keys; detected via audit |

---

## Key Design Decisions

- **Schema as single source of truth** — one Clojure data structure defines all operations. Router, CLI, Datalog predicates, and API discovery are all derived from it. Adding an endpoint means adding one schema entry.
- **EDN end-to-end** — `Content-Type: application/edn`. Terse Alpaca market data keys are expanded to readable names (`bp` → `bid-price`, `o` → `open`). LLMs and humans read the same format.
- **fn-as-URL** — operation name in the URL path (`/market/quote`). The router IS the structural whitelist: unknown route → 404 before any logic runs.
- **Babashka throughout** — proxy server and CLI run on bb. Sub-second startup.
- **No direct API access** — CLI calls proxy, proxy calls Alpaca. The proxy is the required Policy Enforcement Point. There is no `alpaca.api` namespace for direct calls — by design.
- **Backwards-compatible auth** — bearer tokens work unchanged; requester binding is opt-in per token.

---

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| [http-kit](https://github.com/http-kit/http-kit) | 2.8.1 | HTTP server + client |
| [cheshire](https://github.com/dakrone/cheshire) | 6.1.0 | JSON (Alpaca REST API) |
| [cedn](https://github.com/franks42/canonical-edn) | 1.2.0 | Canonical EDN serialization |
| [stroopwafel](https://github.com/franks42/stroopwafel) | 0.8.0 | Capability tokens with requester binding |
| [trove](https://github.com/taoensso/trove) | 1.1.0 | Structured logging |
| [timbre](https://github.com/taoensso/timbre) | 6.8.0 | Logging backend |

## License

See [LICENSE](LICENSE).
