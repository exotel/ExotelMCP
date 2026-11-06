---
topic: playbooks/number-id-capture
title: Capturing numbers, ids and dates
description: Use when the bot must collect phone numbers, OTPs, order or policy ids, amounts or dates spoken by the caller, and must not cut the caller off mid-number or store a wrong value.
tools: exotel_voicebot_catalog_vad_configs, exotel_voicebot_get_details, exotel_voicebot_update_config, exotel_voicebot_conversations_list
---
> Draft: recommended settings pending review by the voicebot team.

## When to use

Any flow where one wrong digit breaks the outcome: callback numbers, OTP checks, account or order ids, dates of
birth, appointment dates, amounts.

## Recommended configuration

| Area | Setting | Why |
|---|---|---|
| Turn end | Turn-taking on for the phone channel (`audio_processing.pstn.turn_taking_config.enable: true`) and `user_speech_timeout_seconds`: start with 1.5 to 2.0 | Without turn-taking a fixed 0.6 s pause ends the turn, which splits a number read in groups. The timeout (0.6 to 2.0) is the most the bot waits after the caller stops |
| VAD | A platform preset from `exotel_voicebot_catalog_vad_configs`, sent with the preset's own `provider_id` | A custom VAD with confidence or minimum volume of 1 never detects speech, so every turn waits the full timeout |
| Interruption | `enable_interruption: true`; `interruption_strategy.min_words`: start with 2 | Caller can correct a read-back, but a lone "yes" or "hmm" does not cut the bot off |
| Nudges | `idle_timeout_seconds`: start with 10 to 12, with a patient `idle_gentle` prompt | Callers stop to look up an id |
| ASR | An ASR model whose languages include every bot language | Digits in the caller's language are transcribed correctly |
| Privacy | `pii_masking_config: {"enabled": true}` for OTPs and personal ids | Masks personal data in stored transcripts |

## Starter template

Read `audio_processing` first with `exotel_voicebot_get_details`, merge the change below into it, and send the whole block.
`exotel_voicebot_update_config` data:

```json
{
  "audio_processing": {"pstn": {"turn_taking_config": {"enable": true}}},
  "user_speech_timeout_seconds": 1.8,
  "enable_interruption": true,
  "interruption_strategy": {"min_words": 2},
  "nudge_config": {"enabled": true, "idle_timeout_seconds": 12,
    "prompts": {"idle_gentle": "The caller may be finding the number. Tell them to take their time."}},
  "pii_masking_config": {"enabled": true},
  "assistant_config": {"instruction": "INSTRUCTION"}
}
```

`nudge_config` is replaced whole: include every key you want to keep.

Instruction outline (capture rules):
1. Ask for one item at a time and say its format: "your 10-digit mobile number".
2. Convert spoken forms to digits: "double five" is 55, "oh" is 0, "triple nine" is 999.
3. Check the length or pattern. If wrong, say what is missing ("I got 9 digits") and ask again.
4. Read back in groups with commas, for example "98765, 43210", then ask "Is that right?".
5. On "no", ask only for the wrong part. After two failed tries, offer another route (agent or SMS).
6. Dates: turn "next Monday" or "fifth March" into a full date and read it back with the weekday.
7. Never read an OTP back in full; confirm only that it was received.

## Known problems

| Symptom | Fix |
|---|---|
| Bot replies after the first few digits | Turn-taking off, or timeout too short; apply the settings above |
| Long pause after every caller turn | Check the VAD values; use a platform preset |
| VAD change rejected with "does not belong to provider" | Send the preset's own `provider_id` with its `config_id` |
| Audio settings seem ignored | A specialization attached to the bot can override `audio_processing` and VAD; read `specializations` in `exotel_voicebot_get_details` |
| Dates come back as words ("twenty october") | Tell the model explicitly to convert spoken numbers and dates to digits |
| Read-back sounds like one long number | Write digits in groups separated by commas in the reply |

## Test checklist

- Say a 10-digit number in two groups with a 1 s pause: no interruption between groups.
- Give a wrong digit, then correct it at read-back.
- Say "yes" while the bot reads back: the bot must not stop mid-sentence.
- Stay silent for 10 s: one gentle nudge, no hang-up.
- Check stored values in the transcript with `exotel_voicebot_conversations_list`.
