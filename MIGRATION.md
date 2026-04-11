# Migration Task: Rewrite alpaca-clj for Three-Library Auth Stack

## What This Is

alpaca-clj needs to be rewritten to use the new three-library authorization stack:

```
signet              — crypto primitives (keys, signing, chains)
stroopwafel         — pure Datalog engine (evaluate facts → decision)
stroopwafel-pdp     — bridge (verify chains → extract facts → evaluate)
```

The old monolithic stroopwafel (with crypto baked in) is gone. The new stroopwafel is a pure Datalog engine with zero deps. All crypto moved to signet. The bridge between them is stroopwafel-pdp.

## Current State

- `bb.edn` already updated to depend on all three via `local/root`
- All three libraries load successfully in bb
- Git is clean at commit `ff5e8d5` (stashed a broken partial attempt)
- 110 tests exist but depend on the OLD stroopwafel API (will fail until rewritten)

## The Three Libraries

### signet (../signet)

Crypto primitives. 54 tests, all passing.

Key types are **records**, not JCA objects:
```clojure
(require '[signet.key :as key])
(def kp (key/signing-keypair))  ;; Ed25519KeyPair record
(:x kp)   ;; 32-byte public key (raw bytes)
(:d kp)   ;; 32-byte private seed (raw bytes)
(key/kid kp)  ;; "urn:signet:pk:ed25519:<base64url>"
```

Signing:
```clojure
(require '[signet.sign :as sign])
(sign/sign-edn kp {:method "post" :path "/market/quote"})
;; → {:type :signet/signed :envelope {:message ... :signer <kid> :request-id <uuid>} :signature <bytes>}

(sign/verify-edn envelope)
;; → {:valid? bool :message ... :signer <kid> :request-id <uuid> :timestamp <ms> ...}
```

Chains (token lifecycle):
```clojure
(require '[signet.chain :as chain])
(def token (chain/extend kp {:facts [[:capability "alice" :read "/data"]]}))
(def token (chain/extend token {:checks [{:id :read-only :query [[:capability "alice" :read "/data"]]}]}))
(def sealed (chain/close token))
(chain/verify sealed)  ;; → {:valid? bool :root <kid> :blocks [...] ...}
```

SSH import:
```clojure
(require '[signet.ssh :as ssh])
(def kp (ssh/load-keypair "~/.ssh/id_ed25519"))  ;; returns Ed25519KeyPair record
```

### stroopwafel (../stroopwafel)

Pure Datalog engine. 66 tests. **ZERO external deps.**

Only function that matters:
```clojure
(require '[stroopwafel.core :as sw])
(sw/evaluate
  {:blocks [{:facts [[:capability "alice" :read "/data"]]
             :rules []
             :checks []}]}
  :authorizer {:facts [[:requested-effect :read]]
               :policies [{:kind :allow :query [[:capability "alice" :read "/data"]]}]})
;; → {:valid? true/false}
```

Blocks are **plain maps** with `:facts`, `:rules`, `:checks`. No envelopes, no signing.

### stroopwafel-pdp (../stroopwafel-pdp)

Bridge. 19 tests. Depends on signet + stroopwafel.

```clojure
(require '[stroopwafel.pdp.core :as pdp])
(require '[stroopwafel.pdp.trust :as trust])
(require '[stroopwafel.pdp.replay :as replay])

(-> (pdp/context)
    (pdp/add-chain signed-chain {:trust-root (key/kid root-kp)})
    (pdp/add-signed-envelope signed-request)
    (pdp/add-facts [[:current-time (System/currentTimeMillis)]])
    (pdp/decide :policies [{:kind :allow :query [...]}]))
;; → {:allowed? true/false}
```

## Files to Rewrite

### 1. `src/alpaca/auth.clj` — THE BIG ONE (437 lines)

Current: uses old `stroopwafel.core` (issue/seal/verify/evaluate), `stroopwafel.crypto`, `stroopwafel.envelope`, `stroopwafel.replay`, `stroopwafel.trust`.

