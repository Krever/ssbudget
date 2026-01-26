# SSBudget Roadmap

## Overview

Development is split into phases. Each phase should result in a usable increment. Sessions pick items from the current phase and implement them fully.

---

## Phase 1: Foundation & Skeleton
**Goal**: Working cross-build with backend serving static frontend, Vite dev setup.

- [ ] **1.1 Multi-Module SBT Build**
  - Three projects: `shared`, `backend`, `frontend`
  - Cross-compilation setup for shared code
  - ES Module output for Scala.js (required for Vite)
  - Dependencies: cats-effect, http4s, tapir, circe, Laminar

- [ ] **1.2 Vite + Scala.js Integration**
  - `frontend/vite.config.mjs` with vite-plugin-scalajs
  - `frontend/package.json` with Bulma, Vite deps
  - `frontend/index.html` entry point
  - Proxy `/api` to backend in dev mode

- [ ] **1.3 Basic Backend**
  - http4s server with tapir
  - Health check endpoint (`GET /api/health`)
  - Static file serving for production (bundled frontend)
  - Configuration via environment variables

- [ ] **1.4 Basic Frontend**
  - Laminar app shell with `@JSExportTopLevel`
  - Bulma CSS integration
  - Simple page showing "Hello" + health check result
  - Verify hot reload works

---

## Phase 2: Data Layer
**Goal**: SQLite database with migrations, core domain models.

- [ ] **2.1 Database Setup**
  - SQLite integration (doobie or skunk)
  - Flyway migrations plugin
  - Connection management with cats-effect Resource

- [ ] **2.2 Core Schema (Migrations)**
  - `V1__accounts.sql` - accounts table
  - `V2__expense_definitions.sql` - expense definitions
  - `V3__periods.sql` - periods table
  - `V4__expense_records.sql` - expense records
  - `V5__balance_snapshots.sql` - balance snapshots
  - `V6__exchange_rates.sql` - exchange rates

- [ ] **2.3 Repository Layer**
  - Type-safe queries with doobie
  - Repository traits in shared, implementations in backend
  - CRUD for all entities

---

## Phase 3: Core Business Logic
**Goal**: Budget calculation engine, tested independently.

- [ ] **3.1 Period Management**
  - Start new period (closes previous)
  - Get current period
  - List period history

- [ ] **3.2 Balance Calculation**
  - Sum balances across accounts
  - EUR conversion with exchange rates
  - Total balance in PLN

- [ ] **3.3 Expense Prediction**
  - Planned expenses: sum unpaid estimates
  - Estimated expenses: scale by remaining days
  - Estimate modes: fixed, lastMonth, average
  - Toggle inclusion for estimated expenses

- [ ] **3.4 Budget Summary**
  - Free money = balance - predicted
  - Daily budget = free money / days remaining
  - Summary data structure for API/notifications

---

## Phase 4: Authentication (Passkeys)
**Goal**: WebAuthn passkey authentication protecting all routes.

- [ ] **4.1 Backend WebAuthn Setup**
  - Add java-webauthn-server dependency
  - Credential storage schema (`V7__passkey_credentials.sql`)
  - RelyingParty configuration

- [ ] **4.2 Registration Flow**
  - `/api/auth/register/start` - generate challenge
  - `/api/auth/register/finish` - verify and store credential
  - First-time setup flow (no existing credentials)

- [ ] **4.3 Authentication Flow**
  - `/api/auth/login/start` - generate challenge
  - `/api/auth/login/finish` - verify credential
  - Session token generation (JWT or simple token)

- [ ] **4.4 Frontend Auth Integration**
  - WebAuthn browser API calls
  - Login page component
  - Registration page component
  - Auth state management
  - Protected route wrapper

- [ ] **4.5 Middleware & Session**
  - Auth middleware for protected endpoints
  - Session cookie or Authorization header
  - Logout endpoint

---

## Phase 5: API Layer
**Goal**: Full REST API with tapir, shared between frontend and backend.

