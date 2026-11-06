---
topic: tools-server
title: Tools-server tools, tables and knowledge bases
description: How to create, test, activate, version and attach the tools a bot agent calls, and how to manage the data tables and knowledge corpora behind them.
tools: exotel_tools_server_tenant_create, exotel_tools_server_tool_list, exotel_tools_server_tool_get, exotel_tools_server_tool_create, exotel_tools_server_tool_update, exotel_tools_server_tool_version_list, exotel_tools_server_tool_version_get, exotel_tools_server_tool_version_create, exotel_tools_server_tool_version_test, exotel_tools_server_table_list, exotel_tools_server_table_get, exotel_tools_server_table_create, exotel_tools_server_table_update, exotel_tools_server_table_delete, exotel_tools_server_table_row_list, exotel_tools_server_table_row_get, exotel_tools_server_table_row_create, exotel_tools_server_table_row_update, exotel_tools_server_table_row_delete, exotel_tools_server_corpus_list, exotel_tools_server_corpus_get, exotel_tools_server_corpus_create, exotel_tools_server_corpus_documents_update, exotel_tools_server_corpus_delete, exotel_voicebot_update_config
---

# Tools-server

A bot agent calls tools that live on the tools-server, under a **tenant** whose id is the bot's
account SID (case-sensitive). An agent sees only the tenant's **active** tools.

## Tenant (once per account)

If `exotel_tools_server_tool_create` returns 404 `TS12001` (tenant not found), call
`exotel_tools_server_tenant_create(tenantId=ACCOUNT_SID, name=ACCOUNT_SID)` once, then retry.
If that fails, stop and tell the user; no tool can work until the account is registered.

## Tool types

All use `exotel_tools_server_tool_create(name, type, description, definitionJson)`. Keep
`response.data.id` (TOOL_ID) and `response.data.latest_version_id` (VERSION_ID).

**Names:** snake_case (letters, digits, `_`; no spaces), at most 255 characters. A name can never
be reused (409 `TS10002`), so pick final names first and never give test tools production names.

### openapi: call one HTTP API

- OpenAPI 3.x JSON with `info.title` and exactly one operation. Definition at most 128,000 characters.
- `servers[0].url` is the base URL. Always set it.
- Path, query and JSON-body properties become the tool's arguments.
- The model reads the **operation `description`** (else `summary`), not the tool's `description`,
  unless the agent's `mcp_tools[].description` overrides it. Write it for the model: when to call,
  what it returns.
- Upstream auth: openapi tools support HTTP basic auth only, and these MCP tools do not set it.

```json
{"openapi": "3.0.0", "info": {"title": "order_status", "version": "1"},
 "servers": [{"url": "https://api.example.com"}],
 "paths": {"/orders/status": {"post": {"operationId": "order_status",
  "description": "Get status and delivery date for the order id the caller gives.",
  "requestBody": {"required": true, "content": {"application/json": {"schema": {"type": "object",
    "properties": {"order_id": {"type": "string", "description": "Order id, e.g. A-1001"}},
    "required": ["order_id"]}}}},
  "responses": {"200": {"description": "ok"}}}}}}
```

### python: computation only

`{"body": "FUNCTION_BODY", "parameters": {"NAME": {"type": "number", "description": "..."}}}`.
Types: string, number, integer, boolean, any. The body ends with `return`; `math`, `json`, `re`,
`datetime` are available. No network or file access (use openapi for HTTP); runs stop at about 10 s. Do not write `<` in the code (tag-shaped text is stripped): write `b > a`. An exception
comes back as `{"error": "Tool execution failed: ..."}` while the test still reports success, so
read `test_result`.

```json
{"body": "r = rate / 1200\nf = (1 + r) ** months\nreturn {\"emi\": round(principal * r * f / (f - 1), 2)}",
 "parameters": {"principal": {"type": "number"}, "rate": {"type": "number"}, "months": {"type": "integer"}}}
```

### knowledge_base: search documents

`{"knowledge_corpus_id": "CORPUS_ID"}`. The corpus must be `ready`. The tool takes one argument,
`query`, and returns text chunks with their source.

### tabular_data: look up rows

`{"data_table_id": "TABLE_ID", "operation": "search", "searchable_columns": ["phone"], "default_limit": 5}`.
`operation` must be `search`; at least one searchable column; `default_limit` 1-500 (default 50).
The tool takes one optional argument per searchable column plus `limit`. Matching is exact and
case-sensitive. Result: `{"rows", "total_matches", "returned"}`.

