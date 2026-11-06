---
topic: playbooks/status-lookup
title: Order or booking status lookup
description: Use when callers ask for the status of an order, booking, ticket or application by its id, and the bot must capture the id, confirm it and read back the status from a table or your API.
tools: exotel_tools_server_table_create, exotel_tools_server_table_row_create, exotel_tools_server_tool_create, exotel_voicebot_te_route_create, exotel_voicebot_te_route_test, exotel_voicebot_te_route_attach_as_tool, exotel_tools_server_tool_version_test, exotel_tools_server_tool_update, exotel_voicebot_update_config
---
> Draft: recommended settings pending review by the voicebot team.

## When to use

"Where is my order", "is my booking confirmed", "what is my ticket status". The bot captures one id, confirms it,
calls one tool and speaks the result.

## Recommended configuration

| Area | Setting | Why |
|---|---|---|
| Data source | `tabular_data` tool for a list you can upload; `openapi` tool for your live API; a Tools Extension route when the lookup needs several steps | One tool, one call per lookup |
| Tool speed | Backend answers in well under 10 s | 10 s is the tool deadline; a slow tool holds up the turns after it |
| Id capture | Settings from playbooks/number-id-capture | Ids are spoken in pieces |
| Fillers | Static, `trigger_delay_ms` 600, "Let me check that." | Covers the lookup |
| Interruption | `enable_interruption: true` | Caller can correct the id during read-back |
| Post-call | Intents such as `status_given`, `id_not_found`, `needs_human` | Measures the lookup success rate |

## Starter template

Table route: `exotel_tools_server_table_create` with columns
`[{"name": "order_id", "type": "string", "searchable": true}, {"name": "status", "type": "string"},
{"name": "eta", "type": "string"}]`, rows via `exotel_tools_server_table_row_create` (store ids in one format,
for example upper case with no spaces). Then `exotel_tools_server_tool_create`, type `tabular_data`:

```json
{"data_table_id": "TABLE_ID", "operation": "search", "searchable_columns": ["order_id"], "default_limit": 1}
```

API route: type `openapi` with one operation, `servers[0].url` set to your API, `order_id` as a required string.
Multi-step route: `exotel_voicebot_te_route_create`, `exotel_voicebot_te_route_test`, then
`exotel_voicebot_te_route_attach_as_tool`.

Test with `exotel_tools_server_tool_version_test` (`{"order_id": "FC1001"}`), activate with
`exotel_tools_server_tool_update`, then `exotel_voicebot_update_config`:

```json
{"assistant_config": {"agents": [{"id": "AGENT_ID", "name": "main", "description": "Status agent",
  "instruction": "INSTRUCTION", "llm_config": {},
  "mcp_tools": [{"tool_name": "tool_server.order_status", "name": "order_status",
    "description": "Look up status and ETA for a confirmed order id. Do NOT call without a confirmed id."}]}]}}
```

Instruction outline: ask for the order id; normalise it (letters upper case, spoken words to digits, no spaces);
read it back in small groups and wait for yes; only then call `order_status`; speak status and ETA in one or two
sentences; if not found, ask once more for the id, then offer an agent; never read out another customer's data.

## Known problems

| Symptom | Fix |
|---|---|
| Lookup returns some unrelated row | A table search with no id returns the first rows of the table. Keep `default_limit` 1 and never call the tool before the id is confirmed |
| "Not found" for an id that exists | Table matching is exact: store and send ids in the same format |
| Long silence after the lookup | Speed up the backend; the next turn waits for the tool to finish |
| Tool never runs | It must be tested and active, referenced as `tool_server.NAME` with `name` set |
| Bot reads a JSON blob aloud | Tell it to speak only status and ETA in plain words |

## Test checklist

- A valid id, spoken naturally with pauses: captured, confirmed, correct status.
- A wrong id, then a correction during read-back.
- An id that does not exist: one retry, then the agent offer.
- Ask for status before giving any id: the bot must not call the tool.
- Read the tool call and result in the test transcript.
