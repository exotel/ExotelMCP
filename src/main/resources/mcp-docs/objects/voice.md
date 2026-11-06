---
topic: objects/voice
title: Voice, ASR, VAD and audio
description: How to set the bot's TTS voice, speech-to-text model, VAD preset, denoiser and turn-taking, which catalog tool gives each id, and how the bot's languages reach TTS and ASR.
tools: exotel_voicebot_tts_list_providers, exotel_voicebot_tts_list_voices, exotel_voicebot_catalog_asr_models, exotel_voicebot_catalog_vad_configs, exotel_voicebot_bot_create, exotel_voicebot_update_config, exotel_voicebot_get_details
---

# Voice, ASR, VAD and audio

All of these are bot fields. Set them in `exotel_voicebot_bot_create` or in a version through
`exotel_voicebot_update_config`, then read them back with `exotel_voicebot_get_details`.

## Where each id comes from

| Field | Tool | What to copy | Paging |
|---|---|---|---|
| `voice_config.provider.id` | `exotel_voicebot_tts_list_providers` | provider `id`; its `supported_languages[].code` | Returns only the 10 newest providers. If `metadata.total` is larger, the one you need may be missing: copy it from a bot that uses it |
| `voice_config.voice.id` | `exotel_voicebot_tts_list_voices(providerId, limit)` | voice `id` (with `name`, `labels`) | Default limit 100; compare with `metadata.total` |
| `voice_config.model.id` | none | Copy `voice_config.model.id` from `exotel_voicebot_get_details` of a bot in the same account that uses that provider, or ask the user | |
| `asr_config` ids | `exotel_voicebot_catalog_asr_models` | row `provider_id` and `id` | Reads up to 100 providers; stops after 60 and warns. Pass `providerId` to narrow |
| `vad_config` ids | `exotel_voicebot_catalog_vad_configs` | row `provider_id` and `id` | Up to 100 active presets, the account's and the shared ones |

Ids differ between environments. Never reuse an id from another environment or from an example.

## voice_config (TTS)

Two shapes exist. Send the nested shape.

| Shape | JSON | Where |
|---|---|---|
| Nested (send this) | `{"provider": {"id": "TTS_PROVIDER_ID"}, "model": {"id": "TTS_MODEL_ID", "initialization_params": {}}, "voice": {"id": "VOICE_ID"}}` | What `exotel_voicebot_bot_create` accepts and `exotel_voicebot_get_details` returns |
| Flat | `{"model_id": "TTS_MODEL_ID", "voice_id": "VOICE_ID", "config": {}, "metadata": {"provider_name", "model_name", "voice_name"}}` | Shown by list views. `exotel_voicebot_bot_create` refuses it. In an update it **keeps the current provider**, so it can only change model or voice within that provider |

Rules:
- Model and voice must belong to the provider you send, or the save fails with "does not belong to provider".
- Without `voice_config` the bot gets the platform default provider, model and voice. A missing part
  (`model` or `voice`) is filled with the platform default too, which can then fail the provider check.
  Always send all three.
- To switch provider, send all three of `provider`, `model` and `voice` in one update.
- In an update, a `model` without `initialization_params` keeps the old params, even when the model
  changes. When you switch model, send `initialization_params` (an empty object clears them).
- `null` for `voice_config` or its `provider` is ignored; you cannot remove the voice.

## Languages

`supported_languages` takes lowercase 2-3 letter codes. Every code must be listed in the TTS provider's
`supported_languages`; with no `voice_config`, only `en` and `hi` are accepted. A language being listed does
not prove the vendor model speaks it well: confirm with a test call.

