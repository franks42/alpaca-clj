# alpaca-clj — Project Context

> Current state snapshot for session continuity.
> Read this + CLAUDE.md + plan.md to get up to speed.
>
> Last updated: 2026-03-18, v0.5.3 (tag: v0.5.3, commit: 732e67f + docs)

---

## What This Project Is

A **capability-gated proxy trading server** for Alpaca Markets running on
Babashka. AI agents interact with Alpaca exclusively through this proxy.
No agent ever holds Alpaca API keys.

Four-party architecture:
1. **Policy Authority** — holds root Ed25519 key, mints capability tokens
2. **AI Agent** — holds own keypair + capability token, signs each request
3. **Proxy** (this project) — verifies token + request signature, forwards to Alpaca
4. **Alpaca Markets** — sees only the proxy's API credentials

## Current State (v0.5.3)

**10 endpoints** across 3 domains (account, market, trade):

| Route | Effect | Method |
|---|---|---|
| /account/info | read | GET |
| /market/clock | read | GET |
| /market/quote | read | POST |
| /market/bars | read | POST |
| /trade/positions | read | GET |
| /trade/place-order | write | POST |
| /trade/orders | read | POST |
| /trade/order | read | POST |
| /trade/cancel-order | destroy | POST |
| /trade/close-position | destroy | POST |

**75 tests, 170 assertions, 0 failures.** Run with `bb test`.

## Architecture — Key Files

```
src/alpaca/
  config.clj              — env vars, paper/live, roster loading
  schema.clj              — single source of truth (routes, params, constraints, alpaca mapping)
  client.clj              — HTTP client to Alpaca REST (JSON internally, EDN at boundary)
  keys.clj                — expand terse Alpaca keys (bp→bid-price, o→open)
  auth.clj                — token issuance, signing, verification, authorization (BIGGEST FILE)
  ssh.clj                 — SSH Ed25519 key import (standalone, no alpaca deps)
  telemetry.clj           — trove/timbre bootstrap, stderr routing
  pep.clj                 — PEP pipeline abstraction (canonicalize→extract→authorize→allow/deny)
  pep/http_edn.clj        — HTTP+EDN canonicalization template for the PEP
  proxy/
    server.clj            — http-kit lifecycle, PID file, startup validation, trust-state logging
    router.clj             — fn-as-URL routing from schema (structural whitelist)
    middleware.clj          — auth middleware (simple/stroopwafel/multi-root), logging, error handling
    handlers.clj           — request validation (types, enums, conditional requires, mutex) + Alpaca forwarding
    log.clj                — structured request logging with token fingerprint + agent key
  cli/
    api.clj                — unified CLI: bb api <operation> [--flags]
    common.clj             — proxy HTTP client with optional agent request signing
    token.clj              — bb token generate-keys/issue/issue-group/inspect
    account.clj, market.clj, trading.clj — legacy convenience aliases

test/alpaca/
  router_test.clj          — 18 tests: routes, 404/405, params, schema validation, business rules
  auth_test.clj            — 42 tests: bearer, SPKI, SDSI, replay, envelope, audience, multi-root, hex
  pep_test.clj             — 10 tests: canonicalization, exemption, credentials, pipeline composition
  keys_test.clj            — 5 tests: key expansion
  ssh_test.clj             — 5 tests: SSH key import, stroopwafel integration

docs/
  authorization-progression.md     — bearer→SPKI→SDSI→federated (one mechanism, four levels)
  trust-roots-and-enforcement.md   — enforcement actor as trust root, multi-authority scoped trust
  dual-pep-client-server-enforcement.md — client-side + server-side PEP
  stroopwafel-envelope-spec.md     — NEXT: generic signed envelope spec (to be implemented)
  alpaca-clj-review-gpt.md         — first GPT review
  alpaca-clj-review-gpt-2.md       — second GPT review (all P0-P2 addressed)
  alpaca-clj-review-gemini.md      — first Gemini review
  alpaca-clj-review-gemini-2.md    — second Gemini review
```

## Authorization Model

**Two-step verify+authorize** (since v0.5.2):

