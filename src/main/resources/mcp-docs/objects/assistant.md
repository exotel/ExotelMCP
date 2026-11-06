---
topic: objects/assistant
title: Assistant, agents and personas
description: What an assistant holds (prompt, LLM, agents, tools, hooks, persona), how to create and change it, and why promoting a bot version can change other bots.
tools: exotel_voicebot_assistant_create, exotel_voicebot_assistant_get_config, exotel_voicebot_update_config, exotel_voicebot_bot_version_promote, exotel_voicebot_bot_traffic_set, exotel_voicebot_catalog_llm_models, exotel_voicebot_persona_list, exotel_voicebot_persona_create, exotel_voicebot_persona_update
---

# Assistant, agents and personas

An assistant is the bot's brain: system prompt, LLM, agents and tools. A bot points at one
assistant through `assistant_config.id` and `assistant_config.version` (`stable` or `vN`).
Several bots can share one assistant.

## Assistant fields

| Field | Required | Rules |
|---|---|---|
| `name`, `description` | yes | Name at most 255 characters |
| `instruction` | yes | System prompt, at most 200,000 characters. See the `prompting` page |
| `llm_config` | no | `{"id": "LLM_MODEL_ID", "initialization_params": {"temperature": 0.3, "timeout": 7}}`. Id from `exotel_voicebot_catalog_llm_models`. Omitted = account default model. Never send `null` |
| `fallback_llm_config` | no | Same shape. Used when the main LLM fails. Recommended |
| `persona_id` | no | Persona to apply (see below). Agents can also carry their own `persona_id` |
| `intent_detection_config` | no | `{"enabled": true, "language": "english", "execution_stage": "PRE", "intents": [{"name", "description"}], "max_consecutive_terminal_intents": 3}` |
| `agents` | yes, 1-10 | See below |
| `mcp_servers` | no | Leave out for tools-server tools: the platform attaches the account's tools-server when any agent has `mcp_tools`. Never list `tool_server` or the Exotel MCP server URL here; a bot cannot call the management server (rejected with `MCP_WIRING_REJECTED`) |

`initialization_params` limits: `temperature` 0.0-1.0, `timeout` above 0 and at most 30 seconds.
For voice keep temperature at 0.3 or lower and the timeout short; a slow turn makes callers talk
over the bot, which cancels and restarts the turn.

## Agents

```json
{"name": "main", "description": "Greets and answers order questions",
 "instruction": "Ask for the order id, then call order_status.",
 "llm_config": {},
 "tools": ["billing"],
 "mcp_tools": [ ... ],
 "pre_agent_hooks": [ ... ]}
```

- `name`, `description`, `instruction` are required. Names must be unique in the assistant; keep them short.
- `llm_config` is required on every agent. `{}` inherits the assistant's LLM.
- `tools` lists the names of other agents this agent may hand off to. Handoff names are saved but
  read back as `[]`, so keep your own record and confirm handoffs with a test call.

### mcp_tools entry

```json
{"tool_name": "tool_server.order_status",
 "name": "order_status",
 "description": "Call when the caller gives an order id. Returns status and delivery date.",
 "input_args": {"order_id": {"description": "Order id exactly as the caller says it"},
                "channel": {"value": "voice"}},
 "output_args": ["status", "eta"]}
```

| Key | Meaning |
|---|---|
| `tool_name` | Required. `tool_server.` plus the exact tools-server tool name. A bare name fails |
| `name` | Required. The name the model sees. Without it the call fails at start |
| `description` | Overrides the description the model reads |
| `input_args` | `{arg: {value?, description?, required?, enum?, default?}}`. An arg with `value` is hidden from the model and always sent. A value that is exactly `{{entities.KEY}}` or `{{session_constants.KEY}}` is read from call state. Array values are rejected |
| `input_mapping` | `{tool_arg: name_the_model_sees}` |
| `output_args` | Keep only these top-level keys of a JSON result |
| `output_mapping` | `{new_key: old_key}`, applied to what `output_args` kept |
| `output_path` | `{"path": "a.b"}` picks a nested value from what `output_args` kept |

The tool must exist and be **active** in the bot's account (see `tools-server`). Nothing checks this
at save time; a missing or inactive tool makes every call fail before the greeting. Add one line to
the agent instruction saying when to call the tool, or the model may say "one moment" and never call it.

