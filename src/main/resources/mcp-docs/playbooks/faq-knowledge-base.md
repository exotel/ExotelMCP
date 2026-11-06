---
topic: playbooks/faq-knowledge-base
title: FAQ answers from documents
description: Use when an inbound bot must answer questions from your own documents (FAQ, policies, product sheets) and hand the caller to a human when the documents do not cover the question.
tools: exotel_voicebot_catalog_llm_models, exotel_tools_server_tenant_create, exotel_tools_server_corpus_create, exotel_tools_server_corpus_get, exotel_tools_server_tool_create, exotel_tools_server_tool_version_test, exotel_tools_server_tool_update, exotel_voicebot_update_config, exotel_voicebot_conversations_list
---
> Draft: recommended settings pending review by the voicebot team.

## When to use

Inbound support where the answers already exist as text. Not for per-caller data such as an order's status:
use playbooks/status-lookup for that.

## Recommended configuration

| Area | Setting | Why |
|---|---|---|
| LLM | A fast model from `exotel_voicebot_catalog_llm_models`, plus `fallback_llm_config` | Each answer is a tool call and a second LLM pass; the fallback keeps one LLM failure from ending the call |
| Knowledge | One corpus of `.md`/`.txt` files, then a `knowledge_base` tool on it | The tool takes one `query` argument and returns the matching chunks |
| Retrieval | `similarityTopK`: start with 3 to 5 (default 10) | Fewer chunks keep the prompt small and the reply fast |
| Fillers | `filler_config` static, `trigger_delay_ms` 600 | Covers the lookup time |
| Interruption | `enable_interruption: true` | Callers cut in with follow-up questions |
| Greeting | `bot_speaks_first`, one sentence naming what the bot can help with | Sets scope early |
| Post-call | `post_session_insights` with one intent per FAQ topic plus `needs_human` | Shows which topics the documents miss |
| Handoff | An explicit transfer line, see playbooks/agent-transfer | The fallback when the documents have no answer |

## Starter template

1. Once per account, if no tools tenant exists: `exotel_tools_server_tenant_create`.
2. `exotel_tools_server_corpus_create` with all files in one call (at most 10 files, 1 MB). Poll
   `exotel_tools_server_corpus_get` until `status` is `ready`.
3. `exotel_tools_server_tool_create`: name `faq_kb`, type `knowledge_base`, definition
   `{"knowledge_corpus_id": "CORPUS_ID"}`. Test with `exotel_tools_server_tool_version_test`
   (`{"query": "refund timeline"}`), then `exotel_tools_server_tool_update` with `status` `active` and the tested
   version id.
4. `exotel_voicebot_update_config` data:

```json
{
  "filler_config": {"enabled": true, "type": "static", "trigger_delay_ms": 600,
    "phrases": ["Let me check that for you."]},
  "post_session_insights": {"input_type": "text",
    "intents": {"refunds": "Caller asked about refunds", "needs_human": "Documents had no answer"}},
  "assistant_config": {"agents": [{"id": "AGENT_ID", "name": "main", "description": "FAQ agent",
    "instruction": "INSTRUCTION", "llm_config": {},
    "mcp_tools": [{"tool_name": "tool_server.faq_kb", "name": "faq_kb",
      "description": "Search the help documents. Call for any product or policy question."}]}]}
}
```

Instruction outline: `## Identity` (company, role) / `## Rules`: call `faq_kb` before answering any factual
question; answer only from its result, in at most two sentences; if the result has no answer, say so and offer an
agent; never guess prices, dates or policy terms / `## Style`: short spoken sentences, no lists or markdown.

## Known problems

| Symptom | Fix |
|---|---|
| Live calls pause while documents upload | Upload everything in one request, outside business hours; never loop file by file |
| Bot answers from general knowledge | Add "Do NOT answer without calling faq_kb" and an anti-trigger for small talk |
| Calls fail at start after adding the tool | The tool must be active and tested; use `tool_server.faq_kb` as `tool_name` and set `name` |
| Another agent lost its tools | `agents` is the full list; resend every agent with its `id` and all its `mcp_tools` |
| Words in angle brackets vanished from the prompt | The API strips tag-shaped text; use `{var}` and `##` headings |
| Other bots broke after a corpus change | A corpus is shared by every bot whose tool uses it; check before deleting or replacing |

## Test checklist

- Ask three questions the documents answer; check each answer against the source text.
- Ask one question they do not cover: the bot must say so and offer an agent, not invent.
- Interrupt mid-answer with a follow-up.
- Read the test conversation with `exotel_voicebot_conversations_list` (`fields` `transcript,insights`).
