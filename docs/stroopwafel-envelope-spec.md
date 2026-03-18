# stroopwafel.envelope — Signed Envelope Specification

> Generic signed message envelope for stroopwafel.
> Separates cryptographic signing from application semantics.
> Designed for migration from alpaca-clj to the stroopwafel repo.
>
> Status: design specification, pre-implementation.
> Last updated: March 2026.

---

## Purpose

A signed envelope is a single data structure that says:

> "I, identified by this public key, assert this message at this time,
> and I vouch for it until this expiration time."

The envelope handles signing, verification, timestamps, and expiry.
It does NOT handle trust decisions, replay caching, audience checking,
or policy evaluation — those belong to the enforcement layer above.

---

## Envelope Structure

### Inner Envelope (what gets signed)

```clojure
{:message    <any EDN value>          ;; opaque to envelope layer
 :signer-key <public-key-bytes>       ;; Ed25519 public key (self-describing)
 :request-id <UUIDv7 string>          ;; timestamp (ms) + nonce (unique)
 :expires    <epoch-ms>}              ;; absolute expiration time
```

**Fields:**

| Field | Type | Required | Description |
|---|---|---|---|
| `:message` | any EDN | yes | The assertion content. Opaque to the envelope — the application decides what goes here. |
| `:signer-key` | byte array | yes | Ed25519 public key of the signer. Self-describing — no lookup needed to verify. |
| `:request-id` | string (UUIDv7) | yes | Timestamp + nonce in one field. Extractable ms-precision timestamp. Guaranteed unique. |
| `:expires` | long (epoch ms) | yes | Absolute expiration time. The signer vouches for this message until this time, no longer. |

### Outer Envelope (what gets transmitted)

```clojure
{:envelope  <inner envelope map>
 :signature <byte array>}
```

The signature is Ed25519 over `CEDN(inner-envelope)` — the canonical byte representation of the inner map.

---

## Operations

### Sign

```clojure
(envelope/sign message private-key public-key ttl-seconds)
;; → {:envelope {:message    <message>
;;               :signer-key <pk-bytes>
;;               :request-id "019d0114-8fb5-..."
;;               :expires    1742000003600000}
;;    :signature <bytes>}
```

The signing function:
1. Generates a UUIDv7 request-id (captures current time + nonce)
2. Computes `:expires` as `now + (ttl-seconds * 1000)`
3. Assembles the inner envelope
4. Serializes via `CEDN(inner)` → deterministic bytes
5. Signs the bytes with Ed25519 using the private key
6. Returns the outer envelope

### Verify

```clojure
(envelope/verify outer-envelope)
;; → {:valid?     true/false        ;; signature check
;;    :message    <the payload>     ;; what was asserted
;;    :signer-key <pk-bytes>        ;; who asserted it
;;    :request-id "019d0114-..."    ;; unique assertion ID
;;    :timestamp  1742000000000     ;; extracted from UUIDv7
;;    :expires    1742000003600000  ;; when it expires
;;    :expired?   true/false        ;; now > expires
;;    :age-ms     3400}             ;; now - timestamp
```

The verification function:
1. Extracts `:signer-key` from the inner envelope
2. Decodes to Ed25519 public key
3. Serializes inner envelope via `CEDN(inner)` → same deterministic bytes
4. Verifies Ed25519 signature against the public key
5. Extracts timestamp from UUIDv7 request-id
6. Computes `:expired?` and `:age-ms`
7. Returns all verified values

