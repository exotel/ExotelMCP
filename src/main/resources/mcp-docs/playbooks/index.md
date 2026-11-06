---
topic: playbooks/index
title: Use-case playbooks
description: Start here to pick the right bot configuration for a business use case; lists every playbook with its key choices.
tools: exotel_voicebot_guide, exotel_voicebot_bot_create, exotel_voicebot_update_config, exotel_voicebot_get_details, exotel_voicebot_bot_version_promote, exotel_voicebot_bot_traffic_set
---
> Draft: recommended settings pending review by the voicebot team.

Each playbook gives the settings to choose, a starter config for `exotel_voicebot_bot_create` or
`exotel_voicebot_update_config`, the known problems for that use case and a test checklist. Read one with
`exotel_voicebot_guide` and the topic in the page column. Most bots combine two or three playbooks, for example
status-lookup with number-id-capture and agent-transfer.

| Use case | Page | Key choices |
|---|---|---|
| Answer questions from your documents | playbooks/faq-knowledge-base | Knowledge corpus plus a `knowledge_base` tool; answer only from the tool result; transfer when the documents have no answer |
| Order, booking or ticket status by id | playbooks/status-lookup | `tabular_data` or `openapi` tool; confirm the id before the lookup; `default_limit` 1 |
| Phone numbers, OTPs, ids, dates | playbooks/number-id-capture | Turn-taking on with a longer speech timeout; `interruption_strategy.min_words`; read back in groups; PII masking |
| Outbound reminders, collections, confirmations | playbooks/outbound-reminders | Fixed greeting, bot speaks first; data from the `session_start` webhook; explicit goodbye; duration cap; consent |
| Qualify leads into a CRM | playbooks/lead-qualification | A `save_lead` tool for structured fields; post-call intents, query resolution and summary; `session_end` webhook |
| Book or reschedule appointments | playbooks/appointment-booking | One tool per step; today's date from `session_start`; re-search after any change; book only after a full read-back |
| Hindi and other Indian languages | playbooks/multilingual | Main language first in `supported_languages`; TTS voice and ASR model that list every language; pin the TTS language |
| Handoff to a human | playbooks/agent-transfer | Explicit transfer sentence; `pre_agent_transfer` webhook with `include_insights`; Connect step in the call flow |
| Faster replies | playbooks/low-latency | Fast LLM plus fallback; fixed greeting; text input; shorter speech timeout; platform VAD preset; static fillers |

## Rules that apply to every playbook

- Build with `exotel_voicebot_bot_create`, change with `exotel_voicebot_update_config`, and read every write back
  with `exotel_voicebot_get_details`. New versions start with no traffic.
- Promote (`exotel_voicebot_bot_version_promote`) or split traffic (`exotel_voicebot_bot_traffic_set`) only after a
  test call and the user's explicit yes.
- Never write angle-bracket placeholders in prompts, greetings or templates; the API removes them. Use `{name}`.
- `agents` is always the full list, and every agent needs `llm_config` (`{}` inherits the assistant's model).
- Reference tools as `tool_server.NAME` with a `name`; a tool must be tested and active first.
- Keep the `session_start` webhook under about 2.5 s, or calls fail before the greeting.
