# Session 007: Multi-Currency Support

**Date**: 2026-01-29
**Phase**: 8 (Polish & Extras)
**Items Completed**: 8.1 (Exchange Rate API), Multi-currency configuration

## Summary

Redesigned currency handling from a hardcoded PLN/EUR enum to a configurable system. Users can now enable currencies from a list of 32 fiat currencies, set a primary currency for totals and calculations, and fetch exchange rates from an external API (Frankfurter). Includes a searchable dropdown for adding new currencies.

## Key Changes

### Data Model Changes

#### Currency type redesign
- **Changed from enum to value class**: `final case class Currency(code: String) extends AnyVal`
- **Added known currencies list**: 32 ISO 4217 codes with names (AUD, BGN, BRL, CAD, CHF, CNY, CZK, DKK, EUR, GBP, HKD, HUF, IDR, ILS, INR, ISK, JPY, KRW, MXN, MYR, NOK, NZD, PHP, PLN, RON, SEK, SGD, THB, TRY, USD, ZAR)
- **File**: `shared/src/main/scala/ssbudget/shared/model/Money.scala`

#### New model: CurrencySetting
```scala
final case class CurrencySetting(
    code: Currency,
    name: String,
    isPrimary: Boolean,
    enabledAt: Instant,
)
```
- **File**: `shared/src/main/scala/ssbudget/shared/model/CurrencySetting.scala`

### Database Migration

**File**: `backend/src/main/resources/db/migration/V3__currency_settings.sql`

- Created `currency_settings` table with code (PK), name, is_primary, enabled_at
- Unique partial index ensures exactly one primary currency
- Recreated accounts, balance_snapshots, exchange_rates, savings_accounts tables without CHECK constraints (replaced with FK references)
- Seeded PLN (primary) and EUR
- Used ISO 8601 timestamp format for Java Instant parsing compatibility

### Backend Implementation

#### CurrencySettingsRepository
- **File**: `backend/src/main/scala/ssbudget/backend/db/repository/CurrencySettingsRepository.scala`
- CRUD operations: findAll, findByCode, findPrimary, create, setPrimary, delete

