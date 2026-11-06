---
topic: tools
title: Tool index
description: Every voicebot and tools-server tool, what it does, its effect class, its key parameters and its limits.
tools: exotel_voicebot_list_all, exotel_voicebot_get_details, exotel_voicebot_create, exotel_voicebot_creation_status, exotel_voicebot_bot_create, exotel_voicebot_delete, exotel_voicebot_update_config, exotel_voicebot_bot_versions_list, exotel_voicebot_bot_version_promote, exotel_voicebot_bot_traffic_set, exotel_voicebot_assistant_get_config, exotel_voicebot_assistant_create, exotel_voicebot_catalog_llm_models, exotel_voicebot_catalog_asr_models, exotel_voicebot_catalog_vad_configs, exotel_voicebot_tts_list_providers, exotel_voicebot_tts_list_voices, exotel_voicebot_persona_list, exotel_voicebot_persona_create, exotel_voicebot_persona_update, exotel_voicebot_place_call, exotel_voicebot_call_status, exotel_voicebot_list_phone_numbers, exotel_voicebot_conversations_list, exotel_voicebot_te_route_list, exotel_voicebot_te_route_get, exotel_voicebot_te_route_create, exotel_voicebot_te_route_update, exotel_voicebot_te_route_delete, exotel_voicebot_te_route_test, exotel_voicebot_te_runs_get, exotel_voicebot_te_route_attach_as_tool, exotel_voicebot_guide, exotel_tools_server_tenant_create, exotel_tools_server_tool_list, exotel_tools_server_tool_get, exotel_tools_server_tool_create, exotel_tools_server_tool_update, exotel_tools_server_tool_version_list, exotel_tools_server_tool_version_get, exotel_tools_server_tool_version_create, exotel_tools_server_tool_version_test, exotel_tools_server_table_list, exotel_tools_server_table_get, exotel_tools_server_table_create, exotel_tools_server_table_update, exotel_tools_server_table_delete, exotel_tools_server_table_row_list, exotel_tools_server_table_row_get, exotel_tools_server_table_row_create, exotel_tools_server_table_row_update, exotel_tools_server_table_row_delete, exotel_tools_server_corpus_list, exotel_tools_server_corpus_get, exotel_tools_server_corpus_create, exotel_tools_server_corpus_documents_update, exotel_tools_server_corpus_delete
---
Classes: R = READ, D = DRAFT, L = LIVE, X = DESTRUCTIVE, C = CALL, E = EXECUTES (see `start`). Optional string parameters may be sent empty. JSON parameters accept an object or a JSON string.

## Bots and versions

| Tool | Does | Class | Key params | Notes |
|---|---|---|---|---|
| `exotel_voicebot_list_all` | List bots | R | `status` (active/inactive), `limit` (20), `offset` | Page until you have `metadata.total`. |
| `exotel_voicebot_get_details` | Every field of a bot | R | `botId`, `version` (vN) | No version = stable with every field. Secrets read back redacted. |
| `exotel_voicebot_bot_create` | Create a bot from a full v1 config | D | `configJson` | Preferred create. v1 is stable at once. Counts against the bot cap. |
| `exotel_voicebot_create` | AI-generated bot from a text brief | D | `textContent` | Async, platform defaults, one agent. Under 5,000 chars. |
| `exotel_voicebot_creation_status` | Status of an AI generation | R | `generationId` | pending, in_progress, completed (or failed). |
| `exotel_voicebot_update_config` | Draft version = source + deep-merged changes | D | `botId`, `dataJson`, `sourceVersion` (latest), `versionDescription` | 0% traffic. Assistant fields fork a new assistant version. `agents` = full list. |
| `exotel_voicebot_bot_versions_list` | Versions, stable, split | R | `botId`, `limit` (50, max 100), `offset` | |
| `exotel_voicebot_bot_version_promote` | Make vN stable at 100% | L | `botId`, `version`, `confirm` | Preview unless `confirm=true`. Also moves the shared assistant's stable. |
| `exotel_voicebot_bot_traffic_set` | Split traffic | L | `botId`, `allocationJson` `{"v2":90,"v3":10}`, `confirm` | Whole numbers summing to 100. Preview unless `confirm=true`. |
| `exotel_voicebot_delete` | Soft-delete a bot | X | `voiceBotId` | Bot stops taking calls. No restore tool. |

## Assistants, models, voices, personas

| Tool | Does | Class | Key params | Notes |
|---|---|---|---|---|
| `exotel_voicebot_assistant_get_config` | Read an assistant | R | `assistantId`, `version` (stable or vN) | |
| `exotel_voicebot_assistant_create` | Create an assistant | D | `assistantJson` | 1-10 agents, each with `name` and `llm_config` (`{}` inherits). Cannot be deleted. |
| `exotel_voicebot_catalog_llm_models` | LLM models | R | `providerId`, `includeDefault` | Row `id` goes in `llm_config.id`. |
| `exotel_voicebot_catalog_asr_models` | ASR models | R | `providerId`, `includeDefault` | For text-input bots only. |
| `exotel_voicebot_catalog_vad_configs` | VAD presets | R | `providerId`, `type` | Send the preset's own `provider_id`. |
| `exotel_voicebot_tts_list_providers` | TTS providers | R | none | May return only the first page of providers. |
| `exotel_voicebot_tts_list_voices` | Voices of a provider | R | `providerId`, `limit` (100) | |
| `exotel_voicebot_persona_list` | Personas | R | `limit` (50), `offset` | |
| `exotel_voicebot_persona_create` | New persona | D | `name`, `instruction`, `languagePrompt`, `genderPrompt` | |
| `exotel_voicebot_persona_update` | Edit a persona | L | `personaId` + any of the three texts | Every assistant using it changes. User's yes first. |

