# alpaca-clj Review

Date: 2026-03-17

## Executive Summary

This project has a strong architectural core.

The combination of:

- a schema-defined proxy surface,
- EDN at the boundary,
- CEDN for deterministic signing/serialization,
- Stroopwafel for signed capability tokens,
- and a strict separation between agent credentials, proxy credentials, and policy authority

is technically coherent and substantially better aligned with capability security than the usual OAuth-style bearer-token stack.

The design is especially compelling for AI-agent execution because it avoids giving agents direct Alpaca credentials and expresses authorization in data and Datalog instead of hard-coded if/else checks.

After reviewing this repository together with the local canonical-edn and stroopwafel projects, my assessment is:

- The foundational direction is sound.
- The current implementation is a credible prototype / early integration.
- Several important claims in the README and design narrative are ahead of the code.
- The biggest remaining gaps are replay resistance, policy expressiveness at the proxy boundary, SDSI-style group integration, and verification via tests.

In short: the project is interesting and well-conceived, but it is not yet at the point where I would call the security model fully realized.

## What Is Strong Already

### 1. The security decomposition is the right one

The most important architectural choice is the one the project gets right from the start: the AI client never holds Alpaca API credentials.

That gives you a materially different trust boundary from typical agent tooling. Even a fully compromised agent runtime only gets:

- a limited token,
- its own signing key,
- and whatever local state was provisioned to that agent.

That is the correct starting point for safe delegated trading workflows.

### 2. Clojure/EDN is a good fit for this problem

This is one of the more convincing uses of Clojure data as a security primitive rather than just an implementation language.

The project benefits from EDN/Clojure in several ways:

- policies are inspectable data,
- routes and capabilities can be derived from one schema,
- Datalog joins map naturally onto entitlement logic,
- and canonical-edn provides a deterministic serialization boundary suitable for signing.

The canonical-edn dependency is not decorative here. It solves a real cryptographic problem: ordinary EDN printing is not stable enough for signatures.

### 3. Schema-driven routing is a major strength

The schema in `src/alpaca/schema.clj` is the best part of the local codebase.

It acts as:

- route registry,
- operation catalog,
- capability metadata source,
- and CLI discovery surface.

That substantially reduces accidental authority expansion. Unknown routes do not fall through into ad hoc handlers. This is exactly the kind of structural whitelisting a capability-oriented proxy should prefer.

### 4. Stroopwafel is a strong conceptual fit

The local stroopwafel project appears to provide the right primitives for where this wants to go:

- signed append-only capability blocks,
- attenuation and sealing,
- requester-bound proof-of-possession requests,
- third-party blocks,
- and SDSI-style name-to-key binding patterns.

That gives alpaca-clj a credible path toward richer delegated authorization without changing its overall model.

### 5. The project stays mostly disciplined in scope

The codebase is small, readable, and avoids premature framework complexity.

That is a real advantage for a security-sensitive system. A small proxy with a clear evaluation pipeline is better than a feature-rich server whose actual authority model is hard to audit.

## Main Findings

## 1. Signed requests are not yet bound tightly enough to the actual HTTP operation

This is the most important security gap.

Today, the signed request flow covers the request body and a timestamp, but not the full execution context. In practice that means the proxy verifies that the client signed some body, not necessarily that it signed this exact HTTP action against this exact endpoint in this exact authorization context.

Current behavior appears to be:

- token carries `:authorized-agent-key` facts,
- client signs body data,
- proxy verifies signature,
- authorizer checks `:effect` and `:domain` against the route.

What is missing from the signed payload is at least:

- HTTP method,
- route/path,
- host or audience,
- and ideally a token hash or capability identifier.

Why this matters:

- A captured signed body can be replayed to the same endpoint.
- If two endpoints accept structurally similar bodies, a signature is not strongly tied to the intended operation.
- The proof-of-possession layer authenticates the agent key, but it does not yet fully authenticate the action envelope.

Recommendation:

- Change the signed payload from roughly `{:body ... :timestamp ...}` to something closer to `{:method ... :path ... :body ... :timestamp ... :token-hash ...}`.
- Make the proxy reconstruct and verify exactly that structure before authorization.
- Treat the signature as an assertion over the whole request envelope, not just the EDN body.

## 2. There is no visible replay defense beyond having a timestamp field

The presence of a timestamp is not sufficient on its own.

I do not see any freshness window, nonce check, or replay cache in alpaca-clj. I also do not see the proxy rejecting stale signatures.

That means a valid signed request can likely be replayed later if an attacker captures:

- the token,
- the signed metadata header,
- and the body.

Requester-bound tokens neutralize simple token theft, but without replay protection they do not neutralize captured request re-use.

Recommendation:

- Enforce a short freshness window, for example 30-120 seconds.
- Add a replay cache keyed on something like `(agent-key, timestamp, request-hash)` or preferably an explicit nonce.
- Consider making nonce replay protection mandatory for destructive operations.