New: use signet for crypto, stroopwafel.core/evaluate for Datalog, stroopwafel.pdp.replay for replay, stroopwafel.pdp.trust for trust roots.

**Key API changes:**

| Old | New |
|---|---|
| `(sw/new-keypair)` → `{:priv :pub}` JCA | `(key/signing-keypair)` → Ed25519KeyPair record |
| `(sw-crypto/encode-public-key pk)` → bytes | `(:x kp)` or `(key/kid kp)` → kid URN |
| `(sw-crypto/decode-public-key bytes)` | `(key/kid->public-key kid)` |
| `(sw-crypto/bytes= a b)` | `(java.util.Arrays/equals a b)` |
| `(sw-crypto/hex->bytes hex)` | Use signet.impl.jvm or inline |
| `(sw-crypto/bytes->hex bs)` | Use signet.impl.jvm or inline |
| `(sw/issue {:facts [...]} {:private-key sk :public-key pk})` | `(chain/extend kp {:facts [...]})` |
| `(sw/seal token)` | `(chain/close token)` |
| `(sw/verify token {:public-key pk})` | `(:valid? (chain/verify token))` |
| `(sw/evaluate token :authorizer {...})` | Extract blocks from verified chain, call `(sw/evaluate {:blocks [...]} :authorizer {...})` |
| `(envelope/sign msg sk pk ttl)` | `(sign/sign-edn kp msg {:ttl ttl})` |
| `(envelope/verify outer)` | `(sign/verify-edn outer)` |
| `(replay/create-replay-guard)` | `(stroopwafel.pdp.replay/create-replay-guard)` |
| `(trust/trust-root-facts roots)` | `(stroopwafel.pdp.trust/trust-root-facts roots)` |

**The `generate-keypair` function** should return a signet Ed25519KeyPair record. All downstream code uses the signet API. The old `{:priv PrivateKey :pub PublicKey}` format is gone.

**The `issue-token` function** should:
1. Build facts vector
2. `(chain/extend kp {:facts all-facts})`
3. `(chain/close token)`
4. Serialize to CEDN string

**The `verify-and-authorize` function** should:
1. Deserialize token from CEDN string
2. `(chain/verify token)` → check valid + root matches trust
3. Extract bare blocks from verified chain for sw/evaluate
4. Handle bearer / bound / SDSI paths via Datalog rules

**The `sign-request` function** should use `sign/sign-edn` with the agent's signet keypair.

**Important**: The verified chain's blocks contain `{:data {:facts [...] ...} :next-key ... :prev-sig ...}`. To feed stroopwafel, extract `:data` from each block and use as bare blocks.

### 2. `src/alpaca/envelope.clj` (10 lines → bridge to signet.sign)

Current: delegates to old stroopwafel.envelope.
New: bridge to signet.sign with the old (sk, pk, ttl) calling convention.

```clojure
(defn sign [message private-key public-key & [ttl-seconds]]
  ;; Convert JCA keys to signet keypair, call sign-edn
  ...)
(defn verify [outer]
  ;; Call sign/verify-edn, add :signer-key for backward compat
  ...)
```

**OR**: eliminate envelope.clj entirely and have auth.clj use signet.sign directly.

### 3. `src/alpaca/ssh.clj` (10 lines → bridge to signet.ssh)

Current: delegates to old stroopwafel.ssh, returns `{:priv :pub}`.
New: delegate to signet.ssh, return signet Ed25519KeyPair record.

### 4. `src/alpaca/pep.clj` (PEP pipeline)

Current: delegates to old stroopwafel.pep.
New: the PEP pipeline code needs to live here (it was moved out of stroopwafel). Copy from `../stroopwafel/_migrated/src/pep.clj` and adapt — it's Ring middleware that canonicalizes requests and calls the auth layer.

### 5. `src/alpaca/client_pep.clj`

Uses auth.clj for token issuance + stroopwafel for evaluation. Update to use signet.chain for issuance and stroopwafel.core/evaluate for policy checking.

### 6. `src/alpaca/cli/token.clj`

