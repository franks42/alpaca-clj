# Action-Oriented Interface Patterns: Clojure, CLI, REST, and MCP

> A pattern observation on interface design for LLM-driven systems, distilled from
> work on the Alpaca × Stroopwafel trading proxy (March 2026).
> Related documents: `alpaca-stroopwafel-capabilities.md`,
> `combining-capability-datalog-policy-language.md`

---

## The Core Observation: Four Syntaxes, One Semantic Structure

Consider placing a stock order. Expressed in four different interface forms:

```clojure
;; Clojure function call
(trade/place-order {:symbol "AAPL" :side :buy :type :limit :qty 100 :price 185.00})
```

```bash
# CLI (bb task)
bb trade place-order --symbol AAPL --side buy --type limit --qty 100 --price 185.00
```

```
# HTTP GET (action-oriented)
GET /trade/place-order?symbol=AAPL&side=buy&type=limit&qty=100&price=185.00
```

```datalog
;; Datalog predicate
trading:place_order("AAPL", "buy", "limit", 100, 185.00)
```

These are not four different interfaces. They are **four syntactic projections of
one semantic structure**: a named function applied to named arguments. The intent
is identical. The representation differs only in the punctuation rules of each
notation.

---

## Why This Matters for LLM-Driven Systems

### Intent Expression as a Single Compositional Unit

LLMs work best when an intent can be expressed and inspected as a single
cohesive expression — one thing the LLM constructs, reads back, and reasons
about before executing. All four forms above satisfy this. What breaks the
pattern is interfaces that **split intent across multiple structural elements**:

```
MCP JSON-RPC:   method: "tools/call"        ← element 1
                tool:   "place_stock_order"  ← element 2
                params: { ... json ... }     ← element 3

Standard REST:  verb:   POST                ← element 1
                url:    /v2/orders           ← element 2
                body:   { ... json ... }     ← element 3
```

Both MCP and standard REST require the LLM to hold a coherent intent across
three separately constructed elements. This is not a fundamental protocol
limitation — it is a consequence of resource-oriented design, where the URL
names a *resource* rather than an *operation*.

Action-oriented design names the operation in the URL itself:

```
GET /trade/place-order?...
```

The intent is in the path. The arguments are in the query string. One string,
one construction, one inspection point.

### Training Signal Density

The LLM's internal model of "how to correctly express an operation" reflects
the density and consistency of that pattern in training data:

| Form | Training signal | Notes |
|---|---|---|
| Clojure function call | Very high | Function application is fundamental |
| CLI / bb task | Very high | Shell commands in every README, tutorial, man page |
| HTTP GET with query params | High | curl examples pervasive in documentation |
| Action-oriented REST | High | Once the pattern is understood |
| Standard REST POST+body | Medium | LLM must know resource→operation mapping |
| MCP JSON-RPC | Lower | Newer, sparser in training data |

The CLI and HTTP GET forms benefit from overlapping with the most common
patterns the LLM has encountered: shell commands and curl examples.

### The curl Isomorphism

A curl command *is* a CLI command is *is* an HTTP GET request:

```bash
# These express the same intent in the same structural form
bb trade place-order --symbol AAPL --side buy --type limit --qty 100 --price 185.00

curl "http://localhost:8080/trade/place-order?symbol=AAPL&side=buy&type=limit&qty=100&price=185.00" \
  -H "Authorization: Bearer <token>"
```

The bb task is a thin wrapper that constructs the same query string the HTTP
interface accepts. They share not just implementation but interface design.
A system designed with this isomorphism in mind can be invoked identically by
a human at a terminal, an LLM constructing a curl call, or a programmatic
client building a URL.

---

## The Datalog Schema as Canonical Source of Truth

The most significant implication of this isomorphism: if the Datalog predicate
schema is designed first, all other interface representations are **mechanical
derivations** from it.

```
Datalog predicate schema
    │
    ├── Clojure function signatures    (same names, same argument structure)
    ├── bb task definitions            (flags mirror predicate arguments)
    ├── HTTP route + query params      (path mirrors predicate name, params mirror arguments)
    └── MCP tool definitions           (tool name + input schema mirror predicate)
```

