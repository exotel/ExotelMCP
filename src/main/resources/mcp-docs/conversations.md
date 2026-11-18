---
topic: conversations
title: Reading conversations
description: How to list a bot's calls, read transcripts and insights without flooding your context, and answer "how is my bot doing".
tools: exotel_voicebot_conversations_list, exotel_voicebot_call_status
---
## exotel_voicebot_conversations_list

| Parameter | Meaning |
|---|---|
| `botId` | Only this bot. Omit for the whole account |
| `startDate`, `endDate` | `YYYY-MM-DD` (a **UTC** day: start 00:00:00.000Z, end 23:59:59.999Z) or an ISO-8601 instant such as `2026-11-05T09:00:00Z`. At most 90 days back |
| `outcome` | Only one outcome value. Copy it from rows you already have |
| `offset`, `limit` | Paging. `limit` default 20, max 100 |
| `fields` | Omit for metadata only; `transcript`, `insights`, or `transcript,insights` |

**Result:** `ok`, `total`, `count`, `offset`, `limit`, `next_offset` (null on the last page), `rows`, `warnings`.

**Row keys:** `conversation_id`, `session_id`, `call_sid`, `external_id`, `bot_id`, `bot_name`, `bot_version`, `assistant_version`, `start_time`, `end_time`, `call_duration`, `outcome`, `disconnected_by`, `network_type`, plus `transcript` and/or `insights` when asked. Caller numbers, recording URLs and custom parameters are left out.

**Insights** give summary, sentiment, outcome and intents. Entities and query resolution are not returned by any read, and live (mid-call) insights reach the customer only through the bot's `insights_events` webhook. Say a field is unavailable rather than guessing it from the transcript.

## Dates

- Dates are UTC. For an IST day, shift by 5 h 30 min: IST 2026-11-05 is `startDate=2026-11-04T18:30:00Z`, `endDate=2026-11-05T18:29:59Z`.
- `endDate` filters on the call's **end** time. Calls still in progress, or never closed, are not returned. To cover a window by start time, set `endDate` a day later and filter rows on `start_time` yourself.
- Always set both dates. Without them the query spans the account's whole history.

## Cost

| Request | Approximate size |
|---|---|
| 100 rows, metadata only | 110 KB |
| 1 row with `transcript` | 16 KB |
| 100 rows with `transcript` | 1.6 MB |

A response over 5 MB fails with `RESPONSE_TOO_LARGE`: lower `limit` or drop `fields`. Ask for transcripts only for rows you will read, with `limit` 20 or less.

## Paging safely

- Rows come newest first, by offset. A call that arrives while you page shifts later pages, so you can see a row twice or miss one.
- End the window in the past, dedupe on `conversation_id`, and check that the unique count equals `total`.
- Follow `next_offset` until it is null. Page slowly. On 429 `RATE_LIMIT_EXCEEDED`, wait 60 s before retrying; never poll in a tight loop.

## Answering "how is my bot doing"

1. **Count.** Metadata only, `limit=100`, the agreed window, paged to the end. Tally `total`, `outcome`, `disconnected_by`, `call_duration` buckets and `bot_version`.
2. **Narrow.** Pick the buckets that matter: a bad outcome, very short calls, one version after a change.
3. **Sample.** Fetch `fields=transcript,insights` for 10–20 calls per bucket. A sample is enough for a rate; say it is a sample.
4. **Summarise per page.** Reduce each page to the facts you need (turn count, where it went wrong, one short quote) before fetching the next.
5. **Mask.** Transcripts hold what callers said. Mask phone numbers to the last four digits, drop names, addresses and long digit runs, and quote only what the user asked for.
6. **Report** the window in UTC (and the user's time zone), counts in a table, the sample size, and the fields you could not read.

To compare before and after a change, split rows on `bot_version` rather than on dates: during a canary both versions take calls on the same day.

## Empty transcripts

An empty transcript on a call with a real duration usually means personal-data masking has not finished. Retry later before you conclude the call was silent.

## exotel_voicebot_call_status

`exotel_voicebot_call_status(callSid)` reads the telephony leg from the Exotel Calls API: status, duration, timestamps and the recording URL. Use it right after `exotel_voicebot_place_call`, or to check a call that has no conversation row. Treat the recording URL as personal data: do not repeat it unless asked. The conversation row's `call_sid` joins the two.

## Not available yet

- No tool returns one call's full trace: per-turn timings, tool-call requests and responses, or the recording audio. Work from the transcript, insights and `call_status`, and tell the user what is missing.
- No filter by version, duration, turn count, language, tool or transcript text, and no aggregate endpoint: counts come from paging metadata.
- To find one call, narrow the window around its time and match `call_sid`.