| Path | How the language is chosen | What to do |
|---|---|---|
| TTS | The model's catalog default language list wins over the bot's languages when the catalog sets one, and only the **first** code is sent. A bot set to Tamil can therefore speak English | Set the language on the bot's own model: `"model": {"id": "TTS_MODEL_ID", "initialization_params": {"language": ["ta"]}}`. These params apply last |
| TTS, Google Chirp3-HD voices | Some Indic languages are mapped to the English (India) locale, so the voice pauses at every comma | Also set the full regional voice name: `"initialization_params": {"language": ["mr-IN"], "voice_id": "mr-IN-Chirp3-HD-VOICE_NAME"}` |
| ASR | The bot's languages are intersected with the ASR model's languages. Base codes match regional ones (`hi` matches `hi-IN`). If nothing overlaps, the ASR silently uses its own default language, usually English | Pick an ASR model that lists every bot language |

## asr_config (speech-to-text)

```json
"asr_config": {"provider": {"id": "ASR_PROVIDER_ID"}, "model": {"id": "ASR_MODEL_ID"}}
```

| `assistant_config.input.modality` | Transcription | `asr_config` |
|---|---|---|
| `text` | An ASR model transcribes, the LLM gets text | Required. Without it the create fails with HTTP 500; the tool refuses first |
| `audio` | The LLM hears the audio and transcribes in a separate LLM call each turn. Needs an audio-capable LLM | Ignored and stored as `{}`. An empty ASR config on an audio-input bot is normal; do not "fix" it |

Send the nested shape. The flat `{"provider_id", "model_id"}` shape is refused by
`exotel_voicebot_bot_create`; in an update `{"model_id"}` alone keeps the current ASR provider. To move a bot
to an ASR, change `assistant_config.input` to `{"modality": "text", "mime_type": "text/plain"}` and send
`asr_config` in the same update.

## vad_config (end of speech detection)

```json
"vad_config": {"provider_id": "VAD_PROVIDER_ID", "config_id": "VAD_CONFIG_ID"}
```

- Take **both** ids from the same `exotel_voicebot_catalog_vad_configs` row. Shared platform presets have
  their own `provider_id`, different from the provider of account-made presets. Keeping the bot's old
  `provider_id` while swapping only `config_id` fails with "VAD config ... does not belong to provider ...".
- `overrides` (sparse parameter changes) is allowed only when the preset's `type` is `recommendation`.
- A preset whose confidence or minimum volume is 1 never detects end of speech, so every caller turn waits
  the full speech timeout. Prefer a shared preset; check the preset before blaming the LLM for slow turns.
- `null` removes the VAD config. The update replaces the whole object.

## audio_processing (denoiser and turn-taking)

Set per channel: `web` (browser calls) and `pstn` (phone calls).

```json
"audio_processing": {
  "pstn": {
    "denoiser_config":    {"enable": true, "model_config": {"initialization_params": {"enhancement_level": 0.5}}},
    "turn_taking_config": {"enable": true}
  }
}
```

| Key | Rules |
|---|---|
| `denoiser_config.enable` | boolean |
| `denoiser_config.provider` | Leave it out; the console never sends it and the platform picks one |
| `initialization_params.enhancement_level` | number 0.0-1.0 |
| licence or API keys in `initialization_params` | Refused. Never put credentials here |
| `turn_taking_config.enable` | Smart end-of-turn detection. Changes the default of `user_speech_timeout_seconds` (0.6-2.0) from 0.6 s to 2.0 s; set the field to choose your own |

The update replaces the whole `audio_processing` object: send both channels if both are set.
Specializations attached to the bot (`specializations`) apply their own audio, VAD, ASR and voice overrides
after the bot's values and win. If a setting seems to have no effect, read the bot's `specializations`.

## audio_logging

`{"enable": true, "log_raw": true, "log_denoised": false, "log_vad": false, "log_tts": false}` records the
chosen audio streams for debugging. Replaced whole on update; visible only in
`exotel_voicebot_get_details(botId)` without a version.

## After a voice change

Read back with `exotel_voicebot_get_details(botId, version="vN")` and check `voice_config`, `asr_config` and
`vad_config` hold the ids you sent. Then make a test call (with the user's yes) and listen to the first
sentence: wrong language, accent or a missing voice shows up there, not in the API response.
