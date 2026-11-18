---
topic: objects/webhooks-insights
title: Webhooks and insights
description: What each webhook in webhook_config sends and expects, why session_start must answer fast, and how to set up live (in-call) and post-call insights.
tools: exotel_voicebot_update_config, exotel_voicebot_bot_create, exotel_voicebot_get_details, exotel_voicebot_conversations_list
---

# Webhooks and insights

These are bot fields. Change them with `exotel_voicebot_update_config`; read them back with
`exotel_voicebot_get_details`.

## webhook_config endpoints

| Key | When | Waits for you? | Your answer |
|---|---|---|---|
| `session_start` | Call setup, before the greeting | **Yes**. The call fails if you are slow or fail | Optional data for this call (below) |
| `transcript_events` | During the call, per turn | No | Ignored. Also switches on mid-call live insights |
| `insights_events` | When live insights are produced | No | Ignored |
| `pre_agent_transfer` | Just before a handoff to a human agent | Yes | Optional transfer message |
| `session_end` | After the call ends | No | Ignored |

### Endpoint shape

Use `actions`. The flat `{url, auth, timeout_sec, retry_config}` form is deprecated.

```json
"webhook_config": {
  "session_end": {
    "actions": [{
      "action_type": "http",
      "config": {
        "url": "https://example.com/voicebot/session-end",
        "method": "POST",
        "headers": {"X-Source": "voicebot"},
        "auth": {"scheme": "bearer", "config": {"token": "TOKEN_FROM_USER"}},
        "timeout_sec": 5,
        "retry_config": {"max_retry": 1, "interval_sec": 2}
      }
    }]
  }
}
```

| Key | Rules |
|---|---|
| `action_type` | Only `http`. (`action_name` names an account webhook template instead; never send both) |
| `config.url` | Required, starts with `http://` or `https://`. Keep the host fixed |
| `config.auth` | `scheme` `bearer` (sends `config.token` as a Bearer header) or `none`. Reads redact the token; get it from the user |
| `config.timeout_sec` | 1-30, default 30 |
| `config.retry_config` | `max_retry` 0-3, `interval_sec` 1-60 |

Updates deep-merge per endpoint and key; an `actions` list is replaced whole. Sending `actions` clears a
flat `url` and vice versa. `null` for an endpoint removes it. (A flat-form update spells retry
`{"max_retries", "timeout_sec"}`, unlike create: one more reason to use `actions`.) Check the result in
`exotel_voicebot_get_details(botId, version="vN")` under `config.webhook_config`.

### What the webhooks send

`session_start` body: `session_id`, `start_time`, `external_id` (the call SID), `session_state`,
`contact_uri` (caller), `network_type`, `bot_id`, `metadata` (`call_sid`, `leg_sid`) and the call's
`custom_parameters`.

The others share one body: the same ids and times plus `account_id`, `conversation_id`, `end_time`,
`landing_number`, `bot_name`, `trigger_reason`, `transcripts`, `events` (insights), `entities`,
`session_constants`; `metadata` adds the caller's earlier calls when `repeated_calls` is on.

### session_start must answer fast

The platform allows about 3 s for the whole call setup, and this webhook runs inside it.

| Your endpoint | Result |
|---|---|
| 2xx in under about 2.5 s | Call proceeds |
| 2xx after about 3 s | Call fails before the greeting, even though you answered 200 |
| Non-2xx twice | Call fails in about 1 s |
| Fails, then a retry that hangs | Call fails at about 3 s |

Keep p99 under 2.5 s with no cold starts (a cold first request can fail one call). Move slow lookups into
tools the agent calls later.

Answer with this envelope. Anything else, or `error_data`, is ignored and the call continues without data:

```json
{"response": {"data": {
  "greeting_message": {"text": "Hello, I am calling about your order."},
  "entities": {"customer_name": "CUSTOMER_NAME"},
  "session_constants": {"tier": "gold"},
  "session_metadata": {"policy_block": "..."}
}}}
```

