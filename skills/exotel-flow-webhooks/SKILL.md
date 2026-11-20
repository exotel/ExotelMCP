---
name: exotel-flow-webhooks
description: Write and test the web endpoints an Exotel call flow calls during a live call - Connect applet Dynamic URL (who to dial, SIP trunk, recording, ring/talk limits), Gather Dynamic URL (IVR prompt and DTMF), Dial-from-URL (numbers as text), Passthru (branch on 200/302) and Stream applet URL. Use when the user builds dynamic IVR or call routing, a Passthru webhook, or their Exotel flow keeps falling back.
---

# Exotel flow webhooks (Dynamic URLs)

During a call, an Exotel flow applet makes an **HTTP GET** to the user's URL with call details as
query parameters (`CallSid`, `CallFrom`, `CallTo`, `Direction`, `From`, `To`, `CurrentTime`, and when
present `digits`, `CustomField`, `DialCallStatus`, `RecordingUrl`) and the header `Exotel-Version: 1.0`.
If the reply is late, not 200, or not in the expected format, Exotel uses the applet's **Fallback URL**.

After writing a handler, always test it the way Exotel will:

```bash
exotel applet test connect https://your-host/route
exotel applet test gather  https://your-host/menu --param digits=1
```

It sends the same GET and header, enforces the timeout and checks the reply. Exit code 1 means Exotel
would fall back. For a local server, expose it first (for example `ngrok http 3000`).

## Contracts

| Applet | Reply | Timeout |
|--------|-------|---------|
| Connect | `application/json`, see below | 5 s |
| Gather | `application/json`, see below | 5 s |
| Dial (number from URL) | `text/plain`: one E.164 number or comma-separated list | 10 s |
| Passthru | Status only: **200 → Choice A**, **302 → Choice B** | 5 s |
| Stream | `application/json` `{"url": "wss://…"}` (or configure a wss URL directly) | 5 s |

### Connect

Full reference (every field, SIP-trunk routing, playback, re-fetch, transitions): `exotel-connect-applet`.
Starter handlers for every applet: `exotel applet scaffold <connect|gather|passthru|dial|stream> --lang node|python`. Handlers need `APPLET_KEY`: put `?key=<APPLET_KEY>` on the URL you give Exotel (requests without it get 401).


```json
{
  "destination": { "numbers": ["+919876543210", "+919812345678"] },
  "record": true,
  "recording_channels": "dual",
  "max_ringing_duration": 30,
  "max_conversation_duration": 900,
  "fetch_after_attempt": false,
  "start_call_playback": { "playback_to": "both", "type": "text", "value": "This call is recorded" }
}
```

- `destination` is required: `numbers` (E.164, tried in order) or `{"trunk": "<trunk_sid>"}` for SIP
  trunking (see `exotel-sip-trunking`).
- `max_ringing_duration` ≤ 60 (default 30); `max_conversation_duration` ≤ 4500 (default 900).
- `music_on_hold`: `default_tone` | `operator_tone` | `custom_tone` (with value).
- `parallel_ringing`: `{"activate": true, "max_parallel_attempts": 1–10}`; this is **chargeable**.
- `outgoing_phone_number` needs Exotel support to enable it.

### Gather

```json
{
  "gather_prompt": { "text": "Press 1 for sales, 2 for support" },
  "max_input_digits": 1,
  "finish_on_key": "#",
  "input_timeout": 5,
  "repeat_menu": 1
}
```

`gather_prompt` is required (`text` or `audio_url`). The caller's input arrives as `digits` on the
next request.

## Rules for the handler

- Respond fast (well under the timeout); do slow work asynchronously.
- Set the exact `Content-Type`. Return strict JSON (no trailing commas; some doc samples have them).
- Numbers in E.164 with `+`.
- Treat query parameters as untrusted input; never put them into shell commands or SQL unescaped.
- Always configure a Fallback URL in the flow.
