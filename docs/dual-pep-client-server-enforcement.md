# Dual PEP: Client-Side and Server-Side Enforcement

> Every trust boundary needs its own enforcement point. Enforcement is
> always local to the party whose policy it enforces.
>
> This document extends the trust root model from `trust-roots-and-enforcement.md`
> to cover client-side policy enforcement, cross-company scenarios, and the
> architectural principle that both sides of a request have independent
> trust chains.

---

## The Missing Enforcement Point

The alpaca-clj proxy enforces server-side policy: does the requester's token
grant the required effect class and domain? But there is a symmetric question
on the client side that we have not addressed:

**Should the client send this request at all?**

This is not the same question as "is the client authorized to access the
resource." It is the question "does the client's own policy allow this
outbound action?" The answers come from different authorities, serve
different interests, and must be enforced independently.

---

## Why Client-Side Enforcement Exists

### Sensitive data in requests

An AI agent handling portfolio strategy might construct a request like:

```clojure
{:symbol "AAPL"
 :side   "buy"
 :qty    100
 :comment "Part of Project Phoenix rebalancing, client: Acme Corp"}
```

The proxy will happily forward this — the token grants `:write` on `"trade"`.
But the comment contains a client name and a project name. Company policy
might prohibit sending client-identifying information to external trading
platforms. The proxy can't enforce this because it doesn't know what's
sensitive — only the agent's own environment knows that.

### Approved destinations

An agent might be configured with multiple proxy endpoints:

```
proxy-paper.internal:8080      — paper trading (safe)
proxy-live.internal:8080       — live trading (dangerous)
proxy-market.vendor.com:443    — third-party market data
```

Company policy might say: "this agent may only use paper trading" or
"this agent may use vendor market data but not send order data to them."
These are outbound routing decisions that must be enforced before the
request leaves the agent.

### Data classification

Some request parameters might be classified at different levels:

```
Symbol (AAPL)          → public, can send anywhere
Order side (buy)       → internal, approved proxies only
Quantity (100)         → internal, approved proxies only
Strategy rationale     → confidential, never send externally
Client identity        → PII, never send in request parameters
```

The proxy doesn't know the classification. The agent does.

---

## The Dual PEP Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Client / AI Agent                                          │
│                                                             │
│  Trust root: the agent process itself                       │
│  Authority: company's outbound policy authority             │
│  Token: outbound capability token (what agent may send)     │
│                                                             │
│  ┌──────────────────────────────────┐                       │
│  │ Client-Side PEP                  │                       │
│  │                                  │                       │
│  │ Enforces:                        │                       │
│  │  - Approved proxy destinations   │                       │
│  │  - Data classification rules     │                       │
│  │  - Outbound content restrictions │                       │
│  │  - Operation routing policies    │                       │
│  │                                  │                       │
│  │ If deny → request never sent     │                       │
│  └──────────────┬───────────────────┘                       │
└─────────────────┼───────────────────────────────────────────┘
                  │ request passes client-side policy
                  │ agent signs envelope (method + path + body + audience + request-id)
                  ▼
┌─────────────────────────────────────────────────────────────┐
│  Server / Proxy                                             │
│                                                             │
│  Trust root: the proxy process itself                       │
│  Authority: resource owner's inbound policy authority       │
│  Token: inbound capability token (what requester may do)    │
│                                                             │
│  ┌──────────────────────────────────┐                       │
│  │ Server-Side PEP                  │                       │
│  │                                  │                       │
│  │ Enforces:                        │                       │
│  │  - Token signature verification  │                       │
│  │  - Requester binding (agent key) │                       │
│  │  - Effect class + domain checks  │                       │
│  │  - Replay protection             │                       │
│  │  - Envelope integrity            │                       │
│  │                                  │                       │
│  │ If deny → 401/403 returned       │                       │
│  └──────────────┬───────────────────┘                       │
└─────────────────┼───────────────────────────────────────────┘
                  │ request passes server-side policy
                  ▼
              Resource (Alpaca Markets)
```

Two enforcement points. Two trust roots. Two independent policy chains.
The client-side PEP protects the **agent's interests** (data doesn't leak,
destinations are approved). The server-side PEP protects the **resource
owner's interests** (only authorized operations execute).

Neither PEP trusts the other's enforcement. Each enforces independently.

---

## Cross-Company: Two Proxies, Two Authorities

When Company A's agent uses Company B's resources, both companies have
policies, and neither trusts the other to enforce theirs:

```
Company A's domain                    Company B's domain
─────────────────                     ─────────────────