- [ ] **5.1 Shared Endpoint Definitions**
  - Expense definition CRUD endpoints
  - Account CRUD endpoints
  - Period management endpoints
  - Balance recording endpoint
  - Expense payment recording endpoint
  - Summary endpoint

- [ ] **5.2 Backend Implementation**
  - Wire endpoints to services
  - Error handling with proper HTTP codes
  - Input validation

- [ ] **5.3 Frontend HTTP Client**
  - tapir-sttp-client setup
  - API service layer
  - Error handling

---

## Phase 6: Frontend - Core UI
**Goal**: Basic functional UI for all operations.

- [ ] **6.1 Layout & Navigation**
  - App shell with Bulma navbar
  - Dashboard page
  - Expenses page
  - Accounts page
  - Settings page
  - Client-side routing (Waypoint or manual)

- [ ] **6.2 Dashboard**
  - Current balance display (big number)
  - Free money / daily budget
  - Days remaining in period
  - Quick actions (update balance, start period)

- [ ] **6.3 Expense Management**
  - List expense definitions (planned + estimated)
  - Add/edit expense definition modal
  - Mark expense as paid (for current period)
  - Toggle estimated expense inclusion

- [ ] **6.4 Account Management**
  - List accounts with latest balance
  - Add/edit account
  - Record new balance snapshot
  - Balance history view

- [ ] **6.5 Period Management**
  - Current period info
  - "Start new period" button
  - Period history list

---

## Phase 7: Notifications & Summary
**Goal**: Summary sharing functionality.

- [ ] **7.1 Summary Formatting**
  - Text format for clipboard/messaging
  - Configurable template (optional)

- [ ] **7.2 Copy to Clipboard**
  - Button on dashboard
  - Visual feedback (toast/notification)

- [ ] **7.3 WhatsApp Integration**
  - Research: WhatsApp Business API vs Twilio vs wa.me links
  - Implement chosen approach
  - Recipient configuration in settings

---

## Phase 8: forms4s-laminar Integration
**Goal**: Build Laminar renderer for forms4s, refactor app to use it.

- [ ] **8.1 Laminar Module Setup**
  - `forms4s-laminar` submodule
  - Dependency on forms4s-core

- [ ] **8.2 Form Renderer**
  - FormRenderer trait for Laminar
  - Basic elements: text, number, select, checkbox
  - Bulma styling
  - Validation display

- [ ] **8.3 Table Renderer**
  - TableRenderer trait for Laminar
  - Column rendering
  - Filtering UI
  - Sorting UI
  - Pagination

- [ ] **8.4 Refactor App**
  - Replace manual forms with forms4s
  - Replace manual tables with forms4s datatables
  - Extract reusable patterns

---

## Phase 9: Polish & Extras
**Goal**: Quality of life improvements.

- [ ] **9.1 Exchange Rate API**
  - Integrate external API (exchangerate-api.com or similar)
  - Manual refresh button
  - Display last updated time

- [ ] **9.2 Historical Data**
  - View expense history per definition
  - Average calculations display
  - Import from CSV/JSON (low priority)

- [ ] **9.3 Mobile Optimization**
  - Responsive design review
  - Touch-friendly controls
  - PWA manifest (optional)

- [ ] **9.4 Data Export**
  - Export to CSV
  - Backup/restore functionality

---

## Phase 10: Production Hardening
**Goal**: Ready for daily use.

- [ ] **10.1 Docker & Deployment**
  - Multi-stage Dockerfile
  - fly.io configuration (fly.toml)
  - Environment variable handling
  - SQLite volume persistence

- [ ] **10.2 Error Handling**
  - Graceful error display in UI
  - Retry logic for network errors
  - Offline indicator

- [ ] **10.3 Logging & Monitoring**
  - Structured logging (log4cats)
  - Health checks for fly.io
  - Basic metrics (optional)

- [ ] **10.4 Security Review**
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

