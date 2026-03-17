# Alpaca × Stroopwafel — Capabilities-Based Trading Proxy

> Design document for a capability-enforced trading service in which AI agents
> interact with Alpaca Markets exclusively through a Stroopwafel-gated proxy.
> No agent holds an Alpaca API key or has direct API access.
>
> Status: architectural design phase. Not yet implemented.
> Predecessor documents: `clj-trading-interface.md`, `combining-capability-datalog-policy-language.md`

---

## 1. Motivation

The Alpaca MCP server (43 tools, MIT licensed) is an excellent API exploration
tool, but it is architecturally wrong for AI-driven trading. It grants an AI
assistant ambient authority over every Alpaca operation — place orders, cancel
orders, close all positions — with no capability gating, no audit trail, and
no enforcement boundary. The AI reasoning layer *is* the execution layer.

This is the exact pattern documented as structurally dangerous in
`combining-capability-datalog-policy-language.md`: absent Policy Enforcement
Point, confused deputy vulnerability, Simon Willison's lethal trifecta (private
portfolio data + untrusted market content + external trade execution) all
present simultaneously.

The proxy service described here is the structural answer. The MCP server is
suitable for development-time API exploration with paper keys. It is not in
the production architecture.

---

## 2. Architectural Layers

```
┌──────────────────────────────────────┐
│  AI Agent (Claude, etc.)             │
│  Holds: Stroopwafel capability token │
│  Can express only: Datalog predicates│
│  in the defined trading schema       │
└──────────────┬───────────────────────┘
               │ HTTP/socket request + token
               │ (cannot call Alpaca directly)
┌──────────────▼───────────────────────┐
│  clj-trading-service (the proxy)     │
│  ─────────────────────────────────── │
│  1. Verify token chain (Stroopwafel) │
│  2. Evaluate Datalog policy          │
│  3. Check effect class constraints   │
│  4. Consume linear capability if     │
│     applicable                       │
│  5. Log to immutable audit trail     │
│  6. Forward to Alpaca REST if allow  │
└──────────────┬───────────────────────┘
               │ Alpaca API key (never leaves service)
┌──────────────▼───────────────────────┐
│  Alpaca Markets (paper or live)      │
└──────────────────────────────────────┘
```

The Alpaca API key lives only in the service process. It is never present in
the AI agent's context. The AI cannot authenticate to Alpaca directly even if
it attempts to. The service is the sole trust boundary between probabilistic
reasoning and real trade execution.

### Why Service Shape, Not Library Shape

Two deployment options exist:

| Shape | Description | Verdict |
|---|---|---|
| **Library** | Stroopwafel evaluation in-process with AI runtime | Weaker — enforcement in caller's process; compromised AI runtime compromises enforcement |
| **Service** | Separate process; AI calls service, service calls Alpaca | Correct — independent process boundary is the real PEP |

For the threat model (AI agents as potentially compromised insiders), enforcement
in the caller's process is not a real boundary. The service shape is required.

---

## 3. The Predicate Schema as Data Dictionary

### The Key Insight

Alpaca's REST API surface is an unusually good guide to the trading predicate
schema. Unlike filesystem operations (unbounded resource space) or LLM tool
calls (unbounded intent space), trading operations are:

- **Finite** — a closed set of operation types
- **Typed** — each operation has a well-defined parameter signature
- **Effect-classifiable** — every operation fits cleanly into read/write/destroy
- **Domain-constrained** — assets, order types, and side values are enumerations

This means the Alpaca API endpoints can be read almost directly as a candidate
predicate vocabulary. The schema design work is a process of translating REST
endpoint signatures into Datalog predicate signatures, then deciding where
parameter constraints live.

### Proposed Effect Class Taxonomy

| Effect Class | Alpaca Operations | Risk Level |
|---|---|---|
| `read` | get_account, get_positions, get_orders, get_quote, get_bars, get_assets, get_clock | Low |
| `write` | place_order (market/limit/stop/options) | High |
| `write_multi_leg` | place_spread, place_straddle, place_condor | High + complex |
| `destroy` | cancel_order, close_position | High |
| `destroy_all` | cancel_all_orders, close_all_positions | Critical |