┌───────────────┐                     ┌───────────────┐
│ A's Policy    │                     │ B's Policy    │
│ Authority     │                     │ Authority     │
│               │                     │               │
│ Issues:       │                     │ Issues:       │
│ outbound      │                     │ inbound       │
│ capability    │                     │ capability    │
│ token         │                     │ token         │
└───────┬───────┘                     └───────┬───────┘
        │                                     │
        ▼                                     ▼
┌───────────────┐                     ┌───────────────┐
│ A's Agent     │   request           │ B's Proxy     │
│ + PEP-A       │ ──────────────────► │ + PEP-B       │
│               │                     │               │
│ Enforces:     │                     │ Enforces:     │
│ "only approved│                     │ "A can only   │
│  vendors"     │                     │  read market  │
│ "no PII in    │                     │  data, no     │
│  requests"    │                     │  trading"     │
│ "only non-    │                     │ "rate limit   │
│  classified   │                     │  100 req/min" │
│  data"        │                     │               │
└───────────────┘                     └───────────────┘
```

**PEP-A enforces Company A's outbound policy:**
- Only interact with approved vendor endpoints
- Never send PII or classified data
- Only use pre-approved operation types

**PEP-B enforces Company B's inbound policy:**
- Company A is authorized for market data read only
- No trading operations permitted for external clients
- Rate limiting per client

These are independent policy domains. A's authority has no say over B's
access rules. B's authority has no say over A's data classification.
Each PEP enforces its own authority's policy.

---

## Client-Side Capability Token

The client-side PEP uses the same stroopwafel mechanism — just with
different facts:

```clojure
;; Company A's outbound policy authority issues this to the agent
(sw/issue
  {:facts [;; Approved proxy destinations
           [:approved-destination "proxy-paper.internal:8080"]
           [:approved-destination "proxy-market.vendor-b.com:443"]

           ;; What the agent may send to each destination
           [:outbound-allow "proxy-paper.internal:8080" :read "market"]
           [:outbound-allow "proxy-paper.internal:8080" :write "trade"]
           [:outbound-allow "proxy-market.vendor-b.com:443" :read "market"]

           ;; Data classification restrictions
           [:data-restriction :no-pii-in-params]
           [:data-restriction :no-strategy-in-comments]
           [:data-restriction :no-client-names]]}
  {:private-key (:priv company-a-authority)})
```

The agent's PEP evaluates before signing any request:

```clojure
;; Agent wants to send a market quote request to vendor B
(sw/evaluate outbound-token
  :authorizer
  {:facts [[:requested-destination "proxy-market.vendor-b.com:443"]
           [:requested-effect :read]
           [:requested-domain "market"]]
   :checks [{:id    :approved-dest
             :query [[:approved-destination "proxy-market.vendor-b.com:443"]]}
            {:id    :outbound-allowed
             :query [[:outbound-allow "proxy-market.vendor-b.com:443"
                       :read "market"]]}]})
;; => {:valid? true} → agent signs and sends

;; Agent wants to send a trade order to vendor B
(sw/evaluate outbound-token
  :authorizer
  {:facts [[:requested-destination "proxy-market.vendor-b.com:443"]
           [:requested-effect :write]
           [:requested-domain "trade"]]
   :checks [{:id    :outbound-allowed
             :query [[:outbound-allow "proxy-market.vendor-b.com:443"
                       :write "trade"]]}]})
;; => {:valid? false} → agent REFUSES to construct the request
```

The request never leaves the agent. No network call, no token presented,
no information leaked. The client-side PEP stopped it.

---

## Audience Binding: Connecting the Two PEPs

When the client-side PEP approves a request, the agent should bind the
signed envelope to the intended destination. This prevents a captured
request from being forwarded to a different proxy:

```clojure
;; The signed request envelope includes the intended audience
{:method     "post"
 :path       "/market/quote"
 :body       {:symbol "AAPL"}
 :request-id "019d0114-8fb5-7806-b96a-980f2ff0f51f"
 :audience   "proxy-paper.internal:8080"}      ;; ← binds to destination
