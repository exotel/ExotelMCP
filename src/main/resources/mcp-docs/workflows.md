---
topic: workflows
title: Workflows
description: Which tools to call, in what order, and which ids pass between steps, to build, change, test, extend and roll back a voicebot.
tools: exotel_voicebot_bot_create, exotel_voicebot_assistant_create, exotel_voicebot_create, exotel_voicebot_update_config, exotel_voicebot_bot_version_promote, exotel_voicebot_bot_traffic_set
---
Placeholders are UPPER_CASE. Get the user's yes before any call with `confirm=true`, `place_call`, or a delete. Show the preview first.

## a. New bot from scratch
1. **Ids.** `exotel_voicebot_catalog_llm_models` → `id` = LLM_ID. `exotel_voicebot_tts_list_providers` → TTS_PROVIDER_ID; `exotel_voicebot_tts_list_voices(providerId, limit=100)` → VOICE_ID. No list gives the TTS model id: copy `voice_config` from a working bot (`exotel_voicebot_get_details`) or a quick draft (recipe b). Text input: `exotel_voicebot_catalog_asr_models` → ASR ids. `exotel_voicebot_catalog_vad_configs` → `provider_id` + `id`.
2. **Tools first**, if the bot needs any (recipes e–h), so you know their exact names.
3. `exotel_voicebot_assistant_create(assistantJson)` → ASSISTANT_ID:
   `{"name":"Pickup reminder","description":"Outbound reminder","instruction":"You are Asha from City Pharmacy...","llm_config":{"id":"LLM_ID"},"agents":[{"name":"main","description":"Main","instruction":"Confirm a pickup time.","llm_config":{}}]}`
4. `exotel_voicebot_bot_create(configJson)` → BOT_ID:
   `{"name":"Pickup reminder","supported_languages":["en"],"assistant_config":{"id":"ASSISTANT_ID","version":"stable","input":{"modality":"text","mime_type":"text/plain"},"output":{"modality":"text","mime_type":"text/plain"}},"voice_config":{"provider":{"id":"TTS_PROVIDER_ID"},"model":{"id":"TTS_MODEL_ID"},"voice":{"id":"VOICE_ID"}},"asr_config":{"provider":{"id":"ASR_PROVIDER_ID"},"model":{"id":"ASR_MODEL_ID"}},"vad_config":{"provider_id":"VAD_PROVIDER_ID","config_id":"VAD_ID"},"greeting_message":{"text":"Hello {customer_name}.","enabled":true}}`
   v1 is stable at once: no promote.
5. **Read back**: `exotel_voicebot_get_details(BOT_ID)` and `exotel_voicebot_assistant_get_config(ASSISTANT_ID, "v1")`. Compare every field you sent.
6. **Test** (recipe j). For inbound calls the user attaches a flow with the Voicebot applet to a number in the Exotel dashboard.

## b. Quick AI-generated draft
1. `exotel_voicebot_create(textContent)` with a brief under 5,000 characters → keep the generation id.
2. Poll `exotel_voicebot_creation_status(generationId)` every 10–15 s: pending → in_progress → completed or failed. Read `http_code` and the status **inside the body**, not only the tool result.
3. When completed, take `bot_id` (or find the bot with `exotel_voicebot_list_all`).
4. `exotel_voicebot_get_details(BOT_ID)`: default voice and LLM, one agent, no tools.
5. Refine with recipes c and d.

## c. Change a live bot safely
1. `exotel_voicebot_bot_versions_list(BOT_ID)` → `stable_version`, `traffic_allocation`; `exotel_voicebot_get_details(BOT_ID)` → current config.
2. `exotel_voicebot_update_config(BOT_ID, dataJson, sourceVersion="latest", versionDescription)` → new `version` vN at 0% traffic. Read the `warnings`.
3. `exotel_voicebot_get_details(BOT_ID, version="vN")`; diff with the previous version.
4. **Test.** Calls follow `traffic_allocation`, so a 0% version is never reached. Canary: `exotel_voicebot_bot_traffic_set(BOT_ID, {"vOLD":90,"vN":10})` → preview → yes → same call with `confirm=true`. Check rows' `bot_version` in `exotel_voicebot_conversations_list`.
5. **Promote**: `exotel_voicebot_bot_version_promote(BOT_ID, "vN")` → preview → yes → `confirm=true`. vN becomes stable at 100%.

## d. Change the prompt or the LLM
`dataJson` for recipe c: `{"assistant_config":{"instruction":"FULL NEW PROMPT"}}` or `{"assistant_config":{"llm_config":{"id":"LLM_ID"}}}`.
- This forks a new assistant version pinned to this bot version only. On promote it becomes the assistant's stable version, so every other bot on that assistant at `stable` changes too. Ask first.
- To edit an agent, read `exotel_voicebot_assistant_get_config(ASSISTANT_ID, VERSION)` and send the **full** `agents` list with ids; a changed agent keeps only the `mcp_tools` you resend.
- Verify: `get_details(version)` → `assistant_version` → `assistant_get_config`.

