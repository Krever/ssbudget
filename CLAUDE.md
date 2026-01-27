# SSBudget - Claude Context File

## Project Overview

Personal budget tracker for tracking monthly expenses, bank balances, and calculating available spending money. Internet-facing with passkey authentication (no user management).

## UI Design Principles

**Spreadsheet-like efficiency** - The UI should feel like a well-designed spreadsheet:
- **Concise**: Maximum information density, minimal chrome
- **Direct manipulation**: Edit in place, no unnecessary modals or multi-step wizards. But explicit submission is ok when needed
- **Minimal clicks**: Common actions (update balance, mark paid) should be 1-2 clicks
- **Scannable**: Numbers aligned, status visible at a glance

Think "Google Sheets for personal budget" not "enterprise dashboard with cards everywhere".

## Core Concepts

### Period
- Starts when paycheck arrives (~25th of month, flexible)
- Ends when next paycheck arrives
- All calculations are relative to current period

### Expense Types

1. **Planned Expenses** - Fixed monthly bills (rent, subscriptions, etc.)
   - Have an estimated amount
   - Get marked as "paid" with actual amount
   - Unpaid ones contribute their estimate to predicted expenses
   - Estimate can be: fixed value, last month's actual, or historical average

2. **Estimated Expenses** - Variable ongoing costs (groceries, fuel, etc.)
   - Have a monthly estimate
   - Never explicitly "paid" - consumed implicitly over time
   - Scale with remaining period (10 days left = 1/3 of monthly estimate)
   - Can toggle whether included in remaining balance calculation

### Savings

**Savings Accounts** - Buckets for accumulating money (emergency fund, vacation, etc.)
- Have a currency and current balance
- Balance is editable directly (for corrections/initial setup)
- Can have an optional monthly target (planned savings amount)
- Transactions (inflows/outflows) modify the balance

**Savings Transactions** - Individual money movements
- Positive = inflow (contributing to savings)
- Negative = outflow (withdrawing from savings)
- Can have multiple per period (e.g., +500, -100, -200)
- Optional note for context

### Key Calculation
```
Free Money = Total Balance - Predicted Expenses - Remaining Savings
Daily Budget = Free Money / Days Until Period End
```

Where:
- `Predicted Expenses = Sum(unpaid planned estimates) + Scaled(estimated expenses)`
- `Remaining Savings = Sum(plannedMonthly - period contributions) for accounts with targets`
- Period contributions = sum of transactions for current period per savings account

## Tech Stack

| Layer       | Technology                               |
|-------------|------------------------------------------|
| Language    | Scala 3.5.2                              |
| Backend     | cats-effect, tapir, http4s               |
| Frontend    | Laminar (Scala.js SPA)                   |
| API         | tapir (shared endpoint definitions)      |
| Database    | SQLite + Flyway migrations               |
| JSON        | circe                                    |
| CSS         | Bootstrap 5 (CSS-only)                   |
| Bundler     | Vite + vite-plugin-scalajs               |
| Auth        | Passkeys (WebAuthn) via java-webauthn-server |
| Deployment  | Docker + fly.io                          |

## Reference Projects