### pre_agent_hooks (agent level only)

A hook runs a tool before the model on every turn and injects the result.

```json
{"name": "load_customer", "type": "mcp_tool", "enabled": true, "timeout_s": 3,
 "on_error": "continue", "skip_on_media": false, "inject": {"mode": "entities"},
 "tool": {"tool_name": "tool_server.customer_lookup", "name": "customer_lookup",
          "input_args": {"phone": {"value": "{{session_constants.contact_uri}}"}, "limit": {"value": 1}}}}
```

Rules: the account must have the feature enabled (else `pre_agent_hooks is not enabled for this
account`). Assistant-level hooks are refused.
`name` unique per agent, `type` = `mcp_tool`, `0 < timeout_s <= 5`, `on_error` = `continue` or
`fail_turn`, `inject.mode` = `tool_message` or `entities` (at most one enabled `tool_message` hook).
Every `input_args` entry needs a `value`. A sent hook list replaces the agent's whole list. Hooks
may be retried, so make hook tools safe to repeat. For table lookups, preset a key that is always
present and preset `limit` to 1 (see `tools-server`, tables).

## Create

`exotel_voicebot_assistant_create(assistantJson)` with the fields above. The tool checks the body
first: unknown keys, missing name/description/instruction, 1-10 agents, each agent named and with an
`llm_config`, unique agent and MCP server names. The new assistant's v1 is its stable version.
There is no assistant list or delete tool: keep the returned `assistant_id`. After a failed
create, tell the user a partial assistant may exist; do not retry blindly.

Read back: `exotel_voicebot_assistant_get_config(assistantId, version)`; `version` is `stable`
(default) or `vN`.

## Change through a bot version

There is no separate assistant-version tool. Change an assistant through
`exotel_voicebot_update_config(botId, dataJson)` with `data.assistant_config`:

```json
{"assistant_config": {
  "instruction": "FULL NEW PROMPT",
  "agents": [
    {"id": "EXISTING_AGENT_ID", "name": "main", "description": "...", "instruction": "...",
     "llm_config": {}, "mcp_tools": [ ...every tool this agent keeps... ]}
  ]}}
```

- Any of `instruction`, `llm_config`, `fallback_llm_config`, `intent_detection_config`, `agents`,
  `mcp_servers`, `name`, `description`, `persona_id` in `assistant_config` **forks** a new
  assistant version. It is not stable; only this new bot version is pinned to it.
- `agents` is the complete list. An agent left out is dropped. An entry with `id` updates that
  agent; one without `id` creates a new agent. Read the current agents (with ids) first.
- An agent you change keeps only the `mcp_tools` you send for it. Resend every tool it keeps.
- To change only the top-level prompt, send `instruction` and leave `agents` out.
- The new bot version gets 0% traffic. Check it with `exotel_voicebot_get_details(botId, version)`.

## Promote: the shared-assistant effect

`exotel_voicebot_bot_version_promote(botId, version, confirm=true)` makes the bot version stable
with 100% traffic **and** marks the assistant version it pins as the assistant's stable version.
Every other bot using that assistant at version `stable` switches to it at once. Before promoting,
ask the user whether other bots share the assistant. To test without that effect, use
`exotel_voicebot_bot_traffic_set` (canary split, stable versions unchanged). Both tools return a
preview first; call again with `confirm=true` only after the user approves.

## Personas

A persona is reusable personality text applied to an assistant or agent through `persona_id`.

| Field | Meaning |
|---|---|
| `name` | Required on create |
| `instruction` | Personality and tone |
| `language_prompt` | Language behaviour |
| `gender_prompt` | Voice gender wording |

- `exotel_voicebot_persona_list(limit, offset)` lists the account's personas.
- `exotel_voicebot_persona_create(name, instruction, languagePrompt, genderPrompt)`.
- `exotel_voicebot_persona_update(personaId, instruction, languagePrompt, genderPrompt)`: at least
  one field; the name cannot change. A persona edit affects every assistant that uses it.

## Text that disappears

String fields (except credentials) lose angle-bracket tags on save: a name wrapped in `<` and `>`
is deleted. Write placeholders as `{customer_name}` and sections as markdown headings. The tools
warn (`TAG_STRIPPED`) where this happens. After every write, read back and compare.
