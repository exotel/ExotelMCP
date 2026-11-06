---
topic: playbooks/lead-qualification
title: Lead qualification to a CRM
description: Use when the bot asks a fixed set of qualifying questions (need, budget, timeline, location) and the answers must reach your CRM as structured fields.
tools: exotel_voicebot_conversations_list, exotel_voicebot_place_call, exotel_tools_server_tool_create, exotel_tools_server_tool_version_test, exotel_tools_server_tool_update, exotel_voicebot_update_config
---
> Draft: recommended settings pending review by the voicebot team.

## When to use

Inbound enquiries or outbound callbacks to web leads, where the result is a lead record: qualified or not, plus
the answers to three to six questions.

## Recommended configuration

| Area | Setting | Why |
|---|---|---|
| CRM write | An `openapi` tool `save_lead` that posts the answers to your CRM, called once at the end | The only path that gives your CRM exact, typed fields during the call |
| Classification | `post_session_insights.intents` such as `qualified`, `not_qualified`, `callback_requested` | Each call is labelled for reporting |
| Query resolution | `post_session_insights.query_resolution` criteria | Without them the later answers land in the wrong fields |
| Summary | `post_session_insights.summary.prompt` asking for a short plain summary | Readable notes; read them with `exotel_voicebot_conversations_list` (`fields` `insights`) |
| End-of-call push | `webhook_config.session_end` to your CRM | Marks the call finished even when `save_lead` was never called |
| Turns | One question per turn, in a fixed order | Easier for callers and for the model |
| Outbound | Follow playbooks/outbound-reminders for greeting, consent and `exotel_voicebot_place_call` | Same call mechanics |

## Starter template

Create `save_lead` with `exotel_tools_server_tool_create` (type `openapi`, one POST operation, body fields
`name`, `need`, `budget`, `timeline`, `city`, `qualified`), test it with `exotel_tools_server_tool_version_test`
and activate it with `exotel_tools_server_tool_update`. Then `exotel_voicebot_update_config` data:

```json
{
  "post_session_insights": {
    "input_type": "text",
    "intents": {"qualified": "Meets budget and timeline", "not_qualified": "Fails a criterion",
      "callback_requested": "Asked to be called later"},
    "query_resolution": {
      "resolved_criteria": {"prompt": "All qualifying questions answered"},
      "unresolved_criteria": {"prompt": "Questions left unanswered", "categories": ["hung_up", "refused"]}},
    "summary": {"prompt": "Summarise the caller's need, budget and timeline in two sentences."}
  },
  "webhook_config": {"session_end": {"url": "https://crm.example.com/voicebot/session-end"}},
  "assistant_config": {"agents": [{"id": "AGENT_ID", "name": "main", "description": "Qualifier",
    "instruction": "INSTRUCTION", "llm_config": {},
    "mcp_tools": [{"tool_name": "tool_server.save_lead", "name": "save_lead",
      "description": "Save the answers once all questions are asked. Call exactly once."}]}]}
}
```

Instruction outline: identity; the questions in order, each with its accepted values (budget bands, timeline
options); map free answers to those values; if the caller skips one, record `unknown` and move on; before
`save_lead`, read the key answers back in one sentence; after it, say the next step (a sales call within one
day) and goodbye. Write placeholders as `{name}`, never in angle brackets.

## Known problems

| Symptom | Fix |
|---|---|
| Outcome text appears under intents, summary under query resolution | Always define at least one intent and the query-resolution criteria |
| No entities in post-call insights | Post-call insights give intents, outcome, resolution and summary only; capture fields with `save_lead` |
| Summary prompt change affected live calls at once | The newest version's summary prompt is used for every call; treat it as live when you create the version |
| JSON inside the summary is often empty | Ask for one flat JSON object with an exact escaped example, or keep the summary plain text |
| `post_session_insights.llm_config` has no effect | Leave it out; the platform picks that model |
| Insights missing on some calls | Set `input_type` to `text` |

## Test checklist

- A fully qualified caller: `save_lead` called once with every field.
- A caller who refuses budget: `unknown` recorded, call continues.
- Hang up midway: `session_end` reaches your CRM.
- Check intents, resolution and summary on the conversation a minute after the call.
