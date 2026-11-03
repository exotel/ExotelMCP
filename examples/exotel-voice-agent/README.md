# Exotel Voice AI Agent (WSS)

One small Python server for the Exotel **Voicebot applet**. Pick the mode by URL path:

| Path | What it does | Needs |
|---|---|---|
| `/echo` | Plays the caller's audio back (tests the setup end to end) | nothing |
| `/receptionist` | Takes the caller's name and reason, logs the message | `OPENAI_API_KEY` |
| `/booking` | Books an appointment (name/date/time) and reads it back to confirm | `OPENAI_API_KEY` |
| `/lead` | Asks the qualifying questions, scores hot/warm/cold | `OPENAI_API_KEY` |

Without `OPENAI_API_KEY`, every path falls back to echo.

## Run (about 5 minutes)

```bash
cd examples/exotel-voice-agent
./start.sh                                  # creates the venv, installs packages, starts on :8080
# optional, for the AI paths:
OPENAI_API_KEY=sk-... COMPANY_NAME="Exotel Demo" ./start.sh
```

In a second Terminal tab:

```bash
.venv/bin/python test_local.py ws://localhost:8080/echo   # fake Exotel call through echo, prints PASS
.venv/bin/python test_ai_mock.py                          # AI path against a fake OpenAI: 15 checks, no key needed
ngrok http 8080                                           # copy the https://xxxx.ngrok-free.app host
```

## Wire it to Exotel

### Fastest: Exotel calls you (Connect Voice AI, no flow needed)

With the [Exotel CLI](https://github.com/exotel/ExotelMCP/tree/main/cli) logged in (`exotel login`):

```bash
exotel voice-ai connect --to +91XXXXXXXXXX --stream-url "wss://xxxx.ngrok-free.app/echo?sample-rate=16000" --wait
```

Your phone rings; answer and you hear yourself. Then swap `/echo` for `/booking`, `/lead` or `/receptionist`.
(Without the CLI, `curl` the Connect Voice AI API: `POST /v1/Accounts/<sid>/Calls/connect.json` with
`From`, `CallerId`, `StreamUrl`, `StreamType=bidirectional`.)

### Callers dial your number (App Bazaar flow)

1. In the Exotel dashboard, go to **App Bazaar** and create a flow: **Voicebot** applet, then a **Hangup** applet.
2. Voicebot URL: `wss://xxxx.ngrok-free.app/echo?sample-rate=16000`. Once echo works, switch `/echo` to `/booking`, `/lead` or `/receptionist`.
   You can also pick the use case with a custom parameter: `wss://…/?usecase=lead`.
3. Assign the flow to your ExoPhone (`exotel numbers update --sid <sid> --voice-url <flow url>`, or in the dashboard) and call it.

## Connector

- Every `save_booking`, `save_lead` and `log_message` call is appended to `records.jsonl`.
- To also POST each record as JSON to a CRM, Zapier, Make or a Google Sheets webhook, set `WEBHOOK_URL=https://...`.
- To add your own tools, edit `agents.py`, then handle them in `server.py` → `on_tool`.

## Logs

- `logs/server.log` has every call event, and per call a summary line: call_sid, mode, audio bytes in/out, time to first audio.
- `logs/<call_sid>.txt` has each call's caller and agent transcript.

## Options (env vars)

- `PORT` (default 8080)
- `OPENAI_MODEL` (default `gpt-realtime`)
- `OPENAI_VOICE` (default `marin`)
- `USE_CASE` (the default route)
- `COMPANY_NAME`
- `WEBHOOK_URL`

## Notes

- Audio is PCM16 mono at the rate Exotel announces in `start` (8 kHz by default; `?sample-rate=16000` is better). It is resampled to and from 24 kHz for OpenAI. Outbound chunks are 3,200 bytes, in line with Exotel's rule of a multiple of 320 bytes between 3.2 KB and 100 KB.
- Barge-in: when the caller starts talking, the server sends `clear` to Exotel (dropping audio Exotel has queued but not
  yet played, even after the AI finished generating) and cancels the AI response if one is still being generated.
- Greeting latency: the OpenAI session is opened as soon as Exotel connects (before `start`), and the log prints
  `first audio to caller N ms after start` for every call. Target: about 1.5 s including the model.
- If OpenAI cannot be reached (bad key, wrong model), the server logs the reason and ends the stream so the flow moves
  on to the next applet instead of leaving the caller in silence.
- Ending the call: the AI calls `end_call`. The server waits for the goodbye to play (via a `mark` event) and then closes the stream, so Exotel moves on to the next applet.
- Tests: `test_local.py` (echo, fake Exotel call) and `test_ai_mock.py` (AI path at 8 and 16 kHz against a fake OpenAI
  Realtime server: resampling, chunk sizes, both barge-in cases, save_booking to records.jsonl and webhook, end_call,
  transcript, logging). Not yet tested on a live call or against the real OpenAI API.
