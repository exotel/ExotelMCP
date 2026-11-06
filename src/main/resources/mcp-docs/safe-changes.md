---
topic: safe-changes
title: Safe changes and rollback
description: What each write affects beyond the object you change, what to save before it, and how to roll it back.
tools: exotel_voicebot_bot_version_promote, exotel_voicebot_bot_traffic_set, exotel_voicebot_persona_update, exotel_voicebot_delete, exotel_voicebot_te_route_update, exotel_voicebot_te_route_delete, exotel_tools_server_tool_update, exotel_tools_server_table_delete, exotel_tools_server_table_row_delete, exotel_tools_server_corpus_delete, exotel_tools_server_corpus_documents_update
---
Many objects are shared across bots. A write that looks local can change every bot in the account that uses the same assistant, persona, tool, table, corpus or route. Before any LIVE or DESTRUCTIVE call (see `start`), find who else uses the object, save what you need to undo it, and get the user's yes.

## Blast radius of each write

| Write | Also affects | Takes effect |
|---|---|---|
| `exotel_voicebot_update_config` | Nothing live. Assistant fields fork a new, non-stable assistant version. | Never, until promote or traffic share |
| `exotel_voicebot_bot_traffic_set` | Callers of this bot, split by version. Stable version unchanged. | Next calls |
| `exotel_voicebot_bot_version_promote` | This bot (100% to the version) **and** every other bot that uses the same assistant at version `stable`: the pinned assistant version becomes that assistant's stable. | Next calls |
| `exotel_voicebot_persona_update` | Every assistant whose `persona_id` is this persona, so every bot on those assistants. Personas have no versions. | Next calls |
| `exotel_voicebot_delete` | This bot stops taking calls, including from phone flows that point at it. | At once |
| `exotel_tools_server_tool_update` (activate or switch version) | Every agent in the account that names `tool_server.TOOL_NAME`. | Seconds later |
| `exotel_tools_server_tool_update` (`status=inactive`) | Every bot whose agents name the tool: one unresolvable tool fails the whole assistant, so those calls fail before the greeting. No usage check runs. | Seconds later |
| `exotel_tools_server_table_update`, `_table_row_create`, `_table_row_update`, `_table_row_delete` | Every `tabular_data` tool on that table, so every bot using those tools. | At once |
| `exotel_tools_server_table_delete` | Deletes all rows. Every `tabular_data` tool on the table stops working, and bots naming those tools fail at call start. | At once |
| `exotel_tools_server_corpus_documents_update` | Answers of every bot whose `knowledge_base` tool uses the corpus. | When status returns to `ready` |
| `exotel_tools_server_corpus_delete` | Every `knowledge_base` tool on the corpus. No in-use check. | At once |
| `exotel_voicebot_te_route_update` | Every tool and bot that calls the route's URL. A new `pathSuffix` breaks callers of the old URL. | At once, no draft |
| `exotel_voicebot_te_route_delete` | Every tool and bot that calls the route. | At once |

## Find who uses an object

| Object | How to check |
|---|---|
| Assistant | `exotel_voicebot_list_all` (page through all), then `exotel_voicebot_get_details` per bot: compare `assistant_config.id` and `version`. Bots pinned to `stable` follow a promote. |
| Persona | `exotel_voicebot_assistant_get_config` on each assistant in use; compare `persona_id`. |
| Tool | Read each in-use assistant and search agents' `mcp_tools[].tool_name` for `tool_server.TOOL_NAME`. |
| Table or corpus | `exotel_tools_server_tool_list`, then `exotel_tools_server_tool_get`: `tabular_data` definitions carry `data_table_id`, `knowledge_base` definitions carry `knowledge_corpus_id`. Then check which agents name those tools. |
| Route | `exotel_tools_server_tool_get` on openapi tools: the route's `published_url` appears as `servers[0].url` plus the path. |

If the check is too large to finish, say so and ask the user before writing.

## Save before you write

