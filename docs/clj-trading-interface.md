# clj-trading-interface — Research Findings

> Research conducted March 2026. Focused on stock trading APIs compatible with a
> Clojure/ClojureScript (cljc) runtime, with Alpaca Markets as the primary candidate.

---

## Executive Summary

The Clojure ecosystem has no production-ready, cross-platform trading library. The space
is wide open. Alpaca Markets is the strongest platform candidate: it is a regulated US
broker-dealer with a clean REST+WebSocket API, a free paper trading environment, and an
open-source MCP server for LLM-driven interaction. Building a `cljc`-native Alpaca client
would fill a real gap on Clojars.

---

## The cljc Runtime Constraint

A cljc library runs on two runtimes with different primitives:

| Concern        | Clojure (JVM)                        | ClojureScript (browser / Node.js)         |
|----------------|--------------------------------------|-------------------------------------------|
| HTTP           | `hato`, `clj-http`, `http-kit`       | `cljs-http`, `js/fetch`                   |
| WebSocket      | `aleph`, `http-kit`, `chord`         | native `js/WebSocket`, `chord`            |
| JSON           | `cheshire`                           | `cljs.reader` / `js/JSON.parse`           |
| Auth (HMAC)    | `buddy-sign`, `pandect`              | `js/crypto.subtle`, npm libs              |

The API platform itself is not the blocker — any pure REST+JSON service is cljc-compatible.
The HTTP and WebSocket layers just need `#?(:clj ... :cljs ...)` reader conditionals.

---

## Alpaca Markets — Primary Candidate

### What It Is

Alpaca is a **FINRA-registered US broker-dealer** (Alpaca Securities LLC, member SIPC),
not merely a data provider. It was founded in 2015 and registered as a broker in 2018.
This matters because you can go from paper simulation all the way to live execution on a
single platform without switching APIs.

- Regulated: FINRA / SIPC, ISO 27001:2022, SOC 2 Type II
- Supports: US equities, ETFs, options, crypto
- Available to individual and business accounts
- Accessible from 30+ countries (paper trading globally, live trading in supported regions)

### Interface Architecture

Alpaca is **REST + optional WebSocket**. There is no proprietary socket protocol, no local
gateway, no Java runtime requirement.

#### REST (primary interface)
- Standard HTTP verbs: `GET`, `POST`, `PATCH`, `DELETE`
- All responses in JSON
- Auth: two HTTP headers (`APCA-API-KEY-ID`, `APCA-API-SECRET-KEY`) — no OAuth dance
- Base URLs:
  - Live trading:  `https://api.alpaca.markets`
  - Paper trading: `https://paper-api.alpaca.markets`
  - Market data:   `https://data.alpaca.markets`

#### WebSocket (optional, real-time)
- Order/account update stream
- Real-time market data (quotes, trades, bars)
- Not required if polling is acceptable for your use case

**cljc verdict:** REST endpoints are trivially portable. WebSocket needs platform-conditional
handling but is optional. Auth is simple header-based — no special crypto required.

### Official SDKs (none in Clojure)

Alpaca provides official SDKs for Python, .NET/C#, Go, and Node.js. **No Clojure SDK
exists.** This is the gap.

---

## Cost Structure

### Paper Trading
**Free. No credit card. No KYC.**

Anyone globally can create a paper-only account with just an email address. The paper
environment starts with a simulated $100k balance (resettable to any amount at any time).
It uses real-time market prices (NBBO) for order matching.

**Known simulation gaps:**
- Does not simulate dividends
- Does not enforce order quantity against actual available liquidity
- Pattern Day Trader rules are simulated
- IEX-only data on the free tier (see Market Data below)

### Live Trading Commissions
Commission-free for US-listed equities and options traded via API. Crypto has a
volume-tiered fee schedule. No account maintenance or inactivity fees.

**Wire withdrawals:** $25 domestic / $50 international. ACH is free.

### Market Data Plans