- **workflow4s-web-ui** (`/Users/krever/Projects/priv/workflow4s/workflows4s-web-ui`) - Reference for Vite + Scala.js setup
- **laminar-full-stack-demo** (https://github.com/raquo/laminar-full-stack-demo) - Reference for Laminar full-stack architecture
- **forms4s** (`/Users/krever/Projects/priv/forms4s`) - Form/datatable library to extend with Laminar support
- **business4s ecosystem** (https://business4s.org/) - Parent OSS ecosystem

### forms4s Integration Strategy
1. Use `forms4s-core` for table/form state management (no UI dependency)
2. Build `forms4s-laminar` module as part of this project (can be extracted later)
3. Leverage existing: TableDef, TableState, filtering, sorting, pagination, URL state encoding

## Data Model (Conceptual)

```
ExpenseDefinition:
  - id, name, type (planned|estimated)
  - estimateMode (fixed|lastMonth|average)
  - fixedEstimate (optional)
  - includeInBalance (for estimated type)

Period:
  - id, startDate, endDate (nullable until closed)

ExpenseRecord (for planned expenses):
  - periodId, expenseDefId, paidAmount (nullable), paidDate

BalanceSnapshot:
  - accountId, amount, currency, timestamp

Account:
  - id, name, currency (PLN|EUR)

SavingsAccount:
  - id, name, currency (PLN|EUR)
  - currentBalance (cents, editable directly)
  - plannedMonthly (optional target per period)

SavingsTransaction:
  - id, accountId, periodId
  - amount (positive = inflow/saving, negative = outflow/withdrawal)
  - note (optional)
  - createdAt

ExchangeRate:
  - fromCurrency, toCurrency, rate, fetchedAt

PasskeyCredential:
  - credentialId, publicKey, signCount, createdAt
```

## Authentication

**Passkeys (WebAuthn)** - Modern passwordless authentication
- No user accounts - just credential registration
- Library: [Yubico java-webauthn-server](https://github.com/Yubico/java-webauthn-server)
- Frontend uses Web Authentication API (browser native)
- Credentials stored in SQLite
- First visitor registers a passkey, subsequent access requires registered passkey

Implementation resources:
- https://developers.yubico.com/java-webauthn-server/
- https://github.com/YubicoLabs/passkey-workshop

## Notifications

- MVP: "Copy to clipboard" button for summary
- Target: WhatsApp integration (via API or webhook)

Summary format (example):
```
Budget Update (Jan 15)
Balance: 5,000 PLN
Predicted: 2,500 PLN
Free: 2,500 PLN
Daily: 250 PLN (10 days left)
```

## File Structure (Target)

```
ssbudget/
├── build.sbt                    # Multi-module build
├── project/
│   ├── build.properties
│   └── plugins.sbt              # ScalaJS, Flyway, native-packager
│
├── shared/                      # Cross-compiled (JVM + JS)
│   └── src/main/scala/ssbudget/shared/
│       ├── api/                 # Tapir endpoint definitions
│       └── model/               # Domain models (Expense, Account, etc.)
│
├── backend/
│   └── src/main/scala/ssbudget/backend/
│       ├── Main.scala
│       ├── db/                  # SQLite + Flyway + repositories
│       ├── auth/                # WebAuthn/passkey handling
│       └── service/             # Business logic
│
├── frontend/                    # Scala.js + Laminar
│   ├── vite.config.mjs
│   ├── package.json
│   ├── index.html
│   └── src/main/scala/ssbudget/frontend/
│       ├── Main.scala           # @JSExportTopLevel entry point
│       ├── api/                 # HTTP client (tapir-sttp-client)
│       ├── components/          # Laminar components
│       └── pages/               # Page components
│
├── forms4s-laminar/             # Laminar integration for forms4s
│   └── src/main/scala/
│
└── docker/
    └── Dockerfile
```

## Development Workflow

Three-terminal setup for development:

```bash
# Terminal 1: Scala.js continuous compilation
sbt '~frontend/fastLinkJS'

# Terminal 2: Vite dev server (hot reload, proxies /api to backend)
cd frontend && npm install && npm run dev

# Terminal 3: Backend server
sbt backend/run
```

Navigate to `http://localhost:3000` - Vite proxies API calls to backend.

## Build Commands

```bash
# Development
sbt '~frontend/fastLinkJS'        # Watch mode for frontend
sbt backend/run                   # Run backend
cd frontend && npm run dev        # Vite dev server

# Production build
sbt frontend/fullLinkJS           # Optimized JS
cd frontend && npm run build      # Vite production bundle
sbt backend/assembly              # Fat JAR with bundled frontend

# Database
sbt backend/flywayMigrate         # Run migrations

# Docker
docker build -t ssbudget .
```

## Critical Build Settings

```scala
// build.sbt - REQUIRED for Vite integration
scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
```

```javascript
// vite.config.mjs
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";

export default defineConfig({
  plugins: [
    scalaJSPlugin({
      cwd: "..",           // Parent directory with build.sbt
      projectID: "frontend" // Must match sbt project name
    })
  ],
  server: {
    proxy: { '/api': 'http://localhost:8080' }
  }
})
```

## Session Workflow

This project uses incremental development across multiple Claude sessions:
1. Check `ROADMAP.md` for current phase
2. Check `docs/sessions/` for completed work
3. Pick next item from roadmap
4. Create detailed plan for the session
5. Implement
6. Update session log and roadmap status

## Code Style

**MANDATORY: Always use curly braces syntax. Never use indentation-based syntax (Scala 3 braceless style).**

**MANDATORY: Always run `sbt scalafmtAll` before finishing work to format all Scala code.**

**circe codecs**: Use `derives Codec.AsObject` for case classes. Only use manual `Encoder`/`Decoder` for:
- AnyVal wrapper types (encode as the underlying type)
- Enums with custom string representations
- Types like `LocalDate`, `Instant` that need custom serialization

## Key Decisions Log

| Decision           | Choice                    | Rationale                                        |
|--------------------|---------------------------|--------------------------------------------------|
| Database           | SQLite + Flyway           | Simple, file-based, migrations built-in          |
| CSS Framework      | Bootstrap 5               | Industry standard, extensive components, good docs |
| Auth               | Passkeys (WebAuthn)       | Modern, passwordless, secure, no passwords to manage |
| Bundler            | Vite + vite-plugin-scalajs| Fast dev, HMR, proven in workflow4s              |
| Historical data    | Per-update                | Track each balance update with timestamp         |
| Expense recurrence | Monthly only              | Keep simple                                      |
| HTTP client        | tapir-sttp-client         | Type-safe, shares endpoint defs with backend     |
| Scala version      | 3.5.2                     | Scala 3.8.1 has Scala.js compiler bug (js.async) |
| UI philosophy      | Spreadsheet-like          | Concise, direct edit, minimal clicks             |
| Savings accounts   | Separate entity           | Different behavior from bank accounts (editable balance, targets) |

## Laminar/Airstream Gotchas

**Signal combination**: When combining 3+ signals, use chained `combineWith` instead of `Signal.combine`:

```scala
// DON'T - Signal.combine with 3+ signals fails silently (pattern match doesn't work)
Signal.combine(sig1, sig2, sig3, sig4).map { case (a, b, c, d) => ... }

// DO - Use chained combineWith (tuplez library flattens to flat tuple)
sig1.combineWith(sig2).combineWith(sig3).combineWith(sig4).map { case (a, b, c, d) => ... }
```

Note: `Signal.combine` with exactly 2 signals works fine.

**ZoneId.systemDefault() in Scala.js**: `ZoneId.systemDefault()` fails silently without the `scala-java-time-tzdb` dependency. If an object has `ZoneId.systemDefault()` in its static initialization, any call to that object (even unrelated methods) will fail silently.

```scala
// DON'T - causes entire object to fail in Scala.js
object Formatting {
  private val zone = ZoneId.systemDefault()  // This breaks everything!
  def formatMoney(cents: Long, currency: Currency): String = ...
}

// DO - use fixed timezone
object Formatting {
  private val zone = ZoneId.of("UTC")  // This works
  def formatMoney(cents: Long, currency: Currency): String = ...
}
```

If you need system timezone support, add `scala-java-time-tzdb` to your dependencies.
