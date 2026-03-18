# alpaca-clj Review Follow-Up

Date: 2026-03-18

## Executive Summary

This pass shows meaningful progress.

Several of the highest-priority findings from the first review are now addressed in the codebase, not just in the docs:

- signed requests are now bound to method, path, and body,
- replay resistance exists via UUIDv7-based freshness and nonce checks,
- the test runner exists and the test suite executes successfully,
- and the proxy boundary now does closed-schema request validation with type coercion.

That materially improves the project's security posture and engineering credibility.

At the same time, the new design documents around dual PEP enforcement and trust roots are still mostly architectural direction rather than implemented system behavior. The core server-side capability proxy is now much stronger, but the broader trust model described in the new docs is only partially realized in the running system.

Current assessment:

- The original prototype concerns around request binding and testability have been substantially improved.
- The project now looks like a serious capability-enforced proxy rather than just a promising sketch.
- The most important remaining gaps have shifted upward from basic correctness to distributed-trust architecture: audience binding, multi-root/scoped trust, explicit trust-root declaration, and client-side enforcement.

## What Was Improved Since The First Review

### 1. Request envelope binding is now materially stronger

This was the biggest issue in the first review, and it has been addressed well.

The signed envelope now includes:

- method,
- path,
- body,
- and a UUIDv7 request-id.

On the proxy side, authorization now verifies that the signed envelope exactly matches the actual HTTP request before capability evaluation.

That closes the earlier gap where proof-of-possession authenticated only the body rather than the action envelope.

This is a real improvement, not a documentation-only claim.

Relevant implementation: [src/alpaca/auth.clj](/Users/franksiebenlist/Development/alpaca-clj/src/alpaca/auth.clj), [src/alpaca/cli/common.clj](/Users/franksiebenlist/Development/alpaca-clj/src/alpaca/cli/common.clj), [src/alpaca/pep/http_edn.clj](/Users/franksiebenlist/Development/alpaca-clj/src/alpaca/pep/http_edn.clj)

### 2. Replay defense now exists

The first review identified replay as a critical weakness. That is no longer true in the same form.

The current implementation:

- uses UUIDv7 request IDs,
- extracts embedded time for freshness validation,
- rejects stale requests,
- rejects some future-skewed requests,
- and tracks seen request IDs in a replay cache.

That is a reasonable first implementation for a single-process or small-scale deployment.

The important distinction is this:

- replay protection now exists,
- but it is still local-process replay protection, not distributed replay protection.

The in-memory cache is enough to close the original “no replay defense” finding, but not enough to satisfy the newer cross-proxy trust model described in the dual-PEP design documents.

Relevant implementation: [src/alpaca/auth.clj](/Users/franksiebenlist/Development/alpaca-clj/src/alpaca/auth.clj)

### 3. Test infrastructure is fixed

This is a major correction from the previous state.

Previously:

- `bb test` was broken,
- the test runner namespace was missing,
- and there was effectively no working regression safety net.

Now:

- [bb.edn](/Users/franksiebenlist/Development/alpaca-clj/bb.edn) points to a real runner,
- [src/alpaca/test_runner.clj](/Users/franksiebenlist/Development/alpaca-clj/src/alpaca/test_runner.clj) exists,
- and `bb test` runs successfully.

I re-ran the suite during this review and got:

- 51 tests
- 126 assertions
- 0 failures
- 0 errors

That closes the prior finding about broken test execution.

### 4. Boundary validation is stronger

The proxy handlers now enforce more of the schema contract at runtime.

Specifically:

- required params are checked,
- unknown params are rejected,
- simple coercion is applied,
- and the request surface behaves like a closed schema.

That is a real improvement over the earlier state where the schema was more descriptive than authoritative.

Relevant implementation: [src/alpaca/proxy/handlers.clj](/Users/franksiebenlist/Development/alpaca-clj/src/alpaca/proxy/handlers.clj)

### 5. Authorization layering is clearer

