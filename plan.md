# alpaca-clj — Implementation Plan

> Capability-gated proxy trading server for Alpaca Markets.
> Babashka runtime. EDN end-to-end. fn-as-URL routing. Stroopwafel authorization.
>
> Last updated: 2026-03-17

---

## Architecture

```
CLI (bb tasks)                    HTTP clients / LLM agents
  │                                  │
  │ POST + EDN body                  │ POST + EDN body
  │ + Bearer token                   │ + Bearer token
  │                                  │
  ▼                                  ▼
┌────────────────────────────────────────────┐
│  alpaca-clj proxy (bb + http-kit)          │
│  ──────────────────────────────────────── │
│  Router (fn-as-URL = structural whitelist) │
│  Auth middleware (Stroopwafel verify)       │
│  Handler (EDN body → fn call → EDN result) │
│  Alpaca client (JSON ↔ Alpaca REST)        │
│  Audit log                                 │
└──────────────────┬─────────────────────────┘
                   │ APCA-API-KEY-ID / APCA-API-SECRET-KEY
                   ▼
          Alpaca Markets (paper / live)
```

---

## Milestones

### Phase 0 — Project Skeleton **[DONE — v0.1.0]**
- [x] `bb.edn` with deps (http-kit 2.8.1, cheshire 6.1.0, cedn 1.2.0, trove 1.1.0, timbre 6.8.0)
- [x] `CLAUDE.md` with conventions
- [x] Directory structure: `src/alpaca/{config,client,schema,keys,telemetry}.clj`, `src/alpaca/proxy/{server,router,middleware,handlers,log}.clj`
- [x] Schema definition (single source of truth with descriptions, param specs, effect classes)
- [x] Server lifecycle: `bb server:start/stop/restart/status` (PID file)
- [x] API discovery: `GET /api` returns full schema as EDN
- [x] Health endpoint: `GET /health`
- [x] Structural whitelist: 404 unknown route, 405 wrong method

### Phase 1 — Read-Only APIs (Zero Financial Risk) **[DONE — v0.1.0]**
- [x] `GET  /account/info`       → Alpaca `GET /v2/account`
- [x] `GET  /market/clock`       → Alpaca `GET /v2/clock`
- [x] `POST /market/quote`       → Alpaca `GET /v2/stocks/{symbol}/quotes/latest`
- [x] `POST /market/bars`        → Alpaca `GET /v2/stocks/bars` (`:param-map {:symbol :symbols}`)
- [x] `GET  /trading/positions`  → Alpaca `GET /v2/positions`
- [x] Key expansion: terse Alpaca keys → readable names (bid-price, open, high, etc.)
- [x] Alpaca client layer (JSON internally, EDN at boundary)
- [x] Simple auth: `PROXY_TOKEN` env var (disabled when unset)
- [x] Unified CLI: `bb api <operation> [--flags]`, convenience aliases
- [x] Telemetry: taoensso trove/timbre, structured logging to stderr, request timing
- [x] All 5 endpoints verified against Alpaca paper trading
- [x] clj-kondo 0 errors 0 warnings, cljfmt clean
- [x] Committed `aab2853`, tagged `v0.1.0`, pushed

### Phase 2 — Stroopwafel Integration **[DONE — v0.2.0]**
Stroopwafel 0.7.0 runs on bb. Token auth fully integrated.
- [x] `alpaca.auth` namespace: token issue, serialize (CEDN), verify, authorize
- [x] `wrap-stroopwafel-auth` middleware: verify signature + evaluate Datalog checks
- [x] Auth checks: `:effect` and `:domain` facts injected per request
- [x] `/health` and `/api` exempt from auth
- [x] `bb token generate-keys` — generate root Ed25519 keypair
- [x] `bb token issue-read-only` — issue sealed read-only token (all domains)
- [x] `bb token issue --effects read,write --domains market,account` — custom tokens
- [x] `bb token inspect <token>` — show token contents
- [x] CLI uses `STROOPWAFEL_TOKEN` env var (falls back to `PROXY_TOKEN`)
- [x] Without token → 401; invalid token → 403; valid token → proxy to Alpaca
- [x] Telemetry: trove/timbre with proper stderr routing (System/err via backend)
- [x] Auth mode auto-detected: `STROOPWAFEL_ROOT_KEY` → stroopwafel, `PROXY_TOKEN` → simple, neither → none
- [x] clj-kondo 0 errors 0 warnings, cljfmt clean

