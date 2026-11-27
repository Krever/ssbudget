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

**Look** - Bootstrap, plus one stylesheet of our own: `frontend/styles/app.css`. A style that is always the same is a class there
(`clickable`, `w-rem-7`, `bar-3`, ...); a `styleAttr` in page code is only for a value that is computed (the width of a bar, the
colour of a series). It sets the scale (root size 14px, so all of
Bootstrap follows), the number face and the surfaces. A change of the general look goes there, not into page code.

**Bank connections** (Banking page) - each bank is a card: the header has the bank, the state of its access (a mark only when it
is not active) and the actions; the body is a ledger of its accounts with "Counts as" for the link. Accounts with no link and no
balance fold into one line. On a wide window the page has two columns: the banks on the left, the tools (import activity, card
groups, the form for a new bank) on the right.

**The frame of a page** - every page is `container-fluid mt-3` with an `h4` title, and its cards have a plain title in the header.
`app.css` gives every table the same quiet head and the same inset when it fills its card, and every card header one size. A page of
forms or of one short table (Settings, Accounts) adds `page-narrow`, a centred column. A status is shown only where it says something: one "open" mark on the Periods table, no badge
on each closed row.

**Accounts ledger** (Dashboard) - one table of groups by account type (spending, on hold, savings, investments; a group with no
account is not shown). The heading row of a group carries
its totals, so there are no "Total" rows. Every amount is in the primary currency so that a column reads down; the own currency of a
foreign account is the small line under its balance. Spending accounts have no change figure. Spending accounts with a small balance fold into one line. The strip under the
table holds what the period did: saved, market result, net worth.

**Accounts page** - the same ledger, for the management of accounts: one table in a narrow centred column, a heading row for each
account type that has an account (the spending group always), each with its "+ Add", and one row for each account. The add row has
a Type field, so a type with no heading can still get its first account. An account shows in the group of its type, and the type is a field
of its edit row. Every account can be edited (name, type); a field that the server is sure to refuse is locked with its reason.

**Charts** - every chart is a `ChartKit.chart` (frontend components), so they are all drawn and read in one way: a plot at the real
pixel width of its container, one labelled scale, a pointer readout that snaps to the nearest position (arrow keys do the same), a
legend with line keys, and the same figures as a table below. The kit owns that mechanism; a chart gives it only its series, how a
pointer position maps to its data, and its text. `FreeMoneyChart`, `MonthlyHistoryChart` and `NetWorthChart` (Periods page: net worth, savings and investments at the end of each
period) are the charts. The one exception is
`ChartKit.spark`, the line in a table row, which has no scale and no readout. A new chart starts from the kit, not from raw SVG.

## Core Concepts

### Period
- Starts when a paycheck arrives; closed by hand when the next one does
- Closing a period and opening the next is one step, in one transaction (`PeriodService.startNew`); the database allows one open period
  only. The step takes a day ("as of" on the Periods page): today means now, and an earlier day is for a paycheck that came some days
  before the period is closed here. The new period then starts at the beginning of that day, and the daily records from that day on go
  to the new period
- Carries its own `expectedEnd` date. Nothing infers it from a payday — the app's only opinion is the date it PROPOSES when a
  period opens (`Period.defaultExpectedEnd`: the same day of the following month), and that is editable in place on the Periods
  page. Days left, day N and the progress bar all read that one date, so a paycheck landing early or late is a one-field fix
  rather than a rule change
- An open period past its `expectedEnd` overruns (days remaining go negative) instead of rolling forward, so being late to close
  it stays visible
- A second paycheck inside a period is not a period boundary: it is income of a category. When that category has a budget (an
  income budget), the paycheck reaches free money through `Pending Income` until it arrives. With no budget it is unplanned income
  when it arrives. Timing is deliberately not modelled — the app takes an approximate, eventually-consistent view of when money
  lands, because dating every expense would cost more effort than the precision is worth
- All calculations are relative to the current period
- A period can be added later for the time before the app was in use (a backfill of the period history, done with a script). The
  Periods page shows its figures from the bank ledger: in, out, saved, market result and the balances at its end, all through
  `BalanceHistory`. It gets NO daily free-money rows: the record of `daily_budget_snapshots` begins on its first row and never grows
  backwards, because old days could only be computed with the budgets of today

### Period boundary

