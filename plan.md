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

### Phase 2 — Stroopwafel Integration **[BLOCKED — stroopwafel needs bb port]**
Stroopwafel is JVM-only (java.security.* crypto). Needs Phase 4 (.cljc cross-platform) in ../stroopwafel before this can proceed.
- [ ] Middleware: verify `Authorization: Bearer <token>` via Stroopwafel
- [ ] Route + EDN body → Datalog fact translation
- [ ] Policy evaluation (allow/deny with explain)
- [ ] `bb token issue-read-only` — mint read-only capability token
- [ ] CLI uses `STROOPWAFEL_TOKEN` env var
- [ ] Deny → 403 with explanation

### Phase 3 — Write APIs (Paper Trading)
- [ ] `POST /trade/place-order`     → Alpaca `POST /v2/orders`      (write)
- [ ] `GET  /trade/orders`          → Alpaca `GET  /v2/orders`       (read)
- [ ] `POST /trade/order`           → Alpaca `GET  /v2/orders/{id}`  (read)
- [ ] `POST /trade/cancel-order`    → Alpaca `DELETE /v2/orders/{id}` (destroy)
- [ ] `POST /trade/close-position`  → Alpaca `DELETE /v2/positions/{symbol}` (destroy)
- [ ] Token facts: allowed symbols, side, type, max qty
- [ ] bb CLI tasks for each operation
- [ ] End-to-end: place a paper limit order, query it, cancel it

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

*Status: Phase 0+1 complete (v0.1.0). Phase 2 blocked on stroopwafel bb port. Phase 3 can proceed independently.*