## 3. The SDSI / named-group model is described, but not yet integrated into alpaca-clj

This is the biggest gap between the project narrative and the current local implementation.

The review of stroopwafel suggests the underlying library is ready for:

- `:named-key` facts,
- authenticated name resolution,
- and third-party attested group membership.

But alpaca-clj currently appears to authorize primarily on:

- `:effect`,
- `:domain`,
- and optional `:authorized-agent-key` requester binding.

I do not see local proxy support for:

- authorizer-managed group bindings,
- trusted external keys,
- third-party membership assertions,
- or named capability subjects such as `"traders"`, `"ops-team"`, etc.

So the current system is closer to:

- bearer capability tokens, optionally requester-bound to a single key,

than to:

- a full SPKI/SDSI-style capability and naming fabric.

Recommendation:

- Expose an authorizer configuration layer in alpaca-clj that can inject `:named-key` facts and trusted external keys.
- Add a first-class policy mode where rights are granted to names/groups rather than directly to one key.
- Decide explicitly which bindings belong in authority-issued tokens, which belong in proxy-local authorizer state, and which can come from third-party signed blocks.

## 4. “Schema as single source of truth” is only partially realized

The schema is doing useful work, but not yet all the work the project claims.

What it does well now:

- route discovery,
- handler dispatch,
- CLI discovery,
- effect/domain metadata.

What it does not yet do well enough:

- runtime type validation,
- enum validation,
- coercion consistency,
- richer operation-specific policy derivation.

Examples:

- Required params are checked, but most declared types are not enforced server-side.
- CLI coercion only partially converts types.
- Boolean values such as `:extended_hours` appear likely to remain strings when passed through the CLI.
- Constraints such as allowed order types, allowed sides, or parameter interdependencies are not enforced from schema.

Recommendation:

- Introduce schema-driven coercion and validation at the proxy boundary.
- Make the server authoritative on types, not the CLI.
- Add constraint support for cross-field rules such as “limit orders require `limit_price`”.
- Treat malformed EDN and invalid schema values as distinct classes of client error.

## 5. Capability vocabulary is still too coarse for a trading system

The current capability model is based mainly on effect classes and domains.

That is a good start, but it is too broad for realistic delegated trading authority.

For example, “write access to trade” is not enough if the real intent is:

- buy only,
- for a bounded symbol universe,
- under a quantity limit,
- during a time window,
- on paper trading only,
- with specific order types only.

The project documentation already points toward this, but the current local enforcement does not yet appear to encode such constraints.

Recommendation:

- Move from coarse facts like `[:effect :write]` and `[:domain "trade"]` toward richer rights facts such as:
  - `[:right subject :write :trade]`
  - `[:allowed-symbol subject "AAPL"]`
  - `[:max-qty subject 100]`
  - `[:allowed-order-type subject "limit"]`
  - `[:paper-only subject true]`
- Inject request facts derived from the actual order payload and let Datalog decide.
- Keep effect/domain, but treat them as the outer perimeter rather than the full policy.

## 6. Domain naming is inconsistent and risks policy confusion

The codebase uses both `trade` and `trading` as domain-like namespaces.

That may be intentional from a route taxonomy perspective, but it is risky in an authorization system because a human issuing tokens can easily misunderstand which label governs which operation.

For example:

- `trading/positions` lives under `trading`
- order operations live under `trade`

This creates unnecessary policy ambiguity.

Recommendation:

- Normalize on one vocabulary for the authorization domain layer.
- Keep user-facing routes if you want, but make policy domains canonical and explicit.
- Avoid requiring operators to remember subtle route namespace distinctions when minting capabilities.

## 7. Auditability is weaker than the README implies

The README promises strong auditing, but the current logging layer looks lighter than that story.

The project does log request metadata, which is good, but I do not see evidence that each request log reliably captures:

- token identity / capability identifier,
- verified agent key identity,
- authorization decision details,
- or a cryptographically chained append-only audit record.

Right now the system looks more like standard structured request logging than a hardened security audit trail.

Recommendation:

- Assign stable capability identifiers or token fingerprints.
- Log verified agent key fingerprints on every authenticated request.
- Separate security audit events from operational request logs.
- If production hardening is the goal, implement append-only chained audit records before calling the system strongly auditable.

## 8. Test coverage is effectively absent right now

This is the strongest practical signal against calling the project mature.

Observed state:

- `bb lint` passes.
- `bb fmt` passes.
- `bb test` fails because `alpaca.test-runner` is missing.
- the `test/alpaca` directory is empty.

That means the project currently has no visible automated verification for:

- auth edge cases,
- replay behavior,
- schema coercion,
- route enforcement,
- Alpaca client translation,
- or SSH key import.

Recommendation:

- Fix the broken test task immediately.
- Add unit tests for auth and middleware before adding more endpoints.
- Add table-driven tests for capability grants vs denied actions.
- Add regression tests for route/method mismatches and malformed EDN.
- Add property tests around canonical serialization and signed request verification where practical.

