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

### Phase 0 — Project Skeleton **[DONE]**
- [x] `bb.edn` with deps (http-kit, cedn, cheshire)
- [x] `CLAUDE.md` with conventions
- [x] Directory structure: `src/alpaca/{config,client,schema}.clj`, `src/alpaca/proxy/{server,router,middleware,handlers}.clj`
- [x] Schema definition (single source of truth for 5 read-only operations)
- [x] bb tasks: `server:start`, `account`, `market`, `trading`
- [x] Basic http-kit server starts and returns 404/405 for unknown routes/methods
- [x] CLI scripts: `src/alpaca/cli/{common,account,market,trading}.clj`
- [x] Health endpoint: `GET /health`
- [x] clj-kondo 0 errors 0 warnings, cljfmt clean

### Phase 1 — Read-Only APIs (Zero Financial Risk) **[DONE]**
- [x] `GET  /account/info`       → Alpaca `GET /v2/account`
- [x] `GET  /market/clock`       → Alpaca `GET /v2/clock`
- [x] `POST /market/quote`       → Alpaca `GET /v2/stocks/{symbol}/quotes/latest`
- [x] `POST /market/bars`        → Alpaca `GET /v2/stocks/bars` (`:param-map {:symbol :symbols}`)
- [x] `GET  /trading/positions`  → Alpaca `GET /v2/positions`
- [x] Alpaca client layer (JSON HTTP calls with API key auth)
- [x] EDN request/response on proxy boundary
- [x] Simple auth: `PROXY_TOKEN` env var (disabled when unset)
- [x] bb CLI tasks: `bb account info`, `bb market clock`, `bb market quote`, `bb market bars`, `bb trading positions`
- [x] End-to-end: all 5 endpoints verified against Alpaca paper trading
- [x] Structural whitelist: 404 unknown route, 405 wrong method
- [x] clj-kondo 0 errors 0 warnings, cljfmt clean

### Phase 2 — Stroopwafel Integration
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

*Status: Phase 0 + Phase 1 complete. Phase 2 (Stroopwafel integration) next.*
