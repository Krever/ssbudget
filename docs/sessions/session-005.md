# Session 005: API Integration & E2E Test Infrastructure

**Date**: 2026-01-28
**Phase**: 4 (API & Business Logic)
**Items Completed**: 4.1, 4.2, 4.3, 4.4, 4.5

## Summary

Connected the Laminar frontend to the http4s backend, replacing in-memory mocks with real API calls and SQLite persistence. Built automated e2e test infrastructure that starts backend and frontend on random ports with fresh database.

## Changes Made

### API Layer

#### Shared DTOs and Endpoints (`shared/src/main/scala/ssbudget/shared/api/`)
- `Dto.scala` - Request/response DTOs for all operations
- `Endpoints.scala` - Tapir endpoint definitions organized by domain:
  - `accounts` - list, create
  - `balances` - listLatest, create
  - `budgetItems` - list, create, update, delete
  - `expenseRecords` - listCurrent, pay, unpay
  - `periods` - list, startNew
  - `savingsAccounts` - list, create, update, delete
  - `savingsTransactions` - listCurrent, create, delete
  - `exchangeRate` - get
  - `test` - reset (test mode only)

#### Backend Routes (`backend/src/main/scala/ssbudget/backend/Routes.scala`)
- Implemented all endpoint handlers using repositories
- Business logic for startNewPeriod (creates expense records for all budget items)
- Test reset endpoint clears database and recreates schema

#### Frontend API Client (`frontend/src/main/scala/ssbudget/frontend/services/`)
- `ApiClient.scala` - HTTP client using sttp FetchBackend, organized by domain
- `ApiDataService.scala` - Implementation of DataService that calls API and updates local Vars
- `LoadingState.scala` - Generic loading state enum (Loading, Loaded, Error)
- `Loading.scala` - UI components for loading states (spinner, actionButton)

### E2E Test Infrastructure

- `TestServers.scala` - Manages backend/frontend lifecycle:
  - Starts backend in-process using cats-effect fibers
  - Spawns Vite process for frontend
  - Uses random ports and temp SQLite database
  - Waits for both servers to be ready
- `E2ESuite.scala` - Master test suite that starts/stops servers
- `E2ESpec.scala` - Base trait with helper methods for test data setup
- `vite.config.e2e.mjs` - Vite config for tests with env var configuration

### Server Refactoring
- `ServerBuilder.scala` - Extracted common server setup logic
- Main.scala and TestServers both use ServerBuilder.build()

## Technical Decisions

1. **Individual endpoints vs bootstrap**: Originally planned bootstrap endpoint, refactored to individual endpoints called in parallel for simpler API design

2. **Domain organization**: Endpoints grouped by domain (accounts, balances, etc.) for easier scanning

3. **Period end date**: Changed from 30-day hardcoded to 25th of next month

4. **Backend returns raw data**: Frontend computes derived values (predictions, remaining savings) - reuses existing computation logic

5. **XPath for button clicks**: Use `contains(.,'text')` instead of `contains(text(),'text')` to match text in child elements (Loading.actionButton wraps labels in spans)

6. **Server abstraction**: Extracted ServerBuilder to avoid duplication between Main and TestServers

## Files Created

### Shared
- `shared/src/main/scala/ssbudget/shared/api/Dto.scala`
- `shared/src/main/scala/ssbudget/shared/api/Endpoints.scala`

### Backend
- `backend/src/main/scala/ssbudget/backend/Routes.scala`
- `backend/src/main/scala/ssbudget/backend/ServerBuilder.scala`

### Frontend
- `frontend/src/main/scala/ssbudget/frontend/services/ApiClient.scala`
- `frontend/src/main/scala/ssbudget/frontend/services/ApiDataService.scala`
- `frontend/src/main/scala/ssbudget/frontend/components/LoadingState.scala`
- `frontend/src/main/scala/ssbudget/frontend/components/Loading.scala`
- `frontend/vite.config.e2e.mjs`

### E2E Tests
- `e2e/src/test/scala/ssbudget/e2e/TestServers.scala`
- `e2e/src/test/scala/ssbudget/e2e/E2ESuite.scala`

## Files Modified

### Backend
- `backend/src/main/scala/ssbudget/backend/Main.scala` - Uses ServerBuilder

### Frontend
- `frontend/src/main/scala/ssbudget/frontend/services/DataService.scala` - Added initialize(), mock switching
- `frontend/src/main/scala/ssbudget/frontend/services/InMemoryDataService.scala` - Mutations return Future[Unit]
- `frontend/src/main/scala/ssbudget/frontend/Main.scala` - Calls initialize()
- `frontend/src/main/scala/ssbudget/frontend/pages/*.scala` - Use Loading.actionButton
- `frontend/src/main/scala/ssbudget/frontend/pages/PeriodsPage.scala` - Shows expected end date
- `frontend/src/main/scala/ssbudget/frontend/util/Formatting.scala` - Added formatLocalDate

### E2E Tests
- `e2e/src/test/scala/ssbudget/e2e/E2ESpec.scala` - Added setup helpers, fixed XPath selectors
- `e2e/src/test/scala/ssbudget/e2e/*Spec.scala` - Tests create their own data

## Verification

- `sbt compile` - All modules compile
- `sbt backend/test` - 50 tests pass
- `sbt e2e/testOnly ssbudget.e2e.E2ESuite` - 32 tests pass
- `sbt scalafmtAll` - Code formatted

## API Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | /api/health | Health check |
| GET | /api/accounts | List accounts |
| POST | /api/accounts | Create account |
| GET | /api/balance-snapshots/latest | Latest balances per account |
| POST | /api/balance-snapshots | Create balance snapshot |
| GET | /api/budget-items | List budget items |
| POST | /api/budget-items | Create budget item |
| PUT | /api/budget-items/:id | Update budget item |
| DELETE | /api/budget-items/:id | Delete budget item |
| GET | /api/expense-records/current | Current period records |
| POST | /api/expense-records/:id/pay | Pay expense |
| POST | /api/expense-records/:id/unpay | Unpay expense |
| GET | /api/periods | List periods |
| POST | /api/periods/start | Start new period |
| GET | /api/savings-accounts | List savings accounts |
| POST | /api/savings-accounts | Create savings account |
| PUT | /api/savings-accounts/:id | Update savings account |
| DELETE | /api/savings-accounts/:id | Delete savings account |
| GET | /api/savings-transactions/current | Current period transactions |
| POST | /api/savings-transactions | Create transaction |
| DELETE | /api/savings-transactions/:id | Delete transaction |
| GET | /api/exchange-rate | Get exchange rate |
| POST | /api/test/reset | Reset database (test mode only) |
