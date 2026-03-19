# Claude Context for alpaca-clj

## Project Overview

**alpaca-clj** — Capability-gated proxy trading server for Alpaca Markets.

**Core principles:**
- Babashka runtime (bb + http-kit) — no JVM startup cost
- EDN end-to-end — `Content-Type: application/edn` on proxy boundary
- fn-as-URL routing — operation name in URL path, router IS the structural whitelist
- Stroopwafel authorization — capability tokens enforce Datalog policy
- Schema as single source of truth — all interfaces derived from one definition
- Paper trading first — zero financial risk during development

---

## Project Structure

```
alpaca-clj/
├── src/alpaca/
│   ├── config.clj              # API keys from env, base URLs, paper/live
│   ├── client.clj              # HTTP client for Alpaca REST (JSON internally)
│   ├── schema.clj              # Predicate schema (single source of truth)
│   ├── auth.clj                # Stroopwafel token auth (bearer, SPKI, SDSI)
│   ├── envelope.clj            # Signed envelope delegation to stroopwafel
│   ├── ssh.clj                 # SSH key import delegation to stroopwafel
│   ├── keys.clj                # Key file I/O
│   ├── client_pep.clj          # Client-side PEP (outbound policy enforcement)
│   ├── telemetry.clj           # Structured logging (trove/timbre)
│   ├── pep/
│   │   └── http_edn.clj        # Server-side PEP for HTTP+EDN wire format
│   ├── cli/
│   │   ├── api.clj             # Unified CLI: bb api <operation>
│   │   ├── token.clj           # Token management CLI
│   │   └── common.clj          # Shared CLI utilities
│   └── proxy/
│       ├── server.clj          # http-kit server lifecycle
│       ├── router.clj          # fn-as-URL routing (structural whitelist)
│       ├── middleware.clj       # Auth, content-type, audit logging
│       └── handlers.clj        # EDN body → fn call → EDN response
├── docs/                       # Design documents
├── plan.md                     # Human-readable implementation plan
├── bb.edn                      # Babashka config and tasks
└── CLAUDE.md                   # This file
```

---

## Common Commands

```bash
bb tasks                        # List available tasks
bb server:start                 # Start proxy server
bb server:stop                  # Stop proxy server

# CLI tasks (one per proxy endpoint)
bb account info                 # GET /account/info
bb market clock                 # GET /market/clock
bb market quote --symbol AAPL   # POST /market/quote
bb market bars --symbol AAPL    # POST /market/bars
bb trading positions            # GET /trading/positions

# Token management
bb token generate-keys          # Generate root keypair
bb token generate-agent-keys    # Generate agent keypair
bb token generate-outbound-keys # Generate outbound authority keypair
bb token issue-read-only        # Issue bearer read-only token
bb token issue --effects read,write --domains market,trade
bb token issue --effects read --domains market --agent-key <hex>
bb token issue-outbound --destinations host:port --effects read --domains market
bb token inspect <token-string> # Show token contents

# Quality checks
bb lint                         # clj-kondo (zero errors AND zero warnings)
bb fmt                          # cljfmt check
bb test                         # Run tests
```

---

## Verification Workflow

**MUST run before committing — zero errors AND zero warnings required:**
```bash
clj-kondo --lint src/           # MUST be 0 errors, 0 warnings
cljfmt check src/               # MUST have no formatting issues
```

Do NOT commit code with lint warnings. Fix all warnings before committing.

---

## Key Technical Notes

1. **Babashka compatible** — All code must run in bb, not just JVM Clojure
2. **http-kit for HTTP** — Server and client, both work in bb
3. **Ring middleware pattern** — `(fn [handler] (fn [req] ...))`
4. **Cheshire for JSON** — Alpaca REST speaks JSON; convert to/from EDN at boundary
5. **CEDN for canonical serialization** — Token signing, cache keys, Datalog fact matching
6. **Never commit API keys** — Alpaca keys via env vars only (`APCA_API_KEY_ID`, `APCA_API_SECRET_KEY`)

---

## EDN Conventions

**Request format:**
```
POST /market/quote
Content-Type: application/edn
Authorization: Bearer <token>

{:symbol "AAPL"}
```

**Response format:**
```
Content-Type: application/edn

{:symbol "AAPL" :bid 184.92 :ask 184.95 :timestamp "2026-03-17T14:32:11Z"}
```

