# MCP server: transaction categorization

The backend has an MCP endpoint at `/mcp`. An assistant (Claude Code, for example) uses it to categorize bank transactions with you:
it reads a batch of uncategorized transactions, proposes rules and manual categories, waits for your confirmation, applies them, and
repeats.

The endpoint refuses all requests until you make an access token.

## Set-up

1. Open **Settings → MCP access** in the app. Give the token a name (for example the computer), confirm with your password or passkey,
   and select **Create token**.
2. The page shows a command of this form, with the real address and token. Copy it and run it in a terminal:

   ```bash
   claude mcp add --transport http --scope user ssbudget https://ssbudget.fly.dev/mcp \
     --header "Authorization: Bearer ssb_mcp_..."
   ```

   The token is shown this one time. If you lose it, revoke it and make a new one.
3. In Claude Code, start the procedure with the prompt `/mcp__ssbudget__categorize_transactions`, or ask "categorize my ssbudget
   transactions".

The table in **MCP access** lists each token with its name, the date it was made and when it was last used. **Revoke** removes it; the
client that uses it loses access with its next request. Make one token for each client, so that you can revoke one and keep the others.

## What the server gives

One prompt, `categorize_transactions`: the procedure (`CategorizationGuide`).

| Tool                 | Writes | Function                                                                                   |
|----------------------|--------|--------------------------------------------------------------------------------------------|
| `list_uncategorized` | no     | One batch, the total that remain, and counts by counterparty for all that remain            |
| `list_categories`    | no     | Categories with ids and transaction counts                                                  |
| `list_rules`         | no     | Rules in evaluation order; `query` / `categoryId` select the related ones                   |
| `preview_rule`       | no     | The effect of a new, changed or deleted rule on ALL transactions, before it is applied      |
| `create_rule`        | yes    | New rule, optionally at a `position`; all rules are applied again                           |
| `update_rule`        | yes    | Change a rule; fields that are not given keep their value                                   |
| `delete_rule`        | yes    | Remove a rule                                                                               |
| `set_categories`     | yes    | Manual category for up to 100 transactions; all or none                                     |
| `create_category`    | yes    | New category (name only)                                                                    |

All writes go through `CategorizationService`, the same code as the web UI.

## Security model

The endpoint is a second door into an internet-facing app, so it is built to be small and separate.

- **Its own credentials.** Access tokens that the server makes. A session cookie does not open `/mcp`, and a token does not open
  `/api`. `SSBUDGET_TEST_MODE` does not open it either. There is no token in the environment or in the configuration.
- **Closed by default.** With no token in the table, no request passes.
- **Token handling.** A token is 256 random bits with the prefix `ssb_mcp_`. It leaves the server one time, in the answer to the
  request that makes it. The database holds only its SHA-256 digest, so a backup or the database export does not give access. The
  token is read only from the `Authorization` header (never a cookie or the URL) and is never logged. Two `Authorization` headers
  are refused.
- **Making a token needs a proof of identity.** The session alone is not sufficient: the request must hold the current password or a
  fresh passkey assertion, as for a password change. A stolen session cookie thus cannot be turned into a token that does not
  expire. Listing and revoking need only the session. At most 10 tokens exist at a time.
- **Revocation.** Immediate, per token, in Settings. `last used` shows if a token is in use.
- **Nothing before authentication.** A request without the token gets 401 for each method and each body. Refused requests are logged,
  one line a minute at most, with the client address.
- **No browser access.** A request with an `Origin` header is refused (the DNS-rebinding guard of the MCP specification), and there are
  no CORS headers. Because cookies are not read, a web page cannot use your login against this endpoint.
- **Least privilege.** The token gives the nine tools above and nothing else: no balances, no periods, no database export or import, no
  reset, no category delete. The raw bank payload of a transaction (`rawJson`) is not returned.
- **Database import.** An imported database brings its own `mcp_tokens` table: tokens of the imported file work, and the tokens
  that existed before are gone.
- **Limits.** POST only, one JSON-RPC message per request, body at most 256 kB, at most 100 assignments per call, rule criteria are
  validated (a blank text criterion, which matches everything, is refused).
- **Audit.** Each write tool call is logged with the name of the token and the arguments (`[mcp] token 'laptop': create_rule {...}`).
- **Transport.** `force_https = true` in `fly.toml`; never use the token on a plain `http://` URL other than localhost.

What the server cannot do for you:

- **Confirmation is done by the client.** The procedure tells the assistant to ask before each write, and the write tools are marked
  as such so that the client asks for permission. The server cannot know that a person agreed. Do not put the write tools of this
  server on an always-allow list.
- **Prompt injection.** A counterparty name or a transfer title is text from other people. The procedure and the server instructions
  tell the assistant to treat it as data, and the tools limit the damage to wrong categories, which you can correct. They cannot move
  money or read other data.
- **The token on your machine** is as safe as the place where you keep it. `claude mcp add` writes it to the Claude Code
  configuration file in your home directory. Tokens do not expire: revoke the ones you do not use.

Static bearer tokens work with Claude Code and with other clients that can send a header. Custom connectors in the claude.ai web app
require OAuth, which this server does not have.

## Protocol notes

Streamable HTTP, stateless: `POST /mcp` with one JSON-RPC message, `application/json` answer, 202 for notifications. There is no event
stream (`GET` is 405) and no session id. Revisions `2025-06-18` and `2025-03-26`. The protocol is written by hand in `McpServer` (about
100 lines) so that the endpoint has no new dependency.