`greeting_message` replaces this call's greeting. `entities` and `session_constants` are kept with the
conversation and sent on later webhooks. `session_metadata` keys are prompt variables, written
`{session_metadata.KEY}` in an agent instruction. A `webhook_config` here (same endpoint shape) overrides
the other four endpoints for this call only, so a call may not use the bot's own webhook settings.

### pre_agent_transfer

Called synchronously before the handoff. Your answer may carry
`{"response": {"data": {"agent_transfer_message": {"text": "Connecting you to an agent."}}}}`; if your
endpoint fails, a default message is used. With `"include_insights": true` on this endpoint, live insights are
generated first and added to the same POST as `events[]` (summary, sentiment, intent, entities). The
handoff then waits for them, usually 1-1.5 s. The top-level `entities` and `session_constants` are the
session-start values; insight entities are only in the `events[]` item whose `eventType` is `entities`.
If this is the call's first insights run, `events` can be `null`: enable `transcript_events` so insights
run earlier.

## Live insights (during the call)

| Field | Value | Notes |
|---|---|---|
| `conversation_assistant_id` | LIVE_INSIGHTS_ASSISTANT_ID | A live insights assistant, not a conversation assistant. None of these tools create one; get the id from the user |
| `insights_assistant_id` | same id | Set both to the same id |
| `webhook_config.transcript_events` | an endpoint | Required for insights **during** the call. Without it insights run once, at call end |
| `webhook_config.transcript_events.min_turns_for_insights` | integer, 0 or more | Caller turns before insights start; 0 = no gate |
| `webhook_config.insights_events` | an endpoint | Where insights are delivered |
| `insights_events.trigger_type` | `none`, `turn_complete`, `timer`, `turn_in_progress` | `timer` needs `trigger_interval_sec` of 30 or more |
| `insights_events.session_end_mode` | `dedup` (default) or `always` | `dedup` reuses insights made at an agent transfer for the call-end delivery; `always` generates them again |

There is no on/off flag: any usable id runs insights on every call, test calls included. To stop them, set
both ids to `null` in a new version. Leave `insights_target` unset. The two ids show only in
`exotel_voicebot_get_details(botId)` without a version, which reads the **stable** version; a draft's ids
cannot be read back until it is promoted.

## Post-call insights

```json
"post_session_insights": {
  "input_type": "text",
  "intents": {"billing_query": "Caller asks about a bill or a payment",
              "cancel_order": "Caller wants to cancel an order"},
  "query_resolution": {
    "resolved_criteria":   {"prompt": "Resolved when the caller confirms a pickup time."},
    "unresolved_criteria": {"prompt": "Unresolved when no time is agreed.", "categories": ["no_answer", "refused"]}
  },
  "summary": {"prompt": "Summarise the call in two sentences."}
}
```

| Key | Rules |
|---|---|
| block | `null` turns post-call insights off; `{}` still runs them with defaults |
| `input_type` | `text` (transcript) or `audio` (recording). Unset means the recording path, which fails on calls without a recording: always send `text` |
| `intents` | Map of intent name to description. In an update, merged per name; `null` for a name removes it |
| `query_resolution` | Criteria prompts; built-in defaults when unset |
| `summary.prompt` | No summary unless set |
| `llm_config` | Stored but not used: every bot uses the platform's insights model |

Traps:
- Answers are filed **by position**: intents, outcome, query resolution, summary. With no intents defined,
  each later answer shifts one field left (the outcome lands in intents). Always define at least one intent.
- Post-call insights have no entities. Use live insights for entities.
- The summary prompt is read from the bot's **newest** version, not the one that served the call. A summary
  change in a 0% draft affects live calls as soon as the version exists: get the user's yes first. Intents
  come from the stable version.
- To get JSON in the summary, ask for one flat object, give an exact escaped example, and require the answer
  to start with `{\"summary\": \"` and end with `\"}`. Nested objects are often lost.
- The call outcome list is platform-wide; it cannot be set per bot.
- Console text tests never produce post-call insights; only real calls do.

Read results with `exotel_voicebot_conversations_list(botId, fields="insights")`, a few rows at a time.