## 9. The project should fail faster on configuration and operational safety

Operationally, the proxy should be stricter at startup.

I do not see strong fail-fast validation for:

- missing Alpaca credentials,
- malformed root public keys,
- invalid auth mode combinations,
- or unsafe production-vs-paper mismatches.

Recommendation:

- Validate all critical env vars at boot.
- Refuse startup if credentials or keys are missing in required modes.
- Emit an explicit startup banner showing whether the server is in `paper` or `live` mode.
- Consider requiring an explicit opt-in for live trading.

## 10. The HTTP client path needs stronger operational guardrails

The client layer is intentionally small, which is good, but I would want stricter defaults before trusting it for agent-driven trading.

Open concerns include:

- timeout policy,
- retry policy,
- handling of transient upstream failures,
- and idempotency expectations for writes.

Recommendation:

- Add explicit request timeouts.
- Be conservative with retries on write operations.
- Distinguish retry-safe reads from unsafe writes.
- Surface upstream correlation IDs where available.

## Observations About The Broader Design

## 1. This is more interesting than “Biscuit in Clojure”

The project is not just reimplementing a known token system. The compelling part is the composition of:

- canonical EDN,
- signed capability blocks,
- signed request envelopes,
- and name bindings.

That combination gives you a native data-oriented authorization model that feels much closer to SPKI/SDSI than to most current API auth stacks.

That is a strong idea.

## 2. The main design opportunity is to make the request itself a first-class signed fact set

The cleanest future direction is not “token says some things, proxy separately interprets request fields imperatively”.

The stronger model is:

- token contributes capability facts,
- proxy contributes contextual facts,
- signed request contributes actor-and-intent facts,
- authorizer contributes local bindings and policies,
- Datalog decides the result.

That would make the signed request a real authorization assertion, not just a proof-of-possession wrapper.

## 3. SDSI-style names are likely the right abstraction for multi-agent ops

Binding every token to one key is fine for the first prototype, but it does not scale operationally.

If this system is going to support:

- multiple agents,
- rotating keys,
- temporary delegations,
- operator groups,
- or environment-specific entitlements,

then named principals and group bindings will matter more than direct per-key grants.

That is where the local stroopwafel work appears especially promising.

## Suggestions For Improvement

## Priority 1: Close the real security gaps

1. Bind signatures to method, route, timestamp, and token hash.
2. Add replay protection with freshness windows and nonce/cache checks.
3. Add richer request-derived facts for order-specific policies.
4. Log verified key fingerprints and capability fingerprints.

## Priority 2: Make the policy model match the design narrative

1. Add SDSI-style `named-key` support to alpaca-clj authorizer configuration.
2. Support trusted external keys / third-party blocks where appropriate.
3. Distinguish clearly between bearer capability, requester-bound capability, and signed authorization assertion.
4. Normalize authorization vocabulary, especially `trade` vs `trading`.

## Priority 3: Strengthen correctness and maintainability

1. Implement server-side schema coercion and validation.
2. Add operation-specific validation rules.
3. Fix the broken test task and create a real test suite.
4. Reconcile version/documentation drift.

## Priority 4: Harden operations

1. Validate startup config strictly.
2. Add explicit HTTP timeouts and safer error handling.
3. Make live trading opt-in and visibly distinct from paper mode.
4. Build a dedicated audit trail rather than relying on generic request logs.

## Concrete Near-Term Roadmap

If I were prioritizing the next iterations, I would do them in this order:

### Phase A: Make the current model safe enough to trust

- Fix tests.
- Add signature freshness and replay defense.
- Bind signatures to method/path/token hash.
- Add schema validation for all existing endpoints.

### Phase B: Make the policy engine express actual trading limits

- Inject request facts for symbol, side, qty, type, paper/live.
- Express limits as Datalog facts/checks.
- Add token issuance helpers for bounded trading policies.

### Phase C: Add identity/group ergonomics

- Introduce named principals / groups.
- Add proxy-local roster bindings.
- Then add third-party attestation support if needed.

### Phase D: Build the production story

- append-only audit trail,
- revocation support,
- live promotion ceremony,
- key rotation procedures,
- and operator tooling for reviewing minted capabilities.

## Final Assessment

This is a strong prototype with a genuinely worthwhile architecture.

The project already demonstrates:

- good language/tool fit,
- sensible trust-boundary design,
- a clean proxy structure,
- and a promising capability model built on credible supporting libraries.

What it does not yet demonstrate is a fully closed, production-grade security envelope. The biggest reasons are:

- replayability concerns,
- insufficient binding of signatures to concrete HTTP actions,
- incomplete realization of SDSI/group semantics,
- and lack of automated verification.

The encouraging part is that these are fixable within the current design. I do not think the architecture needs to be replaced. I think it needs to be tightened.

My overall judgment:

- Concept: very strong
- Architecture: strong
- Implementation maturity: early / prototype
- Security maturity: promising but not yet fully defensible
- Direction: worth continuing