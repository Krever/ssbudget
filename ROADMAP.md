# SSBudget Roadmap

## Overview

Development is split into phases. Each phase should result in a usable increment. Sessions pick items from the current phase and implement them fully.

**UI-Driven Approach**: Since this is a single-purpose personal app, the UI drives API design. We build screens first with mock data, then implement exactly the API endpoints and business logic each screen needs. This avoids over-engineering and ensures the backend serves the frontend's actual requirements.

---

## Phase 1: Foundation & Skeleton
**Goal**: Working cross-build with backend serving static frontend, Vite dev setup.

- [x] **1.1 Multi-Module SBT Build**
  - Three projects: `shared`, `backend`, `frontend`
  - Cross-compilation setup for shared code
  - ES Module output for Scala.js (required for Vite)
  - Dependencies: cats-effect, http4s, tapir, circe, Laminar

- [x] **1.2 Vite + Scala.js Integration**
  - `frontend/vite.config.mjs` with vite-plugin-scalajs
  - `frontend/package.json` with Bootstrap, Vite deps
  - `frontend/index.html` entry point
  - Proxy `/api` to backend in dev mode

- [x] **1.3 Basic Backend**
  - http4s server with tapir
  - Health check endpoint (`GET /api/health`)
  - Static file serving for production (bundled frontend)
  - Configuration via environment variables

- [x] **1.4 Basic Frontend**
  - Laminar app shell with `@JSExportTopLevel`
  - Bootstrap CSS integration
  - Simple page showing "Hello" + health check result
  - Verify hot reload works

---

## Phase 2: Data Layer
**Goal**: SQLite database with migrations, core domain models.

- [x] **2.1 Database Setup**
  - SQLite integration (doobie or skunk)
  - Flyway migrations plugin
  - Connection management with cats-effect Resource

- [x] **2.2 Core Schema (Migrations)**
  - `V1__initial_schema.sql` - all tables in single migration
  - accounts, expense_definitions, periods, expense_records, balance_snapshots, exchange_rates

- [x] **2.3 Repository Layer**
  - Type-safe queries with doobie
  - Repository traits and implementations in backend
  - CRUD for all entities + specialized queries

---

## Phase 3: Frontend - Core UI
**Goal**: Build UI screens with mock data. Let the UI drive API requirements.

*Strategy*: Each screen starts with hardcoded/mock data. As screens mature, we identify exactly what API calls and business logic they need. This ensures we only build backend functionality that the UI actually requires.

- [ ] **3.1 Layout & Navigation**
  - App shell with Bootstrap navbar
  - Dashboard page (placeholder)
  - Expenses page (placeholder)
  - Accounts page (placeholder)
  - Client-side routing (Waypoint or manual)

- [ ] **3.2 Dashboard**
  - Current balance display (big number)
  - Free money / daily budget
  - Days remaining in period
  - Quick actions (update balance, start period)
  - *Mock*: hardcoded summary data

- [ ] **3.3 Expense Management**
  - List expense definitions (planned + estimated)
  - Add/edit expense definition modal
  - Mark expense as paid (for current period)
  - Toggle estimated expense inclusion
  - *Mock*: hardcoded expense list

- [ ] **3.4 Account Management**
  - List accounts with latest balance
  - Add/edit account
  - Record new balance snapshot
  - *Mock*: hardcoded account list

- [ ] **3.5 Period Management**
  - Current period info
  - "Start new period" button
  - Period history list
  - *Mock*: hardcoded period data

---

## Phase 4: API & Business Logic
**Goal**: Implement API endpoints and calculations driven by UI needs.

*Strategy*: For each UI screen, define the tapir endpoints it needs, implement backend handlers, and wire up the frontend. Business logic (calculations, period management) is implemented as needed to support the API.

- [ ] **4.1 Frontend HTTP Client Setup**
  - tapir-sttp-client integration
  - API service layer pattern
  - Error handling utilities

- [ ] **4.2 Account & Balance API**
  - Account CRUD endpoints
  - Balance snapshot recording
  - Sum balances across accounts (with EUR conversion)
  - Wire to Account Management UI

- [ ] **4.3 Expense API**
  - Expense definition CRUD endpoints
  - Expense payment recording
  - Expense prediction calculations (unpaid planned + scaled estimated)
  - Wire to Expense Management UI

- [ ] **4.4 Period API**
  - Period management endpoints (start, current, list)
  - Period state transitions
  - Wire to Period Management UI

