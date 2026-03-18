# Alpaca-clj Second Architectural Review: Trust Roots & Dual-PEP Enforcement

## 1. Evaluation of Previous Concerns
In the previous review, several critical issues were identified regarding proof-of-possession, schema validation, and broken tests. 

*   **Test Infrastructure:** The test suite continues to fail (`Exit Code: 1` observed for `bb test`). Resolving this remains the most pressing operational gap before deploying complex security middleware.
*   **Replay Attacks & PoP:** Based on project state, if request caching (nonces) and strict timestamp drift windows aren't fully integrated, replay attacks remain a theoretical risk in production. 
*   **Schema Enforcement:** Structural whitelisting via Datalog and routing is working beautifully in concept. Relying strictly on the schema to reject malformed parameters at the server boundary minimizes injection risks.

## 2. Review of New Design Documents

### A. Client-Side Policy Enforcement (Dual-PEP)
The introduction of a Dual Policy Enforcement Point (Dual-PEP) architecture is highly innovative and builds on the strengths of the offline-verifiable capability system.
*   **Observations:** By allowing the client (the AI agent or user's local MCP tool) to verify capabilities locally *before* issuing a network request, the system drastically reduces wasted API calls and server-side noise. It enables "fail-fast" UX, meaning an agent knows immediately if it lacks authorization for a specific action without sending an HTTP request.
*   **Security Value:** While the server must still perform the final authorization check (zero-trust), the client PEP provides operational efficiency, privacy, and better error context.
*   **Considerations:** Ensure the client and server use the exact same Datalog constraints (via a shared library or schema). Desyncs here could lead to false positives (client allows, server blocks) or false negatives.

### B. Trust-Chains & Trust-Root Identification
*   **Observations:** Explicit formulation around trust roots solves the "bootstrap" problem of SPKI/SDSI. For an external capability to be trusted, it has to roll up to an explicit public key configured as a "root" to the system.
*   **Security Value:** 
    *   It formalizes identity without an opaque central directory (like LDAP). 
    *   Trust chains allow for dynamic revocation points and localized auditing.
*   **Considerations:** 
    *   **Revocation Lists (CRLs):** Short-lived tokens are preferred to avoid complex token revocation lists. If tokens are long-lived, the proxy will need a synchronized revocation cache.
    *   **Root Key Rotation:** Document how the system gracefully handles the rotation of the trust roots without catastrophically breaking in-flight agent sessions.

## 3. Recommendations & Next Steps
1.  **Mandatory Test Repair:** Before any further feature development or testing of Dual-PEP/Trust-Chains, the `bb test` target must execute successfully to establish a reliable baseline.
2.  **Shared Schema Artifact:** For Dual-PEP to function smoothly, consider packaging `schema.clj` and the base Datalog policy templates into a standard artifact (e.g., a lightweight CLJC library) distributed to both the client proxy and the core server.
3.  **Implement Key Rotation Guidelines:** Formulate the mechanism by which trust roots can be phased out and new ones introduced.
4.  **No-Code Validation:** As requested, the focus is strictly on architectural review and validation, but these designs present a highly defensible, flexible approach to agentic authorization.