The PEP abstraction is cleaner now than it was in the earlier review.

The split between:

- canonicalization,
- credential extraction,
- authorization,
- allow/deny handling,

is conceptually strong and aligns well with the new trust-root documents.

This is one of the better parts of the current architecture because it makes the wire-to-policy binding explicit and reviewable.

Relevant implementation: [src/alpaca/pep.clj](/Users/franksiebenlist/Development/alpaca-clj/src/alpaca/pep.clj), [src/alpaca/pep/http_edn.clj](/Users/franksiebenlist/Development/alpaca-clj/src/alpaca/pep/http_edn.clj)

## Important Findings That Remain

## 1. Audience binding is still missing

This is now the most important security gap relative to the new design docs.

The dual-PEP design document correctly argues that the signed envelope should include an explicit audience so that a request approved for one proxy cannot be replayed against another proxy.

That is not implemented in the current code.

The current signed envelope includes:

- method,
- path,
- body,
- request-id.

It does not include:

- audience,
- proxy identity,
- host binding,
- or capability/token identity binding.

Why this matters:

- If two proxies trust the same authority and accept the same token semantics, a captured request may still be replayable across proxies.
- The local replay cache only protects the current process.
- The new design docs are explicitly right that audience is what closes the gap between client-side approval and server-side execution.

So the status here is:

- original envelope binding concern: substantially fixed,
- cross-proxy replay concern from the new docs: still open.

Relevant docs: [docs/dual-pep-client-server-enforcement.md](/Users/franksiebenlist/Development/alpaca-clj/docs/dual-pep-client-server-enforcement.md)

## 2. Replay protection is process-local, not deployment-global

The replay cache is currently an in-memory atom.

That is appropriate for a first secure implementation, but it has operational limits:

- process restart clears replay memory,
- multiple proxy instances do not share replay state,
- and horizontal scaling weakens the guarantee unless requests are sticky or replay state is externalized.

This is not a criticism of the existence of replay protection. That part is now good.

The critique is that the new trust model described in the docs implies a stronger deployment story than the current implementation provides.

Recommendation:

- For single-node paper trading, current behavior is probably sufficient.
- For multi-node or higher-assurance environments, replay state should move to a shared store or be combined with audience-scoped routing constraints.

## 3. Trust roots are still configured as a single coarse root key

The trust-root design doc is strong conceptually, but the implementation remains much simpler.

Current config supports:

- one `STROOPWAFEL_ROOT_KEY`,
- one optional roster file,
- one proxy process making one broad trust decision.

I do not see implementation for:

- multiple trusted authorities,
- scoped trust per authority,
- explicit authority domains or effect scopes,
- trusted third-party attesters with differentiated scope,
- or a queryable trust-root declaration.

This means the code does not yet realize the strongest claims in [docs/trust-roots-and-enforcement.md](/Users/franksiebenlist/Development/alpaca-clj/docs/trust-roots-and-enforcement.md).

The doc describes a future architecture in which the proxy can say:

- which authorities it trusts,
- for what scope,
- under what invariants,
- and why.

The code is not there yet.

## 4. Dual-PEP is still design-only

The client-side enforcement story is not implemented in this repository.

The current codebase includes:

- server-side PEP structure,
- request signing from the CLI side,
- and a conceptually sound path toward stronger client-side control.

What it does not include is a real client-side PEP that enforces:

- approved destinations,
- data classification constraints,
- outbound routing policy,
- or outbound content restrictions before signing and sending.

So the new dual-PEP document should be read as an architectural roadmap, not as a description of current behavior.

That is fine, but it should be kept explicit.

Relevant docs: [docs/dual-pep-client-server-enforcement.md](/Users/franksiebenlist/Development/alpaca-clj/docs/dual-pep-client-server-enforcement.md)

## 5. Schema validation is improved, but business-rule validation is still shallow

The proxy now enforces type and closed-map correctness better than before, which is good.