Step 1 (crypto): Token carries `[:signer-key <pk-bytes>]`. Verify signature
against the stated key. This step knows nothing about trust.

Step 2 (policy): Trust-root facts + token facts + request facts all go into
Datalog. The join `[:signer-key ?k] ∧ [:trusted-root ?k effect domain]`
decides if the signer is trusted for this operation.

**Four auth levels working:**
- Bearer — `[:effect :read] [:domain "market"]`
- SPKI — `[:authorized-agent-key <pk>]` + signed request envelope
- SDSI groups — `[:right "traders" :read "market"]` + roster file + signed request
- Multi-root — `{pk-bytes → {:scoped-to {:effects #{:write} :domains #{"trade"}}}}`

**Replay protection:** UUIDv7 as combined timestamp+nonce. 120s freshness window + in-memory nonce cache.

**Envelope signing:** Agent signs `{:method :path :body :request-id :audience}`. Proxy reconstructs and verifies envelope matches actual request.

**Business-rule validation:** Declarative constraints in schema:
- `{:enum {:side ["buy" "sell"]}}` — value must be in set
- `{:when {:type "limit"} :require [:limit_price]}` — conditional required
- `{:mutex [:qty :percentage]}` — at most one

## What's Next — The Envelope Refactor

**Read `docs/stroopwafel-envelope-spec.md` for the full spec.**

The current signing is split between `stroopwafel.request` (low-level) and
`alpaca.auth` (HTTP-specific envelope). The goal is a clean generic envelope:

```
stroopwafel.envelope (to be created, here first, migrate later):
  sign:   (message, priv-key, pub-key, ttl) → {:envelope {:message :signer-key :request-id :expires} :signature}
  verify: (outer-envelope) → {:valid? :message :signer-key :timestamp :expires :expired? :age-ms}
```

Key design decisions already made:
- `:expires` not `:ttl` — absolute epoch-ms, one comparison at eval time
- `:signer-key` in envelope — self-describing, no lookup needed
- Per-fact temporal validity — `valid-from` + `valid-to` on every fact
- Pre-eval filter: `(and (<= valid-from now) (< now valid-to))` — two comparisons
- Data model complete (store both bounds), queries simple (just filter at now)
- Envelope is message-opaque — trust, replay, audience all in layers above
- Replaces `stroopwafel.request` with cleaner separation

Implementation plan:
1. Create `alpaca.envelope` namespace (~50 lines) with `sign`/`verify`
2. Refactor `alpaca.auth` to use `envelope` instead of `stroopwafel.request`
3. Update `alpaca.pep.http-edn` and `alpaca.cli.common`
4. Update tests (ensure all 75 still pass)
5. Later: move `alpaca.envelope` → `stroopwafel.envelope`

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| http-kit | 2.8.1 | HTTP server + client |
| cheshire | 6.1.0 | JSON (Alpaca REST) |
| cedn | 1.2.0 | Canonical EDN serialization |
| stroopwafel | 0.9.0 | Capability tokens |
| uuidv7 | 0.5.0 | Timestamp + nonce |
| trove | 1.1.0 | Structured logging |
| timbre | 6.8.0 | Logging backend |

## Migration TODO (alpaca-clj → stroopwafel repo)

- `alpaca.envelope` → `stroopwafel.envelope`
- `alpaca.ssh` → `stroopwafel.ssh`
- `alpaca.pep` → `stroopwafel.pep`
- `alpaca.pep.http-edn` → `stroopwafel.pep.http-edn`
- Replay protection utilities
- `bytes->hex` / `hex->bytes`
- `trust-root-facts` helper

## Key Commands

```bash
bb test                    # 75 tests, 170 assertions
bb server:start &          # start proxy
bb server:stop             # stop proxy
bb server:status           # check running
bb api help                # list operations
bb api market/clock        # call proxy
bb token generate-keys     # root keypair
bb token generate-agent-keys # agent keypair
bb token issue-read-only   # bearer token
bb token issue-group --group traders --effects read --domains market
bb lint && bb fmt          # quality checks
```