Uses auth.clj for keypair generation, token issuance, inspection. Update to use signet API.

### 7. `src/alpaca/cli/common.clj`

Uses auth.clj for request signing. Update to use signet.sign.

### 8. `src/alpaca/pep/http_edn.clj`

Uses auth.clj for verification. Should mostly be fine if auth.clj's API stays compatible.

### 9. Test files

All test files need updating for the new keypair format and API. The signet keypair record uses `:x`/`:d` instead of `:pub`/`:priv`.

## Files That DON'T Change

These are alpaca-specific and have no stroopwafel dependencies:
- `config.clj`, `client.clj`, `schema.clj`, `telemetry.clj`, `keys.clj`
- `proxy/server.clj`, `proxy/router.clj`, `proxy/handlers.clj`
- `proxy/middleware.clj` (uses pep, which wraps auth — indirect)
- `proxy/log.clj`

## Strategy

Rewrite from the inside out:

1. **auth.clj** first — everything depends on it
2. **envelope.clj** and **ssh.clj** — thin bridges, or eliminate
3. **pep.clj** — copy from migrated code, adapt
4. **client_pep.clj** — update for new auth API
5. **cli/token.clj** and **cli/common.clj** — update for new auth API
6. **Tests** — update for signet keypair records

Run `bb test` after each file to catch breakage early.

## Verification

```bash
bb test                           # Must reach 110 tests, 0 failures
clj-kondo --lint src test         # Must be 0 errors, 0 warnings
```

## Key Gotcha: JCA vs Signet Keypairs

The biggest migration pain point. Old code passes JCA PrivateKey/PublicKey objects around. New code uses signet records with `:x` (pub bytes) and `:d` (seed bytes).

If you need to bridge (e.g., for existing tests that construct JCA keypairs):
```clojure
(defn jca-kp->signet [kp]
  (let [pub-bytes  (.getEncoded (:pub kp))
        raw-pub    (byte-array (drop 12 (seq pub-bytes)))   ;; strip X.509 header
        priv-bytes (.getEncoded (:priv kp))
        seed       (byte-array (drop 16 (seq priv-bytes)))] ;; strip PKCS8 header
    (key/->Ed25519KeyPair :signet/ed25519-keypair :Ed25519 raw-pub seed)))
```

But better: just use `(key/signing-keypair)` everywhere and stop using JCA objects.

## Key Gotcha: Token Block Extraction

After `(chain/verify token)`, the result has `:blocks` which are the verified block messages. Each block message has `{:data <content> :next-key <kid> :prev-sig <bytes>}`. The `:data` field contains what was passed to `chain/extend` — typically `{:facts [...] :rules [...] :checks [...]}`.

To feed stroopwafel.core/evaluate:
```clojure
(let [result (chain/verify token)
      bare-blocks (mapv (fn [block-msg]
                          (let [data (:data block-msg)]
                            {:facts  (or (:facts data) [])
                             :rules  (or (:rules data) [])
                             :checks (or (:checks data) [])}))
                        (:blocks result))]
  (sw/evaluate {:blocks bare-blocks} :authorizer {...}))
```

## Key Gotcha: Signer Identity

Old: `[:signer-key pk-bytes]` in token facts, verified via `sw-crypto/encode-public-key`.
New: `[:signer-key kid-urn]` — use the kid URN string as the signer identity in facts.

This means trust-root facts should also use kid URNs:
```clojure
(trust/trust-root-facts "urn:signet:pk:ed25519:...")
;; → [[:trusted-root "urn:signet:pk:ed25519:..." :any :any]]
```

## Hex Utilities

Old stroopwafel.crypto had `hex->bytes` and `bytes->hex`. These are used in CLI and roster handling. Options:
- Use `signet.impl.jvm` (has the same functions as sha-256, but not hex utilities)
- Inline them (they're 2-3 lines each)
- Keep a small `alpaca.util` namespace

Simplest: inline in auth.clj:
```clojure
(defn hex->bytes [hex]
  (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16))
                   (partition 2 hex))))

(defn bytes->hex [bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))
```