But the schema is still not enforcing many domain-important constraints.

Examples still not obviously enforced at the proxy boundary:

- limit orders requiring `limit_price`,
- stop orders requiring `stop_price`,
- valid enum membership for `side`, `type`, and `time_in_force`,
- mutually exclusive field rules,
- and cross-field invariants such as `qty` xor `percentage` on close-position semantics.

So this finding should be updated from the first review, not repeated verbatim:

- boundary validation is no longer missing,
- but semantic operation validation is still incomplete.

Relevant implementation: [src/alpaca/proxy/handlers.clj](/Users/franksiebenlist/Development/alpaca-clj/src/alpaca/proxy/handlers.clj), [src/alpaca/schema.clj](/Users/franksiebenlist/Development/alpaca-clj/src/alpaca/schema.clj)

## 6. SDSI/group authorization exists in code path, but confidence is lower than for bearer and bound-key flows

There is real SDSI-style work in the authorization layer now:

- roster loading,
- `:named-key` facts,
- `:right` facts,
- and group-resolution logic.

That is good progress.

But compared to bearer and requester-bound flows, the validation confidence is lower because I do not see comparable test coverage exercising the SDSI/group path.

That matters because the project narrative increasingly relies on named groups and trust-chain semantics.

Recommendation:

- Add focused tests for roster-backed group authorization before treating the SDSI path as equally mature.

## 7. Auditability is improved structurally, but trust-state auditability is still incomplete

The PEP flow and deny logging are cleaner than before, and explain-data logging is directionally right.

What is still missing relative to the trust-root docs is startup-time or queryable visibility into:

- which root keys are trusted,
- what invariants are active,
- which roster source was loaded,
- and what enforcement mode the proxy is actually in.

That is less a code correctness issue and more an operational audit issue.

For a system making capability decisions on behalf of a brokerage boundary, that metadata should eventually be treated as first-class runtime state.

## How The New Design Docs Fit The Current Implementation

The two new docs are good. They sharpen the architecture rather than dilute it.

### Dual PEP doc

This document is valuable because it correctly identifies that outbound policy and inbound policy are different authorities with different interests.

That is exactly the right direction for AI-agent systems.

The strongest specific idea in it is audience binding. That should move from document to code sooner rather than later.

### Trust roots doc

This document is also conceptually strong. The framing is correct:

- the enforcement actor is the real trust root,
- authority keys are trusted by the enforcer rather than being intrinsically authoritative,
- and scoped trust decisions should be explicit.

This is the right model for moving beyond a single-root prototype.

The main gap is implementation, not theory.

## Updated Recommendations

1. Implement audience binding in the signed envelope.

This is now the highest-value next security improvement because the new docs make the need explicit and the current code still lacks it.

2. Decide whether replay protection is intentionally single-node or intended for clustered deployment.

If clustered deployment is in scope, design shared replay-state handling now rather than after the client-side PEP story grows.

3. Promote trust-root ideas from docs into runtime configuration and observability.

That includes:

- multiple roots,
- scoped trust per root,
- explicit invariants,
- and a visible runtime trust declaration.

4. Expand validation from type correctness to operation semantics.

This is where trading-specific safety starts to matter more than generic schema hygiene.

5. Add focused SDSI and roster-based authorization tests.

The code path exists, but the assurance story is not yet at the same level as bearer and requester-bound operation.

6. Treat client-side PEP as a real implementation target, not just an architectural note.

The project now has a server-side core solid enough to justify that next step.

## Bottom Line

The most important change since the first review is that several earlier concerns are no longer merely conceptual findings.

They were fixed.

That includes:

- test execution,
- request-envelope binding,
- replay defense,
- and stronger proxy-boundary validation.

So this is no longer best described as “a strong idea ahead of its implementation.”

A better characterization now is:

- the server-side capability proxy is becoming real and defensible,
- while the broader distributed-trust architecture described in the new docs remains the next phase rather than the current state.

That is good progress.