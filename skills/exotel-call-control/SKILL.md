---
name: exotel-call-control
description: Program live calls with the Exotel Legs API and ExoML (LP) - dial legs, answer inbound calls on a DID without any App Bazaar flow, stream calls to a voice bot over WSS, say/play/gather DTMF/record/voicemail, hold, mute, transfer, monitor, bridge legs and hang up, driven by real-time HTTPS events. Use when the user wants flow-free call control, a bot on an inbound DID without App Bazaar, warm transfer, conferencing, or asks about ExoML or Legs.
---

# Exotel call control (Legs API + ExoML, "LP")

## Before anything: what the user needs from Exotel support

1. An **LP-enabled API key + token**. Not every API key on an account has Legs access: a key without it
   gets **401 code 1010** on every Legs call.
2. An **LP number (DID)** to use as the caller ID / inbound number. Other numbers fail with
   **503 code 1012** "no route found in Sif".
3. Optional: data-centre / NSO codes (`--dc`, `--nso`) if support gives them.

Host: `https://cpaas-api.in.exotel.com/v2/accounts/{account_sid}` (the CLI picks it). Everything below costs
money or changes live calls: confirm with the user first (the CLI enforces `--yes` or a prompt).

## How it works (verified on a live call)

```
create leg (202) → events: channel_created → leg_connecting → leg_ringing → leg_answered
  → you POST ExoML <StartStream> → stream_initiated → Exotel connects to the bot's wss → stream_started
  → … → leg_terminated
```

- **Act only after `leg_answered`.** Actions sent while the phone is ringing are accepted but lost.
  `GET /legs/{sid}` does not reliably report `state`, so you need the **event endpoint**.