## Lifecycle

1. **Create** → tool is `inactive`, version 1, untested.
2. **Test** → `exotel_tools_server_tool_version_test(toolId, versionId, testInputsJson)`, e.g.
   `{"order_id": "A-1001"}`. An openapi test sends a real request (real side effects). Check `response.data.details.success`, `details.http_status_code`,
   `details.error_message` and `response.data.test_result`. Upstream status 400 or more fails.
   Try a hit, a miss and a bad input.
3. **Activate** → `exotel_tools_server_tool_update(toolId, status="active", currentVersionId=VERSION_ID)`.
   Always pass both. Only a version whose last test passed can be activated (else 400 `TS10003`).
   Allow a few seconds before a call uses it.
4. **Attach** → `exotel_voicebot_update_config` with `data.assistant_config.agents[].mcp_tools`
   entry `{"tool_name": "tool_server.TOOL_NAME", "name": "TOOL_NAME", "description": "..."}`.
   Send every agent (with `id`) and every tool each changed agent keeps (see `objects/assistant`).
   Add an instruction line saying when to call it.
5. **Verify** with a test call that needs the tool before promoting the bot version.

**New version / rollback:** `exotel_tools_server_tool_version_create(toolId, definitionJson, changelog)`,
test the new VERSION_ID, then `exotel_tools_server_tool_update(active, NEW_VERSION_ID)`. To roll back, `exotel_tools_server_tool_update(active,
OLD_VERSION_ID)` on a version that passed its test. Agents need no change: they reference the name.

**Deactivate:** `exotel_tools_server_tool_update(toolId, status="inactive")`, with no usage check: bots that still name
the tool fail at call start, so detach it first. There is no tool delete.

## Data tables

| Item | Rule |
|---|---|
| Create | `exotel_tools_server_table_create(name, description, columnsJson)`, columns `[{"name", "type"}]`; types `string`, `number`, `boolean` |
| Limits | 50 tables per account, 10,000 rows per table |
| Add rows | `exotel_tools_server_table_row_create(tableId, rowsJson)` with a JSON array. Unknown columns are rejected; missing columns are stored as null |
| List rows | `exotel_tools_server_table_row_list(tableId, limit, offset)`, limit 1-500, default 50 |
| Update row | `exotel_tools_server_table_row_update(tableId, rowId, dataJson)` **replaces the whole row**: columns you leave out become null. Read the row first and send every column |
| Update table | `exotel_tools_server_table_update`: `columnsJson` replaces the whole column list; existing rows are not rewritten |
| Delete | `exotel_tools_server_table_delete` deactivates every tool on the table, so bots using them fail at call start. Get the user's explicit yes, naming the bots |

After a table delete: new table, rows, then a **new version of the old tool** with the new
`data_table_id`; test and activate. Agents need no change.

**Missing filter values return other rows.** A null search argument is dropped, so a lookup with
no value returns the first `limit` rows. Always pass an explicit value (`""` matches nothing), preset
keys from values that are always present, keep `default_limit` small, and test the missing-value
case. Store keys such as phone numbers in one format.

## Knowledge corpora

1. **Create:** `exotel_tools_server_corpus_create(name, description, documentsJson, similarityTopK)`.
   `documentsJson` = `[{"filename": "faq.md", "content": "..."}]`, `.txt` or `.md` only, at most 10
   documents and 1 MB in total per call. `similarityTopK` 1-100 (default 10) = chunks per query.
2. **Wait:** processing is asynchronous. Poll `exotel_tools_server_corpus_get(corpusId)` until
   `status` is `ready`. Statuses: `pending`, `indexing`, `ready`, `updating`, `failed`.
3. **Tool:** create a `knowledge_base` tool with that `knowledge_corpus_id`, then test, activate, attach.
4. **Change documents:** `exotel_tools_server_corpus_documents_update(corpusId, addDocumentsJson,
   deleteDocumentIds)`. Document ids come from `exotel_tools_server_corpus_get`. The corpus goes to `updating`, then
   `ready` or `failed`; every bot using it answers from the new content once ready.
5. **Delete:** `exotel_tools_server_corpus_delete` is permanent and does not check whether a bot
   uses the corpus; those knowledge_base tools then return nothing. Confirm with the user first.

Uploads load the tools-server: batch documents and upload outside busy hours.

If calls fail before the greeting after a tools change, a referenced tool is inactive, renamed,
in another account, or its table was deleted.
