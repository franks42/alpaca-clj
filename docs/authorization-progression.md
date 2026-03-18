# Authorization Progression: Bearer → SPKI → SDSI → Federated

> How alpaca-clj's authorization upgrades seamlessly from simple bearer tokens
> to federated multi-party identity — without changing the proxy code.
>
> All four levels use the same mechanism: Datalog fact matching in stroopwafel.
> The difference is which facts are present and how they get there.

---

## The Core Insight

There is no "bearer auth system" and "SPKI auth system" and "SDSI auth system."
There is **one system**: the proxy verifies a token, optionally verifies a request
signature, injects facts into a Datalog evaluator, and checks if a policy matches.

Moving from bearer to SPKI to SDSI to federated is adding richer facts and
rules to the authorizer configuration. The proxy middleware, the token format,
the request signing, the Datalog engine — all unchanged.

---

## Level 1 — Bearer Token

**Trust model:** Whoever holds the token can use it.

**Token facts:**
```clojure
[:effect :read]
[:domain "market"]
```

**Authorizer checks:**
```clojure
{:checks [{:id :e :query [[:effect :read]]}
          {:id :d :query [[:domain "market"]]}]}
```

**What the proxy does:**
1. Extract Bearer token from `Authorization` header
2. Verify token signature chain (root key)
3. Evaluate Datalog: does the token carry the required effect + domain?
4. Allow or deny

