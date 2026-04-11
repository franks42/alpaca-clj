# alpaca-clj — Project Context

> Current state snapshot for session continuity.
> Read this + CLAUDE.md + plan.md to get up to speed.
>
> Last updated: 2026-04-10 — migration to signet + stroopwafel + stroopwafel-pdp

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

## Current State

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

**88 tests, 203 assertions, 0 failures.** Run with `bb test`.

## Architecture — Key Files

```
src/alpaca/
  config.clj              — env vars, paper/live, roster loading (kid URNs)
  schema.clj              — single source of truth (routes, params, alpaca mapping)
  client.clj              — HTTP client to Alpaca REST (JSON internally, EDN at boundary)
  keys.clj                — expand terse Alpaca keys (bp→bid-price, o→open)
  auth.clj                — token issue/sign/verify/authorize (bearer/bound/group modes)
  telemetry.clj           — trove/timbre bootstrap, stderr routing
  pep/http_edn.clj        — HTTP+EDN canonicalization for the auth middleware
  proxy/
    server.clj            — http-kit lifecycle, PID file, startup validation
    router.clj            — fn-as-URL routing from schema (structural whitelist)
    middleware.clj        — auth middleware (simple/stroopwafel), logging, error handling
    handlers.clj          — request validation + Alpaca forwarding
    log.clj               — structured request logging

  cli/
    api.clj               — unified CLI: bb api <operation> [--flags]
    common.clj            — proxy HTTP client with optional agent request signing
    token.clj             — bb token generate-keys/issue/issue-group/inspect
    account.clj, market.clj, trading.clj — legacy convenience aliases

test/alpaca/
  router_test.clj         — 17 tests: routes, 404/405, validation
  auth_test.clj           — 32 tests: bearer, bound, group, replay, audience, multi-root
  http_edn_test.clj       — 9 tests: canonicalize + extract-creds + exempt
  keys_test.clj           — 5 tests: key expansion
  ssh_test.clj            — 6 tests: SSH key import, end-to-end with alpaca tokens
  client_pep_test.clj     — 13 tests: outbound policy + PII/client-name/strategy checks
  dual_pep_test.clj       — 7 tests: full client+server PEP integration
```

## The Three-Library Auth Stack

This project sits on top of three focused libraries:

```
signet              — crypto primitives (Ed25519 keys, signing, chains, SSH import)
stroopwafel         — pure Datalog engine (evaluate facts → decision, zero deps)
stroopwafel-pdp     — PDP bridge (verify chains → extract facts → evaluate)
```

**alpaca.auth** is the policy orchestrator:
1. Dispatch on token shape → `:bearer` / `:bound` / `:group`
2. Build a PDP context: `add-chain` (verifies signatures), `add-facts`
   (inject `[:chain-root kid]`, trust-root facts, roster facts)
3. For bound/group: verify the signed-request envelope binds to the actual
   request (method/path/body/audience), check replay
4. `pdp/decide` with one policy per mode, joining chain facts + local facts
   through a shared `[:trusted-ok]` rule

Every identity is a **kid URN** (`urn:signet:pk:ed25519:<base64url>`) — no
hex, no JCA PublicKey objects, no `[:signer-key bytes]` facts in tokens.
Trust is expressed as `[:trusted-root kid effect domain]` Datalog facts.

## Authorization Modes

**Bearer** — token carries `[:effect ...]`, `[:domain ...]`. Policy:
```
[:effect effect] [:domain domain] [:trusted-ok]
```

**Bound (SPKI)** — token adds `[:authorized-agent-key <kid>]`. Requires
signed-request envelope. Policy:
```
[:effect effect] [:domain domain]
[:authorized-agent-key ?k] [:request-verified-signer ?k]
[:trusted-ok]
```

**Group (SDSI)** — token carries `[:right <group> <effect> <domain>]`.
Requires signed envelope + named-key roster. Policy:
```
[:right ?name effect domain] [:named-key ?name ?k]
[:request-verified-signer ?k] [:trusted-ok]
```

