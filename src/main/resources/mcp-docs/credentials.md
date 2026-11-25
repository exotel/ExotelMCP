---
topic: credentials
title: Credentials envelope
description: Which fields go in the Authorization header, what each one defaults to, and how to write it in an MCP client config.
tools:
---
ExotelMCP has no login of its own. Every MCP request carries one `Authorization` header that holds a JSON credential envelope. Each tool reads the fields it needs and calls Exotel with them as HTTP Basic auth. The agent never needs to see the values.

## Format

```
Authorization: Bearer {"voicebot_account_id":"ACCOUNT_SID","voicebot_api_key":"API_KEY","voicebot_api_token":"API_TOKEN"}
```

- `Bearer ` followed by compact JSON. Raw JSON and `Basic ` followed by JSON are also read.
- Single quotes are turned into double quotes before parsing, so a value must not contain an apostrophe.
- Blank values count as missing.

## Fields

### Voicebot (required for every `exotel_voicebot_*` tool)

| Field | Required | Meaning |
|---|---|---|
| `voicebot_account_id` | yes | The account SID. **Case-sensitive**: copy it exactly as the Exotel dashboard shows it. Letters, digits, `_` and `-`, max 64. |
| `voicebot_api_key` | yes | The account's Exotel API key. |
| `voicebot_api_token` | yes | The API token for that key. |
| `voicebot_base_url` | no | Leave it out: the server uses the base its operator configured. Send it only if it **equals** a base the operator configured (`https`, ending in `/api/v2`); any other value is refused with HTTP 400 at login, and that refuses every tool in the session, not just the voicebot ones. The v1 base is derived by swapping `/api/v2` for `/api/v1`. |

### Tools Extension (needed by `exotel_voicebot_te_*`)

The server operator turns these tools on by setting `TE_BASE_URL` on the server. Without it the route tools return `TE_NOT_CONFIGURED` and the user asks the operator; adding a field to the envelope does not help.

| Field | Required | Meaning |
|---|---|---|
| `te_base_url` | no | Leave it out: the server uses its own Tools Extension base. Send it only if it **equals** a base the operator configured (`TE_BASE_URL` or `VERIFY_TE_API_BASES`); any other value, including any value when the server sets none, is refused with HTTP 400 at login and refuses every tool in the session. |

Route tools also use the voicebot key, token and account SID.

### Tools Server (optional; used by `exotel_tools_server_*`)

| Field | Default | Meaning |
|---|---|---|
| `tools_server_api_key` | `voicebot_api_key` | Key for the Tools Server. |
| `tools_server_api_token` | `voicebot_api_token` | Token for that key. |
| `tools_server_tenant_id` | `voicebot_account_id` | Tenant id. It must be the account SID, because live calls look tools up by the account SID. |
| `tools_server_base_url` | set by the server operator | Your Tools Server base URL. Set it only when Exotel gives you one, and keep it in the same environment as `voicebot_base_url`, or tools are created where the bot cannot see them. |

Leave all four out in the normal case.

### Calls API (used by `exotel_voicebot_place_call`, `exotel_voicebot_call_status`, `exotel_voicebot_list_phone_numbers`)

| Field | Default | Meaning |
|---|---|---|
| `calls_api_key` | `voicebot_api_key` | Exotel API key for calls. |
| `calls_api_token` | `voicebot_api_token` | Token for that key. |
| `calls_account_id` | `voicebot_account_id` | Account SID that owns the ExoPhones. |
| `calls_base_url` | `https://api.in.exotel.com` | Exotel Calls API base. |

`exotel_voicebot_place_call` uses both sets: the voicebot fields to look up the bot's stream, then the calls fields to dial. The default `calls_base_url` places real calls on the production network even when `voicebot_base_url` points at another environment. Set it deliberately.

Other fields in the envelope belong to other Exotel products and are ignored by these tools.

## Where the values come from

The account SID, API key and API token are in the Exotel dashboard under the account's API settings. The user puts them into their MCP client configuration. You never collect them.

## Example envelope (placeholders)

```json
{
  "voicebot_account_id": "ACCOUNT_SID",
  "voicebot_api_key": "API_KEY",
  "voicebot_api_token": "API_TOKEN",
  "calls_base_url": "https://api.in.exotel.com"
}
```

## In an MCP client config (`mcp.json` style)

The header value is a string, so escape the inner quotes:

```json
{
  "mcpServers": {
    "exotel": {
      "type": "http",
      "url": "EXOTELMCP_URL",
      "headers": {
        "Authorization": "Bearer {\"voicebot_account_id\":\"ACCOUNT_SID\",\"voicebot_api_key\":\"API_KEY\",\"voicebot_api_token\":\"API_TOKEN\"}"
      }
    }
  }
}
```

If the client can expand environment variables in headers, keep the envelope in a variable and reference it there, so the file holds no secret and can be shared or committed.

## Rules

1. Credentials come only from the Authorization envelope. Never ask the user to paste a key into chat.
2. Never put a key, token or password into a prompt, greeting, agent instruction, tool definition, Tools Extension YAML, webhook URL, table row or corpus document. Those are stored as plain configuration and are visible to anyone who can read the bot.
3. Never print, log or repeat credential values. Voicebot tool results already show secret fields as redacted; do not try to recover them.
4. The builder tools (`exotel_voicebot_bot_create`, `get_details`, `update_config`, versions, promote, traffic, `assistant_create`, catalogues, conversations and the route tools) use only the envelope and never a server default. A missing field gives `MISSING_CREDENTIALS`.
5. One envelope is one account. To work on another account, the user changes the connection; do not mix accounts in one task.

## Checking the envelope

| Result | Meaning | Fix |
|---|---|---|
| `MISSING_CREDENTIALS`, or text starting `Missing VoiceBot credentials` | Key, token or SID absent or blank | The user adds all three fields. |
| `INVALID_ACCOUNT_ID` | SID has characters outside letters, digits, `_`, `-` | Copy the SID exactly. |
| `INVALID_BASE_URL` | The server's configured base is not https, not an Exotel host, or has the wrong suffix | The server operator fixes `VOICEBOT_BASE_URL` (ending `/api/v2`) or `TE_BASE_URL` (ending `/voicebot`). |
| HTTP 400 `invalid_credentials` naming a `*_base_url` field | The envelope sends a base URL the operator did not configure | The user removes that field from the envelope. |
| `TE_NOT_CONFIGURED` | The server sets no `TE_BASE_URL` | The user asks the server operator to set it. Do not add `te_base_url`. |
| `UNAUTHORIZED` / `Error 401` | Values present but not accepted | See the 401 checklist in `errors`. |

A quick end-to-end check is `exotel_voicebot_list_all` (voicebot fields) and `exotel_tools_server_tool_list` (Tools Server fields).
