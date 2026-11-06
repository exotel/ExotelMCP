---
name: exotel-call-debugging
description: Find out why an Exotel call, VoiceBot call or SMS failed or behaved badly - look up call details, status callbacks, number metadata, recordings and transcripts, and give a cause and a fix. Use when the user says a call did not connect, dropped, was silent, went to the wrong place, or a message was not delivered.
---

# Exotel call and message debugging

Read-only investigation: nothing here costs money. Do not place a new call to "reproduce" unless the
user asks.

## 1. Get an identifier

Ask for the Call SID (best), or the phone number and approximate time. For SMS, the recipient number.

## 2. Collect facts

| What | Tool |
|------|------|
| **Any VoiceBot call: everything at once** (call record, bot session, transcript, likely cause) | `exotel voicebot debug --call-sid <sid>` |
| Call status, direction, from/to, duration, price, recording URL | `exotel api GET /v1/Accounts/{sid}/Calls/<sid>.json` or MCP `exotel_voicebot_call_status` |
| Calls in a time window | `exotel_voice_get_bulk_call_details` |
| Status callbacks Exotel sent for a number | `exotel_voice_search_callbacks_by_number`, `exotel_voice_get_call_status_callbacks` |
| Is the number valid, which operator/circle, DND | `exotel_number_get_metadata` |
| Which numbers the account owns (valid caller IDs) | `exotel_voicebot_list_phone_numbers` |
| VoiceBot transcript | `exotel voicebot transcript --call-sid <sid>` |
| Listen to the recording | `exotel_audio_open_player` or `exotel_audio_download_from_url` |
| SMS delivery | `exotel_sms_get_delivery_status` |
| Account balance (calls failing for everyone) | `exotel balance` |
| SIP trunk calls failing (403/404/488, no audio) | see the error table in `exotel-sip-trunking` |
| Flow falls back / wrong routing | `exotel applet test <type> <url>` (see `exotel-flow-webhooks`) |

## 2b. What does this status / code mean?

`exotel explain <code or word>` (e.g. `408`, `1012`, `23005`, `no-answer`) looks it up in the status and error
tables of both developer.exotel.com and docs.exotel.com, plus codes verified on live calls, and cites the page.
`exotel explain --list` shows every documented status family. The same code can mean different things in
different products (e.g. 1012): pick the row whose page matches the API you called.

## 3. Common causes

| Symptom | Usual cause |
|---------|-------------|
| `failed` immediately, 0 s | Caller ID not owned by the account, number format wrong, or account balance / KYC issue |
| `no-answer` / `busy` | Callee side; retry later. Check DND/metadata for promotional numbers. |
| Connected but silent | Media problem or bot not attached. For VoiceBot: transcript empty → bot never got audio. |
| VoiceBot talks over the caller or waits too long | VAD / interruption settings (`exotel_voicebot_update_config`) |
| VoiceBot off-script | Prompt; see `exotel-voicebot-quickstart` step 5 |
| SMS `failed` / not delivered (India) | DLT template mismatch, unregistered header, or DND for promotional route |

## 4. Report

Give: the identifier, the observed facts (status, timings, any error text quoted exactly), the most
likely cause with the evidence for it, and the fix. If the evidence does not settle the cause, say
what else is needed (for example, the Exotel support ticket with the Call SID).
