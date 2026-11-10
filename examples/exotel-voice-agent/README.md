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

The bot only accepts connections that carry its password (`BOT_TOKEN`), so anyone who finds your tunnel URL
cannot open AI sessions on your OpenAI key. Create one and use it in both Terminal tabs:

```bash
cd examples/exotel-voice-agent
export BOT_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
./start.sh                                  # creates the venv, installs packages, starts on :8080
# optional, for the AI paths:
OPENAI_API_KEY=sk-... COMPANY_NAME="Acme Clinic" ./start.sh
```

(If you don't set `BOT_TOKEN`, `start.sh` creates one for the run and prints the stream URL to use.)

In a second Terminal tab (export the same `BOT_TOKEN` first):

```bash
.venv/bin/python test_local.py ws://localhost:8080/echo   # fake Exotel call through echo, prints PASS
.venv/bin/python test_ai_mock.py                          # AI path against a fake OpenAI: 16 checks, no key needed
ngrok http 8080                                           # copy the https://xxxx.ngrok-free.app host
```

Every stream URL you give Exotel ends with `&token=<BOT_TOKEN>`, for example
`wss://xxxx.ngrok-free.app/echo?sample-rate=16000&token=…`.

## Wire it to Exotel

### Fastest: Exotel calls you (Connect Voice AI, no flow needed)

With the [Exotel CLI](https://github.com/exotel/ExotelMCP/tree/main/cli) logged in (`exotel login`):

```bash
exotel voice-ai connect --to +91XXXXXXXXXX --stream-url "wss://xxxx.ngrok-free.app/echo?sample-rate=16000&token=$BOT_TOKEN" --wait
```

Your phone rings; answer and you hear yourself. Then swap `/echo` for `/booking`, `/lead` or `/receptionist`.
(Without the CLI, `curl` the Connect Voice AI API: `POST /v1/Accounts/<sid>/Calls/connect.json` with
`From`, `CallerId`, `StreamUrl`, `StreamType=bidirectional`.)

### Callers dial your number (App Bazaar flow)

1. In the Exotel dashboard, go to **App Bazaar** and create a flow: **Voicebot** applet, then a **Hangup** applet.
2. Voicebot URL: `wss://xxxx.ngrok-free.app/echo?sample-rate=16000&token=<BOT_TOKEN>`. Once echo works, switch `/echo` to `/booking`, `/lead` or `/receptionist`.
   You can also pick the use case with a custom parameter: `wss://…/?usecase=lead&token=…`.
3. Assign the flow to your ExoPhone (`exotel numbers update --sid <sid> --voice-url <flow url>`, or in the dashboard) and call it.

## Connector

- Each `save_booking`, `save_lead` and `log_message` record is POSTed as JSON to `WEBHOOK_URL=https://...` (a CRM,
  Zapier, Make or a Google Sheets webhook). It must be `https://` (plain `http://` only to `localhost`).
- Records contain callers' personal data, so nothing is written to disk by default. For local testing only, set
  `RECORDS_FILE=records.jsonl` to also append them to a file, and delete it when you are done.
- To add your own tools, edit `agents.py`, then handle them in `server.py` → `on_tool`.

## Logs

- `logs/server.log` has every call event, and per call a summary line: call_sid, mode, number of turns, audio bytes
  in/out, time to first audio.
- Logs never contain what was said, names or full phone numbers (numbers are masked to the last 4 digits).

## Options (env vars)

- `PORT` (default 8080)
- `OPENAI_MODEL` (default `gpt-realtime`)
- `OPENAI_VOICE` (default `marin`)
- `USE_CASE` (the default route)
- `COMPANY_NAME`
- `WEBHOOK_URL`
- `BOT_TOKEN` (required)
- `RECORDS_FILE` (off by default)

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
  token check, privacy of logs). Not yet tested on a live call or against the real OpenAI API.

## Security

- **The public URL is password-protected.** The server refuses to start without `BOT_TOKEN`, and closes (1008) any
  connection without `token=<same value>` in the stream URL (e.g. `wss://<host>/booking?sample-rate=8000&token=…`).
  The only exception is trying `/echo` on this machine: `ALLOW_NO_TOKEN=1` with `HOST=127.0.0.1`.
- Exotel needs `wss://` (TLS) in production; tunnels like ngrok give you that.
- The server listens on `127.0.0.1` (tunnels forward to localhost). Set `HOST=0.0.0.0` only inside a container.
- If you set `RECORDS_FILE`, that file holds callers' details: keep it out of git and delete it when done.
