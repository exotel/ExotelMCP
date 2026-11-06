---
topic: errors
title: Errors and fixes
description: How each tool family reports failure, what every error_code means and how to fix it, and a checklist for 401s.
tools:
---
Any failed result is flagged `isError: true` in the MCP response. Read the text as well: it says what to fix.

## Result shapes

| Tool family | Success | Failure |
|---|---|---|
| Older voicebot tools (`list_all`, `delete`, `create`, `creation_status`, `assistant_get_config`, `persona_*`, `tts_*`, calls tools) | The API's JSON | `Error {status}: {tool} request failed` (4xx, no body), `Error {status}: {tool} - upstream service unavailable` (5xx), `Error: {message}` (input check), `Missing VoiceBot credentials...` or `Missing Calls API credentials...` |
| Builder and route tools (all other `exotel_voicebot_*`) | `{"ok": true, "http_status", ..., "warnings"?, "data"}` | `{"ok": false, "error_code", "human_readable_reason", "suggested_action", "http_status", "body", "upstream_error_code"?}` plus helper fields such as `unknown_keys`, `allowed_keys`, `missing_fields`, `problems`, `preview` |
| Tools Server tools (`exotel_tools_server_*`) | The Tools Server JSON | `Error {status}: {tool} failed - {response body}` (4xx, body included), `Error {status}: {tool} - upstream service unavailable` (5xx), `Error: {message}`, `Missing Tools Server credentials...` |
| Any tool | | `Tool execution failed: {message}`: the server could not run the tool, usually a wrong parameter type. Retry with the documented types. |

Older tools drop the error body; repeat the read with a builder tool such as `exotel_voicebot_get_details` to see the upstream message. Act on `suggested_action`. Never retry a write blindly: read state first, because some failed creates leave an object behind.

## Builder tool error codes: input checks (nothing was sent)

| error_code | Meaning | Fix |
|---|---|---|
| `MISSING_CREDENTIALS`, `INVALID_ACCOUNT_ID`, `INVALID_BASE_URL`, `TE_NOT_CONFIGURED` | Envelope field missing or malformed | See `credentials`. |
| `MISSING_PARAMETER` | A required id, version or JSON is absent or empty | Pass it. |
| `INVALID_PARAMETER` | Bad id, version not `vN`, bad limit, date, method or path | Fix the named parameter. |
| `INVALID_JSON`, `INPUT_TOO_LARGE` | JSON does not parse, or is over 1,000,000 characters | Send one valid JSON object. |
| `INVALID_CONFIG`, `INVALID_DATA`, `INVALID_ASSISTANT` | `configJson`, `dataJson` or `assistantJson` is not an object | Send an object. |
| `UNKNOWN_KEYS` | Keys the create would drop silently, or server-managed keys (`id`, `status`...) | Remove them; see `allowed_keys`. |
| `MISSING_REQUIRED_FIELDS` | Bot: `name`, `supported_languages`, `assistant_config.id`, `assistant_config.input.modality`. Assistant: `name`, `description`, `instruction` | Add the fields in `missing_fields`. |
| `V1_SHAPE_REQUIRED` | `voice_config` or `asr_config` uses `provider_id`/`model_id`/`voice_id` | Use `{"provider":{"id"},"model":{"id"},"voice":{"id"}}` (ASR without `voice`). |
| `ASR_CONFIG_REQUIRED` | Text input without `asr_config` | Pick one with `exotel_voicebot_catalog_asr_models`, or use audio input. |
| `UNKNOWN_DATA_KEYS` | Keys that are not patchable bot fields; often the whole request body was sent | Send only the inner data object; `sourceVersion` and `versionDescription` are separate parameters. |
| `MARK_AS_STABLE_REFUSED` | `markAsStable`, `mark_as_stable` or `is_stable` set to true | Create the draft, check it, then promote. |
| `INVALID_ALLOCATION` | Allocation keys not `vN`, or values not whole numbers 0-100 | Fix per `problems`. |
| `TRAFFIC_SUM_NOT_100` | Percentages do not add to 100 | Adjust; see `sum`. |
| `CONFIRM_REQUIRED` | Gated tool called without `confirm=true`. Nothing changed | Show `preview` to the user; after a yes, call again with `confirm=true`. |
| `ASSISTANT_LEVEL_HOOKS` | `pre_agent_hooks` at assistant level | Move hooks to `agents[].pre_agent_hooks`. |
| `INVALID_LLM_CONFIG` | `llm_config` is not an object | `{"id": "MODEL_ID"}` from `exotel_voicebot_catalog_llm_models`, or omit it. |
| `INVALID_AGENTS` | `agents` missing, empty or over 10 | Send 1-10 agents. |
| `AGENT_NAME_MISSING`, `DUPLICATE_AGENT_NAMES` | Agent without a name, or two with one name | Give each agent a unique name. |
| `AGENT_LLM_CONFIG_MISSING` | An agent without `llm_config` | Add `"llm_config": {}` to inherit. |
| `INVALID_MCP_SERVERS`, `DUPLICATE_MCP_SERVER_NAMES` | `mcp_servers` not an array, or names repeat | Usually leave it out; tools come from `mcp_tools`. |

