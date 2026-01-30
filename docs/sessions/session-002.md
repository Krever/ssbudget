# Session 002: Data Layer Implementation

**Date**: 2026-01-27
**Phase**: 2 (Data Layer)
**Items Completed**: 2.1, 2.2, 2.3

## Summary

Implemented the complete data layer with SQLite database, Flyway migrations, domain models, and repository layer with comprehensive test coverage. Also added utility traits for reducing JSON codec boilerplate.

## Changes Made

### Build Configuration
- Added doobie-core 1.0.0-RC6, doobie-hikari 1.0.0-RC6
- Added sqlite-jdbc 3.47.2.0
- Added flyway-core 10.22.0
- Added scalatest 3.2.19 and cats-effect-testing-scalatest 1.6.0 for testing
- Added scala-java-time 2.6.0 to shared JS for java.time polyfill

### Database Migration
- Created `V1__initial_schema.sql` with all tables:
  - accounts (id, name, currency)
  - expense_definitions (id, name, expense_type, estimate_mode, fixed_estimate, include_in_balance)
  - periods (id, started_at, ended_at) - using Instant timestamps
  - expense_records (id, period_id, expense_def_id, paid_amount, paid_at) - using Instant timestamps
  - balance_snapshots (id, account_id, amount, currency, recorded_at)
  - exchange_rates (from/to_currency, rate, fetched_at) - no id, natural key
- Money stored as INTEGER (cents), timestamps as TEXT (ISO 8601)
- Added indexes for common query patterns

### Domain Models (shared module)
- `Money.scala` - Currency enum, Money case class with arithmetic operations
- `Account.scala` - AccountId, Account
- `ExpenseDefinition.scala` - ExpenseDefId, ExpenseType, EstimateMode, ExpenseDefinition
- `Period.scala` - PeriodId, Period (with Instant timestamps)
- `ExpenseRecord.scala` - ExpenseRecordId, ExpenseRecord (with Instant timestamps)
- `BalanceSnapshot.scala` - BalanceSnapshotId, BalanceSnapshot
- `ExchangeRate.scala` - ExchangeRate with conversion methods (no id, uses natural key)
- All models use `derives Codec.AsObject` for circe derivation

### JSON Utilities (shared module)
- `EnumCodec.scala` - Generic utility for enum JSON codecs with custom string mappings
- `StringId.scala` - Trait for AnyVal string wrapper codecs (reduces boilerplate)

### Database Layer (backend module)
- `Database.scala` - Transactor creation with HikariCP, Flyway migration
- `DoobieMeta.scala` - doobie Meta instances for all custom types
- `Repositories.scala` - Factory class holding all repository instances

### Repository Implementations
- `AccountRepository.scala` - CRUD operations
- `ExpenseDefinitionRepository.scala` - CRUD + findByType
- `PeriodRepository.scala` - CRUD + findCurrent, close
- `ExpenseRecordRepository.scala` - CRUD + findByPeriod, findByPeriodAndExpense, markAsPaid
- `BalanceSnapshotRepository.scala` - CRUD + findByAccount, findLatestByAccount, findAllLatest
- `ExchangeRateRepository.scala` - create, findLatest, findAll (no findById/delete - uses natural key)

### Tests
- Created `RepositorySpec.scala` base trait with in-memory SQLite fixture (scalatest)
- 34 tests covering all repository operations:
  - AccountRepositorySpec (5 tests)
  - ExpenseDefinitionRepositorySpec (6 tests)
  - PeriodRepositorySpec (7 tests)
  - ExpenseRecordRepositorySpec (6 tests)
  - BalanceSnapshotRepositorySpec (6 tests)
  - ExchangeRateRepositorySpec (4 tests)

### Main.scala Updates
- Added database initialization on startup
- Creates data directory if it doesn't exist
- Runs Flyway migrations automatically
- Configurable via SSBUDGET_DB_PATH environment variable

### Other
- Added `data/` to .gitignore

## Technical Decisions

1. **Single migration file**: All tables in V1__initial_schema.sql for clean initial setup
2. **Instant over LocalDate**: Timestamps (Instant) are more reliable for persistence than dates
3. **ExchangeRate without ID**: Uses natural key (from_currency, to_currency, fetched_at)
4. **scalatest over munit**: Better async support with cats-effect-testing-scalatest
5. **StringId trait**: Reduces boilerplate for AnyVal string wrappers
6. **EnumCodec utility**: Generic enum codec with custom string mappings
7. **Flyway via HikariCP datasource**: Required for in-memory SQLite to work correctly (shared cache mode)

## Files Created/Modified

### New Files
- `backend/src/main/resources/db/migration/V1__initial_schema.sql`
- `backend/src/main/scala/ssbudget/backend/db/Database.scala`
- `backend/src/main/scala/ssbudget/backend/db/DoobieMeta.scala`
- `backend/src/main/scala/ssbudget/backend/db/Repositories.scala`
- `backend/src/main/scala/ssbudget/backend/db/repository/AccountRepository.scala`
- `backend/src/main/scala/ssbudget/backend/db/repository/ExpenseDefinitionRepository.scala`
- `backend/src/main/scala/ssbudget/backend/db/repository/PeriodRepository.scala`
- `backend/src/main/scala/ssbudget/backend/db/repository/ExpenseRecordRepository.scala`
- `backend/src/main/scala/ssbudget/backend/db/repository/BalanceSnapshotRepository.scala`
- `backend/src/main/scala/ssbudget/backend/db/repository/ExchangeRateRepository.scala`
- `shared/src/main/scala/ssbudget/shared/model/Money.scala`
- `shared/src/main/scala/ssbudget/shared/model/Account.scala`
- `shared/src/main/scala/ssbudget/shared/model/ExpenseDefinition.scala`
- `shared/src/main/scala/ssbudget/shared/model/Period.scala`
- `shared/src/main/scala/ssbudget/shared/model/ExpenseRecord.scala`
- `shared/src/main/scala/ssbudget/shared/model/BalanceSnapshot.scala`
- `shared/src/main/scala/ssbudget/shared/model/ExchangeRate.scala`
- `shared/src/main/scala/ssbudget/shared/json/EnumCodec.scala`
- `shared/src/main/scala/ssbudget/shared/json/StringId.scala`
- `backend/src/test/scala/ssbudget/backend/db/repository/RepositorySpec.scala`
- `backend/src/test/scala/ssbudget/backend/db/repository/AccountRepositorySpec.scala`
- `backend/src/test/scala/ssbudget/backend/db/repository/ExpenseDefinitionRepositorySpec.scala`
- `backend/src/test/scala/ssbudget/backend/db/repository/PeriodRepositorySpec.scala`
- `backend/src/test/scala/ssbudget/backend/db/repository/ExpenseRecordRepositorySpec.scala`
- `backend/src/test/scala/ssbudget/backend/db/repository/BalanceSnapshotRepositorySpec.scala`
- `backend/src/test/scala/ssbudget/backend/db/repository/ExchangeRateRepositorySpec.scala`

### Modified Files
- `build.sbt` - Added database and test dependencies
- `.gitignore` - Added data/ directory
- `backend/src/main/scala/ssbudget/backend/Main.scala` - Database wiring
- `ROADMAP.md` - Updated status

## Verification

- `sbt compile` - All modules compile successfully
- `sbt backend/test` - All 34 tests pass
- `sbt scalafmtCheckAll` - All code properly formatted
- `sbt backend/run` - Server starts, migrations run, database created at data/ssbudget.db

## Next Steps

Phase 3: Core Business Logic
- Period management
- Balance calculation
- Expense prediction
- Budget summary