| Before | Save |
|---|---|
| Promote or traffic change | `exotel_voicebot_bot_versions_list`: current `stable_version` and `traffic_allocation`. The tool preview shows them too. |
| Bot delete | The full config from `exotel_voicebot_get_details(botId)`. |
| Persona update | The persona's current `instruction`, `language_prompt`, `gender_prompt` from `exotel_voicebot_persona_list`. |
| Tool switch or deactivate | Tool `status` and `current_version_id` from `exotel_tools_server_tool_get`. |
| Row update or delete | The row from `exotel_tools_server_table_row_get`. |
| Table delete | `exotel_tools_server_table_get` (columns) and every row from `exotel_tools_server_table_row_list` (page to the end). |
| Corpus change or delete | The text of each document you remove; the corpus `name`, `description` and document list from `exotel_tools_server_corpus_get`. Corpora have no versions. |
| Route update | The current version number from `exotel_voicebot_te_route_get`. Old versions stay readable. |
| Route delete | The route's `name`, `httpMethod`, `pathSuffix` and YAML. |

## Rollback procedures

**Bot version promote.**
1. `exotel_voicebot_bot_version_promote(botId, version=PREVIOUS_STABLE)` without `confirm`, show the preview, get a yes, call again with `confirm=true`.
2. This also makes the previous version's pinned assistant version stable again, which restores other bots on that assistant.
3. If there was a split before, restore it with `exotel_voicebot_bot_traffic_set`.

**Traffic split.** `exotel_voicebot_bot_traffic_set(botId, allocationJson=SAVED_SPLIT)`, preview, yes, `confirm=true`. Or give the stable version 100.

**Bad draft.** Nothing to roll back. Leave it at 0% or build the next draft with `sourceVersion` set to the good version. Building from an old version drops later changes, so use it only on purpose.

**Persona update.** `exotel_voicebot_persona_update(personaId, ...)` with the saved texts.

**Tool version switch.** `exotel_tools_server_tool_update(toolId, status=active, currentVersionId=PREVIOUS_VERSION_ID)`. The previous version is already tested.

**Tool deactivated by mistake.** `exotel_tools_server_tool_update(toolId, status=active, currentVersionId=SAVED_VERSION_ID)`. If a bot must keep running without the tool, first remove it from the agents' `mcp_tools` with a draft and promote that draft, then deactivate.

**Row change.** `exotel_tools_server_table_row_update` with the saved data, or `exotel_tools_server_table_row_create` to re-add a deleted row. The row id changes.

**Table deleted.** There is no restore.
1. `exotel_tools_server_table_create` with the saved columns, then `exotel_tools_server_table_row_create` with the saved rows (batches). The table id is new.
2. For each `tabular_data` tool that used it: `exotel_tools_server_tool_version_create` with the same definition and the new `data_table_id`, `exotel_tools_server_tool_version_test`, then `exotel_tools_server_tool_update(status=active, currentVersionId=NEW_VERSION_ID)`. Keep the tool name: names are never reusable, and agents already reference it.

**Corpus documents.** `exotel_tools_server_corpus_documents_update` re-adding the saved documents and removing the new ones. Poll `exotel_tools_server_corpus_get` until `ready`.

**Corpus deleted.** Recreate with `exotel_tools_server_corpus_create` from the saved documents, wait for `ready`, then give each `knowledge_base` tool a new version with the new `knowledge_corpus_id`, test it and activate it.

**Route update.** Read the old YAML with `exotel_voicebot_te_route_get(routeId, version=N)`, then `exotel_voicebot_te_route_update(routeId, yaml=OLD_YAML, confirm=true)` after the user's yes. That saves it as a new version. Restore `httpMethod` and `pathSuffix` too if they changed.

**Route deleted.** Recreate with `exotel_voicebot_te_route_create` from the saved definition. If the URL differs, give each tool that called it a new version with the new URL, test and activate it.

**Bot deleted.** These tools cannot restore it. Tell the user at once. To keep serving callers, build a replacement with `exotel_voicebot_bot_create` from the config you read before the delete, and point the phone flow at the new bot id.

## Lower-risk patterns

- Canary first: `exotel_voicebot_bot_traffic_set` with 10% on the new version, read `exotel_voicebot_conversations_list` for that version, then promote.
- To change one bot's prompt when the assistant is shared, consider a new assistant (`exotel_voicebot_assistant_create`) for that bot instead of promoting a fork of the shared one.
- New tool behaviour: add a tool version, test it, switch with `tool_update`; keep the old version id for rollback.
- Corpus uploads: small batches, outside peak call hours.
- Route changes: test a copy as a separate route first, then update the real one.
