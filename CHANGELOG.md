# Changelog

## v1.0.0 — 2026-04-11 — **Production milestone**

alpaca-clj is now a capability-gated proxy with **two complete trust
models** running side-by-side: the Biscuit-style chain model (bearer /
bound / group) and the SPKI/SDSI assertion model (:spki). Both are
tested end-to-end against the real Alpaca paper-trading API and can be
used simultaneously — the auth middleware dispatches on token shape at
request time.

This release marks the completion of the three-library refactor that
began in v0.8.0. Every capability the old monolithic stroopwafel had
now lives in the right layer:

- **signet** — Ed25519/X25519 primitives, capability chains, SSH import
- **stroopwafel** — pure Datalog engine, zero dependencies
- **stroopwafel-pdp** — two parallel PDPs: the chain-shaped original
  (`stroopwafel.pdp.*`) and the new assertion-shaped SPKI/SDSI model
  (`stroopwafel.pdp.spki.*`)
- **alpaca-clj** — four auth modes, one middleware, same tests-and-lint
  discipline as always

### What shipped in v1.0.0

- `:spki` auth mode added to `alpaca.auth/verify-and-authorize`
  (parallel to `:bearer` / `:bound` / `:group`)
- `alpaca.auth/issue-assertion-block` — convenience for minting an
  SPKI/SDSI capability: one signed block with a `:name-binding` plus
  N `:capability` assertions bound to an agent kid
- Token-kind dispatch on `:signet/chain` vs `:signet/signed`, so both
  trust models coexist transparently in the same proxy deployment
- 10 new auth tests covering the SPKI/SDSI happy path, missing
  signature, rogue agent, wrong effect / domain, untrusted root,
  multi-effect grid, tampered block, replay, and envelope binding
  mismatch
- End-to-end dogfood: fresh key generation → signed block → signed
  request → real proxy → real Alpaca paper-api response, all under
  a second on babashka

### Depends on

| Library | Version | Role |
|---|---|---|
| signet | v0.3.1 | Ed25519 primitives, chains, SSH import, bb compat |
| stroopwafel | latest main | Pure Datalog engine |
| stroopwafel-pdp | v0.2.0 | Chain PDP + SPKI/SDSI PDP |

### Test totals across the stack

- signet: 67 JVM + 7 bb
- stroopwafel: 66
- stroopwafel-pdp: 116
- alpaca-clj: 98

**354 tests, 889 assertions, zero failures, zero lint warnings.**

---

## v0.9.0 — 2026-04-11

**Phase 6 — SPKI/SDSI dogfood integration.** Adds the `:spki` mode to
alpaca.auth. First cross-repo validation that the stroopwafel-pdp
`spki.*` namespace tree (Phases 1–5) holds up against a real capability
scenario. No middleware changes — dispatch lives entirely inside
`verify-and-authorize`.

- `issue-assertion-block` → builds + signs an SPKI assertion block
  bundling a `:name-binding` with N `:capability` assertions
- `authorize-spki` helper routes assertion-block tokens through
  `stroopwafel.pdp.spki.core/decide` with the standard templates and
  an SDSI name-resolution rule
- `token-kind` helper distinguishes `:signet/chain` from
  `:signet/signed`; chain path is completely unchanged
- 10 new `auth_test.clj` tests
- End-to-end verified: `bb server:start` + SPKI block as Bearer +
  `STROOPWAFEL_AGENT_SIGN=true` + `bb api market/clock` → returns
  real paper-trading market clock data

---

## v0.8.0 — 2026-04-10

**Migrate to signet + stroopwafel + stroopwafel-pdp.** Rewrite the
authorization stack on the new three-library split; the old monolithic
stroopwafel is gone.

- All identities are kid URNs (`urn:signet:pk:ed25519:...`) at every
  boundary — no hex, no JCA PublicKey objects, no pk-bytes in token
  facts
- **Deletions (~790 lines net):**
  - `alpaca.envelope` / `alpaca.ssh` / `alpaca.pep` — thin
    pass-throughs or single-caller abstractions; callers now use
    `signet.sign` / `signet.ssh` / inlined middleware directly
  - `envelope_test.clj` / `pep_test.clj` — coverage moved to signet's
    suite and a new `http_edn_test.clj` respectively
- **Rewrites:**
  - `alpaca.auth` 437 → ~280 lines; dispatches on token shape to one
    of `:bearer` / `:bound` / `:group`, each calling `stroopwafel-pdp`
    once with a unified `[:trusted-ok]` rule across scoped and
    unscoped trust roots
  - `alpaca.client-pep` 213 → ~180 lines using the same PDP pattern
    for outbound tokens
  - `alpaca.proxy.middleware` — PEP pipeline inlined into
    `wrap-stroopwafel-auth` (one caller); trove logging direct
  - `alpaca.cli.token` / `alpaca.cli.common` — new keyfile format
    `{:x #bytes ... :d #bytes ...}`
- **Breaking changes for users:**
  - `STROOPWAFEL_ROOT_KEY` must now be a signet kid URN string, not hex
  - Keyfiles must be regenerated (`bb token generate-keys`)
  - Roster entries are kid URN strings, not hex
- **Test suite:** 88 tests / 203 assertions, 0 failures; lint 0/0;
  smoke-verified against paper-api.alpaca.markets

---

## v0.7.1 and earlier

See the git tag history for pre-migration versions. Major milestones:

- **v0.7.x** — stroopwafel signed-envelope unification (0.10.x line)
- **v0.6.x** — SDSI group-based authorization, multi-root scoped trust
- **v0.5.x** — requester-bound tokens, envelope signing, replay
  protection
- **v0.4.x and earlier** — initial 10 endpoints + schema-as-
  source-of-truth foundation
