---
name: exotel-connect-applet
description: Set up and program the Exotel Connect applet - static configuration in App Bazaar or a Dynamic URL your server answers per call - to route calls to numbers, agents or a SIP trunk, with recording, ring/talk limits, hold music, whisper/greeting playback, parallel ringing, re-dial on failure, custom SIP headers and a fallback URL. Use when the user builds call routing, click-to-call bridging, agent/queue dialing, inbound SIP trunk routing, or their Connect applet falls back or dials the wrong party.
---

# Exotel Connect applet (static and Dynamic URL)

The Connect applet dials a second party and bridges them with the caller. Configure it either:

1. **In App Bazaar** (static): "Dial whom" numbers or `sip:<trunk_sid>`, plus the options below in the UI.
2. **With a Dynamic URL** (per call): Exotel calls your URL and your JSON reply decides who to dial and how.

Transitions (what runs next after the dial: answered, busy, no-answer, failed…) are always set in the flow
builder, whichever way you configure parameters.

## Dynamic URL: the request Exotel sends

`GET <Primary URL>?<URL-encoded query parameters>` with header `Exotel-Version: 1.0`.

| Parameter | Meaning |
|-----------|---------|
| `CallSid` | Call identifier |
| `CallFrom` / `CallTo` | Caller / the number dialled (inbound: the ExoPhone) |
| `From` / `To` | Inbound: caller / ExoPhone. Outbound: first-leg number / number dialled |
| `Direction` | `incoming` or `outbound-dial` |
| `Created`, `StartTime`, `CurrentTime` | Timestamps (`EndTime` is a constant placeholder; use the Calls API after the call) |
| `DialCallDuration` | Seconds of the previous second-leg dial (0 if none) |
| `DialWhomNumber` | Last agent number dialled |
| `CallType`, `flow_id` | Call type and flow id |
| `CustomField` | The `CustomField` passed when the call was created (Connect API), when present |
| `DialCallStatus`, `digits`, `RecordingUrl` | When an earlier applet produced them (e.g. `digits` from a Gather) |

With `fetch_after_attempt: true`, a failed dial makes Exotel call the URL again with the previous attempt's
`<connect>` details added, so you can choose the next destination.

## Dynamic URL: the reply

`200 OK`, `Content-Type: application/json`, within **5 seconds**.

```json
{
  "fetch_after_attempt": false,
  "destination": { "numbers": ["+919812345678", "+919812345679"] },
  "outgoing_phone_number": "+918047115777",
  "record": true,
  "recording_channels": "dual",
  "max_ringing_duration": 30,
  "max_conversation_duration": 900,
  "music_on_hold": { "type": "custom_tone", "value": "https://example.com/hold.wav" },
  "parallel_ringing": { "activate": true, "max_parallel_attempts": 3 },
  "dial_passthru_event_url": "https://example.com/dial-events",
  "start_call_playback": { "playback_to": "callee", "type": "text", "value": "Customer from the premium plan" }
}
```

| Field | Rules |
|-------|-------|
| `destination` | **Required.** `{"numbers": [E.164, …]}` dialled in order, or `{"trunk": "<trunk_sid>"}` for a SIP trunk |
| `fetch_after_attempt` | Default `false`. `true` re-fetches after a failed dial; stops if two fetches return the same numbers |
| `outgoing_phone_number` | ExoPhone to dial from (default: the incoming ExoPhone). Must be on the account and in the same telecom circle; otherwise Exotel silently uses the first leg's ExoPhone. Ask support before using |
| `record`, `recording_channels` | Default `false`, `single`; `dual` puts caller and callee on separate channels |
| `max_ringing_duration` | Default 30 s, up to 60 |
| `max_conversation_duration` | Default 900 s, up to 4500 |
| `music_on_hold` | `{"type": "default_tone" \| "operator_tone"}` or `{"type": "custom_tone", "value": "<audio url>"}` |
| `parallel_ringing` | `{"activate": true, "max_parallel_attempts": 1–10}` (default 5). **Chargeable**: confirm with the account manager |
| `dial_passthru_event_url` | Your URL called on dial start and dial end |
| `start_call_playback` | Before connecting: `playback_to` `both` \| `callee`, `type` `audio_url` \| `text`, `value` |
| `in_call_playback` | During the call: `start_delay`, `repeat_frequency`, `play_to_callee`, `play_to_caller`, `type`, `value` |
| `end_call_playback` | At the end: `type`, `value` |
| `custom_params` | Trunk-routed calls only: `"k1=v1&k2=v2"` sent as custom SIP headers. See `exotel-custom-data` for limits |

Audio for playback: **8 kHz, 16-bit, mono, WAV** (128 kbps). Exotel caches by file name, so use a new file name
when the audio changes. For playback the URL must also answer `HEAD` with the same headers as `GET`.

## Fallback URL

Exotel calls the Fallback URL (if set) when the Primary URL: is not `200 OK`; takes longer than 5 s; returns a
Content-Type other than `application/json`; misses `destination`; or returns an `audio_url` / `text` that is
not a valid HTTP(S) resource answering 200. Always set a Fallback URL in production.

## Recipes

| Need | Reply |
|------|-------|
| Route to the customer's own agent from your CRM | `{"destination": {"numbers": ["<agent>"]}}` (look up `From`) |
| Hunt group, one by one | `{"destination": {"numbers": ["<a1>", "<a2>", "<a3>"]}, "max_ringing_duration": 20}` |
| Ring everyone at once | add `"parallel_ringing": {"activate": true, "max_parallel_attempts": 3}` (chargeable) |
| Next agent only if the first fails | `"fetch_after_attempt": true`, reply with the next number on the re-fetch |
| Send the call to your PBX / Voice AI over SIP with context | `{"destination": {"trunk": "<trunk_sid>"}, "custom_params": "X-Order=4521&X-Lang=hi"}` |
| Whisper context to the agent only | `"start_call_playback": {"playback_to": "callee", "type": "text", "value": "Premium customer, order 4521"}` |
| Compliance notice to both | `"start_call_playback": {"playback_to": "both", "type": "audio_url", "value": "https://…/recorded.wav"}` |
| Route by IVR choice | read `digits` from the request (a Gather before Connect) |

## Build and test

```bash
exotel applet scaffold connect --lang node     # starter handler with these fields
exotel applet test connect https://your-host/connect --param CustomField=order-4521,digits=1
```

`applet test` sends the same GET and header as Exotel, applies the 5 s limit and checks every field above,
including `custom_params` limits and reserved prefixes. Exit code 1 means Exotel would fall back.
For the inbound SIP side see `exotel-sip-trunking`; for passing data through the call see `exotel-custom-data`.