#### CurrencyService
- **File**: `backend/src/main/scala/ssbudget/backend/service/CurrencyService.scala`
- `getSettings()` - Returns enabled currencies and available currencies list
- `enableCurrency(code)` - Validates against known currencies, creates setting
- `disableCurrency(code)` - Validates not primary, not in use by accounts
- `setPrimaryCurrency(code)` - Updates primary flag atomically
- `refreshRates()` - Fetches from Frankfurter API (https://api.frankfurter.dev)

#### New Endpoints
- `GET /api/currencies` - Get settings and available currencies
- `POST /api/currencies` - Enable a currency
- `DELETE /api/currencies/{code}` - Disable a currency
- `PUT /api/currencies/primary` - Set primary currency
- `POST /api/currencies/refresh` - Refresh exchange rates from API

### Frontend Implementation

#### DataService updates
- Added `currencySettings: Signal[List[CurrencySetting]]`
- Added `availableCurrencies: Signal[List[(String, String)]]`
- Added `enabledCurrencies: Signal[List[Currency]]`
- Added `primaryCurrency: Signal[Currency]`
- Methods: `enableCurrency()`, `disableCurrency()`, `setPrimaryCurrency()`, `refreshExchangeRates()`

#### SettingsPage Currencies Card
- Table showing enabled currencies with exchange rates
- Primary currency marked, cannot be removed
- "Set Primary" button on non-primary currencies
- Remove button (disabled for primary)
- **Searchable datalist dropdown** for adding currencies
- Shows available currency count
- "Refresh Rates" button in header

#### AccountsPage updates
- Currency selects now use `dataService.enabledCurrencies` instead of hardcoded enum
- Dynamic options based on configured currencies

### E2E Tests

**File**: `e2e/src/test/scala/ssbudget/e2e/CurrencySettingsSpec.scala`

8 new tests:
1. Settings page shows Currencies card with PLN and EUR
2. Shows PLN as primary currency by default
3. Has Refresh Rates button
4. Can add a new currency (USD)
5. Can set a different currency as primary
6. Can remove a non-primary currency
7. Does not show remove button for primary currency
8. Shows enabled currencies in account creation dropdown

## Files Created

- `shared/src/main/scala/ssbudget/shared/model/CurrencySetting.scala`
- `backend/src/main/resources/db/migration/V3__currency_settings.sql`
- `backend/src/main/scala/ssbudget/backend/db/repository/CurrencySettingsRepository.scala`
- `backend/src/main/scala/ssbudget/backend/service/CurrencyService.scala`
- `e2e/src/test/scala/ssbudget/e2e/CurrencySettingsSpec.scala`

## Files Modified

### Shared
- `shared/src/main/scala/ssbudget/shared/model/Money.scala` - Currency type change, knownCurrencies list
- `shared/src/main/scala/ssbudget/shared/api/Dto.scala` - New DTOs (EnableCurrencyRequest, SetPrimaryCurrencyRequest, KnownCurrency, CurrencySettingsResponse, ExchangeRatesResponse)
- `shared/src/main/scala/ssbudget/shared/api/Endpoints.scala` - Currency endpoints (server + client)
- `shared/src/main/scala/ssbudget/shared/api/TapirSchemas.scala` - New schemas

### Backend
- `build.sbt` - Added http4s-ember-client dependency
- `backend/src/main/scala/ssbudget/backend/db/DoobieMeta.scala` - Currency meta for value class
- `backend/src/main/scala/ssbudget/backend/db/Repositories.scala` - CurrencySettingsRepository
- `backend/src/main/scala/ssbudget/backend/Routes.scala` - Currency endpoints handlers
- `backend/src/main/scala/ssbudget/backend/ServerBuilder.scala` - HTTP client, CurrencyService wiring

### Frontend
- `frontend/src/main/scala/ssbudget/frontend/services/DataService.scala` - Currency signals and methods
- `frontend/src/main/scala/ssbudget/frontend/services/ApiDataService.scala` - API implementation
- `frontend/src/main/scala/ssbudget/frontend/services/ApiClient.scala` - Currency API methods
- `frontend/src/main/scala/ssbudget/frontend/services/InMemoryDataService.scala` - Mock implementation
- `frontend/src/main/scala/ssbudget/frontend/pages/SettingsPage.scala` - Currencies card with datalist
- `frontend/src/main/scala/ssbudget/frontend/pages/AccountsPage.scala` - Dynamic currency selects

### E2E
- `e2e/src/test/scala/ssbudget/e2e/E2ESpec.scala` - Added findCardByH5 helper
- `e2e/src/test/scala/ssbudget/e2e/E2ESuite.scala` - Added CurrencySettingsSpec

## Technical Decisions

1. **Value class for Currency**: Allows database-driven configuration without enum recompilation
2. **Frankfurter API**: Free, no API key, uses ECB data, 32 currencies
3. **Partial unique index for primary**: SQLite feature to ensure exactly one primary
4. **ISO 8601 timestamps in migration**: Required for Java Instant parsing (`strftime('%Y-%m-%dT%H:%M:%SZ', 'now')`)
5. **Datalist for currency selection**: HTML5 feature for searchable dropdown with autocomplete
6. **Available currencies filtered**: Already-enabled currencies hidden from add dropdown

## Verification

- `sbt backend/compile` - Success
- `sbt frontend/compile` - Success
- `sbt backend/test` - 50 tests pass
- `sbt e2e/test` - 86 tests pass (78 existing + 8 new)
- `sbt scalafmtAll` - Code formatted

## API Reference

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | /api/currencies | Required | Get enabled currencies and available list |
| POST | /api/currencies | Required | Enable a currency |
| DELETE | /api/currencies/{code} | Required | Disable a currency |
| PUT | /api/currencies/primary | Required | Set primary currency |
| POST | /api/currencies/refresh | Required | Refresh rates from Frankfurter API |