All three share this rule, covering unscoped (`:any`) and scoped trust:
```clojure
[{:id :trusted-any
  :head [:trusted-ok]
  :body [[:chain-root ?k] [:trusted-root ?k :any :any]]}
 {:id :trusted-scoped
  :head [:trusted-ok]
  :body [[:chain-root ?k] [:trusted-root ?k effect domain]]}]
```

**Replay protection:** UUIDv7 as combined timestamp+nonce. 120s freshness
window + in-memory nonce cache via `stroopwafel.pdp.replay`.

**Envelope content binding:** `alpaca.auth/check-envelope-binding` verifies
the signed envelope's claimed method/path/body/audience match the actual
request. This is alpaca-specific and lives outside the PDP (which only
verifies signatures).

## Migration History

**Completed 2026-04-10** — rewrote alpaca-clj on the three-library stack:

| Before | After |
|---|---|
| `alpaca.envelope` (thin pass-through) | **deleted** — call `signet.sign` directly |
| `alpaca.ssh` (thin pass-through) | **deleted** — call `signet.ssh` directly |
| `alpaca.pep` (one-caller abstraction) | **deleted** — pipeline inlined in middleware |
| `alpaca.auth` 437 lines | **~280 lines**, three PDP calls vs three hand-written `sw/evaluate` |
| `alpaca.client-pep` 213 lines | **~180 lines**, PDP for outbound token verification |
| `[:signer-key bytes]` token facts | removed — `chain/verify`'s `:root` kid is the identity |
| Hex utilities | removed — kid URNs at all boundaries |
| Keyfile `{:priv PKCS8 :pub X.509}` | `{:x :d}` raw bytes via CEDN `#bytes` |
| `STROOPWAFEL_ROOT_KEY` as hex | kid URN string |
| Roster hex pk bytes | kid URN strings |

Net: ~790 lines removed (source + tests).

**Bug fixes during migration** (both in signet, for babashka compatibility):
- `signet.sign/sign` used to indirect through `signing-private-key` + `register!`,
  which triggered `ed25519-seed->public-key` → `proxy SecureRandom` — bb can't
  proxy concrete classes. Fix: read `:d` directly.
- `signet.ssh/read-private-key` re-derived the public key via the same broken
  path. Fix: use the pubkey already embedded in the OpenSSH private blob.

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| http-kit | 2.8.1 | HTTP server + client |
| cheshire | 6.1.0 | JSON (Alpaca REST) |
| cedn | 1.2.0 | Canonical EDN serialization |
| signet | local | Crypto primitives (Ed25519, chains, SSH) |
| stroopwafel | local | Pure Datalog engine |
| stroopwafel-pdp | local | PDP bridge |
| uuidv7 | 0.5.0 | Timestamp + nonce |
| trove | 1.1.0 | Structured logging |
| timbre | 6.8.0 | Logging backend |

## Keyfile Format

Keyfiles live at `.stroopwafel-root.edn`, `.stroopwafel-agent.edn`,
`.stroopwafel-outbound.edn`. All gitignored. Format:

```clojure
{:x #bytes "..."   ;; 32-byte Ed25519 public key
 :d #bytes "..."}  ;; 32-byte Ed25519 seed
```

Loaded via `(signet.key/signing-keypair x d)`.

## Roster Format

`.stroopwafel-roster.edn` (or `$STROOPWAFEL_ROSTER`):

```clojure
{"traders"  ["urn:signet:pk:ed25519:..." "urn:signet:pk:ed25519:..."]
 "monitors" ["urn:signet:pk:ed25519:..."]}
```

## Key Commands

```bash
bb test                       # 88 tests
bb lint && bb fmt             # quality checks (MUST be 0/0)
bb server:start &             # start proxy
bb server:stop                # stop proxy
bb api help                   # list operations
bb api market/clock           # call proxy
bb token generate-keys        # root keypair
bb token generate-agent-keys  # agent keypair
bb token issue-read-only      # bearer token
bb token issue-group --group traders --effects read --domains market
bb token inspect <token>      # show token structure
```
