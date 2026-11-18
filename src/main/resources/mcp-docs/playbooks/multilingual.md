---
topic: playbooks/multilingual
title: Indian-language and multilingual bots
description: Use when a bot must speak Hindi or another Indian language, or switch between languages (for example Hindi and English) within a call, and you need the right languages, TTS voice and ASR model.
tools: exotel_voicebot_tts_list_providers, exotel_voicebot_tts_list_voices, exotel_voicebot_catalog_asr_models, exotel_voicebot_persona_create, exotel_voicebot_bot_create, exotel_voicebot_update_config, exotel_voicebot_conversations_list
---
> Draft: recommended settings pending review by the voicebot team.

## When to use

Callers speak Hindi, Tamil, Telugu, Kannada, Marathi, Bengali, Gujarati or another Indian language, often mixed
with English.

## Recommended configuration

| Area | Setting | Why |
|---|---|---|
| Languages | `supported_languages` with the main language first, for example `["hi", "en"]` | Each code must be offered by the chosen TTS provider, or the save fails |
| TTS provider | One whose catalogue lists every bot language (`exotel_voicebot_tts_list_providers`) | The language list is checked against the provider |
| TTS voice | A voice of that same provider made for the main language (`exotel_voicebot_tts_list_voices`) | An English voice reading Indian-language text sounds broken |
| TTS language | `voice_config.model.initialization_params.language`, for example `["hi"]` | Pins the spoken language; without it some voice models fall back to a default language |
| ASR | A model from `exotel_voicebot_catalog_asr_models` that lists every bot language | With no overlap, the ASR silently listens in its own default language |
| Audio-in option | `assistant_config.input.modality: "audio"` with an audio-capable LLM | The LLM transcribes the caller itself; costs a second LLM call per turn, so slower |
| LLM | A model with strong Indian-language output; check the catalogue | Answer quality in the caller's language |
| Persona | A persona with a `languagePrompt` (`exotel_voicebot_persona_create`) | Keeps tone and language rules out of each agent |
| Intent detection | `intent_detection_config.language`: start with `"english"` | Supported values are `english` and `arabic`; test that goodbyes and transfer lines in your language still end or transfer the call |

## Starter template

On create (`exotel_voicebot_bot_create`) or later in `exotel_voicebot_update_config` data:

```json
{
  "supported_languages": ["hi", "en"],
  "voice_config": {"provider": {"id": "TTS_PROVIDER_ID"},
    "model": {"id": "TTS_MODEL_ID", "initialization_params": {"language": ["hi"]}},
    "voice": {"id": "VOICE_ID"}},
  "asr_config": {"provider": {"id": "ASR_PROVIDER_ID"}, "model": {"id": "ASR_MODEL_ID"}},
  "greeting_message": {"text": "GREETING_IN_MAIN_LANGUAGE", "enabled": true}
}
```

Send the full `voice_config` with `provider`, `model` and `voice` when you change voices; a partial one keeps the
old provider.

Instruction outline:
- "Reply in the language the caller last spoke. If unclear, use Hindi."
- "Use simple everyday Hindi; keep common English words (order, payment, OTP) in English, as callers say them."
- "Write Hindi in Devanagari script; never in Latin letters."
- "Say numbers as digits grouped with commas; say amounts as callers say them."

## Language switching

The voice speaks one configured language well. A caller who switches language mid-call gets replies in that
language only if the voice and its model can speak it; test each switch you promise. Offer only languages that
both the TTS voice and the ASR model support.

## Known problems

| Symptom | Fix |
|---|---|
| Bot set to Tamil speaks English | Set `voice_config.model.initialization_params.language` to `["ta"]` |
| Voice pauses at every comma or word | The voice is the wrong language for the text; pick a voice for that language |
| Transcripts are English or nonsense | The ASR model lacks the bot's language; pick one that lists it |
| 400 on `supported_languages` | The TTS provider does not offer that code; choose another provider |
| A voice is missing from the list | Take the voice from the same provider you send; lists are paged |
| A language is listed but sounds wrong | Check the vendor's own language list for that exact model before promising it |

## Test checklist

- First sentence of a test call is in the main language with natural pronunciation.
- Speak in English mid-call; check what the bot does matches what you promised.
- Say a 10-digit number and an amount in the main language: correct transcript.
- Say goodbye in the main language: the call ends.
- Read the transcript with `exotel_voicebot_conversations_list`.