### Phase 3 — Write APIs (Paper Trading) **[DONE — v0.3.0]**
- [x] `POST /trade/place-order`     → Alpaca `POST /v2/orders`      (write)
- [x] `POST /trade/orders`          → Alpaca `GET  /v2/orders`       (read)
- [x] `POST /trade/order`           → Alpaca `GET  /v2/orders/{id}`  (read)
- [x] `POST /trade/cancel-order`    → Alpaca `DELETE /v2/orders/{id}` (destroy)
- [x] `POST /trade/close-position`  → Alpaca `DELETE /v2/positions/{symbol}` (destroy)
- [x] Alpaca client: POST (JSON body) and DELETE methods
- [x] bb CLI tasks for all operations (via schema-driven `bb api`)
- [x] End-to-end: place limit order → query → cancel (paper trading)
- [x] Auth: read-only token → 403 on write; write token → 200
- [x] Effect classes enforced: `:read`, `:write`, `:destroy` with domain scoping
- [x] clj-kondo 0 errors 0 warnings, cljfmt clean

### Phase 4 — Extended Coverage
- [ ] Assets: `GET /trading/assets`, `POST /trading/asset`
- [ ] Watchlists: CRUD operations
- [ ] Calendar, portfolio history
- [ ] Crypto market data + crypto orders
- [ ] Options: contracts, quotes, multi-leg orders
- [ ] Account configuration

### Phase 5 — Production Hardening
- [ ] Audit log (append-only, cryptographic chaining)
- [ ] Linear capabilities (single-use tokens via revocation IDs)
- [ ] Temporal constraints (market hours window, token expiry)
- [ ] Injected account state (daily P&L, buying power checks in Datalog)
- [ ] Paper-to-live promotion ceremony
- [ ] Adversarial schema review

---

## Key Design Decisions

| Decision | Rationale |
|---|---|
| **Schema as single source of truth** | One Clojure data structure defines all operations. Router, bb tasks, and Datalog predicates derived from it. |
| **EDN end-to-end** | `POST Content-Type: application/edn`. Request and response are Clojure maps. No JSON on proxy boundary. |
| **fn-as-URL** | Operation name in URL path (`/market/quote`). Router IS the structural whitelist. Unknown route → 404 before any logic. |
| **Babashka throughout** | Proxy server and CLI run on bb. http-kit works in bb. No JVM startup cost. |
| **JSON internal to Alpaca client** | `client.clj` speaks JSON to Alpaca REST, converts to/from EDN at boundary. Cheshire for JSON. |
| **bb-mcp-server patterns** | Reuse http-kit lifecycle, middleware composition. Simpler: no module system, flat route set. |
| **Simple auth first** | Phase 1 uses `PROXY_TOKEN` env var. Stroopwafel wired in Phase 2. |

---

## Dependencies

| Library | Purpose | Source |
|---|---|---|
| `http-kit` | HTTP server | Maven |
| `cheshire` | JSON parsing (Alpaca REST) | Maven |
| `cedn` | Canonical EDN serialization | `../canonical-edn` (local) |
| `stroopwafel` | Capability token auth | `../stroopwafel` (local) |

## Reference Projects

| Directory | Role |
|---|---|
| `../alpaca-py` | Alpaca Python SDK — endpoint reference |
| `../alpaca-mcp-server` | 53 MCP tools — parameter/response shapes |
| `../alpaca-cli` | CLI patterns |
| `../bb-mcp-server` | http-kit server architecture patterns |
| `../stroopwafel` | Authorization token API |
| `../canonical-edn` | CEDN library |

---

*Status: Phase 0+1+2+3 complete (v0.3.0). Phase 4 (extended coverage) next.*
