# SSBudget

Personal budget tracker for tracking monthly expenses, bank balances, and calculating available spending money.

## Development Setup

### Prerequisites

- JDK 21+
- sbt 1.12+
- Node.js 18+

### Running (Development)

Three terminals are needed:

**Terminal 1 - Scala.js compilation (watch mode):**
```bash
sbt '~frontend/fastLinkJS'
```

**Terminal 2 - Vite dev server:**
```bash
cd frontend
npm install
npm run dev
```

**Terminal 3 - Backend server:**
```bash
sbt backend/run
```

Open http://localhost:3000 in your browser.

- Vite serves the frontend on port 3000
- Backend runs on port 8080
- Vite proxies `/api/*` requests to the backend

### Useful Commands

```bash
sbt compile              # Compile all modules
sbt scalafmtAll          # Format all Scala code
sbt frontend/fastLinkJS  # Build frontend JS (development)
sbt backend/run          # Run backend server
```
