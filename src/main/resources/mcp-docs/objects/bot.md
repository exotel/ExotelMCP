---
topic: objects/bot
title: Bot fields and versions
description: Which top-level bot fields you can set and their allowed values, how a new bot version is built from a source version, and which read shows which field.
tools: exotel_voicebot_bot_create, exotel_voicebot_update_config, exotel_voicebot_get_details, exotel_voicebot_bot_versions_list, exotel_voicebot_bot_version_promote, exotel_voicebot_bot_traffic_set
---

# Bot fields and versions

`exotel_voicebot_bot_create` takes the whole bot; `exotel_voicebot_update_config` takes only the keys to
change. Both accept the keys below and refuse any other key, because the API would drop it silently.

## Top-level fields

| Field | Type, allowed values | Notes |
|---|---|---|
| `name` (required) | string, at most 50 characters; no quotes, backticks, semicolons, backslashes, braces or angle brackets | `null` in an update is ignored |
| `supported_languages` (required) | lowercase 2-3 letter codes, e.g. `["hi","en"]` | Each must be offered by the TTS provider. See `objects/voice` |
| `assistant_config` (required) | object, below | Which assistant version the bot runs |
| `voice_config`, `asr_config`, `vad_config`, `audio_processing`, `audio_logging` | objects | See `objects/voice`. No `voice_config` = platform default voice |
| `greeting_message` | `{"text": "Hi {customer_name}", "enabled": true}` | `enabled` defaults to **false** when omitted. An update may send only `text` or only `enabled` |
| `greeting_message_mode` | `bot_speaks_first` (default), `bot_waits_for_user`, `bot_speaks_first_with_llm_generated_message` | |
| `enable_greeting_interruption` | boolean, default `false` | Caller may talk over the greeting |
| `enable_interruption` | boolean, default `true` | Caller may talk over the bot |
| `interruption_strategy` | `{"min_words": 1-8, "use_interim": true}` | Words needed to stop the bot. Needs transcripts, so it acts on text-input (ASR) bots |
| `user_speech_timeout_seconds` | number 0.6-2.0 | Pause after the caller stops before the turn ends. Unset: 2.0 s with turn-taking on in `audio_processing`, else 0.6 s |
| `enable_call_threshold` | boolean, default `false` | Turns the call-length limit on |
| `call_duration_threshold_sec` | integer, default 14400 | Ignored unless `enable_call_threshold` is true |
| `max_duration_message` | string, at most 1000 characters | Spoken once before that hang-up; blank = silent |
| `filler_config` | `{"enabled": true, "type": "static", "trigger_delay_ms": 600, "phrases": ["One moment."]}` | `type` `static` or `llm`; delay 0-10000 ms; `phrases` required for `static` |
| `nudge_config` | object, below | Replaced whole on update |
| `pii_masking_config` | `{"enabled": true, "model_selection": "SYSTEM_DEFAULT"}` | `SYSTEM_DEFAULT`, `ASSISTANT_MODEL`, or `CUSTOM` plus `llm_config_id` from `exotel_voicebot_catalog_llm_models`. `customer_prompt` at most 2000 characters |
| `webhook_config`, `post_session_insights`, `conversation_assistant_id`, `insights_assistant_id` | objects, ids | See `objects/webhooks-insights` |
| `repeated_calls` | `{"enabled": true, "lookback_period_min": 1440}` | Puts the caller's earlier calls in that window into webhook metadata |
| `specializations` | `[{"id": "SPECIALIZATION_ID", "type": "TYPE", "priority": 1.0}]` | One per type, priority 0.0-1.0. Their overrides apply after the bot's voice, ASR, VAD and audio settings and win |
| `variants` | map of variant key to overrides | Replaced whole; `null` removes all |

Accepted but do not send: `low_latency_bot` (recomputed on every read), `bot_gen` (always `gen2`),
`stt_config` (legacy; use `asr_config`), `cues`, `bot_config`. Set by the API and refused: `id`, `bot_id`,
`account_id`, `account_type`, `active`, `status`, `version`, `date_created`, `date_updated`, `frozen`,
`frozen_at`, `frozen_by`, `frozen_by_name`, `ccm_flow_id`.

### assistant_config

```json
{"id": "ASSISTANT_ID", "version": "stable",
 "input":  {"modality": "audio", "mime_type": "audio/wav"},
 "output": {"modality": "text",  "mime_type": "text/plain"},
 "enable_turn_cancellation": true}
```