**The verify function does NOT:**
- Check replay (that's the PEP's replay cache)
- Check trust (that's the Datalog trust-root join)
- Check audience (that's the application layer)
- Reject expired envelopes (it reports `:expired?`, caller decides)

---

## TTL and Expiration

### Envelope-Level Expiry

The `:expires` field is the **absolute expiration time** in epoch milliseconds.
It is computed at signing time from the TTL:

```
expires = System/currentTimeMillis + (ttl-seconds * 1000)
```

The signer thinks in TTL (relative, human-friendly: "valid for 2 minutes").
The envelope stores expiry (absolute, machine-friendly: "valid until epoch X").
Comparison at evaluation time is a single `(< now expires)` — no arithmetic.

### Per-Fact Expiry

When the message contains Datalog facts, individual facts may have their
own expiration times. The envelope `:expires` is the **ceiling** — no fact
can outlive the envelope.

```clojure
{:message
 {:facts [[:named-key "traders" <alice-pk>]                ;; no per-fact expiry
                                                           ;; → inherits envelope expires
          [[:effect :read] 1742000003600000]               ;; per-fact expiry (1 hour)
          [[:method "post"] 1742000000030000]               ;; per-fact expiry (30 seconds)
          [:max-qty 100]]}                                  ;; inherits envelope expires
 :signer-key <bytes>
 :request-id <uuidv7>
 :expires    1742000086400000}     ;; ceiling: 24 hours
```

Fact representation:
- `[fact]` — bare fact, inherits envelope `:expires`
- `[fact expires-ms]` — fact with per-fact absolute expiry, must be ≤ envelope `:expires`

### Temporal Fact Validity

Every fact has a **validity window** — a time range during which the
assertion is true:

```
valid-from  ≤  T  <  valid-to    →  fact is live at time T
```

- `valid-from` — when the assertion starts being true (default: signing time from UUIDv7)
- `valid-to` — when the assertion stops being true (default: envelope `:expires`)

```clojure
;; Fact representations in the message
[:effect :read]                                 ;; inherits both from envelope
[[:method "post"] {:valid-to 1742000000030000}] ;; custom expiry, inherits valid-from
[[:scheduled-action :rebalance]                 ;; explicit window
 {:valid-from 1742000050000000                  ;; starts in 50 seconds
  :valid-to   1742000086400000}]                ;; expires in 24 hours
```

Defaults when not specified:
- `valid-from` = envelope signing time (extracted from UUIDv7 request-id)
- `valid-to` = envelope `:expires`

Constraints:
- Per-fact `valid-to` must be ≤ envelope `:expires` (the ceiling)
- Per-fact `valid-from` must be ≥ envelope signing time (can't assert before signing)

### Pre-Evaluation Filter

Before Datalog evaluation, the fact store is filtered by `now`:

```clojure
(defn live-at? [now {:keys [valid-from valid-to]}]
  (and (<= valid-from now) (< now valid-to)))
```

Two comparisons per fact. Captures `now` once, filters once, evaluates
against the frozen set. The result is valid as of that moment.

This filter is the **only** temporal logic in the current implementation.
No `:as-of` parameter, no cross-time queries, no temporal scanning.
The data model carries full validity windows (`valid-from` + `valid-to`),
but the runtime only asks: "is this fact live right now?"

### Data Model vs. Queries

The fact store retains `valid-from` and `valid-to` on every fact,
even though the current implementation only uses them for the simple
live-at-now filter. This is intentional:

**Data model (complete — do now):**
- Every fact carries `valid-from` and `valid-to`
- Pre-eval filter checks both bounds
- Expired facts retained in the store (not deleted)

**Advanced queries (enabled by data model — do later):**
- `:as-of` evaluation at arbitrary timestamps
- Denial diagnostics: "would this have worked 5 seconds ago?"
- Audit: "was this authorized at processing time?"
- Temporal scanning for expiry analysis

The advanced queries require no schema or data model changes — only
query-level work. The data is already there when they're needed.

```clojure
;; Current: simple filter
(evaluate facts)  ;; filters at now, evaluates

;; Future (no data changes needed, only query changes):
(evaluate facts {:as-of (- now 5000)})  ;; same data, different filter time
```

### Expiry Subsumes Several Mechanisms

| Mechanism | How temporal validity handles it |
|---|---|
| Request freshness | Short `valid-to` on request facts (30s) |
| Session validity | Medium `valid-to` on session facts (hours) |
| Policy rotation | Long `valid-to` on policy facts (days) |
| Scheduled actions | `valid-from` in the future |
| Soft revocation | Don't renew — let `valid-to` pass naturally |
| Immediate revocation | Revocation facts in Datalog (separate, orthogonal) |
| Audit trail | Retain all facts, query at historical `:as-of` time |
| Denial diagnostics | Re-evaluate at earlier time to find what expired |

---

## What the Envelope Does NOT Do

The envelope is deliberately minimal. These concerns belong above it:

| Concern | Belongs to | Why not in envelope |
|---|---|---|
| **Replay protection** | PEP (replay cache) | Requires stateful tracking of seen request-ids |
| **Trust evaluation** | PEP (Datalog join) | Requires trust-root configuration |
| **Audience binding** | Application layer | Application knows its own identity |
| **Message interpretation** | Application layer | Envelope is message-opaque |
| **Revocation** | PEP (Datalog facts) | Revocation is just more facts in the join |
| **Block structure** | Application layer | Message is any EDN — blocks are app semantics |

---

## Relation to Existing Stroopwafel Primitives

| Primitive | Purpose | Lifetime |
|---|---|---|
| **Token** (stroopwafel.core) | Chain of signed capability blocks with attenuation | Long-lived (hours/days) |
| **Envelope** (stroopwafel.envelope) | Single signed assertion with per-fact expiry | Short-lived (seconds/minutes) |
| **CEDN** (canonical-edn) | Deterministic serialization for signing | N/A (serialization layer) |
| **UUIDv7** (uuidv7) | Timestamp + nonce for request identification | N/A (identity layer) |

A typical authorization flow uses both:

```
Token:    long-lived capability grant (what the agent is allowed to do)
Envelope: short-lived request assertion (what the agent wants to do right now)
```

The PEP receives both, extracts facts from both, adds trust-root facts,
and evaluates the combined fact set in Datalog. The token's facts have
long expiry. The envelope's request facts have short expiry. Per-fact
expiry means both can coexist in the same evaluation — the Datalog engine
just sees live facts, regardless of which artifact they came from.

---

## Dependencies

- **CEDN** — for deterministic serialization (`cedn/canonical-bytes`)
- **UUIDv7** — for request-id generation and timestamp extraction
- **Ed25519** — for signing and verification (JCA on JVM, bb-compatible)
- No other dependencies. The envelope is a standalone primitive.

---

## Implementation Notes

**Portability:** All operations must work on JVM, Babashka, and ClojureScript.
No `java.security.PublicKey` in the API — use byte arrays for keys.
Ed25519 operations via the same primitives stroopwafel.crypto already uses.

**CEDN is load-bearing:** The signature is over CEDN bytes, not `pr-str`.
Two structurally identical maps must produce the same bytes, which only
CEDN guarantees. Standard EDN printing is not stable enough for signatures.

**UUIDv7 is both timestamp and nonce:** No need for separate timestamp and
nonce fields. The UUIDv7 encodes millisecond-precision time (extractable
via `uuidv7/extract-ts`) and guarantees uniqueness (74-bit monotonic counter).
One field, two properties.

---

*This spec is the target design for `stroopwafel.envelope`.
To be implemented in the stroopwafel repo, replacing `stroopwafel.request`.
Current alpaca-clj code approximates this design in `alpaca.auth`.*
