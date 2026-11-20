---
name: exotel-connect-voice-ai
description: Put the user's own voice AI bot (OpenAI Realtime, ElevenLabs, Gemini Live, Sarvam, Pipecat, Deepgram/AssemblyAI + LLM + TTS, any WebSocket bot) on real phone calls with Exotel's Connect Voice AI API - call a number and stream audio both ways to a wss:// URL, or route calls through an App Bazaar flow with a Voicebot applet. Use for voice agent telephony, PSTN calling for an AI bot, bidirectional audio streaming, AgentStream, or inbound/outbound AI calls on Exotel.
---

# Exotel Connect Voice AI (your bot on phone calls)

Exotel dials the number and streams the call audio over a WebSocket to the user's bot; the bot speaks
back on the same socket. The bot runs on the user's side. If they want Exotel to host the bot instead,
use `exotel-voicebot-quickstart`.

Reference bot bridges for every major provider: https://github.com/exotel/Agent-Stream
(`integrations/<provider>/`, each with README and `.env.example`).

## Rules

- Calls ring real phones and are billed. Confirm the number, caller ID and bot URL with the user before
  each call; only call numbers they gave you. The CLI enforces this (`--yes` or a prompt; exit 3 otherwise).
- Provider keys (OpenAI, ElevenLabs, …) go in the bot's `.env`, typed by the user. Never echo or commit them.
- Connect Voice AI must be enabled on the Exotel account; a 403 means ask Exotel support to enable it.

## Choose the path

| Path | When | How the call reaches the bot |
|------|------|------------------------------|
| **A. Direct (standard)** | Outbound calls straight to the bot | `exotel voice-ai connect --to … --stream-url wss://…` |
| **B. Flow** | Inbound calls, or the bot needs other applets around it (greeting, IVR, Passthru, transfer to an agent) | Flow in App Bazaar with a **Voicebot applet** holding the wss URL; outbound with `exotel voice-ai connect --to … --app-id <app_id>`, inbound by assigning the ExoPhone to the flow |
| C. Legs API + ExoML `<StartStream>` (LP) | No flow at all, outbound **and inbound** (DID → event endpoint); greeting while the stream starts | Needs an **LP-enabled API key + token and an LP number from Exotel support**. Outbound: `exotel voice-ai connect --via legs …`; inbound: `exotel voice-ai inbound --number <LP DID> …`. See `exotel-call-control` |

**Hosted platforms (ElevenLabs, Bolna):** they accept Exotel audio only through path B: their WSS refuses
streams from numbers not registered with them (ElevenLabs 403) or calls they did not start (Bolna `run_id`).
Use `exotel voice-ai setup --platform elevenlabs|bolna` for the exact URL and steps, and `exotel-setup` →
`exotel integrate …` for registration and test calls.

**Flow (app) ids:** only use an app id the user gives you for a flow they built with a Voicebot applet
pointing at the intended URL. Never reuse an existing flow's id or guess one.

Start with A unless the user needs inbound calls or other applets.

## 1. Run the bot

**Quickest start (no AI key needed to prove the audio path):** the example server in this repo,
`examples/exotel-voice-agent` (Python, one file). Paths: `/echo` (plays the caller back), and with
`OPENAI_API_KEY` set, `/receptionist`, `/booking`, `/lead` on OpenAI Realtime with barge-in, a
`WEBHOOK_URL` connector. It needs `BOT_TOKEN` (`start.sh` creates one) and `&token=<BOT_TOKEN>` in the stream
URL, and keeps no transcripts.

```bash
cd examples/exotel-voice-agent && ./start.sh            # OPENAI_API_KEY=sk-... ./start.sh for AI paths
.venv/bin/python test_local.py && .venv/bin/python test_ai_mock.py   # both must pass before a real call
ngrok http 8080                                          # the user runs this; gives the public host
exotel voice-ai connect --to <user number> --stream-url "wss://<host>/echo?sample-rate=16000" --wait
```

Once the user hears their own voice, switch `/echo` to the use case. For other providers use the
reference bridges below.

```bash
git clone https://github.com/exotel/Agent-Stream.git && cd Agent-Stream/integrations/<provider>
python3 -m venv venv && source venv/bin/activate && pip install -r requirements.txt   # Node: npm install
cp .env.example .env    # user fills in provider keys
```