The schema is not just the security boundary (as documented in
`combining-capability-datalog-policy-language.md`). It is the **single source
of truth from which all interface representations are derived**. The hard
intellectual work — what operations exist, what their parameters are, what
constraints they carry — is done once. Everything else is projection.

This also means:
- Adding a new operation means adding a predicate to the schema, then deriving
  the four interface forms. No independent interface design decisions.
- Removing an operation from the schema removes it from all interfaces
  simultaneously.
- The LLM working with any interface form is always reasoning about the same
  underlying predicate, just in different syntax. There is no semantic gap to
  cross — only a syntactic one. Intent translation is natural because the
  intent vocabulary is the same regardless of surface.

---

## Action-Oriented vs. Resource-Oriented Design

Standard REST convention is resource-oriented: URLs name resources, HTTP verbs
name the action class. This creates a vocabulary mismatch for LLMs:

```
DELETE /v2/positions/{symbol}   ← Does "delete" mean "close"? "cancel"? "liquidate"?
POST   /v2/orders               ← What kind of order? The URL gives no hint.
```

Action-oriented URLs make the operation name explicit in the path:

```
GET /trade/close-position?symbol=AAPL
GET /trade/place-order?symbol=AAPL&side=buy&...
GET /trade/cancel-order?order-id=9b2c
GET /market/quote?symbol=AAPL
GET /account/buying-power
```

The URL path *is* the function name. The LLM does not need to know that
"closing a position" maps to `DELETE /v2/positions/{symbol}` — the mapping
is encoded in the URL. This is RPC-over-HTTP rather than REST, which is
unfashionable in API design circles but **semantically honest** about what
is actually happening in an operation-driven system.

### The GET-for-Mutations Question

Using GET for mutating operations (place order, cancel order) is technically
incorrect by HTTP semantics: GET should be idempotent and safe. This matters
in systems with CDN caching, intermediate proxies, and public clients.

For a **local capability-gated service called by an AI agent**, these concerns
are substantially reduced:
- No CDN or intermediate caches
- Access logs are the intended audit trail (GET appears in logs — that is the goal)
- Idempotency is enforced at the capability token layer, not the HTTP verb layer

If correct HTTP semantics matter (public API, infrastructure with caching,
future extensibility), use POST with query-string parameters and minimal or
empty body. This preserves single-string constructability while being
technically well-formed:

```bash
curl -X POST "http://localhost:8080/trade/place-order?symbol=AAPL&side=buy&type=limit&qty=100&price=185.00" \
  -H "Authorization: Bearer <token>"
```

---

## Token / Capability Passing by Interface

Each interface form has a natural, idiomatic place for the capability token:

| Interface | Token placement | Notes |
|---|---|---|
| Clojure function | Thread-local context, or explicit map arg | Internal call — token in call context |
| CLI / bb task | `STROOPWAFEL_TOKEN` env var | Standard credential practice; out of shell history |
| HTTP GET/POST | `Authorization: Bearer <token>` header | Standard; not in URL; not in logs |
| MCP | Extra tool parameter or HTTP header | Less standardized; header preferred |

For CLI/bb, the env var approach means the AI agent's session is initialized
with a token once, and every subsequent command inherits it without the agent
needing to manage it explicitly. The token lifecycle is external to the
individual command.

---

## Interface Priority for LLM-Driven Systems

Given the above, a pragmatic ordering when building an LLM-callable service:

1. **Define the Datalog predicate schema** — this is the interface design work.
   Everything else derives from it.

2. **Clojure implementation** — the predicate vocabulary maps directly to
   function signatures. PEP lives here. This is the shared core.

3. **bb tasks** — thin wrappers over the Clojure implementation. One task per
   predicate. Primary LLM interface. Fastest to build, best LLM ergonomics,
   natural for iterative development.

4. **HTTP interface** — action-oriented routes, query-string parameters, bearer
   token auth. Low cost to add given the shared implementation. Enables
   programmatic clients and is callable via curl (same ergonomics as bb for LLMs).

5. **MCP interface** — optional, derived mechanically from the same predicate
   schema. Add only if a specific MCP client integration use case requires it.
   Not the primary LLM interface.

---

## Audit Trail as a Free Bonus