`PeriodWindow` (backend service) is the ONE definition of where a period begins and ends for the bank ledger and the balance history.
A bank entry has a date and no time, so the rule is: the day a period opens is of that period, in full. Its three views are the
entries of the period (first day up to, not including, the day it was closed), the balance at a boundary (end of the day before), and
the movement since the baseline (from the day after the first day, for the free-money terms). New code asks `PeriodWindow`; it does
not compute a boundary of its own.

### Category Budgets

The plan is made of **Category Budgets** only: a category is flagged as a budget, and real bank transactions drive what it still
expects, for spend and for income. Two independent settings per category:
- **Budget** (`CategoryBudget`) picks the monthly figure: `Average` / `Median` over the last N completed months (blank = all
  history), or `Fixed` — a typed amount, history ignored. Leading/trailing empty months are never counted, so a short history isn't
  diluted by a long window; interior gap months count as zero.
- **Type** (`CategoryBudgetType`) spreads that figure over the period: Steady / Bill / Subscription, with a per-period manual
  override of what's remaining.

Transactions have no tags, and none are planned (decided 2026-11-26): the note of a transaction and the search on the Transactions
page do that work. An old migration (V18) says that tags will come; that line is history.

A one-off item (an exceptional bill, a bonus) is a category with a `Fixed` budget. The override closes it when no matching
transaction arrives: the "Paid" / "Received" button of the Plan card sets the remaining amount to zero for the period.

There are no hand-declared planned items. The code of that feature was removed in November 2026. Its data was NOT dropped: the
tables `expense_definitions` and `expense_records` (and the view `v_planned_items` on a database that had it) stay, and no code
reads them. Its two terms also stay in `daily_budget_snapshots` (see Data Model).

Both are edited in a settings strip that opens under the category's row on the Transactions page; the table itself stays
read-only so the figures scan like a spreadsheet. The strip charts `CategorySummary.monthlyHistory` — the same series the
statistic ran over — with the lookback window shaded, so the window can be picked by eye rather than guessed.

### Savings

**Savings Accounts** - buckets for accumulating money (emergency fund, vacation, etc.)
- Have a currency and a current balance; excluded from spendable balance
- Balance is editable directly (manual accounts) or driven by a bank sync
- No per-account targets and no hand-kept ledger (both were removed in the August 2026 cleanup, with the manual `savings_transactions` table)
- **Planned saving** is one amount per period (`Period.plannedSavingCents`, primary currency), typed in place on the Periods page or on
  the Dashboard (click "plan" in the Free money card), and editable at any time in the period. A new period starts with the amount of
  the period before it. It reaches free money as one signed term, the **saving reserve** (`Period.savingReserveCents`)
- The plan is counted down with what was SAVED, and that is the "Saved" figure of the Accounts card: the change of the balances of the
  savings accounts since the period opened, plus what was moved into the investment accounts (`BudgetSnapshotService.savedCents` for
  the daily record, `DataService.savedThisPeriod` for the page). It is never typed. The market result is not in it. A balance holds
  everything: pay that arrives directly on a savings account counts, and a bill paid from a savings account lowers it, so money that is
  moved there only to pay a bill is not saving
- It is NOT the opposite of the `fromSavings` term (it was, until November 2026). That term sees only what left a spending account,
  so it missed pay that stays on a savings account. `fromSavings` is a term of the plan's own line only
- The balance of a savings account with no bank link changes when the user types it, so a transfer to such an account counts as
  saved from that moment

**Holding accounts** (`AccountRole.Holding`, "On hold" in the UI) - pay that came early and waits for the next period
- Not spendable, so not in free money; not savings, so not in "Saved"; part of the net worth. The Accounts card shows them as a group
  of their own, with a balance and no change figure
- To put money on hold, or to take it from there, is not saving: an internal transfer between a spending account and a holding account
  is not in `fromSavings`. The other side of a transfer is known by its IBAN (`bank_account_links.iban` of an account with this role)
- Money from hold is income when it arrives: it counts in `unplannedIncome`, so the plan's own line leaves it out. A budgeted bill that
  is paid directly from a holding account counts the same way. On the day a period opens it is part of the baseline, as the paycheck is
- An account of any type can change type on the Accounts page, except the account of a card group

**Investment accounts** (`AccountRole.Investment`) - money at market value (a crypto or stock portfolio)
- Not spendable, and NOT part of the savings figures: the balance change of such an account is mostly what the market did
- The change of a period has two parts. **Moved** is deposits less withdrawals; it is saving, and it joins the savings figures
  ("Saved" = change of the savings accounts + moved). **Market result** is the rest, and it is shown apart
