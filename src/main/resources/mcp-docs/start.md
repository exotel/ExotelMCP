---
topic: start
title: Start here
description: How Exotel voicebot objects connect, the rules every change must follow, and which guide page to read for your task.
tools: exotel_voicebot_guide
---
Read this page before your first change. Open other pages with `exotel_voicebot_guide(topic)`.

## How the objects connect

```
account (tenant = account SID, case-sensitive)
 |- bot ................ versions v1, v2, ... ; stable_version ; traffic_allocation {"v2": 100}
 |   |- voice, ASR, VAD, greeting, nudges, fillers, timeouts, webhooks, post-call insights
 |   `- assistant_config {id, version, input, output} --> pins one assistant version
 |- assistant .......... versions v1, v2, ... (one is stable)
 |   |- instruction, llm_config, fallback_llm_config, persona_id, intent_detection_config
 |   `- agents [1..10] {name, instruction, llm_config, tools (handoffs), mcp_tools}
 |                                                        `- {"tool_name": "tool_server.TOOL_NAME", "name": "TOOL_NAME"}
 |- persona ............ shared text blocks referenced by assistants (persona_id)
 |- Tools Server tenant  tools (openapi, python, knowledge_base, tabular_data), data tables, knowledge corpora
 `- Tools Extension .... routes (Camel YAML) served as URLs; attach one as an openapi tool
```

| Fact | Consequence |
|---|---|
| The voice lives on the bot; the prompt lives on the assistant. | Greeting, TTS, ASR, VAD, timing and webhooks are bot fields. Instruction, LLM, agents and tools are assistant fields. |
| A bot version pins an assistant version. | Changing assistant fields through `exotel_voicebot_update_config` forks a new assistant version and pins the new bot version to it. |
| A new bot version gets 0% traffic. | Nothing changes for callers until you promote it or give it a traffic share. |
| Several bots can use one assistant at version `stable`. | Promoting a bot version also makes its pinned assistant version that assistant's stable version, so the other bots change too. |
| Agent tools resolve at call time from the account's Tools Server tenant (tenant id = account SID). | A tool must exist and be `active`. A wrong or inactive tool name is not caught on save; the call fails before the greeting. |

## The 8 core rules

1. **Read before you change, and read back after.** Use `exotel_voicebot_get_details` (and with `version` for a draft) and `exotel_voicebot_assistant_get_config`. A 2xx answer does not prove a field was stored.
2. **Change a live bot only through drafts.** `exotel_voicebot_update_config` creates a draft version at 0% traffic. Then `exotel_voicebot_bot_traffic_set` (canary) or `exotel_voicebot_bot_version_promote` (100%).
3. **Show the preview and get the user's yes before `confirm=true`.** Tools with a `confirm` parameter return `CONFIRM_REQUIRED` with a `preview` and change nothing. Show it, wait for an explicit yes, then call again with the same arguments and `confirm=true`.
4. **Get the user's yes before** `exotel_voicebot_delete`, `exotel_voicebot_place_call`, activating or deactivating a tool (`exotel_tools_server_tool_update`), `exotel_voicebot_persona_update`, `exotel_voicebot_te_route_update` and `exotel_voicebot_te_route_delete`, and every table, row or corpus delete.
5. **Promoting changes the shared assistant.** Before a promote, check whether other bots use the same assistant at version `stable` and tell the user.
6. **Credentials come only from the Authorization envelope.** Never ask for, print, store or pass keys in chat, prompts, tool definitions, YAML or webhook URLs. See `credentials`.
7. **No angle-bracket text.** The API silently strips anything shaped like an HTML tag (a `<` followed by a letter, `/`, `!` or `?`) from prompts, greetings, YAML and tool definitions. Write placeholders as `{name}` or `UPPER_CASE`, and write comparisons as `b > a`, not with `<`.
8. **This MCP server is not a bot tool.** It builds and edits bots; a bot cannot call it during a call. To give a bot a tool, attach a tools-server tool to an agent with `mcp_tools` `[{"tool_name": "tool_server.{name}", "name": "{name}"}]`. Never add this server's URL, or `tool_server`, to `mcp_servers`: the platform connects tools-server itself, and the write tools reject it with `MCP_WIRING_REJECTED`.

## Effect classes

Every tool belongs to one class. The class tells you what a call can break.

| Class | Meaning | Tools |
|---|---|---|
| READ | No change anywhere. | `list_all`, `get_details`, `creation_status`, `bot_versions_list`, `assistant_get_config`, `catalog_llm_models`, `catalog_asr_models`, `catalog_vad_configs`, `tts_list_providers`, `tts_list_voices`, `persona_list`, `conversations_list`, `call_status`, `list_phone_numbers`, `te_route_list`, `te_route_get`, `te_runs_get`, `guide`; tools-server `tool_list`, `tool_get`, `tool_version_list`, `tool_version_get`, `table_list`, `table_get`, `table_row_list`, `table_row_get`, `corpus_list`, `corpus_get` |
| DRAFT | Creates something no caller reaches yet. | `update_config`, `create`, `bot_create`, `assistant_create`, `persona_create`, `te_route_attach_as_tool`; tools-server `tenant_create`, `tool_create`, `tool_version_create`, `table_create`, `corpus_create` |
| LIVE | Changes what live calls do, at once. | `bot_version_promote`, `bot_traffic_set`, `persona_update`, `te_route_create`, `te_route_update`; tools-server `tool_update`, `table_update`, `table_row_create`, `table_row_update`, `corpus_documents_update` |
| DESTRUCTIVE | Removes something; no undo tool. | `delete`, `te_route_delete`; tools-server `table_delete`, `table_row_delete`, `corpus_delete` |
| CALL | Rings a real phone. | `place_call` |
| EXECUTES | Runs your code or HTTP request for real, including writes to external systems. | `te_route_test`; tools-server `tool_version_test` |

Names above drop the `exotel_voicebot_` prefix; tools-server names drop `exotel_tools_server_`. A bot made with `bot_create` or `create` is stable at once, but takes calls only after a phone flow points at it or you place a call.

## Which page to read

| You want to | Read |
|---|---|
| Find the right tool and its parameters | `tools` |
| Know what a write affects and how to undo it | `safe-changes` |
| Understand or set bot fields (greeting, timing, interruption, versions) | `objects/bot` |
| Choose TTS voice, ASR model or VAD preset | `objects/voice` |
| Write or change the prompt, LLM, agents, handoffs, persona | `objects/assistant` |
| Set webhooks, live insights or post-call insights | `objects/webhooks-insights` |
| Give an agent a tool, table lookup or knowledge base | `tools-server` |
| Call an API through a custom multi-step HTTP flow | `tools-extension` |
| Follow a step-by-step recipe (new bot, change, release, rollback) | `workflows` |
| Start from a use case (FAQ, status lookup, outbound, booking, transfer) | `playbooks/index` |
| Write prompts that work well on voice | `prompting` |
| Read calls, transcripts and outcomes | `conversations` |
| Something returned an error | `errors` |
| Something behaves oddly without an error | `known-problems` |
| Set up or fix the Authorization envelope | `credentials` |

## First moves

1. `exotel_voicebot_list_all` to see the account's bots. A `MISSING_CREDENTIALS` or 401 result means the envelope is wrong: read `credentials`.
2. For an existing bot: `exotel_voicebot_get_details(botId)` and `exotel_voicebot_bot_versions_list(botId)`.
3. For a new bot: read `workflows`.
