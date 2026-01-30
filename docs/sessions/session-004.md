# Session 004: Savings Support Implementation

**Date**: 2026-01-27
**Phase**: 3.5 (Savings Support)
**Items Completed**: 3.5.1, 3.5.2

## Summary

Added comprehensive savings support to the budget tracker. Savings accounts are separate buckets for accumulating money (emergency fund, vacation, etc.) with editable balances, optional monthly targets, and transaction tracking. UI allows managing accounts on Accounts page and tracking transactions on Budget page.

## Changes Made

### Database Schema (V1__initial_schema.sql)
- Added `savings_accounts` table: id, name, currency, current_balance, planned_monthly
- Added `savings_transactions` table: id, account_id, period_id, amount, note, created_at

### Shared Models
- `SavingsAccount.scala` - Savings account with balance and optional monthly target
- `SavingsTransaction.scala` - Transaction with amount (+/-), optional note, and timestamp
- `SavingsAccountId` and `SavingsTransactionId` - Type-safe ID wrappers

### Backend Repositories
- `SavingsAccountRepository.scala` - CRUD + updateBalance method
- `SavingsTransactionRepository.scala` - CRUD + findByAccount, findByPeriod, findByAccountAndPeriod, deleteByAccountId
- Updated `DoobieMeta.scala` with new ID type conversions
- Updated `Repositories.scala` to include new repositories

### Frontend DataService
Added to `DataService.scala` trait:
- `savingsAccounts: Signal[List[SavingsAccount]]`
- `savingsTransactions: Signal[List[SavingsTransaction]]`
- `currentPeriodSavingsTransactions: Signal[List[SavingsTransaction]]`
- `remainingSavingsTarget: Signal[Money]`
- CRUD methods for savings accounts and transactions

### Frontend Pages
- **AccountsPage.scala** - Added Savings Accounts section with add/edit/delete functionality
- **BudgetPage.scala** - Added Planned Savings card with expandable rows showing transactions, supports multiple expanded rows simultaneously
- **DashboardPage.scala** - Added savings accounts to accounts table with separator, included in bulk balance editing

### E2E Tests
Updated and added tests for savings functionality:
- `AccountsPageSpec.scala` - 6 new tests for savings account CRUD
- `BudgetPageSpec.scala` - 7 new tests for planned savings and transactions
- `DashboardSpec.scala` - Fixed clipboard test with CDP permission grant
- `E2ESpec.scala` - Fixed button text references

## Technical Decisions

1. **Savings vs Bank Accounts**: Kept separate from regular bank accounts - savings are "buckets" for goal tracking, not real bank accounts
2. **Transaction Tracking**: Each inflow/outflow is a transaction with optional note and timestamp
3. **Period-based Progress**: Transactions are tied to periods, progress shows contributions in current period
4. **Multi-expand Support**: Changed `expandedSavingsId: Var[Option[Id]]` to `expandedSavingsIds: Var[Set[Id]]` for better UX
5. **Remaining Target in Predictions**: `remainingSavingsTarget` included in `predictedExpenses` calculation
6. **CDP Clipboard Permissions**: Used Chrome DevTools Protocol to grant clipboard read permissions for E2E tests

## Files Created

### Shared Models
- `shared/src/main/scala/ssbudget/shared/model/SavingsAccount.scala`
- `shared/src/main/scala/ssbudget/shared/model/SavingsTransaction.scala`

### Backend Repositories
- `backend/src/main/scala/ssbudget/backend/db/repository/SavingsAccountRepository.scala`
- `backend/src/main/scala/ssbudget/backend/db/repository/SavingsTransactionRepository.scala`

### Backend Tests
- `backend/src/test/scala/ssbudget/backend/db/repository/SavingsAccountRepositorySpec.scala`
- `backend/src/test/scala/ssbudget/backend/db/repository/SavingsTransactionRepositorySpec.scala`

## Files Modified

### Schema
- `backend/src/main/resources/db/migration/V1__initial_schema.sql` - Added savings tables

### Backend
- `backend/src/main/scala/ssbudget/backend/db/DoobieMeta.scala` - Added SavingsAccountId, SavingsTransactionId
- `backend/src/main/scala/ssbudget/backend/db/Repositories.scala` - Added savings repositories

### Frontend
- `frontend/src/main/scala/ssbudget/frontend/services/DataService.scala` - Added savings signals and methods
- `frontend/src/main/scala/ssbudget/frontend/services/InMemoryDataService.scala` - Added mock implementation
- `frontend/src/main/scala/ssbudget/frontend/pages/AccountsPage.scala` - Added savings section
- `frontend/src/main/scala/ssbudget/frontend/pages/BudgetPage.scala` - Added planned savings with transactions
- `frontend/src/main/scala/ssbudget/frontend/pages/DashboardPage.scala` - Added savings to accounts table

### E2E Tests
- `e2e/src/test/scala/ssbudget/e2e/AccountsPageSpec.scala` - Fixed + added savings tests
- `e2e/src/test/scala/ssbudget/e2e/BudgetPageSpec.scala` - Added savings transaction tests
- `e2e/src/test/scala/ssbudget/e2e/DashboardSpec.scala` - Fixed clipboard test, summary labels

### Documentation
- `ROADMAP.md` - Added Phase 3.5, updated session log
- `CLAUDE.md` - Updated data model with savings

## Verification

- `sbt backend/test` - 50 tests pass (including 17 new savings tests)
- `sbt e2e/test` - 33 tests pass (13 new savings tests)
- `sbt compile` - All modules compile
- `sbt scalafmtAll` - Code formatted

## UI Behavior

### Accounts Page - Savings Section
- Table showing: Account name, Currency badge, Balance, Target/mo
- Add button creates new savings account with name, currency, optional target
- Edit mode allows changing name, currency, target; has delete button

### Budget Page - Planned Savings Card
- Shows only savings accounts with targets (plannedMonthly defined)
- Columns: Account (with expand arrow), Target, Saved, Remaining
- Color coding: green when target met, yellow/warning when not
- Click row to expand/collapse (supports multiple expanded)
- Expanded view shows transactions with date, note, amount (+/-)
- "+ Add" button shows form with note input, amount (pre-filled with remaining)
- Footer shows "Remaining to Save" total

### Dashboard - Accounts Table
- Separator row "-- Savings --" between bank and savings accounts
- Savings accounts show balance, included in bulk edit mode
- Not included in total balance (separate tracking)