- The **market result also holds what the exchange rate did** to the savings and investment accounts in another currency: the balance at
  the start of the period × (rate at the end − rate at the start) (`PeriodService.HeldChange`, `DataService.savingsCurrencyEffect`). The
  change of a savings account in "Saved" is valued at the rate of the end, so it has no part of that. "Saved" + market result = the change
  of value of the savings and investment accounts. On the Dashboard a foreign savings account shows that part as "rate ±x" under its change
- Such an account usually has no bank ledger in the app, so "moved" is read from the other side: a category can be made the
  **transfer category** of one investment account (`Category.transferAccount`, the "Transfer ↔ account" entry of the Type dropdown
  in the category settings strip). Its transactions are then transfers: not income, not spend, never a budget. Nothing in the code
  names a category; the link is data
- In the free-money terms, a transfer category on a spending account counts in `fromSavings`, as an internal transfer does, and
  not in `unplannedIncome`. A withdrawal from an investment thus lowers "moved to savings"
- An investment account can also have a ledger of its own (transactions on a bank account uid that is linked to it; today only a
  script makes one, from a statement of the broker). Its non-internal transactions are money that entered or left the investment:
  they count in "moved" with their own sign, and a payment is spend as on any other account
- Known limit: money spent or received INSIDE the investment account that is in no ledger (a payout into it, card payments not
  yet imported) lands in the market result
- The `savings` term of the daily snapshots holds savings accounts only

`savingsPeriodChange` (current balance − balance at period start, at the rate of now) is reported on the Dashboard as information only;
it is not part of the free-money calculation. The Periods page shows the same change for each period. Both read the balance at a period boundary
from `BalanceHistory`: the recorded balance (snapshot) at or before that instant, and, before an account's first record, that first
record carried back through the bank ledger. A bank entry has a date and no time, so the ledger answer is the balance at the end of the
day BEFORE the boundary: what moves on the day a period opens is of the new period. A manual account has no ledger, so its first
record stands for all the time before it.

### Exchange rates

A rate is the rate of one day (`exchange_rates.on_date`, one row for each pair and day): the reference rate of the European Central
Bank for a business day, from Frankfurter. A day with no rate (a weekend, a holiday) takes the rate of the last day before it
(`RateTable`, backend service). Every figure of a past day uses it: a balance at a period boundary at the rate of the day that it is of
(the day before the boundary, see `PeriodWindow`), an entry of the ledger at the rate of its own day. So the figures of a closed period
do not move when the rates change. Figures of now (free money, the budgets) use the latest rate.

- `CurrencyService.fillHistory` keeps the history complete for each currency in use (accounts and bank entries), from a week before
  the first record (period, balance or bank entry) to the last business day before today. It gets all missing days in one request. It
  runs before each pass of the daily record (there is no scheduler) and after a refresh; a failed or empty attempt waits an hour.
  A failure is a warning (`BackgroundHealth.Task.ExchangeRates`)
- The Refresh button and each bank sync get the newest rates of the enabled currencies and of the currencies in use, then fill the history
- The currency effect of the open period is computed on the server (`AccountPeriodBaseline.currencyEffectCents`, by `HeldChange`); the
  page only adds it up

### Key Calculation
```
Free Money = Spending Balance - Predicted Expenses + Pending Income - Saving Reserve
Daily Budget = Free Money / Days Until Period End
```

Where:
- `Predicted Expenses = Sum(remaining per category budget that expects spend)` ("Still to pay")
- `Pending Income = Sum(remaining per category budget that expects income)` ("Still to receive")
- `remaining` for a category budget = the manual override of the period when there is one, else what its type still expects
  (`CategorySummary.remainingCents`)
- `Saving Reserve` is positive while a part of the planned saving is not yet moved to savings: `clamp(planned − net moved, 0, planned)`.
  That money is still in the spending balance, and it is not free
- It is negative when MORE than the plan was moved: the excess, up to what is still to pay. The plan says what is saving, and the rest
  waits in the savings account for bills (instalments taken from that account). This keeps free money flat when one transfer holds both
  the saving and the money for such bills. With no plan the term is zero
- The savings BALANCE is not subtracted: moving money to a savings bucket already lowers the spending balance, so reserving it
  again would double-count

### Free Money Trend

Free money is already a forecast of what is left at period end, so its line over a period shows how the plan was wrong, day by day.
`FreeMoneyTrend` (shared) reads it from `daily_budget_snapshots`; `FreeMoneyChart` draws it on the Dashboard (current period, with a
projection) and on the Periods page (a row sparkline, and a chart in the expanded row).