```

The server-side PEP verifies:
1. Signature is valid (agent key)
2. Envelope matches actual request (method, path, body)
3. **Audience matches this proxy's identity** (prevents cross-proxy replay)
4. Request-id is fresh and not replayed

The audience field closes the gap between the two PEPs. The client-side PEP
decides "I will send to this proxy." The audience in the signed envelope
proves "this request was intended for this proxy and no other."

---

## The Three PEP Patterns

### Pattern 1: Single PEP (current alpaca-clj)

```
Agent → Proxy(PEP) → Resource
```

One enforcement point. Server-side only. Agent trusts proxy implicitly.
Sufficient for single-company, single-proxy, paper trading.

### Pattern 2: Dual PEP (next step)

```
Agent(PEP-client) → Proxy(PEP-server) → Resource
```

Two enforcement points. Agent enforces outbound policy before sending.
Proxy enforces inbound policy before forwarding. Independent trust chains.
Required when: agent handles sensitive data, multiple proxy destinations
exist, or company policy restricts outbound communication.

### Pattern 3: Chained PEP (cross-company)

```
Agent(PEP-A) → A's Proxy(PEP-A-outbound) → B's Proxy(PEP-B-inbound) → Resource
```

Three enforcement points. Company A enforces outbound through its own proxy.
Company B enforces inbound through its own proxy. Required when: companies
don't trust each other's enforcement, regulatory data boundaries exist, or
audit requirements demand independent enforcement.

---

## Same Mechanism, Different Facts

All three patterns use the same stroopwafel primitives:

| PEP | Token source | Facts checked | Enforcement action |
|---|---|---|---|
| Client-side | Company's outbound authority | Approved destinations, data restrictions | Refuse to sign/send |
| Server-side (proxy) | Resource owner's authority | Effect class, domain, agent binding | Return 401/403 |
| Cross-company proxy | Each company's authority | Outbound + inbound policies independently | Each proxy enforces its own |

No new code. No new protocol. The Datalog evaluator doesn't know or care
whether it's evaluating outbound or inbound policy — it just matches facts
and checks constraints. The difference is **which facts** are in the token
and **who issued** the token.

---

## The Trust Root at Each PEP

From `trust-roots-and-enforcement.md`: the trust root is the enforcement
actor, not the authority key. This applies to both PEPs:

**Client-side trust root:** the agent process itself
- Chooses to trust Company A's outbound policy authority
- Enforces: data classification, approved destinations, outbound restrictions
- Invariant: never send PII in request parameters (hardcoded, no token overrides)

**Server-side trust root:** the proxy process itself
- Chooses to trust the resource owner's inbound policy authority
- Enforces: token verification, effect classes, domains, replay protection
- Invariant: never execute without valid token (hardcoded, no override)

Each trust root is independent. Each chooses its own authority. Each
has its own hardcoded invariants that no token can override.

---

## Implementation Path

### Phase A: Audience binding (minimal, high value)

Add `audience` field to the signed request envelope. Proxy verifies
the audience matches its configured identity. Prevents cross-proxy
request replay. One field, one check.

### Phase B: Client-side PEP library

Extract the pattern into a reusable client-side enforcement module:

```clojure
(require '[alpaca.client-pep :as pep])

(pep/check-outbound
  outbound-token
  {:destination "proxy-paper.internal:8080"
   :method      :post
   :path        "/trade/place-order"
   :body        {:symbol "AAPL" :side "buy" :qty 100}})
;; => {:allowed true} or {:allowed false :reason "..."}
```

The agent calls this before signing and sending. If denied, the request
is never constructed.

### Phase C: Cross-company demo

Two proxy instances, two root keys, two authority scopes. Agent presents
Company A's outbound token to A's proxy, which verifies and forwards to
B's proxy with B's inbound token. Full dual-PEP chain demonstrated.

---

## Why This Matters for AI Agents

AI agents are uniquely dangerous outbound actors because:

1. **They process untrusted content** — market data, news, user prompts —
   that might influence what they put in requests
2. **They construct requests programmatically** — a prompt injection could
   cause the agent to include sensitive data in a trade comment or order
3. **They might be convinced to change destinations** — "actually, send
   this order to proxy-live instead of proxy-paper"
4. **They handle data from multiple classification levels** — portfolio
   strategy (confidential) alongside market prices (public)

The client-side PEP is the structural answer. It doesn't matter what the
LLM was convinced to do — the PEP is deterministic code that checks the
outbound request against policy before it leaves the process. The LLM
constructs the intent; the PEP gates the action.

This is the same principle as the server-side proxy: the LLM is always
the requester, never the enforcer. On both sides of the request.

---

*This is the symmetric completion of the trust model. The server-side PEP
protects resources. The client-side PEP protects the agent's interests.
Together they form a complete trust boundary around every request.*

---

*Document status: design reference.*
*Last updated: March 2026.*
*Related: `trust-roots-and-enforcement.md`, `authorization-progression.md`*