The CLI/HTTP GET shape produces audit log entries that are human-readable
*and* machine-parseable without reconstruction:

```
[2026-03-16T14:32:11Z] GET /trade/place-order?symbol=AAPL&side=buy&type=limit&qty=100&price=185.00
  token-revocation-id: 7f3a...
  principal: agent-session-42
  authorized-by: human-operator
  alpaca-order-id: 9b2c...
  result: accepted
```

The exact operation the AI executed, with all parameters, *is* the log entry.
No reconstruction, no separate audit event schema. Combined with the Stroopwafel
token chain (which carries the principal identity and grant chain), this falls
out naturally from the interface design without additional instrumentation.

---

## Summary

| Principle | Implication |
|---|---|
| Action-oriented naming | Operation intent explicit in path/name; no resource→verb mapping required |
| Single-string constructability | LLM reasons about intent holistically; no multi-part assembly |
| Clojure fn ≅ CLI ≅ HTTP GET ≅ Datalog predicate | Same semantic structure, four syntactic projections |
| Datalog schema as canonical source | Design once; derive all interface forms mechanically |
| Schema = interface definition | Predicate vocabulary defines the expressible space across all interfaces |

The practical design rule: **design the interface as if designing Clojure
function signatures and CLI flags first. Let the HTTP routes and MCP tool
definitions follow mechanically.** The LLM ergonomics follow from the same
design choices that make the Clojure API clean.

---

## MCP and the Wrong Abstraction

The patterns above converge on a critique of MCP that is worth stating directly.

### What MCP Actually Is

MCP is JSON-RPC with a discovery protocol. A server declares named tools with
JSON Schema parameter definitions; a client calls them by name with a JSON
argument object. The "protocol" is mostly the handshake, capability negotiation,
and transport (stdio or HTTP/SSE). JSON-RPC was a pragmatic choice — widely
understood, library support in every language, familiar to anyone who has worked
with REST APIs.

But it inherited the **resource/method split** from that world, and that split
is exactly what creates friction for LLMs.

### The Specific Failure: Data Assembly vs. Expression

MCP treats tool invocation as **data assembly**: construct a JSON object
matching a schema. The JSON Schema parameter definition is there to tell the
LLM what to construct — it is compensating for the fact that the interface is
not self-describing in the way a function signature is.

```
MCP:      here is a JSON Schema describing the shape of data you must
          assemble to invoke this operation

Function: place-order [symbol side type qty price]
```

The function signature *is* the interface. The JSON Schema is a description
of an interface that is not directly expressible — a layer of indirection
where the call itself would suffice.

The JSON Schema compensation also fails in a subtle way: it describes
*structure* but not *semantics*. It can say `qty` is an integer, but it
cannot say `qty` is bounded by the token's capability grant. The schema and
the policy remain separate things — which is precisely the problem a
capability system like Stroopwafel solves at the authorization layer.

### What a Better Abstraction Might Have Been

Two cleaner primitives were available:

**Functions as the primitive.** If the primitive were a function signature
rather than a JSON Schema, the LLM would construct a function call, not a
data structure. This is what nREPL does for Clojure tooling, what LSP does
for code intelligence, what gRPC does with Protobuf service definitions.
The function *is* the interface — no separate schema layer required.

The likely reason this was not chosen: JSON Schema is language-neutral;
function signatures are not. Polyglot compatibility was prioritized.
The cost of that neutrality is the friction identified here.

**Action-oriented URLs as the primitive.** The interface form this document
converges on from first principles — action-oriented URLs with typed query
parameters — is essentially just HTTP. Servers declare operations as URL
patterns. Clients invoke them with GET or POST. Discovery is an OpenAPI
document. The LLM already knows this pattern from its training data.
The "MCP client" becomes an HTTP client; the transport problem is solved
by thirty years of HTTP infrastructure.

The likely reason this was not chosen: Anthropic wanted a **stateful session**
model — the ability to sample from the LLM mid-execution, maintain context
across calls, support streaming responses. HTTP is stateless by default;
SSE gives streaming but not bidirectional flow. The JSON-RPC session model
was chosen to get those statefulness properties cleanly.

### The Honest Assessment

