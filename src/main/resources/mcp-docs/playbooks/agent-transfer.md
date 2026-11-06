---
topic: playbooks/agent-transfer
title: Handoff to a human agent
description: Use when the bot must hand some callers to a human agent, send the agent's system a summary of the call before the agent picks up, and keep the caller informed during the handoff.
tools: exotel_voicebot_update_config, exotel_voicebot_get_details
---
> Draft: recommended settings pending review by the voicebot team.

## When to use

Any bot with an escalation path: the caller asks for a person, the bot cannot help, or a rule says a human must
decide (complaints, payments above a limit, medical questions).

## How a transfer works

1. The bot says an explicit transfer sentence, such as "I am transferring you to an agent now, please hold."
2. The platform recognises that sentence as a transfer and ends the bot's part of the call. A question such as
   "Would you like me to transfer you?" is not a transfer; the bot waits for the answer.
3. The `pre_agent_transfer` webhook posts the call context to your URL.
4. The call returns to its Exotel call flow, which connects the caller to agents. The flow needs a step after the
   Voicebot applet that checks the bot's outcome and a Connect applet to your agents; set it up in the console or
   with Exotel support.

## Recommended configuration

| Area | Setting | Why |
|---|---|---|
| Trigger | Instruction lists when to transfer and the exact transfer sentence | The sentence is what starts the transfer |
| Webhook | `webhook_config.pre_agent_transfer.url` | Your CRM or agent desktop gets the context before pickup |
| Summary for the agent | `pre_agent_transfer.include_insights: true`, with a live insights assistant set on the bot (`insights_assistant_id`) | Adds summary, sentiment, intent and entities to the same POST as `events[]` |
| Wait | Expect about 1 to 1.5 s extra before the transfer with `include_insights` (up to about 8 s) | Insights are generated before the POST |
| Caller experience | Transfer sentence ends with "please hold"; hold music or ringback on the Connect applet | Avoids dead air during the handoff |
| Post-call | Intent `transferred` with its reason | Shows why callers needed a human |

## Starter template

`exotel_voicebot_update_config` data:

```json
{
  "insights_assistant_id": "INSIGHTS_ASSISTANT_ID",
  "conversation_assistant_id": "INSIGHTS_ASSISTANT_ID",
  "webhook_config": {"pre_agent_transfer": {"url": "https://crm.example.com/voicebot/transfer",
    "include_insights": true}},
  "post_session_insights": {"input_type": "text",
    "intents": {"transferred": "Caller was handed to an agent", "resolved_by_bot": "Bot resolved the query"}}
}
```

The live insights assistant itself is created in the console. Its ids show only in `exotel_voicebot_get_details`;
check them there after the update.

Instruction outline:
- "Transfer when: the caller asks for a person twice; you cannot answer after one try; the topic is one of TOPICS."
- "Before transferring, collect the caller's name and reason in one question, so the agent has them."
- "To transfer, say exactly: I am transferring you to an agent now, please hold. Say nothing after it."
- "If the caller only hints at an agent, ask once whether they want one; transfer only on yes."

## Payload notes

- Live-insight entities arrive only inside `events[]`, in the item whose type is `entities`.
- The top-level `entities` and `session_constants` are the values from call start, not from the conversation.
- `events` can be missing when insights do not finish in time; your receiver must still accept the transfer.

## Known problems

| Symptom | Fix |
|---|---|
| Bot says it will transfer but the call ends | The flow has no Connect step after the Voicebot applet |
| Bot asks "shall I transfer?" and nothing happens | Expected: the bot waits for yes, then says the transfer sentence |
| `events` empty on every transfer | Check `include_insights` and `insights_assistant_id` in the live version, read with `exotel_voicebot_get_details` |
| Long silence before the agent rings | Use a fast model for the insights assistant, keep the webhook fast, and play hold audio |

## Test checklist

- Ask for an agent: transfer sentence, then the agent leg rings.
- Hint vaguely at an agent: one clarifying question, no transfer.
- Check your receiver got the POST with a summary in `events[]`.
- Confirm the `transferred` intent on the conversation.