- The line that is drawn and projected is the **plan's own line** (`DailyBudgetSnapshot.planLineCents`), not raw free money. Raw
  free money also steps when money moves to or from savings and when unplanned income arrives; those steps say nothing about the plan
  and no slope survives them. The plan's own line falls steadily in every recorded period
- It is built from three ledger terms, cumulative from the day AFTER the period opened: `ledger` (all movement on spending accounts),
  `fromSavings` (internal transfers and transfer-category movements seen on spending accounts, plus budgeted bills paid from a
  non-spending account) and
  `unplannedIncome` (per category without a budget: its net, where positive). The line is `ledger − fromSavings − unplannedIncome +
  stillToReceive − stillToPay`. It uses `ledger`, not `spendable`, because a balance and a booking date are often a day apart
- Projection: current free money + the **median daily change** of the line × days left, never above the current value, and only
  with 5+ days. Chosen by backtest on the recorded periods: about half the error of "no change"; straight-line fits were too steep
  (large bills come early), and the median ignores them
- The chart anchors the line so that it ends on the real free money of the last day
- The days of the OPEN period follow the record and the configuration as they are now: each pass of
  `BudgetSnapshotService.ensureUpToDate` computes again all their terms that are not balances (the plan terms, the three ledger terms and the saving reserve).
  A changed budget or estimate, a recategorised transaction or a late transaction thus moves the days it belongs to, and is not read as
  one bad day. The measured balances stay. A closed period keeps what it was recorded with
- Each dot of a chart of the open period names the largest causes of that day's change (`DayCauses`, from
  `BudgetSnapshotService.ensureUpToDateWithCauses`): per category, its movement less the change of what its budget still expects; for a
  category with no budget, its spend. The causes add up to the change of the line. They exist for the open period only
- A second, thinner line (`FreeMoneyTrend.projectionHistory`) is the projected end from each day at the usual daily change of the full
  record. It moves only by the difference between a day and the usual day, so it rises when the period goes better than projected. The
  Dashboard also shows its change across the last 7 days. It is deliberately NOT the projection each day in fact gave: in a backtest
  that one moved from day to day as much as its own error, mostly while the median settled

## Tech Stack

| Layer       | Technology                               |
|-------------|------------------------------------------|
| Language    | Scala 3.9.0                              |
| Backend     | cats-effect, tapir, http4s               |
| Frontend    | Laminar (Scala.js SPA)                   |
| API         | tapir (shared endpoint definitions)      |
| Database    | SQLite + Flyway migrations               |
| JSON        | circe                                    |
| CSS         | Bootstrap 5 (CSS-only)                   |
| Bundler     | Vite + vite-plugin-scalajs               |
| Auth        | Password (Argon2id) + passkeys (java-webauthn-server) |
| Deployment  | Docker + fly.io                          |

## Data Model (Conceptual)

```
Period:
  - id, startDate, expectedEnd (stored, editable), endDate (nullable until closed)
  - plannedSavingCents (stored, editable; 0 = no plan)

BalanceSnapshot:
  - accountId, amount, currency, timestamp   -- append-only history

DailyBudgetSnapshot (table: daily_budget_snapshots):
  - one row per day: the TERMS of the free-money calculation, never the answer
  - spendable / plannedToPay / plannedToReceive / budgetsToSpend / budgetsToReceive / savings, daysRemaining
  - plannedToPay / plannedToReceive          -- terms of the removed planned items. New rows hold 0. The days of periods closed before
                                                the removal keep their amounts, and free money still counts them
  - ledger / fromSavings / unplannedIncome   -- cumulative since the period's first day, from the bank ledger (see Free Money Trend)
  - savingReserve                            -- planned saving not yet moved (+), or the excess above the plan that waits for bills (−);
                                                in free money, NOT in the plan's own line
  - source (live|reconstructed)              -- measured that day, or rebuilt from the record later
  - free money is derived in `v_daily_budget`, so a dip can always be traced to the term that caused it

Account (spending accounts and savings buckets, unified):
  - id, name, currency
  - role (spending|holding|savings|investment)
  - balanceCents, balanceSource (manual|bank|card_group), balanceUpdatedAt

Category / CategoryBudgetOverride:
  - category: id, name, color
      budgetType (steady|bill|subscription)  -- how the monthly figure is drawn down over a period; NULL = not a budget
      budget: CategoryBudget                 -- how that figure is DERIVED; one value, flattened to three columns
        method (average|median|fixed)
        lookbackMonths                       -- completed months the average/median sees; NULL = all history
        fixedCents                           -- signed; the figure itself when method = fixed
      billPayments                           -- payments a Bill expects per period (default 1); each releases 1/N of the budget
      transferAccount                        -- an investment account; the category is then its deposits and withdrawals, and no budget
  - override: (periodId, categoryId) -> remainingCents   -- per-period manual remaining

ExchangeRate:
  - fromCurrency, toCurrency, rate (× 10000), onDate (the day the rate is for), fetchedAt

PasskeyCredential:
  - credentialId, publicKey, signCount, createdAt

McpToken (table: mcp_tokens):
  - id, name, tokenHash (SHA-256; the token itself is never stored), createdAt, lastUsedAt
```