The lethal trifecta structural constraint from `combining-capability-datalog-policy-language.md`
applies directly: **no single token should be permitted to combine `destroy_all`
with `read` on portfolio data and `write` on orders.** That combination is
functionally ambient authority, regardless of how it is expressed in Datalog.

### Candidate Predicate Vocabulary (First Draft)

```datalog
; ── READ ──────────────────────────────────────────────────────────────
trading:account_info()
trading:positions()
trading:position(?symbol)
trading:orders(?status)          ; status ∈ #{open closed all}
trading:order(?order_id)
trading:quote(?symbol)
trading:bars(?symbol, ?timeframe, ?limit)
trading:asset(?symbol)
trading:clock()

; ── WRITE ─────────────────────────────────────────────────────────────
; Parameters carry constraints — not just operation names
trading:place_order(?symbol, ?side, ?type, ?qty, ?limit_price)
;   side       ∈ #{buy sell sell_short}
;   type       ∈ #{market limit stop stop_limit trailing_stop}
;   qty        — integer, bounded at token issuance
;   limit_price — decimal or nil for market orders

; Multi-leg (options)
trading:place_spread(?symbol, ?strategy, ?expiry, ?qty)
;   strategy   ∈ #{bull_call_spread bear_put_spread iron_condor straddle strangle}

; ── DESTROY ───────────────────────────────────────────────────────────
trading:cancel_order(?order_id)
trading:close_position(?symbol, ?qty_or_all)

; ── DESTROY_ALL (highest-privilege, separate effect class) ────────────
trading:cancel_all_orders()
trading:close_all_positions()
```

### What Belongs in Token Facts vs. Datalog Rules

This is the central schema design question. Two extremes:

```datalog
; Too coarse — ambient trading authority in a single fact
check("trading")

; Too granular — combinatorial explosion, unmanageable
check("place_order", "AAPL", "buy", "limit", 100, 185.00)
```

The right level is operation + constraint bounds, where bounds are integers
or enumeration values that can be compared in Datalog expressions:

```datalog
; Token fact: agent may buy equities, limit orders only, max 200 shares per order
trading:allow_order("buy", "limit", 200)

; Token fact: restricted to a specific symbol universe
trading:allowed_symbol("AAPL")
trading:allowed_symbol("MSFT")
trading:allowed_symbol("SPY")

; Datalog rule: order is valid if side/type/qty within granted bounds
; and symbol is in allowed set
valid_order(?sym, ?side, ?type, ?qty, ?price) <-
  trading:allow_order(?side, ?type, ?max_qty),
  trading:allowed_symbol(?sym),
  ?qty <= ?max_qty
```

This preserves composability without requiring per-order token issuance.

---

## 4. Temporal and Stateful Constraints

### Token-Level Temporal Bounds

Following the `t-issued`/`t-expires` as integral tuple fields principle:

```datalog
; Built into every token fact — not metadata
trading:valid_window("09:30", "16:00", "America/New_York")
trading:token_expires(1743638400)   ; unix epoch, checked at evaluation time
```

### Stateful Constraints Requiring External Facts

Some trading constraints cannot be evaluated from token facts alone — they
require current account state. These must be explicitly modeled as external
facts that the authorizer layer injects at evaluation time:

| Constraint | Required external fact | Notes |
|---|---|---|
| Daily loss limit | Current P&L for session | Service queries Alpaca account before evaluation |
| Position concentration | Current position sizes | Prevents over-concentration in single symbol |
| Buying power check | Available buying power | Prevents orders the account cannot fund |
| PDT rule compliance | Day trade count (rolling 5-day) | Regulatory; paper trading simulates this |

The pattern: before Datalog evaluation, the service fetches the minimal required
account state, converts it to Datalog facts, and passes them into the authorizer
context alongside the token. The Datalog rules then operate on this enriched
fact set.