- [ ] **4.5 Dashboard Summary API**
  - Budget summary endpoint
  - Free money calculation
  - Daily budget calculation
  - Wire to Dashboard UI

---

## Phase 5: Authentication (Passkeys)
**Goal**: WebAuthn passkey authentication protecting all routes.

- [ ] **5.1 Backend WebAuthn Setup**
  - Add java-webauthn-server dependency
  - Credential storage schema
  - RelyingParty configuration

- [ ] **5.2 Registration Flow**
  - `/api/auth/register/start` - generate challenge
  - `/api/auth/register/finish` - verify and store credential
  - First-time setup flow (no existing credentials)

- [ ] **5.3 Authentication Flow**
  - `/api/auth/login/start` - generate challenge
  - `/api/auth/login/finish` - verify credential
  - Session token generation (JWT or simple token)

- [ ] **5.4 Frontend Auth Integration**
  - WebAuthn browser API calls
  - Login page component
  - Registration page component
  - Auth state management
  - Protected route wrapper

- [ ] **5.5 Middleware & Session**
  - Auth middleware for protected endpoints
  - Session cookie or Authorization header
  - Logout endpoint

---

## Phase 6: Notifications & Summary
**Goal**: Summary sharing functionality.

- [ ] **6.1 Summary Formatting**
  - Text format for clipboard/messaging
  - Configurable template (optional)

- [ ] **6.2 Copy to Clipboard**
  - Button on dashboard
  - Visual feedback (toast/notification)

- [ ] **6.3 WhatsApp Integration**
  - Research: WhatsApp Business API vs Twilio vs wa.me links
  - Implement chosen approach
  - Recipient configuration in settings

---

## Phase 7: forms4s-laminar Integration
**Goal**: Build Laminar renderer for forms4s, refactor app to use it.

- [ ] **7.1 Laminar Module Setup**
  - `forms4s-laminar` submodule
  - Dependency on forms4s-core

- [ ] **7.2 Form Renderer**
  - FormRenderer trait for Laminar
  - Basic elements: text, number, select, checkbox
  - Bootstrap styling
  - Validation display

- [ ] **7.3 Table Renderer**
  - TableRenderer trait for Laminar
  - Column rendering
  - Filtering UI
  - Sorting UI
  - Pagination

- [ ] **7.4 Refactor App**
  - Replace manual forms with forms4s
  - Replace manual tables with forms4s datatables
  - Extract reusable patterns

---

## Phase 8: Polish & Extras
**Goal**: Quality of life improvements.

- [ ] **8.1 Exchange Rate API**
  - Integrate external API (exchangerate-api.com or similar)
  - Manual refresh button
  - Display last updated time

- [ ] **8.2 Historical Data**
  - View expense history per definition
  - Average calculations display
  - Import from CSV/JSON (low priority)

- [ ] **8.3 Mobile Optimization**
  - Responsive design review
  - Touch-friendly controls
  - PWA manifest (optional)

- [ ] **8.4 Data Export**
  - Export to CSV
  - Backup/restore functionality

---

## Phase 9: Production Hardening
**Goal**: Ready for daily use.

- [ ] **9.1 Docker & Deployment**
  - Multi-stage Dockerfile
  - fly.io configuration (fly.toml)
  - Environment variable handling
  - SQLite volume persistence

- [ ] **9.2 Error Handling**
  - Graceful error display in UI
  - Retry logic for network errors
  - Offline indicator

- [ ] **9.3 Logging & Monitoring**
  - Structured logging (log4cats)
  - Health checks for fly.io
  - Basic metrics (optional)

- [ ] **9.4 Security Review**
  - HTTPS enforcement
  - CORS configuration
  - Input validation audit
  - Rate limiting (optional)

---

## Future Ideas (Not Planned)

- Multiple currencies beyond EUR
- Budget goals/targets
- Expense forecasting
- Mobile native app (or PWA)
- Multi-user with proper accounts
- Recurring income tracking
- Bill due date reminders
- Receipt photo storage
- Bank API integration (open banking)

---

## Session Log

| Session | Date       | Phase | Items Completed  | Notes                                  |
|---------|------------|-------|------------------|----------------------------------------|
| 0       | 2026-01-26 | -     | Initial planning | Created CLAUDE.md, ROADMAP.md, spec.md |
| 1       | 2026-01-26 | 1     | 1.1, 1.2, 1.3, 1.4 | Foundation complete, Scala 3.5.2 due to JS bug |
| 2       | 2026-01-27 | 2     | 2.1, 2.2, 2.3    | Data layer complete with doobie, flyway, scalatest, 34 tests |