| Key | Values | Notes |
|---|---|---|
| `id` | ASSISTANT_ID | From `exotel_voicebot_assistant_create` |
| `version` | `stable` (default), `latest`, `vN` | `stable` follows the assistant, so a promote on another bot sharing it changes this bot too. `vN` pins |
| `input` | `modality` `audio` or `text`; `mime_type` `audio/wav`, `text/plain`, `application/json` | Required (HTTP 500 without it). `audio`: the LLM hears and transcribes. `text`: an ASR model transcribes and `asr_config` is required |
| `output` | `{"modality": "text", "mime_type": "text/plain"}` | |
| `enable_turn_cancellation` | boolean, default `true` | A new caller utterance cancels the reply in progress |

In an update `assistant_config` is deep-merged; `null` for `id` or `version` is ignored. Any of
`instruction`, `llm_config`, `fallback_llm_config`, `intent_detection_config`, `agents`, `mcp_servers`,
`persona_id`, `name`, `description` inside it creates a new (not stable) assistant version pinned to this
bot version. See `objects/assistant`.

### nudge_config

```json
{"active": true, "idle_timeout_seconds": 7, "max_consecutive_intent_nudges": 1,
 "prompts": {"idle_gentle": "Are you still there?", "idle_direct": "...", "idle_end_call": "...",
             "dead_turn_recovery": "...", "intent_follow_up": "..."}}
```

`active` switches idle nudges on or off. `idle_timeout_seconds` 1-60 (default 7).
`max_consecutive_intent_nudges` 0 or more (default 1). Prompts at most 500 characters each; leave one out
for the platform default, never send `""`. An update replaces the whole object.

## How a version is built

`exotel_voicebot_update_config` creates the next version = `sourceVersion` + `dataJson`.

| Rule | Detail |
|---|---|
| Source | `latest` (default), `stable` or `vN`. Every field you do not send comes from it, so an older source silently loses later changes (webhooks, greeting). Use `latest` unless the user wants a rollback |
| `null` | Clears the key; ignored for `name`, `assistant_config`, `voice_config`, `asr_config` |
| Replaced whole | `filler_config`, `nudge_config`, `pii_masking_config`, `interruption_strategy`, `repeated_calls`, `specializations`, `variants`, `supported_languages`, `vad_config`, `audio_processing`, `audio_logging` |
| Deep-merged | `greeting_message`, `assistant_config`, nested `voice_config`/`asr_config`, `webhook_config` (per endpoint and key), `post_session_insights` (per key; `intents` per name) |
| New version | Not stable, 0% traffic, and immutable: fix a bad one with another version |
| Cap | 100 active versions per bot by default. Archiving is not in these tools; the user archives old versions in the console |

`exotel_voicebot_bot_create` makes v1 the stable version at once.

## Going live

| Goal | Tool | Effect |
|---|---|---|
| One version takes every call | `exotel_voicebot_bot_version_promote` | Stable with 100% traffic. Its pinned assistant version becomes that assistant's stable version, changing other bots that use the assistant at `stable` |
| Canary or A/B | `exotel_voicebot_bot_traffic_set` | `{"v2": 90, "v3": 10}`, whole numbers summing to 100; unlisted versions get 0%. Stable does not change |
| Roll back | promote the previous version | |

Both return a preview and change nothing until called again with `confirm=true` after the user agrees.
Calls follow `traffic_allocation`, not `stable_version`; check both in `exotel_voicebot_bot_versions_list`.
A frozen bot refuses changes (HTTP 423); an inactive one answers 422.

## Which read shows which field

| Read | Shows | Missing |
|---|---|---|
| `exotel_voicebot_get_details(botId)` | The **stable** version, every stored field, including `assistant_config.input`/`output`, `user_speech_timeout_seconds`, `interruption_strategy`, both insights assistant ids | Drafts |
| `exotel_voicebot_get_details(botId, version="vN")` | That version's status, traffic, `assistant_version`, and `config` with the other fields (`specializations` sit under `bot_config`) plus the resolved assistant | `user_speech_timeout_seconds`, `interruption_strategy`, both insights assistant ids, `repeated_calls`, `audio_logging`, `variants`, `assistant_config.input`/`output` |
| `exotel_voicebot_bot_versions_list` | `stable_version`, `traffic_allocation`, per-version status and traffic | Config. `config_diff_fields` misses changes; not proof |

After every write, read the version back and compare each field you sent: a 2xx does not prove storage.
Do not re-send a field only because one read lacks it. Fields missing from the version read appear in
`exotel_voicebot_get_details(botId)` once that version is stable. Secrets come back redacted.