MCP made the right tradeoffs for the problem it was actually solving:
**getting heterogeneous tools callable from heterogeneous LLM clients with
minimal friction for tool authors.** JSON Schema is easy to write, every
language has libraries for it, and the protocol works.

The cost was paid by the LLM *callers*, not the tool authors. The LLM must
assemble a JSON object from a schema description rather than calling a
function. For simple tools this is acceptable. For a capability-gated system
where the interface vocabulary *is* the policy vocabulary, it introduces
exactly the indirection you want to eliminate.

The deeper lesson: **MCP optimized for tool-author ergonomics and polyglot
neutrality. A system designed specifically for LLM callers and a single
implementation language can make different tradeoffs and recover a cleaner
interface.** The generality of MCP is genuinely valuable at ecosystem scale.
At the scale of one well-designed service, it is unnecessary overhead.

The function-call abstraction — which Clojure, CLI, action-oriented HTTP, and
Datalog predicates all share — is the right primitive for LLM-callable
interfaces. MCP's JSON Schema layer is a workaround for not having that
primitive natively. When you control both ends of the interface, you can
drop the workaround.

---

## EDN End-to-End: Eliminating the Last Translation Layer

The action-oriented HTTP interface described above still has one residual
translation: the LLM reasons in Clojure data structures but must express
parameters as HTTP query strings.

```clojure
;; LLM reasons in:
{:symbol "AAPL" :side :buy :type :limit :qty 100 :price 185.00M}

;; must transliterate to:
?symbol=AAPL&side=buy&type=limit&qty=100&price=185.00
```

Keywords become strings. The Clojure type vocabulary — keywords, symbols,
sets, vectors, decimals — flattens to an impoverished string representation.
This transliteration is friction, and it is unnecessary if both ends speak
EDN natively.

### The Zero-Translation Form

POST with `Content-Type: application/edn`, body as a Clojure map:

```bash
POST /trade/place-order
Content-Type: application/edn
Authorization: Bearer <token>

{:symbol "AAPL" :side :buy :type :limit :qty 100 :price 185.00M}
```

The LLM constructs a Clojure map — the form it is already reasoning in —
and sends it directly. The server reads EDN, destructures into function
arguments, executes, returns EDN. No translation in either direction.
The full Clojure type vocabulary is now available on the wire:

```clojure
{:side :buy}              ; keyword, not string "buy"
{:type :stop-limit}       ; keyword with hyphen — not clean in query params
#{:AAPL :MSFT :SPY}       ; symbol set — not expressible in query params at all
{:price 185.00M}          ; BigDecimal literal, not float
```

### The Four Forms Are Now Fully Isomorphic

```clojure
;; Clojure function call (in-process)
(trade/place-order {:symbol "AAPL" :side :buy :type :limit :qty 100 :price 185.00M})

;; CLI / bb task (human interface)
bb trade place-order --symbol AAPL --side buy --type limit --qty 100 --price 185.00

;; HTTP POST + EDN body (LLM-native interface)
POST /trade/place-order  body: {:symbol "AAPL" :side :buy :type :limit :qty 100 :price 185.00M}

;; Datalog predicate (authorization layer)
trading:place_order("AAPL", "buy", "limit", 100, 185.00)
```

The HTTP/EDN form is now structurally identical to the internal Clojure call.
The only difference is the transport wrapper. The LLM reasons, constructs,
and receives responses in a single language throughout, with no intermediate
format to translate to or from.

### The Request IS the Capability Claim

The EDN map the LLM sends maps directly onto the Datalog fact the token must
authorize:

```clojure
;; request body
{:symbol "AAPL" :side :buy :type :limit :qty 100 :price 185.00M}

;; Datalog authorization fact (mechanical translation)
trading:place_order("AAPL", "buy", "limit", 100, 185.00)
```

The server's authorization step is: read EDN body → translate to Datalog fact
→ evaluate against token. The translation is trivial — just reading the route
name and argument map. The request and the capability claim are the same
structure in two notations. Intent, authorization, and execution share a
single data representation throughout.

---

## fn-as-URL-Endpoint: Structural Enforcement Over Explicit Whitelisting

Having established EDN as the body format, the choice of what goes in the URL
path matters for a security-relevant reason, not just ergonomics.

