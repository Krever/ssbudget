# Session: 008 - Database Backup/Restore

**Date**: 2026-01-29
**Phase**: 8 (Polish & Extras)
**Items**: 8.4

## Goal

Add database import and export functionality for backup and restore purposes.

## Plan

### Step 1: Backend Export/Import Endpoints
- [x] Add tapir endpoints for database download and import
- [x] Implement export as file download
- [x] Implement import with SQLite backup API for live restore

### Step 2: Frontend UI
- [x] Add Data card to Settings page
- [x] Export button (download link)
- [x] Import button with file picker

### Step 3: E2E Tests
- [x] Create DatabaseSpec with functional tests for export/import

## Implementation Notes

**Initial approach (file replacement)**: First attempted to replace the SQLite file directly during import. This caused `SQLITE_READONLY_DBMOVED` error because HikariCP's connection pool held references to the old file.

**Final approach (SQLite backup API)**: Used `org.sqlite.SQLiteConnection.getDatabase.restore()` to copy data from uploaded file into the running database. This works without restart because it operates through an existing connection.

**Tapir consistency**: Initially implemented with raw http4s routes, then refactored to use tapir endpoints for consistency with the rest of the codebase. Added `Endpoints.database.download` and `Endpoints.database.import` in shared module.

**Scala 3 keyword**: `export` is a reserved keyword in Scala 3, renamed endpoint to `download`.

## Completed

- [x] Backend export endpoint (GET /api/database/export) - returns SQLite file with timestamped filename
- [x] Backend import endpoint (POST /api/database/import) - accepts raw bytes, validates SQLite header, restores via backup API
- [x] Frontend Data card on Settings page with Export/Import buttons
- [x] Warning message about data replacement
- [x] 3 functional e2e tests (export downloads valid SQLite, import restores data, invalid file shows error)

## Deferred / Follow-up

- [ ] CSV export (lower priority, SQLite export covers backup use case)

## Files Changed

```
shared/src/main/scala/ssbudget/shared/api/Endpoints.scala - Added database.download and database.import endpoints
backend/src/main/scala/ssbudget/backend/Routes.scala - Added exportDatabase and importDatabase handlers
backend/src/main/scala/ssbudget/backend/ServerBuilder.scala - Pass transactor to Routes
backend/src/main/scala/ssbudget/backend/Main.scala - Pass transactor to ServerBuilder
frontend/src/main/scala/ssbudget/frontend/pages/SettingsPage.scala - Added Data card with export/import UI
e2e/src/test/scala/ssbudget/e2e/DatabaseSpec.scala - New test spec (5 tests)
e2e/src/test/scala/ssbudget/e2e/E2ESuite.scala - Added DatabaseSpec to suite
e2e/src/test/scala/ssbudget/e2e/TestServers.scala - Updated for new ServerBuilder signature
e2e/src/test/scala/ssbudget/e2e/AuthTestServers.scala - Updated for new ServerBuilder signature
```

## Testing Done

- [x] Manual testing of export (downloads .db file)
- [x] Manual testing of import (restores database without restart)
- [x] 3 functional e2e tests passing (DatabaseSpec):
  - Export downloads valid SQLite file (verified header)
  - Import restores data from backup (creates data, exports, adds more data, imports, verifies original data restored)
  - Import shows error for invalid file
- [x] All existing e2e tests still passing

## Next Session Recommendations

- Phase 9 (Production Hardening): Docker deployment, error handling, logging
- Or Phase 8.2/8.3: Historical data views, mobile optimization