## Builder tool error codes: from the API or the network

| error_code | HTTP | Meaning | Fix |
|---|---|---|---|
| `UPSTREAM_VALIDATION_ERROR` | 400 | A field was rejected; the message names it. An unknown account SID also shows here | Fix that field. For an account error check the SID's case. |
| `UNAUTHORIZED` | 401 | Credentials not accepted | 401 checklist below. |
| `FORBIDDEN` | 403 | Valid credentials, not allowed on this endpoint | Ask Exotel to enable voicebot API access for the key, or use credentials that have it. |
| `NOT_FOUND` | 404 | Id does not exist in this account | Check the id and that it belongs to `voicebot_account_id`. |
| `CONFLICT` | 409 | Name or version clash | Read current state; use another name or version. |
| `UNPROCESSABLE` | 422 | Operation refused, often an inactive or archived bot or version | Use an active bot or version. |
| `BOT_FROZEN` | 423 | Bot is frozen | The user unfreezes it in the console. |
| `RATE_LIMITED_OR_CAP_REACHED` | 429 | Too many requests, or the account's bot or assistant cap is full | Cap named: remove unused bots (user's yes) or ask Exotel for a higher cap. Otherwise wait 60 s and slow down. |
| `UPSTREAM_ERROR` | 5xx | API failure, or a malformed payload | Retry once; after a failed assistant create, look for a half-built one first. |
| `UPSTREAM_HTTP_{n}` | other | Unmapped status | Read `body`. |
| `UPSTREAM_UNREACHABLE` | | Could not connect | Check the base URL; retry. |
| `RESPONSE_TOO_LARGE` | | Answer over 5 MB | Smaller `limit` or fewer `fields`. |
| `UNEXPECTED_RESPONSE`, `INTERNAL_ERROR`, `SERIALIZATION_ERROR` | | Fault reading the answer or inside the tool | Retry once, then report tool name and time. |

`exotel_voicebot_te_route_test` returns the route's own answer in `route_output` with its `route_status`; on failure read `exotel_voicebot_te_runs_get(routeId, includeSteps=true)`. `exotel_voicebot_te_route_attach_as_tool` returns `ok: false` with the Tools Server text in `tools_server_result` when the tool create fails.

## Common Tools Server codes (in the error body)

| Code | Meaning | Fix |
|---|---|---|
| `TS12001` | Tenant not found | `exotel_tools_server_tenant_create(tenantId=ACCOUNT_SID, name)`. |
| `TS12002` | Tenant already exists | Fine; continue. |
| `TS10001` / `TS11001` | Tool / version not found | Check ids with `tool_list` / `tool_version_list`. |
| `TS10002` | Tool name taken (names are never reusable) | Pick a new name, or add a version to the existing tool. |
| `TS10003` | Tool validation failed, including "must be tested before activation" | Fix the definition, or run `exotel_tools_server_tool_version_test` before activating. |
| `TS14001` / `TS14004` / `TS14009` | Corpus not found / not in a usable state / name taken | Check id; wait for `ready`; new name. |
| `TS15001` / `TS15002` | Table / row not found | Check ids. |
| `TS15003`-`TS15006` | Table or row limit / validation failed / name taken | Remove unused items; fix columns or rows; new name. |
| `TS17001` / `TS17002` | Rate limited / resource cap reached | Wait and slow down / remove unused items or ask Exotel. |

## 401 checklist

1. All three fields are present and non-blank: `voicebot_account_id`, `voicebot_api_key`, `voicebot_api_token`.
2. The account SID matches the dashboard exactly, including upper and lower case.
3. The key and token are a pair, and belong to that account.
4. The key is still active; if it was regenerated, the user updates the envelope.
5. `voicebot_base_url` is the environment the key was issued for (default `https://voicebot.in.exotel.com/voicebot/api/v2`).
6. No stray spaces, quotes or apostrophes in values.
7. For `place_call`, `call_status`, `list_phone_numbers`: the `calls_*` fields, or their voicebot fallbacks, are valid for `calls_base_url`.
8. Tools Server tools: remove any wrong `tools_server_*` override so the voicebot values are used.
9. Still 401 with all of the above: the account may not be enabled for API-key access to voicebot. Stop and ask the user to contact Exotel support. Do not try other credentials.
