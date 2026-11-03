---
name: exotel-call-records
description: Find, filter and export Exotel call records and recordings - list calls by date, status, direction or number, get one call with per-leg details, download recordings (single or dual channel), keep them past retention, and send them to quality analysis. Use when the user asks for call logs, call reports, CDRs, how many calls failed, a recording, or wants to back up or analyse recordings.
---

# Exotel call records and recordings

Voice v1 Calls API (`/v1/Accounts/{sid}/Calls`). All read-only except where noted.

## Find calls

```bash
exotel calls list --since 2026-11-01 --until 2026-11-03 --status failed
exotel calls list --since 2026-11-03 --from +919876543210 --page-size 100
exotel calls list --direction inbound --exophone 08047XXXXXX --since 2026-11-01
exotel calls get --call-sid <CallSid> --details        # --details adds per-leg information
```

| Filter | Flag | Notes |
|--------|------|-------|
| Date range | `--since`, `--until` (`YYYY-MM-DD [HH:MM:SS]`) | The API needs both ends; the CLI fills the missing one (until now / 30 days back). Up to **6 months** of history, **1 month per request** |
| Status | `--status` | `completed`, `failed`, `busy`, `no-answer` |
| Direction | `--direction` | `inbound`, `outbound-dial`, `outbound-api` |
| Numbers | `--from`, `--to`, `--exophone` | Up to 5 comma-separated each |
| Paging | `--page-size` (max 100), `--after <cursor>` | The CLI prints the next `--after` cursor |
| Pre-signed recording links | `--recording-url-validity 5..60` | Minutes the returned `RecordingUrl` stays valid |

Raw API for anything else: `exotel api GET /v1/Accounts/{sid}/Calls.json --query 'Duration=gte:30s&Price=gte:0.5'`.

## Recordings

- Turn recording on per call (`Record=true`, `RecordingChannels=single|dual` on the Connect API —
  `exotel voice-ai connect … --record --recording-channels dual`), per flow (Connect / Voicebot applet
  "record"), or account-wide (Exotel account manager).
- Download: `exotel calls recording --call-sid <sid> [--out call.mp3]`. URLs are time-limited and retention
  depends on the plan (typically 30–90 days): **copy recordings to your own storage** if you need them longer.
- Dual channel puts each party on its own channel: better for transcription, QA and speaker separation.
- Legs API calls record with ExoML `<StartRecording …/>`; the URL arrives in the `recording_available` event
  (see `exotel-call-control`).

## Reports and analysis

- Failure breakdown for a day: `exotel calls list --since <day> --until <day> --status failed --json` then
  group by `To` / `PhoneNumberSid`; explain any status with `exotel explain <status>`.
- VoiceBot calls: `exotel voicebot transcript --call-sid <sid>` / `exotel voicebot debug --call-sid <sid>`.
- Quality scoring: send the recording URL to Conversational Intelligence (`exotel-conversation-quality`).

## Rules

- Recordings and numbers are personal data: store them securely, honour retention and consent rules, and do
  not paste recording URLs into public places.
