# Alpaca-clj Architecture and Security Review

## Executive Summary
`alpaca-clj` implements an API proxy for Alpaca Markets designed to isolate sensitive broker credentials from AI agents. By leveraging a schema-driven Babashka/HTTP-kit runtime, it enforces fine-grained, declarative access control. The primary innovation is its authentication and authorization architecture: an append-only, capability-based token system inspired by Biscuit, extended through Clojure data structures, Canonical EDN (CEDN), and Datalog-driven policy engines (Stroopwafel). 

## Architectural Strengths & Core Concepts

### 1. The Capability Model (Stroopwafel & Biscuit influence)
Following the Biscuit capability token model, `alpaca-clj` issues offline-verifiable, delegatable tokens. Because the token is self-contained and append-only, agents can securely attenuate their own capabilities before passing the token to sub-agents, adhering to the principle of least privilege without contacting a central authorization server.

### 2. Canonical EDN (CEDN) Foundation
Relying on `canonical-edn` provides deterministic byte-level serialization of complex data structures. This resolves the notorious canonicalization issues of JSON and allows secure Ed25519 cryptographic signing over rich Clojure data types (sets, keywords, nested maps) used in policy facts.

### 3. SPKI/SDSI Name-Bindings
By incorporating local name bindings and assertions inspired by SPKI/SDSI, the system enables flexible group and attribute abstractions. Local namespaces prevent the need for global consensus on identity, allowing seamless delegation networks (e.g., `(user X says user Y is an Agent)`).

### 4. Datalog as the Policy Engine
Datalog naturally maps over Clojure/EDN data. Rather than imperative access controls, the authorization logic is purely declarative:
- The token provides *facts* (delegations, bounds).
- The proxy request provides *context* (what is being accessed).
- Datalog unifies these to deduce *permit* or *deny*.

### 5. Schema-Driven "Structural Whitelist"
Every proxy capability is derived from a single source of truth in `schema.clj`. This prevents endpoint sprawl, automatically gates operations, and defines the exact parameters required, acting as an upfront structural whitelist.

---

## Observations & Vulnerabilities

### A. Request Replay & Proof-of-Possession (PoP) Gaps
While the token establishes *authorization*, binding it to a specific HTTP request currently exhibits gaps. If an attacker intercepts a valid signed request, they might inject it again (Replay Attack). 
- **Observation:** The request signature must securely bind the token to the HTTP Method, Route Path, and Body.
- **Risk:** Without strict freshness guarantees (timestamps + seen-nonces cache), signed requests are vulnerable to replay within the proxy's network boundary.

### B. Schema Validation & Coercion
While `schema.clj` describes the valid shapes, the execution phase needs strict, closed-map validation before converting EDN to JSON and forwarding to the live Alpaca API. 
- **Observation:** Relying solely on Datalog authorization does not guarantee the runtime data integrity of the request body parameters.

### C. Testing Infrastructure
The current repository cannot successfully run `bb test`. The `alpaca.test-runner` is undefined or missing in `bb.edn`, and the `test/alpaca` directory lacks regression tests. Security middleware is notoriously brittle without unit and integration testing.

---

## Recommendations & Roadmap

1. **Implement Defense Against Replay Attacks**
   - Introduce a mandatory `nonce` and strictly monotonic `timestamp` to the request envelope.
   - Maintain a sliding-window cache at the proxy tier to reject duplicate nonces within the acceptable time drift window.
   - Ensure the signature covers the HTTP method and path, not just the body, preventing endpoint-switching attacks.

2. **Restore and Expand the Test Suite**
   - Fix the `bb.edn` test aliases.
   - Write explicit tests for Stroopwafel integration:
     - Provide an expired token (Assert 401).
     - Provide a token attempting an unauthorized endpoint (Assert 403).
     - Provide a valid token but mutate the EDN payload post-signature (Assert 401/Invalid Sig).

3. **Runtime Schema Enforcement (Malli/Spec)**
   - Introduce strict validation (e.g., using `malli`) based on `schema.clj` immediately after AuthZ success, but before Alpaca REST execution. Any un-schema'd keys should trigger a `400 Bad Request`.

4. **Enhance Audit Logging**
   - Given the dynamic nature of delegated SPKI/SDSI capabilities, log the entire Datalog proof tree (or the core deductive chain) on denial (`403 Forbidden`) so that token attenuation debugging is transparent to the issuing party.