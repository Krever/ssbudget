# SSBudget

A personal budget tracker built to answer one question: **"How much can I spend this month?"**

## The Problem

Traditional budget apps obsess over categories and past spending. SSBudget focuses on the future.

SSBudget takes a different approach:
- Let **category budgets** derive what you still expect to pay and to receive this period from real bank transactions
- Give a category a fixed budget for a bill or an income that history cannot predict
- Set how much you plan to save this period
- Get your remaining free cash — your budget until the next paycheck
  
[demo.webm](https://github.com/user-attachments/assets/54a9688f-e4d3-4279-988b-be4c40bc5f5a)

### Additional Features

- **Multi-currency** — PLN, EUR, USD + 30 more with live exchange rates
- **Password + passkey auth** — One owner, no user management; passkeys for daily login
- **Import/Export** — You can take your whole database with you (god bless SQLite!) 
- **Spreadsheet-like UI** — Edit in place, minimal clicks, maximum density
- **Self-hosted** — You can run it yourself anywhere.
- **E2E test coverage** — Everything important is covered with e2e selenium test. 
- **Share the summary** - Copy a short budget summary, or open a WhatsApp share link with it.

## Built With Claude

This project was 100% vibe-coded using [Claude Code](https://www.anthropic.com/claude-code) over 8 sessions (~4 days).
The entire codebase — backend, frontend, database schema, tests, deployment config — was generated through conversation
with Claude Opus/Sonnet.

Check `CLAUDE.md` for the context file that guides development.

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
- sbt 1.12+
- Node.js 20+

### Development

```bash
./dev.sh                  # Vite on https://localhost:3000, backend on :8080, Scala.js watch, Metabase
./dev.sh --no-analytics   # the same without Metabase
```

Open https://localhost:3000. First visit prompts password setup.

### Production Build

```bash
./build.sh              # Builds backend + frontend + Docker image (Metabase bundled)
docker run -p 8080:8080 -v ./data:/data ssbudget
```

The image includes the Metabase that powers the Analytics page, and the container starts and
configures it on its own. Use `./build.sh --no-analytics` for an app-only image (~630MB smaller,
runs in 512MB).

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
- `shared-cpu-2x:2048MB`, since the container runs the app and Metabase side by side

## Project Structure

```
ssbudget/
├── shared/     # Cross-compiled models and API definitions
├── backend/    # http4s server, repositories, auth
├── frontend/   # Laminar SPA
├── e2e/        # Selenium tests
└── docs/       # MCP guide, audit log
```