```datalog
; Injected external fact (not from token)
account:daily_pnl(-125.50)
account:buying_power(8420.00)
account:day_trade_count(2)

; Token rule using external fact
valid_order_given_risk(?sym, ?side, ?type, ?qty, ?price) <-
  valid_order(?sym, ?side, ?type, ?qty, ?price),
  account:daily_pnl(?pnl),
  account:buying_power(?bp),
  ?pnl > -500.00,          ; daily loss limit from token
  ?bp > (* ?qty ?price)    ; can fund the order
```

This is a clean separation: the token carries the *policy* (max loss = $500),
the service injects the *current state* (actual P&L = -$125.50), Datalog
evaluates the combination deterministically.

---

## 5. Linear / Consumable Capabilities

Standard Datalog facts are persistent — once derived, they remain available
for repeated matching. Some trading capabilities should be consumed on use:

- A token authorizing exactly one market order to exit a position
- A token valid for a single options strategy execution
- A "fire once" stop-loss trigger

These are modeled as linear capabilities at the token/service layer — not in
Datalog itself (which has no native linear types), but enforced by the service:

```
On evaluation:
  1. Verify token is marked linear
  2. Check it has not been consumed (service-side state or revocation ID)
  3. Evaluate Datalog — if allow:
     4. Record consumption (add revocation entry or mark spent)
     5. Forward to Alpaca
  6. If token re-presented: reject at step 2
```

This maps directly to Stroopwafel's revocation ID machinery. A consumed linear
token is treated identically to a revoked token — a revocation entry exists for
its ID.

---

## 6. Multi-Leg Options

Options strategies (spreads, straddles, condors) are a single logical operation
composed of multiple API calls. Two design options:

**Option A — Atomic multi-leg token:** Token encodes the full strategy. Service
executes all legs or none (best-effort atomic). Simpler token model, harder
execution guarantee.

**Option B — Sequenced single-leg tokens with shared correlation ID:** Each leg
is a separate token. Tokens share a `correlation_id` fact. Service tracks
partial execution state. More complex token issuance, cleaner rollback story.

Recommendation for initial implementation: **Option A** for simplicity, with
a flag in the schema for strategy-level atomicity. Revisit Option B if partial
fill handling becomes a real requirement.

---

## 7. Token Issuance Ceremony

The proxy service trusts tokens signed by a known root key. The issuance flow
is where human intent is translated into a bounded capability grant:

```
Human operator:
  1. Decides: "I want the AI agent to be able to buy up to 100 shares of AAPL
     or MSFT with limit orders only, today only, stopping if daily loss > $200"
  2. Mints a Stroopwafel token encoding those constraints as Datalog facts
  3. Signs with root/admin key
  4. Hands token to AI agent session

AI agent:
  5. Presents token with each request to the proxy service
  6. Service verifies chain, evaluates Datalog, injects account state
  7. Allow/deny — no appeal path

Attenuation (optional):
  8. AI agent may further attenuate the token before delegating to a sub-agent
     (e.g., strip the buy capability, keep read-only) — Stroopwafel block model
```

The issuance ceremony is where the "insider threat with a well-scoped job
description" metaphor is operationalized. The human explicitly describes the
job description before handing over the credential.

---

## 8. Schema Governance

Following the slow/fast separation from `combining-capability-datalog-policy-language.md`:

| Layer | Change velocity | Governance |
|---|---|---|
| **Predicate schema** (what operations exist, what effect classes they carry) | Slow | Human review + sign-off; versioned artifact |
| **Token policy** (Datalog rules within schema) | Medium | Policy authority, audited |
| **Token facts** (per-session capability assertions) | Fast | Token issuance process |
| **Injected account state** | Per-request | Service fetches from Alpaca |

The schema is the real security boundary. Adding a new predicate (e.g.,
`trading:transfer_funds`) to the schema is a high-governance event.
Writing a new policy rule within the existing predicate space is medium.
Issuing a new token for a specific agent session is routine.