## Database rules

- **Foreign keys are ON** for the connections of the app (`Database.hikariConfig`). A `REFERENCES` in a migration is thus enforced:
  write one only for a rule that the app keeps. A parent row is deleted after its children. Two links are on purpose NOT references:
  a rate can be for a currency that is not enabled, and a bank transaction stays after its connection is gone (V33).
- **Migrations run with foreign keys OFF** (`Database.migrateWithDataSource`), because SQLite changes a table by making it again. A
  migration that makes a table again must first drop the reporting views that read it. After each migrate the app makes the views
  again when one is missing (`Database.restoreMissingViews`), so `R__analytics_views.sql` does not have to change with it.
- `MigrationOnDataSpec` runs the migrations on a database with rows. A migration that changes rows adds its rows and checks there.
- **An instant has one text form**: UTC with milliseconds, 24 characters (`DoobieMeta`, V32). The columns are compared as text, so
  raw SQL that writes an instant must use this form.
- The tables of the removed planned items hold old rows that break a reference (`expense_records`). No code reads or writes them.
- **A column of a fixed set** (a role, a status, a kind) accepts only the values of that set: two triggers for each such column (V34).
  To add a case to such an enumeration, write a migration that makes the two triggers of its column again with the new list;
  `EnumColumnsSpec` fails until the two lists are the same. A migration that makes one of these tables again must make its triggers
  again.

## Analytics

The Analytics page is served by a **bundled Metabase**, not by in-app charting. It is optional:
without `SSBUDGET_METABASE_URL` the proxy, provisioning and nav entry all disappear.

- Metabase binds to loopback; the app proxies it under `/metabase` on its own origin, gated by the
  app session and injecting `X-Metabase-Session` from a service account (`AnalyticsProxyRoutes`).
- The in-app dashboard is a **static embed** — a short-lived signed JWT, minted per page load.
- Metabase explores `v_*` SQL views, not raw tables (cents → major units, ISO dates, resolved names).
- A canonical dashboard is seeded from JSON on first boot. It is only ever touched again when the
  spec's `version` is bumped, and even then **the user's edits win**: the dashboard is copied first,
  then replaced whole only if Metabase's revision log says nobody has edited it — otherwise it just
  gains the cards it is missing, appended below everything already there. Deleted stays deleted.
  Version, revision and the card-key map live in `analytics_state`.

`./dev.sh` runs it locally and `./build.sh` bundles it into the image — both on by default, both
take `--no-analytics`. The container generates and persists the Metabase credentials itself, so a
deploy needs no analytics config.

## MCP server

`POST /mcp` lets an assistant categorize transactions with the user (`backend/mcp`). See `docs/mcp.md`.

- Its own credentials: access tokens that the server makes (`McpTokenService`). The user makes and revokes them in Settings → MCP access, which
  also gives the `claude mcp add` command. A token is shown one time; the `mcp_tokens` table holds only its SHA-256 digest. Making a
  token needs an `IdentityProof` (password or passkey), not only the session, because the token does not expire.
- The session cookie does not open `/mcp`, a token does not open `/api`, and test mode has no effect on either rule. Requests with an
  `Origin` header are refused. With no tokens, no request passes.
- The tool list in `CategorizationTools` is the FULL power of a token: read transactions, categories and rules; write categories and
  rules. Do not add a tool that reads or writes something else without a decision on the security model.
- The procedure (batch → proposal → user confirms → apply → repeat) is the MCP prompt in `CategorizationGuide`. Tools and prompt change
  together.