| Plan              | Cost        | Data coverage                                                  |
|-------------------|-------------|----------------------------------------------------------------|
| **Basic**         | **Free**    | IEX exchange only (partial volume); indicative options feed    |
| **Algo Trader Plus** | **$99/mo** | Full SIP (100% US market volume via CTA + UTP); full OPRA options; 10k API calls/min; 7+ years historical |

For paper trading and API development, the **free Basic plan is sufficient** to start.
The $99/month plan becomes relevant when realistic fill simulation and full market coverage
matter for strategy validation.

**Elite tier:** Deposit $100k and the Algo Trader Plus subscription is included free,
along with lower margin rates and white-glove support.

---

## Paper Trading — Use Case & Scenario Development

Paper trading is the recommended starting point for any cljc library development and
strategy analysis work.

### Why Paper Trading First

- Zero financial risk — pure simulation
- API is **identical** to live trading (same endpoints, same JSON shapes, only the base URL differs)
- Switching from paper to live is a single config change (API key + base URL)
- Multiple concurrent paper accounts can be run simultaneously (up to $1M simulated funds)
- Freely resettable — you can wipe and restart scenarios at will

### What Paper Trading Enables

- Full API integration testing (orders, positions, account state)
- Strategy backtesting against real-time market prices
- Order type validation (market, limit, stop, OCO, IOC, MOO, MOC)
- Multi-leg options strategy testing (spreads, straddles, condors)
- Pattern Day Trader rule simulation
- WebSocket streaming validation

### Scenario Development Workflow (proposed)

```
1. Sign up → paper account (email only, globally available)
2. Generate paper API keys from dashboard
3. Point cljc client at paper-api.alpaca.markets
4. Build and test strategy logic against live market prices
5. Observe fills, slippage, order state machine
6. Reset account balance as needed between scenarios
7. When confident → swap to live API keys (no code changes required)
```

---

## MCP Server — LLM Interface Option

### What It Is

Alpaca publishes an **official, open-source MCP (Model Context Protocol) server** at:
`https://github.com/alpacahq/alpaca-mcp-server` (MIT license).

MCP is an open standard developed by Anthropic that lets AI assistants (Claude, Cursor,
VS Code Copilot, ChatGPT) interact with external tools and services through a structured
protocol. The Alpaca MCP server acts as a bridge between an LLM and Alpaca's Trading API.

### Feature Surface (43 Tools)

| Category            | Capabilities                                                                 |
|---------------------|------------------------------------------------------------------------------|
| Account             | Balances, buying power, account status                                       |
| Orders              | Market, limit, stop, stop-limit, trailing-stop; replace, cancel, query       |
| Positions           | View all, close specific or all positions                                    |
| Options             | Multi-leg strategies (spreads, straddles, condors), Greeks, contract lookup  |
| Market Data         | Real-time quotes, historical bars, latest trades                             |
| Assets              | Search and filter tradable assets by exchange and class                      |
| Watchlists          | Create, update, add/remove symbols, list all                                 |
| Corporate Actions   | Dividends, splits, mergers                                                   |
| Market Clock        | Trading hours, open/close status                                             |

### Natural Language Examples

```
"Place a bull call spread with SPY July options: sell one 5% above and
 buy one 3% below the current SPY price"

"Show me the option Greeks for TSLA250620P00500000"

"What is my current buying power before I place this limit order?"

"Get all NASDAQ active equity assets filtered to only tradable securities"
```

### Technical Requirements

- Python 3.10+ and `uv` (runs as a local sidecar process)
- Alpaca API keys (paper keys work fine)
- An MCP-compatible client: Claude Desktop, Cursor, VS Code

### MCP for Paper Trading Exploration

The MCP server with paper API keys is an excellent tool for **use-case analysis and
scenario development** before writing any Clojure code:

- Explore the API surface in natural language
- Prototype trading strategies interactively
- Understand order state transitions and data shapes
- Validate that a given strategy is expressible through the API
- Zero code required to start — just configure and prompt

