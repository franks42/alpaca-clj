# alpaca-clj

Capability-gated proxy trading server for [Alpaca Markets](https://alpaca.markets).

Babashka runtime. EDN end-to-end. [Stroopwafel](https://github.com/franks42/stroopwafel) authorization.

## What It Does

The proxy sits between any client (CLI, LLM agent, script) and Alpaca Markets. No client can talk to Alpaca directly — the proxy is the only process with the API keys.

```
Client (bb CLI, LLM, curl)
  │
  │  POST /market/quote
  │  Content-Type: application/edn
  │  Authorization: Bearer <stroopwafel-token>
  │  Body: {:symbol "AAPL"}
  │
  ▼
alpaca-clj proxy (bb + http-kit)
  │  1. Verify token signature (Ed25519)
  │  2. Check token grants :read + "market" via Datalog
  │  3. Forward to Alpaca REST API
  │  4. Convert JSON → EDN with readable keys
  │
  ▼
Alpaca Markets (paper or live)
```

## Quick Start

```bash
# 1. Set up credentials
source .env.paper                    # Alpaca paper trading keys

# 2. Start proxy (no auth — development mode)
bb server:start &

# 3. Use it
bb api help                          # list all operations
bb api market/clock                  # market open/closed
bb api market/quote --symbol AAPL    # latest quote
bb api market/bars --symbol SPY --limit 5
bb api account/info                  # account details
bb api trading/positions             # open positions

# 4. Stop
bb server:stop
```

## Authorization with Stroopwafel

### Token Issuance

A human operator generates a root keypair and mints capability tokens:

```bash
# One-time: generate root Ed25519 keypair
bb token generate-keys
# → writes .stroopwafel-root.edn (private, gitignored)
# → prints STROOPWAFEL_ROOT_KEY (public, for server config)

# Mint a read-only token (all domains)
bb token issue-read-only

# Mint a restricted token (market data only)
bb token issue --effects read --domains market

# Mint a broader token (read + write, specific domains)
bb token issue --effects read,write --domains market,trading
```

### What a Token Encodes

Two types of Datalog facts:

- **Effect classes**: `:read`, `:write`, `:destroy` — what kind of operations are allowed
- **Domains**: `"account"`, `"market"`, `"trading"` — which API areas are accessible

Tokens are cryptographically sealed (Ed25519 signature chain) and serialized as CEDN with `#bytes` tagged literals. They travel as plain strings in the `Authorization: Bearer` header.

### How Authorization Works

On every request, the proxy:

1. Extracts the Bearer token from the header
2. Verifies the cryptographic signature chain (was this minted by our root key?)
3. Looks up the requested operation in the schema (e.g. `/market/quote` → effect `:read`, domain `"market"`)
4. Injects those as Datalog checks: does the token carry `[:effect :read]` AND `[:domain "market"]`?
5. If both checks pass → forward to Alpaca. If not → 403 Forbidden.

### Running with Auth Enabled

```bash
source .env.paper
export STROOPWAFEL_ROOT_KEY="302a300506032b6570..."   # from generate-keys
bb server:start &

# Issue and use a token
TOKEN=$(bb token issue-read-only 2>/dev/null)
export STROOPWAFEL_TOKEN="$TOKEN"

bb api market/quote --symbol AAPL    # works — token grants read + market
bb api account/info                  # works — token grants read + account
```

Without a token → 401. Invalid/tampered token → 403. `/health` and `/api` are exempt (discoverable without auth).

### Auth Modes

The proxy auto-detects the auth mode from environment variables:

| Env var | Mode | Description |
|---|---|---|
| `STROOPWAFEL_ROOT_KEY` | Stroopwafel | Full capability token auth |
| `PROXY_TOKEN` | Simple | Shared secret Bearer token |
| Neither | None | No auth (development mode) |

## API Discovery

```bash
# Offline (from local schema)
bb api help

# Live (from running proxy)
bb api
# or: curl http://localhost:8080/api
```

The `/api` endpoint returns the full operation schema as EDN — an LLM or script can read it and know exactly what operations are available, what parameters each takes, and what they mean.

## Available Operations (v0.2.0)

| Operation | Method | Route | Effect | Description |
|---|---|---|---|---|
| `account/info` | GET | `/account/info` | read | Account balances, buying power, status |
| `market/clock` | GET | `/market/clock` | read | Market open/closed, next open/close times |
| `market/quote` | POST | `/market/quote` | read | Latest bid/ask for a stock |
| `market/bars` | POST | `/market/bars` | read | Historical OHLCV price bars |
| `trading/positions` | GET | `/trading/positions` | read | Open positions |

## Server Lifecycle

```bash
bb server:start              # start (foreground, default port 8080)
bb server:start &            # start in background
bb server:start 9090         # custom port
bb server:stop               # stop via PID file
bb server:restart             # stop + start
bb server:status             # check if running
```

## Known Limitations (v0.2.0)

**Bearer tokens only (currently).** The proxy currently uses bearer tokens — whoever holds the token can use it. Stroopwafel 0.8.0 now supports requester-bound tokens via signed requests, but this is not yet wired into the proxy middleware.

**Requester-bound tokens** (stroopwafel 0.8.0, integrated):

```bash
# 1. Generate agent keypair
bb token generate-agent-keys

# 2. Issue token bound to agent
bb token issue --effects read --domains market --agent-key <hex>

# 3. Agent signs every request
export STROOPWAFEL_AGENT_SIGN=true
bb api market/quote --symbol AAPL    # agent signs automatically
```

How it works:
- Token carries `[:authorized-agent-key <agent-public-key>]` — bound to a specific agent's Ed25519 key
- Agent signs each request with its private key; signature goes in `X-Agent-Signature` header
- Proxy verifies signature, then Datalog join confirms the key in the signature matches the key in the token
- **Stolen token is useless without the agent's private key**
- Bearer-only request with a bound token → 403 "requires signed request"
- Wrong agent key → 403 "Token not bound to this agent key"

Backwards compatible: tokens without `[:authorized-agent-key ...]` work as bearer tokens (no signature required).

## Key Design Decisions

- **Schema as single source of truth** — one Clojure data structure defines all operations. Router, CLI, Datalog predicates, and API discovery are all derived from it.
- **EDN end-to-end** — `Content-Type: application/edn`. Request and response bodies are Clojure maps. Terse Alpaca market data keys are expanded to readable names (`bp` → `bid-price`).
- **fn-as-URL** — operation name in the URL path (`/market/quote`). The router IS the structural whitelist: unknown route → 404 before any logic runs.
- **Babashka throughout** — proxy server and CLI run on bb. No JVM startup cost.
- **No direct API access** — CLI calls proxy, proxy calls Alpaca. The proxy is the required Policy Enforcement Point (PEP).

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| [http-kit](https://github.com/http-kit/http-kit) | 2.8.1 | HTTP server + client |
| [cheshire](https://github.com/dakrone/cheshire) | 6.1.0 | JSON (Alpaca REST) |
| [cedn](https://github.com/franks42/canonical-edn) | 1.2.0 | Canonical EDN serialization |
| [stroopwafel](https://github.com/franks42/stroopwafel) | 0.8.0 | Capability token auth (requester binding available) |
| [trove](https://github.com/taoensso/trove) | 1.1.0 | Structured logging |
| [timbre](https://github.com/taoensso/timbre) | 6.8.0 | Logging backend |

## License

See [LICENSE](LICENSE).