### Adversarial Schema Review Process

Before any schema version is trusted in production:

1. Human designer produces candidate schema
2. A separate LLM session (not the one that participated in design) audits
   for emergent over-expressiveness: "given these predicates, what combinations
   could approximate ambient authority?"
3. Automated expressiveness testing: enumerate predicate combinations, check
   for compositions that violate intent
4. Human sign-off on schema version

The conflict-of-interest constraint is hard: the LLM that helped design the
schema should not be the one that audits it.

---

## 9. Open Design Questions

These require explicit resolution before implementation can proceed:

**Q1 — Relative vs. absolute constraints.**
"No single order may exceed 5% of portfolio value" requires computing 5% of
current portfolio during evaluation. How is current portfolio value injected
as a Datalog fact without the service needing to make a blocking Alpaca call
on every request? Options: (a) service caches account state with TTL, (b)
token carries absolute dollar limit computed at issuance time, (c) separate
pre-flight "compute limits" step before token evaluation.

**Q2 — Options multi-leg atomicity.**
Alpaca's REST API does not guarantee multi-leg atomicity across separate order
calls. If leg 2 of a spread fails after leg 1 executes, the service must
either handle rollback (cancel leg 1) or accept the partial state. What is
the service's responsibility here?

**Q3 — WebSocket streaming and authorization.**
Real-time order/account update streams (WebSocket) operate on a persistent
connection, not a per-request model. Capability token validation on stream
subscription vs. per-message evaluation — which model? Token validity at
connection time seems insufficient if the token expires or is revoked
mid-stream.

**Q4 — Paper-to-live promotion.**
The proxy service should make paper-to-live promotion a conscious, governed
act — not just a URL swap. What ceremony or configuration change should be
required to activate a live key? Options include separate token fact
(`trading:env("live")`), separate service configuration, or explicit
human-in-the-loop confirmation step.

**Q5 — Audit log trust.**
The audit log must be independent of the AI agent and ideally tamper-evident.
Append-only log with cryptographic chaining (each entry signed with the
service key, chained to previous entry hash) would satisfy this. What is the
minimal viable audit log format for a first implementation?

---

## 10. Recommended Implementation Sequence

1. **Define and lock schema v0.1** — read-only operations only (`get_quote`,
   `get_positions`, `get_account`, `get_clock`). No write predicates. Verify
   the proxy service architecture with zero financial risk.

2. **Add `write` predicates with hard bounds** — `place_order` with
   symbol allow-list, side/type constraints, and max quantity. Test against
   paper trading with deliberately tight limits.

3. **Add temporal constraints** — `t-expires`, market hours window, daily
   loss limit (requires account state injection).

4. **Add linear capability support** — consumable single-use tokens. Verify
   revocation ID machinery handles consumed tokens correctly.

5. **Add `destroy` predicates** — cancel and close operations. These require
   the most conservative approach: explicit order ID in token facts, no
   "cancel anything" wildcard in v1.

6. **Adversarial schema review** — before any `destroy_all` predicate is
   added, external LLM audit + automated expressiveness testing.

7. **Options / multi-leg** — deferred to later phase; requires Q2 resolution.

---

## 11. Relation to Existing Work

| System | Relationship |
|---|---|
| **Stroopwafel v0.6.0** | Core token machinery — block isolation, Datalog evaluation, revocation IDs, CEDN serialization all directly used |
| **clj-trading-interface.md** | API research — Alpaca endpoint surface, cost structure, paper trading workflow |
| **combining-capability-datalog-policy-language.md** | Architectural principles — three-layer model, effect class taxonomy, schema governance, insider threat framing |
| **Alpaca MCP server** | Development tool only — for API exploration with paper keys, not in production architecture |
| **Biscuit spec** | Token format reference — Stroopwafel maintains compatibility |

---

*Document status: architectural design, pre-implementation.*
*Last updated: March 2026.*
*Next action: resolve Open Design Questions 1–4, then begin schema v0.1 definition.*
