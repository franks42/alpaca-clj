# Trust Roots and Enforcement: Where the Chain Ends

> The trust root in a capability system is not the authority that signs tokens.
> It is the enforcement actor — the process that holds the resource and decides
> whether to grant access. Everything else is delegation from that root.
>
> This document formalizes where trust chains end in alpaca-clj and why it
> matters for systems with multiple authorities, each trusted for a limited domain.

---

## The Question Nobody Asks

When someone presents a signed capability token to a proxy, the natural
question is: "is this token valid?" The verification is straightforward:
check the cryptographic signature chain back to a trusted root key.

But there is a prior question that almost nobody asks:

**Who decided to trust that root key, and why?**

The answer is: the enforcement actor decided. The proxy process read a
public key from an environment variable and chose to accept tokens signed
by that key. That choice is the true root of trust — not the key itself.

---

## The Trust Chain, Made Explicit

In alpaca-clj, the trust chain from the protected resource outward:

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Alpaca Markets                                           │
│    Trusts: whoever holds the API key+secret                 │
│    This is a credential-based trust — Alpaca has no idea    │
│    about tokens, agents, Datalog, or capabilities.          │
└──────────────────────────┬──────────────────────────────────┘
                           │ API key (the resource boundary)
┌──────────────────────────▼──────────────────────────────────┐
│ 2. The Proxy Process (THE TRUST ROOT)                       │
│                                                             │
│    Holds: Alpaca API key (controls the resource)            │
│    Is: a deterministic process, not an LLM                  │
│    Decides: which authority keys to trust (from config)     │
│    Enforces: token verification, signature checks,          │
│              effect classes, domains, replay protection      │
│    Could: ignore all of this and allow everything           │
│            (but its code chooses not to)                     │
│                                                             │
│    THE PROXY IS THE TRUST ROOT BECAUSE IT IS THE ACTOR      │
│    WITH ACTUAL POWER OVER THE RESOURCE.                     │
└──────────────────────────┬──────────────────────────────────┘
                           │ "I choose to trust this key"
                           │ (from STROOPWAFEL_ROOT_KEY config)
┌──────────────────────────▼──────────────────────────────────┐
│ 3. Policy Authority (identified by root public key)         │
│                                                             │
│    Holds: root Ed25519 private key                          │
│    Trusted for: issuing capability tokens                   │
│    Trusted by: the proxy (because the proxy chose to        │
│                trust this specific key)                      │
│    Cannot: force the proxy to do anything — the proxy       │
│            could reject all tokens if it wanted to           │
└──────────────────────────┬──────────────────────────────────┘
                           │ signed capability token