## e. HTTP API as a tool
1. `exotel_tools_server_tool_create(name, "openapi", description, definitionJson)`: OpenAPI 3.0, one operation, `servers[0].url`; the operation `description` is what the model reads. Keep `response.data.id` (TOOL_ID) and `response.data.latest_version_id` (VERSION_ID). On 404 `TS12001` run `exotel_tools_server_tenant_create(ACCOUNT_SID, ACCOUNT_SID)` once and retry.
2. `exotel_tools_server_tool_version_test(TOOL_ID, VERSION_ID, testInputsJson)`: a hit, a miss, a bad input (real requests). Read `details.success` and `test_result`.
3. Fix with `tool_version_create` → new VERSION_ID → test again.
4. `exotel_tools_server_tool_update(TOOL_ID, "active", VERSION_ID)`: send both.
5. **Attach** with recipe c: `{"assistant_config":{"agents":[...every agent with id; on the target agent "mcp_tools":[...existing, {"tool_name":"tool_server.order_status","name":"order_status","description":"Call when the caller gives an order id."}]]}}`, and add an instruction line saying when to call it.

## f. Knowledge-base bot
1. `exotel_tools_server_corpus_create(name, description, documentsJson, similarityTopK)`: `[{"filename":"faq.md","content":"..."}]`, at most 10 .txt/.md files and 1 MB per call. One request, off-peak.
2. Poll `exotel_tools_server_corpus_get(CORPUS_ID)` until `status` is `ready` (or `failed`).
3. `tool_create(name, "knowledge_base", description, {"knowledge_corpus_id":"CORPUS_ID"})`.
4. Test with `{"query":"refund window"}` (expect `_kb_chunks`), activate, attach as in e5.

## g. Lookup-table bot
1. `exotel_tools_server_table_create(name, description, columnsJson)`: `[{"name":"phone","type":"string","searchable":true},{"name":"plan","type":"string"}]`.
2. `exotel_tools_server_table_row_create(TABLE_ID, rowsJson)`. Matching is exact and case-sensitive: store numbers in one format.
3. `tool_create(name, "tabular_data", description, {"data_table_id":"TABLE_ID","operation":"search","searchable_columns":["phone"],"default_limit":5})`.
4. Test with a value **and without one** (no filter returns unfiltered rows), activate, attach as in e5.

## h. Tools Extension route as a tool
1. `exotel_voicebot_te_route_create(name, "POST", "orders/status", yaml, changeLog)` → ROUTE_ID. Version 1 is live at once. The body arrives as a string: start with `- unmarshal: {json: {library: Jackson}}` before `${body[order_id]}`; return compact JSON with `Content-Type: application/json`.
2. `exotel_voicebot_te_route_test(ROUTE_ID, bodyJson={"order_id":"A-1001"})`. Outbound calls really happen. On failure `exotel_voicebot_te_runs_get(routeId=ROUTE_ID, includeSteps=true)`.
3. `exotel_voicebot_te_route_attach_as_tool(ROUTE_ID, toolName, description, inputSchemaJson)` → TOOL_ID and version id in `tools_server_result`.
4. Test, activate, attach as in e2–e5. After a call, `te_runs_get(sessionId=SESSION_ID)` lists its runs.

## i. Roll back
- **Bot version**: `bot_versions_list` → `bot_version_promote(BOT_ID, "vPREV", confirm=true)` after a yes, or `bot_traffic_set` with `{"vPREV":100}`.
- **Tool version**: `tool_version_list` → `tool_update(TOOL_ID, "active", OLD_VERSION_ID)`. Test it first if `tested` is false.
- **Route**: `te_route_get(ROUTE_ID, includeVersions=true)` → `te_route_get(ROUTE_ID, version=N)` → `te_route_update(ROUTE_ID, thatYaml, "Roll back to N")` → preview → yes → `confirm=true`.

## j. Test call
1. Get the number to call and the user's yes. `exotel_voicebot_list_phone_numbers` → an ExoPhone for `callerId`.
2. `exotel_voicebot_place_call(toNumber="+91XXXXXXXXXX", voiceBotId=BOT_ID, callerId=EXOPHONE)` → Call Sid. Use `+` and the country code; a bare 10-digit number is treated as Indian.
3. `exotel_voicebot_call_status(CALL_SID)` → status, duration.
4. `exotel_voicebot_conversations_list(botId=BOT_ID, startDate=TODAY_UTC, fields="transcript,insights", limit=5)` → match `call_sid`. The call runs whichever version traffic selects.