### The Generic Endpoint Problem

A single `/invoke` endpoint accepting arbitrary EDN expressions:

```clojure
POST /invoke
body: (trade/place-order {:symbol "AAPL" ...})
```

...requires an explicit whitelist check in application code:

```clojure
(defn invoke-handler [request]
  (let [expr (edn/read-string (slurp (:body request)))
        fn-sym (first expr)]
    (if (contains? allowed-fns fn-sym)   ; ← must be maintained separately
      (dispatch expr)
      (deny))))
```

This is a **second source of truth**: the allowed function set must be kept in
sync with the actual implementation. It is also structurally a weaker
guarantee — the whitelist is a runtime check, not an architectural constraint.
It is the equivalent of an `allow`/`deny` policy check rather than
inexpressibility.

### fn-as-URL Makes the Router the Whitelist

With the function name in the URL path:

```clojure
(defroutes trading-routes
  (POST "/trade/place-order"    req (handle-place-order    req))
  (POST "/trade/cancel-order"   req (handle-cancel-order   req))
  (POST "/trade/close-position" req (handle-close-position req))
  (GET  "/market/quote"         req (handle-quote          req)))
```

The routing table *is* the whitelist. A function name not in the routing table
returns 404 before any application logic runs. The router enforces the closed
vocabulary **structurally**, at the infrastructure layer, before the token is
evaluated and before any business logic executes.

This directly mirrors the Datalog schema argument from
`combining-capability-datalog-policy-language.md`: the predicate schema makes
operations inexpressible rather than merely denied. The routing table does
the same thing at the HTTP layer. The router, the predicate schema, and the
Datalog authorizer form a **layered inexpressibility stack**:

```
Request for unknown fn  → 404 from router          (infrastructure layer)
Request with wrong verb → 405 from router          (effect class layer)
Request without token   → 401 from auth middleware (identity layer)
Token lacks capability  → 403 from Datalog eval    (policy layer)
```

Each layer is a structural constraint, not a policy check. Policy is only
evaluated for requests that have passed all structural filters.

### Routing Table and Predicate Schema as Co-Derived Artifacts

The routing table and the Datalog predicate schema are now two representations
of the same thing — the closed vocabulary of expressible operations. They
should be kept explicitly in sync, ideally derived from the same source:

```clojure
;; Single source: predicate schema definition
(def trading-schema
  [{:pred 'trade/place-order  :effect :write   :route "/trade/place-order"}
   {:pred 'trade/cancel-order :effect :destroy :route "/trade/cancel-order"}
   {:pred 'market/quote       :effect :read    :route "/market/quote"}])

;; Router derived from schema
(defroutes app-routes
  (for [{:keys [route handler]} (derive-routes trading-schema)] ...))

;; Datalog predicates derived from schema
(def predicates (derive-predicates trading-schema))
```

One definition, two structural artifacts. The schema is the source; the router
and the predicate set are its projections.

---

## Canonical EDN (CEDN) as a Required Primitive

For reads/queries using GET with the EDN map as a query parameter, HTTP
infrastructure caching becomes available — but only if the cache key is stable.

The URL (including query parameters) is the cache key for every standard
HTTP caching layer — browser cache, reverse proxy, CDN. If two semantically
identical requests produce different URL bytes, they get different cache
entries. This is a correctness bug, not just an efficiency loss:

```
# Same intent, different map key orderings — cache miss on second request
GET /market/quote?p=base64({:symbol "AAPL" :timeframe :1D :limit 10})
GET /market/quote?p=base64({:timeframe :1D :symbol "AAPL" :limit 10})
```

Standard EDN gives no ordering guarantees on map keys. The LLM constructing
the request might produce either form, or a third.

**Canonical EDN (CEDN) fixes this.** Given the same data structure, CEDN
always produces the same byte sequence — sorted keys, normalized whitespace,
canonical number forms. The cache key is stable across callers, sessions,
and LLM instances:

```
GET /market/quote?p=base64(cedn({:limit 10 :symbol "AAPL" :timeframe :1D}))
                                  ^^ keys always sorted alphabetically
```

### CEDN Is Load-Bearing in Three Places