┌──────────────────────────▼──────────────────────────────────┐
│ 4. AI Agent (identified by agent public key)                │
│                                                             │
│    Holds: capability token + own private key                │
│    Trusted for: nothing — must prove everything             │
│    Proves: identity (signed request) + authorization (token)│
│    Cannot: exceed token's capability grants                 │
│            forge signatures                                 │
│            talk to Alpaca directly                           │
└─────────────────────────────────────────────────────────────┘
```

The critical insight is at level 2. The proxy is not a passive relay that
mechanically follows the authority's instructions. The proxy is an
autonomous actor that **chooses** to delegate its trust to the authority
key. That delegation is itself a trust decision — the most fundamental one
in the system.

---

## Why This Matters: The Enforcement Actor's Autonomy

Consider what the proxy could do if it wanted to:

- **Ignore the root key entirely** and allow all requests (broken, but possible)
- **Use a different root key** than the one configured (compromised, but possible)
- **Apply hardcoded rules** beyond what the Datalog policy says (defense in depth)
- **Refuse certain operations** regardless of what any token grants (safety invariants)
- **Trust multiple authorities** for different domains (multi-root trust)

Every one of these is a decision the enforcement actor makes. The authority
key doesn't control the proxy — the proxy controls itself and *chooses*
to listen to the authority.

In traditional systems, this autonomy is invisible because the enforcement
actor is a simple deterministic program whose behavior is fixed at compile
time. Nobody asks "what does the proxy choose to trust?" because the answer
is hardcoded and obvious.

But the moment you have:
- Multiple authorities, each trusted for different domains
- LLM-based agents that might become enforcement actors
- Dynamic trust configuration that changes at runtime
- Federated identity providers attesting group membership

...the enforcement actor's autonomy becomes the foundation that everything
else rests on. If you can't state precisely what the enforcement actor
trusts, you can't reason about the security of any token, any signature,
or any policy.

---

## Multiple Authorities, Scoped Trust

The simplest model has one root key trusted for everything. But real
systems need multiple authorities, each trusted for a limited domain.

Consider a trading proxy that trusts:

```
Authority A (trading desk lead)    — trusted to grant: write on trade
Authority B (compliance officer)   — trusted to grant: read on account
Authority C (market data admin)    — trusted to grant: read on market
External IdP (corporate SSO)       — trusted to attest: group membership
```

No single authority has ambient power over the entire system. The trading
desk lead can grant trading capabilities but cannot authorize account
access. The compliance officer can grant account read access but cannot
authorize trades. This is separation of duty expressed as scoped trust
delegation.

The proxy's trust declaration:

```clojure
{:trust-roots
 [{:key       authority-a-pk
   :name      "trading-desk"
   :scoped-to {:effects #{:write :destroy}
               :domains #{"trade"}}}

  {:key       authority-b-pk
   :name      "compliance"
   :scoped-to {:effects #{:read}
               :domains #{"account"}}}

  {:key       authority-c-pk
   :name      "market-data-admin"
   :scoped-to {:effects #{:read}
               :domains #{"market"}}}

  {:key       idp-pk
   :name      "corporate-sso"
   :type      :third-party
   :scoped-to {:attestations #{:named-key}}}]}
```

A token signed by Authority A granting `[:effect :read] [:domain "account"]`
would be **rejected** — not because the signature is invalid, but because
the proxy's trust root scoping says Authority A is only trusted for
write/destroy on trade. The signature is valid but the authority is acting
outside its trusted scope.

This is the key difference between **signature verification** (is the
crypto valid?) and **trust evaluation** (do I trust this signer for this
claim?). Most systems conflate the two. A scoped trust model separates them.

---

## The XACML Parallel

In XACML terms:

| XACML Concept | alpaca-clj Equivalent |
|---|---|
| PEP (Policy Enforcement Point) | The proxy process — the trust root |
| PDP (Policy Decision Point) | The Datalog evaluator + token verifier |
| PAP (Policy Administration Point) | The policy authority that mints tokens |
| PIP (Policy Information Point) | Injected account state (buying power, P&L) |

The PDP *recommends*. The PEP *enforces*. The distinction matters because:

- The PDP can say "allow" — the PEP might still deny (hardcoded safety rules)
- The PDP can say "deny" — the PEP cannot override to allow (closed world)
- The PAP issues policies — the PEP chooses which PAPs to trust
- The PIP provides facts — the PEP chooses which PIPs to query

The PEP's choices are the trust root. Everything else is advisory.

In XACML working groups, this distinction was considered "too theoretical"
because the PEP was always a simple deterministic gateway. With LLM agents
in the picture — agents that might be asked to enforce policies, agents
that might interpret policies creatively, agents that might be convinced
to ignore policies — the distinction becomes operational, not theoretical.

**The enforcement actor must be deterministic code, not an LLM.** The LLM
is always the requester, never the enforcer. An LLM enforcement actor has
a probabilistic "self" — its willingness to follow the PDP's recommendation
is influenced by prompts, context, and training. That is not a trust root.

---

## Formalizing the Trust Root Declaration

The proxy should make its trust decisions explicit and auditable.
At startup, it declares:

```clojure
{:trust-root
 {:type      :deterministic-process
  :identity  {:pid (process-pid) :host (hostname) :started-at (now)}
  :controls  [:alpaca-api-credentials]
  :invariants
  ["No direct Alpaca access from outside this process"
   "Sealed tokens only — no unsealed tokens accepted"
   "No destroy-all without explicit grant"
   "Live trading requires explicit opt-in"
   "Replay protection enforced on all write/destroy operations"]}

 :delegated-trust
 [{:entity    :root-authority
   :key       STROOPWAFEL_ROOT_KEY
   :source    :environment-variable
   :scoped-to {:effects #{:read :write :destroy}
               :domains #{"account" "market" "trading" "trade"}}
   :delegated [:token-issuance :capability-grants]}]

 :enforcement
 [:token-signature-verification
  :request-signature-verification
  :effect-class-checks
  :domain-checks
  :replay-protection
  :timestamp-freshness]}
```

This declaration answers the question "what does this proxy trust and why?"
at any point in time. It is:

- **Auditable** — logged at startup, queryable via API
- **Explicit** — no implicit trust, no ambient authority
- **Scoped** — each trusted entity has bounded authority
- **Invariant-carrying** — hardcoded rules that no token can override

---

## The Three Layers of Enforcement

The proxy enforces at three layers, in order of precedence:

### Layer 1: Hardcoded Invariants (highest precedence)

Rules built into the proxy's code that no token, no authority, and no
configuration can override:

- The proxy process is the only process with Alpaca API credentials
- Unsealed tokens are never accepted from external sources
- `destroy_all` operations require explicit, specific grants
- Live trading requires explicit opt-in at the configuration level
- The enforcement actor is deterministic code, never an LLM

These are the proxy's "self" — its non-negotiable behaviors. They exist
because the proxy is the trust root and some decisions are too important
to delegate.

### Layer 2: Configured Trust (medium precedence)

Trust decisions made at deployment time via configuration:

- Which authority keys to trust
- What scope each authority has
- Which IdPs to trust for third-party attestations
- Whether to run in paper or live mode
- Freshness windows and replay cache parameters

These can change between deployments but not at runtime. They are the
proxy's delegation decisions — "I choose to trust Authority A for
trading capabilities."

### Layer 3: Token-Evaluated Policy (lowest precedence)

Decisions made by evaluating the Datalog policy in the token against
the request context:

- Does the token grant the required effect class?
- Does the token grant the required domain?
- Does the agent key match the token's binding?
- Do temporal constraints pass?
- Do quantity/symbol restrictions pass?

This is where the rich capability model lives — but it operates within
the bounds set by layers 1 and 2. A token cannot override a hardcoded
invariant. A token signed by an authority outside its configured scope
is rejected before Datalog evaluation even begins.

---

## Why Formalize This?

Three reasons:

**1. Multi-authority systems require scoped trust.**

When multiple authorities each cover a limited domain, you need to
state precisely what each is trusted for. Without formalization, you get
ambient authority by accumulation — each authority's scope bleeds into
a de facto ambient set.

**2. LLMs make enforcement actor autonomy visible.**

In a world where LLMs might be enforcement actors (they shouldn't be,
but they will be), the question "what does the enforcer choose to trust?"
is no longer theoretical. The enforcer's "self" is the trust root, and
if that self is probabilistic, the trust root is unstable.

**3. Audit requires knowing what was trusted, not just what was decided.**

When reviewing a security incident, knowing "the request was allowed"
is not enough. You need to know: which authority signed the token, was
that authority trusted for this type of operation, what invariants were
in effect, and what the enforcement actor's trust configuration was at
the time. The trust root declaration is the foundation of that audit.

---

## Connection to the Authorization Progression

The four-level authorization progression (bearer → SPKI → SDSI → federated)
described in `authorization-progression.md` is built on top of this
trust root:

```
Level 1 (bearer):     trust-root → one authority → bearer token → allow
Level 2 (SPKI):       trust-root → one authority → bound token + signed request → allow
Level 3 (SDSI):       trust-root → one authority → group token + signed request + roster → allow
Level 4 (federated):  trust-root → authority + IdP → group token + IdP attestation + signed request → allow
```

At every level, the trust root is the same: the enforcement actor.
What changes is the length and richness of the delegation chain between
the trust root and the final allow/deny decision.

The trust root formalization ensures that no matter how long the chain
gets — multiple authorities, multiple IdPs, multiple levels of
attenuation — there is always a fixed point at the bottom that says:
"I am the process that controls the resource, and here is what I
choose to trust."

---

## Inert Facts: The Closed-World Safety Property

A critical safety property of the Datalog-based trust model: **facts without
matching trust roots are inert.** They sit in the fact store doing nothing.

Consider a token with `[:signer-key <unknown-pk>]` presented to the proxy.
The proxy adds the token's facts to the Datalog DB. But the proxy's config
has no `[:trusted-root <unknown-pk> ...]` fact for this key. The authorization
join requires both:

```
[:signer-key ?k] ∧ [:trusted-root ?k ?effect ?domain]
```

Without the second fact, the join never fires. No derived facts, no policy
match, closed-world default → deny. The unknown signer's facts are just
stroopwafel filling — they take up space but can't contribute to an allow
decision.

This is fundamentally different from systems where adding facts can grant
access. In our model, **only the enforcement actor can add trust-root facts**
(from its configuration). Without those, no amount of token-supplied facts
can produce an allow. The token can say whatever it wants — `[:effect :write]`,
`[:domain "trade"]`, `[:right "admin" :destroy "everything"]` — none of it
matters without a matching trust root.

The worst case for an unrecognized token is wasted space in the fact store,
not unauthorized access. And since fact stores are per-request (not
persistent), even the space is reclaimed immediately.

This property holds regardless of fact insertion order: trust-root facts
can be added before or after token facts. The Datalog engine joins them
when it evaluates, not when they're inserted. An authority key added to
the config after a token was already processed would authorize future
requests from that authority — but never retroactively authorize past ones
(those evaluations already completed with a deny).

---

*This is the foundation. Everything else is delegation.*

---

*Document status: design reference.*
*Last updated: March 2026.*
*Related: `authorization-progression.md`, `alpaca-stroopwafel-capabilities.md`*