Start it per its README and expose it publicly, for example `ngrok http <port>` → `wss://<id>.ngrok-free.app`.
Full URL: `wss://<host><path>?sample-rate=8000` (path per README: OpenAI `/`, Sarvam `/ws`,
ElevenLabs `/v1/convai/conversation/exotel`).

## 2A. Call through Connect Voice AI (direct)

```bash
exotel voice-ai connect --to +91XXXXXXXXXX --stream-url "wss://<host><path>?sample-rate=8000" --record --wait --dry-run
```

Show the dry-run, then run without `--dry-run`. Options: `--caller-id <ExoPhone>` (default: the saved
one; list with `exotel numbers list`), `--record`, `--recording-channels single|dual`,
`--time-limit <≤14400 s>`, `--custom-field <text>`, `--stream-name <label>`,
`--status-callback https://… --status-events answered,terminal,ringing`. `--wait` polls until the call
ends and prints status, duration and recording URL.


## 2B. Call through a flow (Voicebot applet)

`exotel voice-ai setup --stream-url wss://…` (or `--platform elevenlabs|bolna`) prints the URL to paste and these steps.

1. The user builds a flow in **App Bazaar** (my.exotel.com): add a **Voicebot applet** with the bot's
   wss URL (or an https URL that returns `{"url": "wss://…"}`), sample rate, optional Basic Auth
   (`wss://<user>:<pass>@host/path`) and up to 3 custom parameters (`?k=v`, 256 chars total). Add any
   other applets around it (greeting, Gather, Passthru for post-call logic, Connect to an agent).
2. Note the flow's **app id** (App Bazaar → My Apps).
3. Outbound: `exotel voice-ai connect --to +91XXXXXXXXXX --app-id <app_id> [--timeout 30] [--record] --wait`
   (MCP: `exotel_voice_connect_to_call_flow`).
4. Inbound: point an ExoPhone at the flow: `exotel numbers update --exophone-sid <sid> --voice-url <flow URL>`
   (shows a confirmation; it changes live routing).

## 3. WebSocket protocol the bot must speak

Audio: **raw/slin, 16-bit PCM little-endian, mono, base64**; 8000 Hz by default, `16000` or `24000`
via `?sample-rate=`.

Exotel → bot (`event` field): `connected`; `start` (`stream_sid`, `start.call_sid`, `start.from`,
`start.to`, `start.custom_parameters`, `start.media_format`); `media` (`media.payload`, `media.chunk`,
`media.timestamp`); `dtmf` (`dtmf.digit`, `dtmf.duration`); `mark` (`mark.name`, your audio finished
playing); `stop` (`stop.reason`: `stopped` or `callended`).

Bot → Exotel: `{"event":"media","stream_sid":"…","media":{"payload":"<base64>"}}`;
`{"event":"mark","stream_sid":"…","mark":{"name":"label"}}`; `{"event":"clear","stream_sid":"…"}`
(drop unplayed audio when the caller barges in).

**Outbound audio chunks: at least 3.2 KB (100 ms), at most 100 KB, and a multiple of 320 bytes.**
Smaller chunks distort, larger ones time out, other sizes leave gaps. Closing the socket ends the bot's
part of the call. Test without a phone: `wscat -c "ws://localhost:<port>/?sample-rate=8000"`, send
`{"event":"connected"}`.

## 4. Verify and debug

- Bot logs should show `connected` → `start` → `media` both ways within a second or two of answer.
- `exotel api GET /v1/Accounts/{sid}/Calls/<CallSid>.json` for status, duration and recording.
- No WebSocket connection: URL not publicly reachable, wrong path, or a proxy that needs `wss://`.
- Caller hears nothing or choppy audio: wrong encoding (must be PCM16, not μ-law), wrong sample rate,
  or chunk sizes outside 3.2 KB–100 KB / not a multiple of 320 bytes.
- Bot talks over the caller: on the provider's interruption signal, send `clear`.
- High latency: see `docs/exotel-developer/LATENCY_NOTES.md` in Agent-Stream; run the bot near the Exotel
  region (Mumbai for `api.in.exotel.com`).