- Events are POSTed (JSON) to `leg_event_endpoint` (https://). Reply 2xx within ~1 s; then act.
- Every action is **ExoML** sent to `POST /legs/{sid}/actions` (or `/bridges/{sid}/actions`).
  There are no per-action JSON endpoints such as `/actions/start_stream` (they return 404).

## Event endpoint

| Situation | Do this |
|-----------|---------|
| Local development | `exotel events listen --port 8787`, expose 8787 with ngrok/cloudflared. With `voice-ai … --ws-proxy <bot port>` the bot's WebSocket is served on the same URL, so one tunnel covers both |
| Real app | `exotel events scaffold --lang node\|python` (handles `leg_incoming` → Answer, `leg_answered` → StartStream at once when `BOT_WSS` is set). The generated server refuses to start without `EXOTEL_EVENTS_TOKEN` (then the event URL is `https://<host>/exotel/events/<token>`) or `EXOTEL_EVENTS_USER`/`EXOTEL_EVENTS_PASS`. |
| Debugging | `exotel leg events --leg-sid <sid>` (history; only exists when events were delivered) |

## Bot on a call, no flow

```bash
# outbound: the CLI runs the event receiver and streams on answer
exotel voice-ai connect --via legs --exophone <LP number> --to <E.164> \
  --stream-url wss://<public>/echo?sample-rate=8000 --events https://<public>/events --listen 8787 --ws-proxy 8786

# inbound on a DID: Exotel support attaches the DID to your HTTPS event endpoint (no public API for this step);
# then the CLI answers and streams each call
exotel leg inbound --number <LP DID> --events https://…     # prints the exact request to send to support
exotel voice-ai inbound --number <LP DID> --stream-url wss://<public>/echo --events https://<public>/events --listen 8787 --ws-proxy 8786
```

Attaching a DID to an event endpoint replaces how its inbound calls are handled. Exotel support does it; for a
fully self-serve inbound bot use a flow instead (`exotel voicebot attach --number … --app-id <flow>`).

## Actions (one-liners; all ExoML)

| Action | CLI | ExoML |
|--------|-----|-------|
| Answer inbound | `exotel leg answer --leg-sid <sid>` | `<Answer/>` |
| Hang up | `exotel leg hangup --leg-sid <sid>` | `<Hangup/>` |
| Speak | `exotel leg say --leg-sid <sid> --text "…" [--voice Aditi --language en --loop 1 --engine polly\|googletts]` | `<Say …>text</Say>` |
| Stop speaking | `exotel leg stop-say --leg-sid <sid>` | `<StopSay/>` |
| Play audio | `exotel leg play --leg-sid <sid> --url https://…/a.wav\|sound:<file> [--loop 1]` | `<StartPlay>…</StartPlay>` |
| Stop audio | `exotel leg stop-play --leg-sid <sid>` | `<StopPlay/>` |
| Collect DTMF | `exotel leg gather --leg-sid <sid> --digits 4 --timeout 10 --finish-on "#" [--prompt "…"]` | `<Gather …><Say/></Gather>` → `gather_success` with digits |
| Record | `exotel leg record --leg-sid <sid> [--format mp3 --channel stereo --storage s3\|https --storage-url … --storage-key … --storage-token …]` | `<StartRecording …/>` → `recording_available` with URL |
| Voicemail | `exotel leg voicemail --leg-sid <sid> --prompt "…" --silence 5 --timeout 30 --finish-on "#"` | `<StartRecording silenceInSec…><Say/></StartRecording>` |
| Stop recording | `exotel leg stop-record --leg-sid <sid>` | `<StopRecording/>` |
| Bot stream | `exotel leg stream --leg-sid <sid> --url wss://… [--direction unidirectional --source-direction in]` | `<StartStream streamType streamUrl/>` (escape `&` as `&amp;`; up to 25 query params / 800 chars) |
| Stop stream | `exotel leg stop-stream --leg-sid <sid> [--stream <stream_sid>]` | `<StopStream/>` |
| Hold / unhold | `exotel leg hold --leg-sid <sid> [--moh-url …]` / `unhold` | `<Hold><StartPlay/></Hold>` / `<UnHold/>` |
| Mute / unmute | `exotel leg mute\|unmute --leg-sid <sid> [--direction in\|out\|both]` | `<Mute/>` / `<UnMute/>` |
| Dial a 2nd party | `exotel leg dial --leg-sid <sid> --to <num> --exophone <LP number>` | `<Dial contactUri exophone/>` → `dial_success` |
| Send DTMF | `exotel leg send-digits --leg-sid <sid> --digits 1234#` | `<SendDigits>…</SendDigits>` |
| Listen / whisper | `exotel leg monitor --leg-sid <sid> (--monitor-leg <sid> \| --to <supervisor>) [--rx muted\|enabled]` / `stop-monitor` | `<StartMonitoring/>` |
| Move events | `exotel leg refer --leg-sid <sid> --events <new endpoint>` | `<Refer legEventEndpoint/>` |
| Anything else | `exotel leg action --leg-sid <sid> --file flow.xml` (validated first) | any of the above |

Bridges: `exotel bridge create --legs <a,b> --events <url>` (both required), `bridge get | update | stop`,
`bridge join --bridge-sid <sid> --leg-sid <sid>` / `bridge leave --leg-sid <sid>`, and bridge-wide
`exotel bridge say|play|record|stop-record|stop-say|stop-play --bridge-sid <sid> …`.
Validate any ExoML with `exotel exoml validate flow.xml`.

## Typical flows

Full recipes with the event sequence for each step: `exotel-exoml-flows`. Passing ids and context: `exotel-custom-data`.


- **Outbound bot:** create leg → `leg_answered` → `<Say>` greeting + `<StartStream>` → `stream_started` → `<StopSay/>`.
- **Inbound bot:** register DID → `leg_incoming` → `<Answer/>` → `leg_answered` → `<StartStream>`.
- **Warm transfer:** `<Hold/>` the customer → `<Dial>` agent 2 from agent 1's leg → talk → agent 1 `<Hangup/>` →
  `<UnHold/>` the customer.
- **IVR without a flow:** `<Gather><Say>Press 1 …</Say></Gather>` → `gather_success` → branch in your endpoint.
