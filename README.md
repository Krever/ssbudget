# SSBudget

A personal budget tracker built to answer one question: **"How much can I spend this month?"**

## The Problem

Traditional budget apps obsess over categories and past spending. SSBudget focuses on the future.

SSBudget takes a different approach:
- Define your **fixed expenses** (rent, bills), **savings goals**, and **estimated variable costs** (groceries, fuel)
- Update account balances and mark previously planned payments throughout the period
- Get your remaining free cash — your budget until the next paycheck
  
[demo.webm](https://github.com/user-attachments/assets/54a9688f-e4d3-4279-988b-be4c40bc5f5a)

### Additional Features

- **Multi-currency** — PLN, EUR, USD + 30 more with live exchange rates
- **Passkey auth** — Passwordless login, no user management needed
- **Spreadsheet-like UI** — Edit in place, minimal clicks, maximum density
- **Self-hosted** — Your data stays on your SQLite file

## Built With Claude

This project was 100% vibe-coded using [Claude Code](https://www.anthropic.com/claude-code) over 8 sessions (~4 days).
The entire codebase — backend, frontend, database schema, tests, deployment config — was generated through conversation
with Claude Opus/Sonnet.

Check `docs/sessions/` for the session logs and `CLAUDE.md` for the context file that guided development.

## Tech Stack

| Layer    | Stack                               |
|----------|-------------------------------------|
| Language | Scala 3 (JVM + Scala.js)            |
| Backend  | cats-effect, http4s, tapir, doobie  |
| Frontend | Laminar (Scala.js SPA), Bootstrap 5 |
| Database | SQLite + Flyway migrations          |
| Auth     | WebAuthn passkeys + Argon2 password |
| Build    | sbt, Vite                           |

## Quick Start

### Prerequisites

- JDK 21+
- sbt 1.9+
- Node.js 18+

### Development (3 terminals)

```bash
# Terminal 1: Scala.js watch
sbt '~frontend/fastLinkJS'

# Terminal 2: Vite dev server
cd frontend && npm install && npm run dev

# Terminal 3: Backend
sbt backend/run
```

Open http://localhost:3000. First visit prompts password setup.

### Production Build

```bash
./build.sh              # Builds backend + frontend + Docker image
docker run -p 8080:8080 -v ./data:/data ssbudget
```

### Environment Variables

| Variable              | Default                                       | Description               |
|-----------------------|-----------------------------------------------|---------------------------|
| `SSBUDGET_DB_PATH`    | `data/ssbudget.db`                            | SQLite database path      |
| `SSBUDGET_PORT`       | `8080`                                        | Server port               |
| `SSBUDGET_RP_ID`      | `localhost`                                   | WebAuthn relying party ID |
| `SSBUDGET_RP_ORIGINS` | `http://localhost:3000,http://localhost:8080` | Allowed origins           |

## Deployment

See `fly.toml` for a fly.io deployment example. Key points:

- Persistent volume for SQLite
- Secrets for `SSBUDGET_RP_ID` and `SSBUDGET_RP_ORIGINS`

## Project Structure

```
ssbudget/
├── shared/     # Cross-compiled models and API definitions
├── backend/    # http4s server, repositories, auth
├── frontend/   # Laminar SPA
├── e2e/        # Selenium tests
└── docs/       # Session logs
```