Canonicality is not an isolated requirement. It is required at every layer
where byte-level identity of a data structure matters:

| Layer | Why CEDN is required |
|---|---|
| HTTP GET cache key | Identical intent → identical URL bytes → cache hit |
| Stroopwafel token signing | Identical token content → identical signature → verifiable |
| Datalog fact matching | Identical parameters → same fact → correct policy evaluation |

All three require the same property: a given data structure always serializes
to the same bytes. CEDN is already the answer Stroopwafel adopted for token
signing. Using it as the HTTP GET parameter encoding means the **same
canonicalization guarantees that make tokens verifiable also make cache keys
stable and Datalog facts matchable**. One mechanism, three enforcement points.

### The Transport Boundary Responsibility

The LLM should not be required to implement CEDN. The correct division of
responsibility:

```
LLM:       reasons in and constructs EDN maps (native form)
bb task:   applies CEDN serialization → base64 → URL parameter
HTTP POST: no encoding needed — EDN body is already the canonical form
           (map key ordering irrelevant; body is not a cache key)
```

For GET requests, the bb task wrapper handles canonicalization at the
transport boundary. The LLM expresses intent as a Clojure map; the bb task
encodes it correctly. For direct HTTP calls without bb mediation, a client
library applies CEDN before encoding.

This is the same separation of concerns that already exists in Stroopwafel
between its public API and internal CEDN serialization: the caller works with
Clojure data structures; canonicalization is an implementation detail of the
transport layer.

---

## The Complete Picture

The full design, from first principles to implementation:

```
LLM client
  reasons in EDN / Clojure data structures throughout
  expresses intent as: {:symbol "AAPL" :side :buy ...}
  GET (query): bb task applies CEDN → base64 → stable cache key
  POST (mutation): sends EDN body directly, no encoding needed

HTTP layer
  GET  → safe, idempotent, cacheable (read / :read effect class)
  POST → non-idempotent, not cached  (write/destroy effect classes)
  fn-as-URL-endpoint: router IS the structural whitelist
  unrecognized fn → 404 before any application logic

Authorization layer
  EDN body → mechanical translation → Datalog fact
  token evaluated against fact
  HTTP verb pre-checked against effect class before Datalog eval

Implementation layer
  fn-as-URL maps to Clojure fn with same name
  EDN body destructures directly to fn arguments
  result returned as EDN — zero translation in either direction

Caching layer
  GET + CEDN-encoded params → stable URL → correct infrastructure caching
  POST responses cached at application level, keyed on EDN map hash
```

The routing table, the predicate schema, the Datalog authorizer, the Clojure
function signatures, and the bb task definitions are all projections of one
underlying artifact: the operation vocabulary. Design that vocabulary once.
Everything else is mechanical derivation.

---

## HTTP Caching Patterns for an EDN Proxy

### Fundamentals

The cache key is the **URL + selected request headers** (via `Vary`). The
two rules that govern everything:

- `GET` responses — cacheable by infrastructure if the server sends
  `Cache-Control` or `Expires`
- `POST` responses — never cacheable by infrastructure, regardless of headers

The cache never sees a POST body. So infrastructure caching (CDN, reverse
proxy, browser) only activates for GET requests with stable URLs. For an
internet-facing proxy where LLM clients send plain unordered EDN bodies,
this creates a gap: the client cannot be relied on to produce canonical URLs,
but infrastructure caching requires them.

The redirect pattern closes this gap.

---

### The POST → 303 See Other → Canonical GET Pattern

`303 See Other` is the HTTP status code specifically designed for the
POST → cacheable GET transition. The server receives an unordered POST body,
canonicalizes it internally, and redirects the client to the stable GET URL:

```
1. Client  →  POST /market/quote
              Content-Type: application/edn
              body: {:symbol "AAPL" :timeframe :1D :limit 10}
              (plain EDN, unordered, from LLM — not a stable cache key)

2. Server  →  303 See Other
              Location: /market/quote?p=<cedn-base64({:limit 10 :symbol "AAPL" :timeframe :1D})>
              (server canonicalized, constructed stable URL)

3. Client  →  GET /market/quote?p=<stable-cedn-key>
              (HTTP clients follow 303 automatically, method changes to GET)

4. Cache   →  HIT if seen before → return cached response
              MISS → forward to server

5. Server  →  200 OK
              Cache-Control: max-age=5
              Content-Type: application/edn
              body: {:symbol "AAPL" :bid 184.92M :ask 184.95M :timestamp ...}
```