- `CategorizationService` holds the manual-category write and the rule create / update / delete, and their preview. One `RuleChange`
  value describes a rule change for both `previewChange` and `applyChange`, so a preview shows what the write does. The REST routes
  and the MCP tools both call it. `RuleEngineService.winner` is the one decision "which rule sets the category"; the engine and the
  preview both read it. Rule import, export, reorder and the live preview are in this service too.

## Authentication

One owner, no user accounts.

- **Password**: set once at setup (`/api/auth/setup`), stored as an Argon2id hash. The password login is throttled
  (`LoginThrottle`: 5 free failures, then a lock that doubles).
- **Passkeys (WebAuthn)**: registered in Settings after the first login, as a second way in. Library:
  [Yubico java-webauthn-server](https://github.com/Yubico/java-webauthn-server). A passkey request that waits for its answer is kept
  under its own challenge (`PendingChallenges`: good for 5 minutes, taken one time), so a second "start" from anybody does not make
  a login that is in progress fail.
- **Session**: a random token in an HttpOnly cookie, 30 days, `SameSite=Lax`, `Secure` when the configured origins are https. The
  `sessions` table holds only the SHA-256 digest of the token (`SessionService`), so a copy of the database holds no working login.
  A request with no valid session gets status 401 (every other error is 400, with a text); the client then goes back to the login.
- **Identity proof**: an action with lasting effect needs the password or a passkey again, not only the session (`IdentityProof`).
  A password change and a new MCP token carry the proof in the request. Database export, database import, and adding or deleting a
  passkey need a RECENT proof on the session (`AuthRoutes.validateConfirmed`, 10 minutes, `sessions.confirmed_at`): a login counts,
  and after that `POST /api/auth/confirm`. The Settings page asks for it in a dialog when it is needed.
- **Every route of the app** goes through `Hardening`: a write is accepted only from the app's own page (the `Origin` of the request is
  its `Host`, or a configured origin), a body has a size limit, and each answer has the content policy and the other browser headers.
  The Vite proxies keep the `Host` header for this (`changeOrigin: false`). A new inline `<script>` or a script from another host would
  be blocked by the policy.
- **Test mode** (`SSBUDGET_TEST_MODE=1`) turns the login off. It is for local runs and the e2e tests only: the app refuses to start
  in it when `SSBUDGET_RP_ID` is not `localhost`.

## Errors and warnings in the UI

`NoticeStrip` (in the layout, above every page) shows two things. A failed write: `ApiClient` reports EVERY write that the server
refuses or does not answer to `Notices`, so a page does not have to. The client also counts every accepted write
(`ApiClient.changes`): `DataService.refreshIfStale`, which a page calls when it opens, loads again only after a change or after a
minute. A failed read stays with the page. And the warnings of
`GET /api/warnings` (`DataHealth`): faults that make a figure wrong (a currency in use with no exchange rate counts 1 to 1), and a
background task that failed (`BackgroundHealth`: the daily record), until it succeeds again.

**Errors of the API** - an error is an `ApiError(kind, message)` (shared): on the wire the HTTP status is the kind and the body is
the message. The kinds are invalid (400), unauthorized (401), needs confirmation (403), not found (404) and failed (500). A handler
refuses with a text (that is "invalid") or with an `ApiError` that names its kind; an exception in a handler becomes "failed" with
a text, and the cause goes to the log. The client acts on the kind, never on the words. A failure of the bank API or of the rate
source is "failed", not "invalid": it is not a wrong request of the user.

**Routes and services** - `Routes.scala` only connects an endpoint to a service method (`route` for a handler that can refuse,
`read` for one that cannot, `confirmedRoute` for one that needs a recent proof of identity). The rules are in the services:
`AccountService`, `CategoryService`, `CategorizationService` (rules: write, order, import, export, preview), `TransactionQueryService`,
`BackupService`, `PeriodService`, `BudgetSnapshotService`, `CurrencyService`. A new rule of the app goes into a service, with a test.

A write of more than one step is ONE transaction, in one repository method (`deleteWithDependents`, `importAll`, `rollover`): a
service does not chain repository writes that must all happen or not at all.

## Bank entries

An entry of the bank is stored only when it is booked and has an amount and a date that can be read (`TransactionImportService`).
A pending entry is skipped: the bank sends it again when it is booked. An entry with no amount or no date is left out and the
sync reports it. Nothing is invented. The currency of an account cannot change after it has a bank link or a balance history.

## Sharing the summary