## Calls and conversations

| Tool | Does | Class | Key params | Notes |
|---|---|---|---|---|
| `exotel_voicebot_place_call` | Outbound call handled by a bot | C | `toNumber`, `voiceBotId`, `callerId`, `customField`, `record`, `statusCallback` | User's yes first. 10-digit numbers get +91. Returns a CallSid. |
| `exotel_voicebot_call_status` | Call details | R | `callSid` | |
| `exotel_voicebot_list_phone_numbers` | Account ExoPhones | R | none | Pick `callerId` here. |
| `exotel_voicebot_conversations_list` | Conversations with filters | R | `botId`, `startDate`, `endDate`, `outcome`, `limit` (20, max 100), `offset`, `fields` | 90 days back. Transcripts ~16 KB per row. |

## Tools Extension routes (need `te_base_url`)

| Tool | Does | Class | Key params | Notes |
|---|---|---|---|---|
| `exotel_voicebot_te_route_list` | Routes | R | `limit` (20), `offset` | |
| `exotel_voicebot_te_route_get` | One route or version | R | `routeId`, `version` (number), `includeVersions` | |
| `exotel_voicebot_te_route_create` | New route | L | `name`, `httpMethod`, `pathSuffix`, `yaml`, `changeLog` | Version 1 is served at once. YAML max 128,000 chars. |
| `exotel_voicebot_te_route_update` | New live version | L | `routeId`, `yaml`, `changeLog`, `httpMethod`, `pathSuffix`, `confirm` | Live at once for every caller. |
| `exotel_voicebot_te_route_delete` | Soft-delete a route | X | `routeId`, `confirm` | Tools calling it fail at once. |
| `exotel_voicebot_te_route_test` | Run once | E | `routeId`, `bodyJson`, `headersJson`, `queryParamsJson` | Outbound requests really happen. |
| `exotel_voicebot_te_runs_get` | Run traces | R | `routeId`, `runId`, `sessionId`, `includeSteps` | |
| `exotel_voicebot_te_route_attach_as_tool` | Wrap a route as an openapi tool | D | `routeId`, `toolName`, `description`, `inputSchemaJson` | Tool starts inactive. |

## Tools Server (tenant = account SID)

| Tool | Does | Class | Key params | Notes |
|---|---|---|---|---|
| `exotel_tools_server_tenant_create` | Register the tenant | D | `tenantId` (account SID), `name` | Once per account; "already exists" is fine. |
| `exotel_tools_server_tool_list` / `exotel_tools_server_tool_get` | Tools | R | `limit` (20, max 100), `offset`, `active`, `type` / `toolId` | |
| `exotel_tools_server_tool_create` | New tool, inactive, version 1 | D | `name`, `type`, `description`, `definitionJson` | openapi, python, knowledge_base, tabular_data. Names never reusable. |
| `exotel_tools_server_tool_update` | Activate, deactivate, switch version | L | `toolId`, `status`, `currentVersionId` | Send `status` with `currentVersionId`; version must be tested. User's yes first. |
| `exotel_tools_server_tool_version_list` / `exotel_tools_server_tool_version_get` | Versions | R | `toolId`, `versionId` | |
| `exotel_tools_server_tool_version_create` | New inactive version | D | `toolId`, `definitionJson`, `changelog` | Live only after `tool_update`. |
| `exotel_tools_server_tool_version_test` | Run a version once | E | `toolId`, `versionId`, `testInputsJson` | Real request. |
| `exotel_tools_server_table_list` / `exotel_tools_server_table_get` | Data tables | R | `limit`, `offset` / `tableId` | |
| `exotel_tools_server_table_create` | New table | D | `name`, `description`, `columnsJson` | |
| `exotel_tools_server_table_update` | Rename or change columns | L | `tableId`, `name`, `description`, `columnsJson` | |
| `exotel_tools_server_table_delete` | Delete table and rows | X | `tableId` | Tabular tools on it stop working. |
| `exotel_tools_server_table_row_list` / `exotel_tools_server_table_row_get` | Rows | R | `tableId`, `limit` (50, max 500), `offset` / `rowId` | |
| `exotel_tools_server_table_row_create` / `exotel_tools_server_table_row_update` | Add or edit rows | L | `tableId`, `rowsJson` (array) / `rowId`, `dataJson` | Live at once. |
| `exotel_tools_server_table_row_delete` | Delete a row | X | `tableId`, `rowId` | |
| `exotel_tools_server_corpus_list` / `exotel_tools_server_corpus_get` | Knowledge corpora | R | `limit`, `offset`, `status`, `fromDate`, `toDate` / `corpusId` | |
| `exotel_tools_server_corpus_create` | Corpus from text docs | D | `name`, `description`, `documentsJson`, `similarityTopK` (1-100, 10) | .txt/.md, 10 files and 1 MB per call. Async. |
| `exotel_tools_server_corpus_documents_update` | Add or remove documents | L | `corpusId`, `addDocumentsJson`, `deleteDocumentIds` | Changes answers for every bot using it. |
| `exotel_tools_server_corpus_delete` | Delete corpus | X | `corpusId` | Knowledge tools on it stop working. |

## Guide

| Tool | Does | Class | Key params |
|---|---|---|---|
| `exotel_voicebot_guide` | Returns these pages | R | `topic` (empty = index) |