**Important:** As of early 2026, Alpaca does not provide a hosted remote MCP server.
It runs locally or via self-hosted Docker/Kubernetes. Some LLM clients (Claude Pro,
Cursor Pro) may require paid subscriptions for heavy MCP usage.

### MCP vs. Direct REST Client

| Aspect                  | MCP Server                              | Direct cljc REST Client              |
|-------------------------|-----------------------------------------|--------------------------------------|
| Language                | Python sidecar                          | Native Clojure/ClojureScript         |
| Use case                | Exploration, prototyping, NL interface  | Production, automation, integration  |
| Paper trading support   | Yes (env var toggle)                    | Yes (base URL swap)                  |
| cljc compatible         | No (external process)                   | Yes (by design)                      |
| Open source             | Yes (MIT)                               | To be built                          |

---

## Existing Clojure Ecosystem — Honest Assessment

The ecosystem is sparse, mostly old, and JVM-only. Nothing targets Alpaca or any modern
REST-based broker.

| Library                    | Broker         | Status          | Notes                                      |
|----------------------------|----------------|-----------------|--------------------------------------------|
| `ib-re-actor` (cbilson)    | IBKR TWS       | Abandoned ~2013 | Clojure wrapper for IB's Java/TCP API      |
| `ib-re-actor-976-plus`     | IBKR TWS       | Semi-active fork| Updated to newer IB API versions; JVM-only |
| `ibclj` (mshaulskiy)       | IBKR ticks     | Abandoned       | Market tick data only; JVM-only            |
| `xicotrader`               | Crypto         | Abandoned       | Interesting component+core.async arch      |
| `clojure-backtesting` (HKU)| Backtesting    | Active (academic)| No live trading capability                |
| `stocktrader_clojure`      | Backtesting    | Abandoned       | Master's thesis project                   |
| `stockings` (fxtlabs)      | Yahoo Finance  | Dead (2011)     | Data only, likely broken                  |

**No cljc-native trading library exists on Clojars.** The space is open.

---

## Other Brokerages Evaluated

| Broker       | API Type        | cljc Friendly? | Notes                                                      |
|--------------|-----------------|----------------|------------------------------------------------------------|
| **Alpaca**   | REST + WS       | ✅ Yes         | Best fit; clean auth, paper trading, no gateway            |
| **Tradier**  | REST + WS       | ✅ Yes         | Developer-centric; strong options; OAuth Bearer            |
| **Polygon/Massive** | REST + WS | ✅ Yes (data only) | Market data only, no order placement; rebranded Oct 2025 |
| **SnapTrade**| REST            | ✅ Yes         | Aggregates 125M+ accounts across multiple brokers          |
| **IBKR Client Portal** | REST + WS | ⚠️ JVM only | Requires local Java gateway; not usable from CLJS       |
| **IBKR TWS** | TCP socket (Java) | ❌ No       | Java library; JVM-only by definition                      |

---

## Recommended Next Steps

1. **Create a paper account** at `app.alpaca.markets` (email only, instant)
2. **Stand up the MCP server** locally pointing at paper keys — explore the API surface
   in natural language before writing any Clojure code
3. **Define the cljc namespace structure** — suggested:
   ```
   alpaca.client       ; HTTP layer with #? reader conditionals
   alpaca.trading      ; orders, positions, account
   alpaca.market-data  ; REST quotes, bars, snapshots
   alpaca.streaming    ; optional WebSocket layer
   alpaca.paper        ; paper-trading config helpers
   ```
4. **Use `hato` (JVM) + `cljs-http` (CLJS)** as the HTTP pair — both accept plain maps,
   making the abstraction clean
5. **Start with REST only** — defer WebSocket until core trading ops are solid
6. **Publish to Clojars** as `clj-alpaca` or similar — there is genuinely nothing there

---

*Document generated from research conducted in Claude Project: clj-trading-interface.*
*Sources: Alpaca official documentation, alpacahq GitHub, Clojars, broker review sites.*