The Dashboard has two buttons: "Copy Summary" puts a short text on the clipboard, and "WhatsApp" opens a share link with the same
text. Nothing is sent by the app itself: the backend has no scheduler, and the machine sleeps when nobody uses it.

## File Structure

```
ssbudget/
├── build.sbt, project/          # multi-module sbt build
├── shared/                      # cross-compiled (JVM + JS): tapir endpoints (api/), domain models (model/), rules
├── backend/                     # http4s server: Routes (wiring only), auth/, banking/, mcp/, analytics/, service/, db/ (repositories)
│   └── src/main/resources/db/migration/   # Flyway: V<n>__*.sql (never edit one that is released), R__analytics_views.sql
├── frontend/                    # Scala.js + Laminar SPA: pages/, components/, services/, styles/app.css
├── e2e/                         # Selenium browser tests
├── docker/entrypoint.sh         # container start: database copy, Metabase, the app
├── Dockerfile, fly.toml         # the image and the fly.io deployment
├── build.sh, dev.sh             # release build, local development
└── docs/                        # mcp.md, audit-2026-11.md (open findings, with status)
```

## Development and build

```bash
./dev.sh                  # the whole stack locally: Vite on https://localhost:3000, backend on :8080, Scala.js watch, Metabase
./dev.sh --no-analytics   # the same without Metabase

sbt unitTests             # the fast test loop: the shared logic on the JVM and as JavaScript (Node), the page logic (Node), the backend
sbt backend/test          # the backend only (a plain `sbt test` runs all but the browser tests: `e2e` is not in the root aggregate)
sbt e2e/test              # browser tests (Chrome)
sbt scalafmtAll           # format; CI checks it
sbt dependencyUpdates     # lists newer versions of the libraries (the sbt-updates plugin)

./build.sh --no-image     # release artifacts: backend/stage, optimized frontend, docker/VERSION (the commit)
fly deploy --remote-only  # fly.io builds the image from those artifacts
```

- The container starts as root only to give `/data` to the service user `ssbudget` (uid 10001); the database copy, Metabase and the
  app then run as that user (`docker/entrypoint.sh`). `fly ssh console` is still root: a file that root makes in `/data` while the
  app runs is not writable for the app until the next start.
- The image build checks the SHA-256 of the Metabase jar (`Dockerfile`: change `METABASE_VERSION` and `METABASE_SHA256` together).
- After a library or Scala update, run `sbt frontend/fastLinkJS` and the browser tests, not only the compile: a JavaScript-side library
  can need a newer Scala.js linker (`sbt-scalajs` in `project/plugins.sbt`), and only the link step shows that. `waypoint` stays at
  10.0.0-M1: its newer milestones bring a milestone of Laminar.
- The page loads the linked code through `frontend/main.js` (`import 'scalajs:main.js'`), never by a path under `target/`: that path
  holds the Scala version, and after a Scala update the page would keep the output of the old compiler.
- The app migrates its database at start (Flyway). The container copies the database to `/data/backups` before that
  (`docker/entrypoint.sh`, newest 5 kept), and the deploy waits for the health check in `fly.toml`.
- `GET /api/health` answers `ok <commit>`: it says which build is live.
- To check a change on real data, run the backend with `SSBUDGET_TEST_MODE=1` on a COPY of the database
  (`SSBUDGET_DB_PATH`, `SSBUDGET_PORT`, `SSBUDGET_STATIC_DIR=frontend/dist`).
- Open findings and ideas are in `docs/audit-2026-11.md`. Change the status of an item when you work on it.

## Code Style

**MANDATORY: Always use curly braces syntax. Never use indentation-based syntax (Scala 3 braceless style).**

**MANDATORY: Always run `sbt scalafmtAll e2e/scalafmtAll` before finishing work to format all Scala code.**

**Compiler warnings are errors in CI.** `sbt-tpolecat` sets the strictest compiler options. Locally a warning is only shown; with `CI`
set (GitHub Actions sets it; locally use `SBT_TPOLECAT_CI=1 sbt Test/compile`) a warning fails the build. Keep the build at zero
warnings: remove an unused import, and write `val _ = expr` when a result is dropped on purpose. Test code compiles without the two
options that report unused values, because a ScalaTest test is a list of assertions.

