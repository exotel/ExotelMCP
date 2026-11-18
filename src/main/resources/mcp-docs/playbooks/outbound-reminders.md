---
topic: playbooks/outbound-reminders
title: Outbound reminders and confirmations
description: Use when the bot calls customers to remind, confirm or collect (payment due, delivery slot, appointment), with the bot speaking first, short turns and a clean end of call.
tools: exotel_voicebot_update_config, exotel_voicebot_list_phone_numbers, exotel_voicebot_place_call, exotel_voicebot_call_status, exotel_voicebot_conversations_list
---
> Draft: recommended settings pending review by the voicebot team.

## When to use

One-purpose outbound calls: payment reminders and collections, delivery or appointment confirmation, renewal
nudges. The call should take under two minutes and end with a recorded answer.

## Recommended configuration

| Area | Setting | Why |
|---|---|---|
| Greeting | `greeting_message_mode: bot_speaks_first`, a fixed `greeting_message` naming the company and the purpose | A fixed greeting plays at once with no LLM wait |
| Greeting interruption | `enable_greeting_interruption: false` (the default) with a short greeting | The caller's first "hello" does not cut off who is calling |
| Personal data | `session_start` webhook returns `entities` (name, amount, due date); use them as `{customer_name}` in the instruction | Values are substituted before the first reply |
| Webhook speed | `session_start` answers in under about 2.5 s | A slow or failing `session_start` fails the call before the greeting |
| Call end | Instruction ends with an explicit goodbye line | The platform ends the call after the bot's explicit closing sentence |
| Duration cap | `enable_call_threshold: true`, `call_duration_threshold_sec`: start with 300, a polite `max_duration_message` | Stops runaway calls |
| Nudges | Defaults; set an `idle_end_call` prompt that says goodbye | Silent callers are closed politely |
| Outcome | `post_session_insights.intents`: `confirmed`, `reschedule`, `promised_to_pay`, `refused`, `wrong_person` | Campaign reporting without reading transcripts |

## Starter template

`exotel_voicebot_update_config` data:

```json
{
  "greeting_message_mode": "bot_speaks_first",
  "greeting_message": {"text": "Hello, this is Asha calling from Acme Pharmacy about your order.", "enabled": true},
  "enable_greeting_interruption": false,
  "enable_call_threshold": true,
  "call_duration_threshold_sec": 300,
  "max_duration_message": "Thank you for your time. We will call you again. Goodbye.",
  "webhook_config": {"session_start": {"url": "https://crm.example.com/voicebot/session-start"}},
  "post_session_insights": {"input_type": "text",
    "intents": {"confirmed": "Customer confirmed", "reschedule": "Customer asked for another time",
      "wrong_person": "Not the intended customer"}}
}
```

Instruction outline: identity and purpose; replies of one or two sentences; confirm you are speaking to `{customer_name}` before giving details;
state the one fact (`{amount}` due on `{due_date}`); ask the one question; handle "wrong person", "call later" and
"not interested" in one sentence each; never threaten or argue; end every path with "Thank you, goodbye."

Placing a call:
1. `exotel_voicebot_list_phone_numbers` to pick a `callerId` the account owns.
2. `exotel_voicebot_place_call` with `toNumber` in full international format (`+9198XXXXXXXX`), `voiceBotId`,
   `callerId`, `record: true`, and a `statusCallback` URL if you track results.
3. `exotel_voicebot_call_status` with the returned call SID; then read the conversation with
   `exotel_voicebot_conversations_list`.

Consent: call only people who agreed to be contacted, in permitted hours, honouring opt-outs and do-not-call rules.

## Known problems

| Symptom | Fix |
|---|---|
| Call drops before the greeting | Time your `session_start` webhook; keep it fast and always 2xx |
| `{customer_name}` spoken literally | The webhook did not return that entity; the key names must match exactly |
| Bot keeps talking after "goodbye" | The closing line must be an explicit goodbye, not a question |
| Calls rejected during a campaign | The account has a concurrent-call cap (5 by default); ask Exotel support to raise it before a campaign |
| `place_call` refuses the bot | The account needs an ExoPhone and the bot a call flow; test with a recently created bot or ask Exotel support |

## Test checklist

- Place one call to your own phone; the greeting plays within a second of answering.
- Say "hello?" during the greeting: the greeting still completes.
- Answer as the wrong person, then as "call me later": both end politely.
- Stay silent: nudges, then a goodbye and hang-up.
- Check the outcome intent on the conversation.
