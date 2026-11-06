---
topic: playbooks/low-latency
title: Reducing response time
description: Use when callers wait too long for the bot (slow greeting, long pauses after they speak, silence after tool calls) or when a use case needs the fastest possible replies.
tools: exotel_voicebot_assistant_get_config, exotel_voicebot_catalog_llm_models, exotel_voicebot_update_config, exotel_voicebot_bot_traffic_set, exotel_voicebot_conversations_list
---
> Draft: recommended settings pending review by the voicebot team.

## Where the time goes

Each reply is: end-of-speech detection, then transcription, then the LLM (plus any tool calls), then speech
synthesis. Fix the largest part first: read a few test transcripts, and the current model with
`exotel_voicebot_assistant_get_config`, before changing anything.

## Recommended configuration

| Area | Setting | Why |
|---|---|---|
| LLM | The fastest model in `exotel_voicebot_catalog_llm_models` that passes your test calls; set `fallback_llm_config` | The LLM is usually the largest part of each reply |
| Prompt | Lean instruction; short sections; no repeated rules; replies limited to one or two sentences | Smaller prompts start faster and short replies finish speaking sooner |
| Input | `assistant_config.input.modality: "text"` (ASR) | Audio input makes the LLM transcribe each turn first: two LLM calls per turn |
| Greeting | `greeting_message_mode: bot_speaks_first` with a fixed text | `bot_speaks_first_with_llm_generated_message` waits for an LLM call before the first word |
| Turn end | Without turn-taking a fixed 0.6 s pause ends the turn. With turn-taking on, `user_speech_timeout_seconds` (0.6 to 2.0, 2.0 if unset) is the longest wait: start with 1.0 to 1.2 for short-answer bots | The default 2.0 s adds up to 2 s on each turn |
| VAD | A platform preset such as `low_latency_default`, with its own `provider_id` | A custom VAD with confidence or minimum volume of 1 never fires, so every turn waits the full timeout |
| Fillers | `filler_config` static, `trigger_delay_ms` 600 | A filler covers tool and slow-LLM turns; static phrases need no generation |
| Tools | Backends that answer in well under a second; fewer knowledge-base chunks (`similarityTopK` 3 to 5) | Tool time adds directly to the reply, and a slow tool delays the turns after it |
| Call start | `session_start` webhook under about 2.5 s; move other lookups into tools | It runs before the greeting, and a slow one fails the call |
| Do not set | `low_latency_bot` | The platform computes this tag from the account's criteria |

## Starter template

`exotel_voicebot_update_config` data:

```json
{
  "greeting_message_mode": "bot_speaks_first",
  "greeting_message": {"text": "Hi, this is Asha from Acme. How can I help?", "enabled": true},
  "user_speech_timeout_seconds": 1.2,
  "vad_config": {"provider_id": "VAD_PROVIDER_ID", "config_id": "VAD_PRESET_ID"},
  "filler_config": {"enabled": true, "type": "static", "trigger_delay_ms": 600,
    "phrases": ["One moment.", "Let me check."]},
  "assistant_config": {"llm_config": {"id": "LLM_MODEL_ID"},
    "fallback_llm_config": {"id": "FALLBACK_LLM_MODEL_ID"}}
}
```

Prompt style by model family (the `llm_config` you pick):
- Gemini Flash: numbered imperative rules, one per line, "Do NOT" for negatives, no repeated rules.
- Gemma: short lines (about 15 words), decision tables, sections under about 500 tokens, "Respond in 1-2 sentences".
- GPT-4.1 mini: exhaustive explicit rules, a catch-all default, "NEVER output markdown or JSON".
- Qwen: role first, `##` sections, plain speech only, thinking mode off.

Change one thing per version. Split traffic with `exotel_voicebot_bot_traffic_set` only with the user's yes.

## Known problems

| Symptom | Fix |
|---|---|
| Dozens of cancelled turns, caller says "Hello?", bot repeats an apology | The LLM is slower than the caller's patience: faster model, leaner prompt, `fallback_llm_config` |
| Long pause after every caller turn, LLM times normal | Check the VAD values and the speech timeout first |
| Silence after a tool call, then a late answer | Speed up the tool; the next turn waits until it finishes |
| Slow greeting only on browser test calls | Browser calls add a short silence before the greeting; measure on a phone call |

## Test checklist

- Phone test call: first word of the greeting within about a second of answering.
- Short answers ("yes", "no") get a reply without a noticeable pause.
- A tool turn plays a filler, then the answer.
- Compare turn timings in transcripts (`exotel_voicebot_conversations_list`) before and after each change.