On the second request for the same symbol/params from any client, step 4
is a cache hit and step 5 never executes. The LLM client never knew about
CEDN. The server owns canonicalization entirely. Infrastructure caching
works correctly. The `303` semantics are precise: "your POST was received;
the resource you asked about lives at this GET URL." Every HTTP client
follows 303 correctly and changes method from POST to GET on the redirect.

---

### The "Latest → Versioned" Variant

A sharper version of the same pattern: the POST resolves an unstable
reference ("give me the latest quote") to an immutable one (the quote at
a specific timestamp), then redirects to the immutable URL which can be
cached permanently:

```
Client  →  POST /market/quote
           body: {:symbol "AAPL"}         ; unstable — "latest"

Server  →  303 See Other
           Location: /market/quote?symbol=AAPL&t=2026-03-17T14:32:00Z
           (server resolved "latest" to a specific timestamp)

Client  →  GET /market/quote?symbol=AAPL&t=2026-03-17T14:32:00Z

Cache   →  immutable — cache forever
           Cache-Control: max-age=31536000, immutable
```

A quote at a specific timestamp never changes. The POST/redirect pattern
moves clients from the unstable reference ("latest") to the stable one
("as of T"), after which the stable form can be cached indefinitely.

This pattern is used in package registries (resolve `/package/latest` →
`/package/1.2.3`), content delivery (resolve `/article/current` →
`/article/<content-hash>`), and some financial data APIs.

For the trading proxy, the natural application by resource type:

| Resource | Cache TTL | Notes |
|---|---|---|
| Market data at specific timestamp | Immutable / forever | Use Latest→Versioned redirect |
| Latest quote | 1–5 seconds | Short TTL GET, or POST→303→timestamped GET |
| Historical bars | Immutable once market closed | Aggressive caching after close |
| Account state, positions | No-cache or very short | Changes on every trade |
| Order status | No-cache until terminal | Cache permanently once filled/cancelled |

---

### Authorization and Caching: The Vary Problem

A cache in front of the authorization layer creates a security risk: a
cached response authorized for one token could be served to a different
token. `Vary: Authorization` addresses this by making the Authorization
header part of the cache key — different tokens get different cache entries:

```
Cache-Control: max-age=5
Vary: Authorization
```

For market data this is unnecessarily conservative — a quote for AAPL is
the same bytes regardless of which authorized client requested it.
Authorization just gates access to the data; it does not change the data.

The correct architecture:

```
Request → auth check (proxy, before cache) → application cache (keyed on CEDN params only)
```

Authorization happens once per request at the proxy. The cache is keyed
purely on data identity — the CEDN-encoded parameters. This is both
correct (no cross-token data leakage) and efficient (one cache entry per
unique query regardless of how many clients request it).

For the POST→303→GET redirect pattern, authorization is checked on the
POST (step 2). The redirect target GET (step 3 onward) can be served
from infrastructure cache without re-authorization, because the proxy
only issues the redirect after confirming the token is valid.

---

### Summary of Caching Patterns

| Client type | Request form | Caching |
|---|---|---|
| Internal / bb | GET + CEDN params (client canonicalizes) | Infrastructure + application |
| External LLM | POST + plain EDN body | Application only (server-side) |
| External LLM, caching wanted | POST + plain EDN → 303 → canonical GET | Infrastructure caching via redirect |
| "Latest" queries | POST → 303 → timestamped GET | Immutable cache on redirect target |

The POST → 303 → canonical GET redirect is the complete solution for
external LLM clients: the client sends plain EDN with zero knowledge of
CEDN, the server handles canonicalization at the redirect boundary, and
infrastructure caching works correctly from that point forward.

---

*Distilled from Alpaca × Stroopwafel trading proxy design work, March 2026.*
*Related: `alpaca-stroopwafel-capabilities.md`, `combining-capability-datalog-policy-language.md`*