**Tests to run for a change:** `sbt unitTests` (about 30 s) and `sbt e2e/test` (about 4 min, Chrome). A test of logic in `shared`
goes in `shared/src/test`: it then runs on the JVM and, compiled by Scala.js, on Node, which is the form the browser runs. A figure
that the page computes itself (the `final` members of `DataService`) or a format has its test in `frontend/src/test` (Node, no DOM).
Because `e2e` is not in the root aggregate, name it: `e2e/scalafmtAll`, `e2e/Test/compile`. Do not run two sbt commands
at once in one checkout.

**Browser tests and data**: each spec starts on the database of a new installation. `E2ESpec.beforeAll` calls
`TestServers.resetDatabase`, which puts the rows of every table back to a copy that was made after the migrations (some milliseconds;
the backend stays up). The tests of ONE spec share the database and can build on each other. A spec must make all the data it needs.
A browser test waits for a state of the page, never for a fixed time; "nothing there" is checked with the lookup wait off, after the
page that would show it is there.

**Sums from a repository** come back as a named row (`LedgerRows.scala`: `CategoryAmount`, `DailyCategoryNet`, ...), not as a tuple:
two fields of one type in the wrong order would compile and give a wrong figure.

**Time**: a service reads the time from an `AppClock` that it is given, never from the system: the money logic (period rules, the
daily record, category figures, reports), the sessions, the bank services, the exchange rates and the MCP tokens. A test gives it `AppClock.fixed(...)`,
or a clock that it moves (`AppClock(ref.get)`), and builds its data on a fixed date. Still on the system time: the signing of the
bank API token and the passkey records.

**circe codecs**: Use `derives Codec.AsObject` for case classes. Only use manual `Encoder`/`Decoder` for:
- AnyVal wrapper types (encode as the underlying type)
- Enums with custom string representations
- Types like `LocalDate`, `Instant` that need custom serialization

## Key Decisions Log

| Decision           | Choice                    | Rationale                                        |
|--------------------|---------------------------|--------------------------------------------------|
| Database           | SQLite + Flyway           | Simple, file-based, migrations built-in          |
| CSS Framework      | Bootstrap 5               | Industry standard, extensive components, good docs |
| Auth               | Password + passkeys       | One owner; passkeys (WebAuthn) for daily use, a password as the base |
| Bundler            | Vite + vite-plugin-scalajs| Fast dev, HMR, proven in workflow4s              |
| Historical data    | Per-update                | Track each balance update with timestamp         |
| Expense recurrence | Monthly only              | Keep simple                                      |
| HTTP client        | tapir-sttp-client         | Type-safe, shares endpoint defs with backend     |
| Scala version      | 3.9.0                     | Was pinned at 3.5.2 for a Scala.js compiler bug in 3.8.1; 3.9.0 passes all tests |
| UI philosophy      | Spreadsheet-like          | Concise, direct edit, minimal clicks             |
| Savings accounts   | Same table, `role` flag   | Just buckets with a balance; excluded from spendable, no targets  |

## Laminar/Airstream Gotchas

**Signal combination**: When combining 3+ signals, use chained `combineWith` instead of `Signal.combine`:

```scala
// DON'T - Signal.combine with 3+ signals fails silently (pattern match doesn't work)
Signal.combine(sig1, sig2, sig3, sig4).map { case (a, b, c, d) => ... }

// DO - Use chained combineWith (tuplez library flattens to flat tuple)
sig1.combineWith(sig2).combineWith(sig3).combineWith(sig4).map { case (a, b, c, d) => ... }
```

Note: `Signal.combine` with exactly 2 signals works fine.

**`value <--` on a `<select>` with async options**: a `<select>` silently discards a value that matches no `<option>` yet, and the binder doesn't re-fire when the options arrive later — so the control shows the wrong entry while the underlying state is correct. Declare `value <--` *after* the `children <--` that builds the options (modifiers bind in order, so within one transaction the options are inserted first), and combine the options signal into the value signal so it re-applies when they load:

```scala
// DON'T - value is applied before the options exist; select falls back to the first option
select(
  value <-- categoryFilter.signal,
  option(value := "all", "All"),
  children <-- cats.map(_.map(c => option(value := c.id.value, c.name))),
)

// DO - options first, and the value re-applies whenever they change
select(
  option(value := "all", "All"),
  children <-- cats.map(_.map(c => option(value := c.id.value, c.name))),
  value <-- categoryFilter.signal.combineWith(cats).map { case (selected, _) => selected },
)
```

The same trap is why the budget-type dropdown in `TransactionsPage.categoriesCard` marks the current option with `selected :=` instead of using `value :=` on the select.

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