**Security properties:**
- Token scoped by effect class and domain
- Token is sealed (can't expand authority)
- But: token theft = full access within scope

**When to use:** Development, internal services, short-lived sessions with
low-value operations.

**alpaca-clj status:** Implemented (v0.2.0).

---

## Level 2 — SPKI (Signed Request / Requester Binding)

**Trust model:** Token is bound to a specific agent's Ed25519 public key.
The agent must prove it holds the corresponding private key on every request.

**Token facts:**
```clojure
[:authorized-agent-key <agent-pk-bytes>]  ;; NEW — binds to agent
[:effect :read]
[:domain "market"]
```

**Request signing (agent side):**
```clojure
(req/sign-request {:symbol "AAPL"} (:priv agent-kp) (:pub agent-kp))
;; → {:body {...} :agent-key <bytes> :sig <bytes> :timestamp <ms>}
```

**Authorizer rules + checks:**
```clojure
{:facts [[:request-verified-agent-key <verified-key>]]  ;; from signature verification
 :rules '[{:id   :agent-bound
           :head [:agent-can-act ?k]
           :body [[:authorized-agent-key ?k]        ;; from token
                  [:request-verified-agent-key ?k]]}]  ;; from verified request
 :checks [{:id :e :query [[:effect :read]]}
          {:id :d :query [[:domain "market"]]}]
 :policies '[{:kind :allow :query [[:agent-can-act ?k]]}]}
```

**What the proxy does (additionally):**
1. Extract `X-Agent-Signature` header
2. Verify request signature → get `verified-key`
3. Inject `[:request-verified-agent-key verified-key]` into authorizer facts
4. Datalog join: `[:authorized-agent-key ?k]` ∧ `[:request-verified-agent-key ?k]`
   — both must bind `?k` to the **same** public key bytes

**Security properties:**
- Token theft neutralized — useless without agent's private key
- Proof-of-possession on every request (not just at session start)
- Agent identity is cryptographic, not ambient

**When to use:** AI agents with trading authority, any scenario where token
exfiltration is a realistic threat.

**alpaca-clj status:** Implemented (v0.3.1). SSH key reuse via `alpaca.ssh`.

---

## Level 3 — SDSI (Named Groups)

**Trust model:** Token grants authority to a *name* (group). The authorizer
maintains a roster of which keys belong to that name. Group membership
changes don't require token reissuance.

**Token facts:**
```clojure
[:right "traders" :read "market"]     ;; authority granted to the NAME
[:right "traders" :write "trade"]     ;; not to a specific key
```

**Authorizer facts + rules:**
```clojure
{:facts [[:request-verified-agent-key <verified-key>]
         ;; SDSI name bindings — the group roster
         [:named-key "traders" alice-pk-bytes]
         [:named-key "traders" bob-pk-bytes]
         [:named-key "monitors" carol-pk-bytes]]
 :rules '[{:id   :resolve-name
           :head [:authenticated-as ?name]
           :body [[:named-key ?name ?k]
                  [:request-verified-agent-key ?k]]}]
 :policies '[{:kind  :allow
              :query [[:authenticated-as ?name]
                      [:right ?name ?action ?resource]]}]}
```

**Datalog chain:**
```
[:named-key "traders" ?k]           — key belongs to group (authorizer roster)
[:request-verified-agent-key ?k]     — request signed by this key
→ [:authenticated-as "traders"]      — derived: agent is a "trader"
[:right "traders" :read "market"]    — token grants "traders" read on market
→ ALLOW
```

**Security properties:**
- All SPKI properties (proof-of-possession)
- Group membership managed in authorizer config, not in tokens
- Add/remove agents = config change, not token reissuance
- One token can serve multiple agents (reduced token management overhead)
- Different groups can have different capability profiles

**Operational model:**
```
Token issuance:  rare (per-role, not per-agent)
Group roster:    updated as agents come and go (config file, database, or API)
Token content:   stable — "traders can read market + write trade"
```

**When to use:** Multiple AI agents with the same role, team-based access control,
scenarios where agent lifecycle is faster than token lifecycle.

**alpaca-clj status:** Available (stroopwafel 0.9.0). Not yet wired into proxy
middleware — the authorizer rules need to be configurable. Phase 5 item.

---

## Level 4 — Federated (Third-Party Blocks)

**Trust model:** An external Identity Provider (IdP) attests group membership
by signing a third-party block. Neither the token authority nor the execution
service needs to know the group roster in advance — they just trust the IdP's key.

**Token facts (authority block):**
```clojure
[:right "verified-users" :read "market"]
```

**Third-party block (signed by IdP, bound to this specific token):**
```clojure
[:named-key "verified-users" agent-pk-bytes]
```

**Issuance flow:**
```clojure
;; 1. Token holder creates a request for the IdP
(def tp-request (sw/third-party-request token))

;; 2. IdP verifies the agent (email, MFA, corporate directory, etc.)
;;    and signs a block attesting the name→key binding
(def tp-block
  (sw/create-third-party-block
    tp-request
    {:facts [[:named-key "verified-users" agent-pk-bytes]]}
    {:private-key (:priv idp-kp) :public-key (:pub idp-kp)}))

;; 3. Token holder appends the IdP's block
(def token-with-idp (sw/append-third-party token tp-block))
```

**Authorizer (trusts the IdP):**
```clojure
{:trusted-external-keys [(:pub idp-kp)]
 :facts [[:request-verified-agent-key <verified-key>]]
 :rules '[{:id   :resolve-name
           :head [:authenticated-as ?name]
           :body [[:named-key ?name ?k]
                  [:request-verified-agent-key ?k]]}]
 :policies '[{:kind  :allow
              :query [[:authenticated-as ?name]
                      [:right ?name ?action ?resource]]}]}
```

**Datalog chain (same as Level 3, but the name→key binding comes from
a separately signed block):**
```
[:named-key "verified-users" ?k]     — from IdP's third-party block (trusted)
[:request-verified-agent-key ?k]     — from request signature
→ [:authenticated-as "verified-users"]
[:right "verified-users" :read "market"] — from authority block
→ ALLOW
```

**Security properties:**
- All SPKI + SDSI properties
- Decentralized trust — multiple IdPs can attest different groups
- Authority doesn't need to know agent identities (privacy)
- IdP doesn't need to know what capabilities the token grants (separation)
- Cross-organizational: IdP in org A, token authority in org B, execution in org C

**When to use:** Multi-organization deployments, external agent verification,
regulatory scenarios where identity and authorization must be separated.

**alpaca-clj status:** Stroopwafel supports third-party blocks. Not yet
integrated into proxy. Future work.

---

## The Progression Is Seamless

| Level | Token changes | Proxy code changes | Authorizer config changes |
|---|---|---|---|
| 1 → 2 | Add `[:authorized-agent-key]` fact | None — middleware already checks header | Add agent-bound rules + policies |
| 2 → 3 | Replace key binding with group rights | None | Replace key-specific facts with group roster |
| 3 → 4 | Same | None | Add `trusted-external-keys`, same rules |

The proxy middleware does the same thing at every level:
1. Verify token signature (root key)
2. Verify request signature if `X-Agent-Signature` present
3. Inject facts
4. Evaluate Datalog
5. Allow or deny

**The security level is determined by policy, not by code.**

---

## Why This Works: One Mechanism, Four Expressions

The four levels are not four different auth systems. They are four
configurations of the same Datalog evaluator:

```
Level 1:  token-fact = request-fact             (bearer match)
Level 2:  token-fact ∧ verified-key = token-key (SPKI join)
Level 3:  token-fact ∧ name→key ∧ verified-key  (SDSI join)
Level 4:  token-fact ∧ idp-attested-name→key ∧ verified-key (federated SDSI join)
```

Each level adds one more fact to the join. The join semantics are the same.
The Datalog engine doesn't know or care which "level" it's evaluating — it
just matches patterns and checks that all required facts are present.

This is the SPKI/SDSI design from 1996, expressed as Datalog facts instead
of certificate fields. The industry spent 25 years on bearer tokens (OAuth 2.0),
realized bearer is insufficient, and is now adding proof-of-possession back
(DPoP RFC 9449 in 2023, GNAP RFC 9635 in 2024). Stroopwafel expresses what
SPKI got right from the start, in a form that composes with modern
authorization logic (Datalog) instead of certificate chain validation.

---

## Safety Property: Inert Facts

A fact in the Datalog DB without a matching trust root is inert — it cannot
contribute to an allow decision. This holds across all four levels.

A token can carry any facts it wants: `[:effect :write]`, `[:domain "trade"]`,
`[:right "admin" :destroy "everything"]`. None of it matters without a
corresponding `[:trusted-root <signer-key> ...]` fact from the enforcement
actor's configuration. The authorization join requires both sides:

```
[:signer-key ?k] ∧ [:trusted-root ?k ?effect ?domain]
```

Without the trust root half, the join never fires. Closed-world default → deny.
The worst case for an unrecognized signer is wasted space in the per-request
fact store, not unauthorized access. Facts are just stroopwafel filling
without the matching trust root to complete the join.

---

*Document status: design reference, reflecting alpaca-clj v0.5.2 + stroopwafel 0.9.0.*
*Last updated: March 2026.*
