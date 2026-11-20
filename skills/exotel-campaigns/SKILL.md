---
name: exotel-campaigns
description: Run outbound call campaigns on Exotel - call a list of numbers through an IVR/voice-bot flow or a text greeting, with retries, scheduling, throttling, contact lists and webhooks; pause, resume, complete and report on them. Use when the user wants bulk/auto-dial calls, reminders, collections or survey calls, an outbound voice-bot campaign, or campaign results.
---

# Exotel outbound call campaigns

API: `/v2/accounts/{sid}/campaigns` (contacts in `/lists` and `/contacts`). Every campaign calls real people
and is billed: show the user the numbers (count + a sample), caller ID, content and schedule, and get a yes.

## Before you start

- **Consent and rules:** call only numbers that agreed to be contacted; respect DND and calling-hour rules.
  Transactional campaigns are the default type.
- A caller ID (ExoPhone) and what to play: an App Bazaar flow (`--app-id`, e.g. a Voicebot applet for an AI
  agent, or an IVR) or a text greeting (`--text`, supports `@@ColumnName` variables from a contact list).
- Limits: **5,000 numbers per campaign**, up to **3 retries**, about **60 calls/minute** default capacity,
  30-day default duration.

## Create

```bash
# numbers inline, through a flow (IVR or voice bot), retry busy/no-answer twice, scheduled
exotel campaigns create --name "Nov reminders" --caller-id <ExoPhone> \
  --numbers +9198XXXXXXX1,+9198XXXXXXX2 --app-id <flow app id> \
  --retries 2 --retry-on busy,no-answer --retry-interval 15 \
  --send-at 2026-11-04T10:00:00+05:30 --end-at 2026-11-04T18:00:00+05:30 \
  --call-status-callback https://your-host/call-status --status-callback https://your-host/campaign-done --dry-run

# from contact lists, with a greeting that uses list columns
exotel campaigns lists                          # find list SIDs
exotel campaigns create --caller-id <ExoPhone> --lists <list_sid> --text "Hi @@first_name, your appointment is tomorrow" --dry-run
```

Show the dry run, then run it without `--dry-run` (it asks to confirm; `--yes` in scripts after the user agreed).
Optional: `--throttle <calls/min>` (custom pacing), `--custom-field` (your metadata),
`--call-schedule-callback` (after all retries for a number).

The live API expects the body as `{"campaigns": [ {…} ]}`; the CLI does this (the developer portal shows a flat
body, which the API rejects with "Campaigns is mandatory").

## Manage and report

```bash
exotel campaigns list [--status InProgress,Paused]
exotel campaigns get --campaign-sid <id>
exotel campaigns calls --campaign-sid <id> [--status failed]
exotel campaigns pause|resume --campaign-sid <id>
exotel campaigns complete --campaign-sid <id>     # stops it; remaining calls marked failed
exotel campaigns archive|delete --campaign-sid <id>
```

`pause` works from Created/InProgress, `resume` from Paused, `complete` from Paused, `archive` from Completed.

## Webhooks

| Setting | Fires | Payload |
|---------|-------|---------|
| `--call-status-callback` | Each call attempt | `campaign_sid`, `call_sid`, `number`, `status` (completed/failed/busy/no-answer) |
| `--call-schedule-callback` | All retries for a number done | `campaign_sid`, `to`, `from`, `call_sids[]`, final `status` |
| `--status-callback` | Campaign finished | `campaign_sid`, `status` (Completed/Failed/Canceled), `report_url` (CSV) |

Use the schedule callback for follow-ups (e.g. SMS to unreachable numbers, see `exotel-messaging-and-calls`).

## AI-agent campaigns

Point `--app-id` at a flow whose Voicebot applet streams to the bot (`exotel voice-ai setup …`), or, without a
flow, loop over numbers with `exotel voice-ai connect` (Connect Voice AI) under your own pacing.
