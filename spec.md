# SSBudget - Specification

## Overview

Personal budget tracker for managing monthly expenses and calculating available spending money.

## Core Workflow

1. Define known monthly expenses (planned) and variable expenses (estimated)
2. At period start, all planned expenses are "unpaid" with their estimates
3. Throughout the period:
   - Mark planned expenses as paid (with actual amount)
   - Update bank account balances
4. App calculates:
   - **Free Money** = Total Balance - Predicted Expenses
   - **Daily Budget** = Free Money / Days Until Period End
5. Send summary to self/wife via notification

## Expense Types

### Planned Expenses
Fixed monthly bills that get explicitly paid.
- Examples: rent, subscriptions, insurance, utilities
- Have an estimated amount (configurable: fixed, last month, or average)
- Get marked as "paid" with actual amount and date
- Unpaid ones contribute their estimate to predicted expenses

### Estimated Expenses
Variable ongoing costs that are "consumed" over time.
- Examples: groceries, fuel, entertainment
- Have a monthly estimate
- Never explicitly marked as paid
- Scale with remaining period: `estimate * (days_remaining / period_length)`
- Can toggle whether included in remaining balance calculation
- Useful for "what if" scenarios (e.g., "do I have enough if I don't count groceries?")

## Period

- Starts when paycheck arrives (typically ~25th, but flexible)
- Manually triggered (not automatic)
- Ends when next period starts
- All expenses reset to "unpaid" at period start

## Accounts & Currency

- Multiple bank accounts
- Each account has a currency (PLN or EUR)
- EUR accounts converted to PLN for totals
- Exchange rate: manually set, with option to fetch from API
- Balance updates tracked with timestamp for historical record

## Estimate Modes

Three ways to determine planned expense estimate:
1. **Fixed value** - Manually set amount
2. **Last month** - Use previous period's actual payment
3. **Average** - Calculate from historical data

## Authentication

- Internet-facing (accessible from anywhere)
- **Passkeys (WebAuthn)** - modern passwordless authentication
- No user accounts - just credential registration
- First visitor registers a passkey, subsequent access requires registered passkey
- Anyone with a registered passkey can view and edit

## Notifications

- Generate summary text for current budget status
- MVP: Copy to clipboard button
- Target: WhatsApp message to configured recipients

### Summary Format (Example)
```
Budget Update (Jan 15)
Balance: 5,000 PLN
Predicted: 2,500 PLN
Free: 2,500 PLN
Daily: 250 PLN (10 days left)
```

## Historical Data

- Track each balance update with timestamp
- Store actual payment amounts for planned expenses
- Enable historical averages for estimates
- Support data import (CSV/JSON) for bootstrapping

## Tech Stack

| Component  | Technology                                    |
|------------|-----------------------------------------------|
| Language   | Scala 3                                       |
| Backend    | cats-effect, http4s                           |
| Frontend   | Laminar (Scala.js SPA)                        |
| API        | tapir (shared definitions)                    |
| Database   | SQLite                                        |
| Migrations | Flyway                                        |
| JSON       | circe                                         |
| CSS        | Bulma (CSS-only)                              |
| Bundler    | Vite + vite-plugin-scalajs                    |
| Auth       | Passkeys (WebAuthn) via java-webauthn-server  |
| Deployment | Docker + fly.io                               |

## Integration Goals

- Leverage and extend **forms4s** (https://github.com/business4s/forms4s)
- Build reusable Laminar components that can be extracted to OSS
- Part of **business4s** ecosystem (https://business4s.org/)

## Non-Goals (Current Scope)

- Multiple users/roles
- Currencies beyond PLN and EUR
- Non-monthly expense recurrence
- Expense categories/tags
- Automated bank sync
- Mobile native app