**Rules:**
- All proxy request bodies are EDN maps
- All proxy responses are EDN maps (or EDN vectors of maps)
- Keywords for keys, not strings
- Alpaca JSON ↔ EDN conversion happens in `client.clj`, nowhere else

---

## Schema as Source of Truth

The schema in `schema.clj` defines every operation. All other artifacts derive from it:

```clojure
{:name   :market/quote
 :effect :read
 :route  "/market/quote"
 :method :post
 :params {:symbol {:type :string :required true}}
 :alpaca {:method :get
          :base   :data
          :path   "/v2/stocks/:symbol/quotes/latest"}}
```

**From one schema entry, derive:**
- Router entry (route + method)
- Handler parameter validation
- bb CLI task (flags from params)
- Datalog predicate (for Stroopwafel authorization)

**Adding a new endpoint:** Add schema entry → implement handler → add bb task. No independent interface design.

---

## Alpaca API Key Handling

**Environment variables (never in source, never committed):**
```bash
export APCA_API_KEY_ID="your-key"
export APCA_API_SECRET_KEY="your-secret"
export APCA_PAPER="true"              # true = paper, false = live
export PROXY_TOKEN="shared-secret"    # Phase 1 simple auth
```

**Base URLs (derived from APCA_PAPER):**
- Paper trading: `https://paper-api.alpaca.markets`
- Live trading: `https://api.alpaca.markets`
- Market data: `https://data.alpaca.markets`

---

## Plan Tracking

**Two sources, kept in sync:**
- `plan.md` — Human-readable plan with checkboxes, updated at every milestone
- MCP memory (tag: `alpaca-clj,plan`) — Machine-queryable, survives across sessions

**Update both when:** a phase changes status, a design decision is made, or an endpoint is completed.

---

## Dependencies

| Library | Purpose | Source |
|---|---|---|
| `org.httpkit/http-kit` | HTTP server + client | Maven |
| `cheshire/cheshire` | JSON parsing (Alpaca REST) | Maven |
| `com.github.franks42/cedn` | Canonical EDN | `../canonical-edn` or Maven |
| `com.github.franks42/stroopwafel` | Capability token auth | `../stroopwafel` (local) |
| `com.github.franks42/uuidv7` | UUIDv7 (replay protection, request-ids) | Maven |
| `com.taoensso/trove` | Structured logging | Maven |
| `com.taoensso/timbre` | Logging backend | Maven |

---

## Reference Projects

| Directory | Role |
|---|---|
| `../alpaca-py` | Alpaca Python SDK — endpoint signatures, models |
| `../alpaca-mcp-server` | 53 MCP tools — parameter/response shapes |
| `../alpaca-cli` | CLI patterns |
| `../bb-mcp-server` | http-kit server patterns, middleware, lifecycle |
| `../stroopwafel` | Authorization token API |
| `../canonical-edn` | CEDN library |

---

## Version Lookup Policy

**NEVER trust training data or web search snippets for library versions.**

When looking up the latest version of any library:
1. **Clojure/Java libs:** `WebFetch` directly on `https://clojars.org/<lib>/versions`
2. **GitHub projects:** `WebFetch` directly on `https://github.com/<org>/<repo>/releases`

---

## Refactoring

After any namespace rename, grep the entire codebase for remaining references to the old name before considering the task complete. Check: require calls, configuration files, build files, and tests.

---

## Memory Storage Habits

**After completing a task or phase:**
```
mcp__memory__memory_store
  content: "alpaca-clj: Completed <what>. Key changes: <files>. Decisions: <why>."
  metadata: {tags: "alpaca-clj,checkpoint,<topic>", type: "note"}
```

**After a design decision:**
```
mcp__memory__memory_store
  content: "alpaca-clj decision: <what>. Rationale: <why>."
  metadata: {tags: "alpaca-clj,decision,<topic>", type: "decision"}
```

**Update plan.md** at every milestone with current checkbox status.

---

## Language-Specific Notes

- **macOS:** Do NOT use `timeout` command (doesn't exist). Use Babashka timeout options instead.
- **Bash `!` escaping:** AI tool environments escape `!` in single-quoted strings. ALWAYS use double quotes for strings containing `!` (e.g., `swap!`, `reset!`).
- **Babashka reader conditionals:** `:bb` MUST come before `:clj` in `#?()` forms.

---

*Last Updated: 2026-03-18*
