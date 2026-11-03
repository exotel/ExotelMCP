# Testing the Exotel CLI on your laptop

Five user stories, each runnable in a terminal in 5–15 minutes. Every step says what you should see.
Anything that rings a phone or changes live routing asks for confirmation (or needs `--yes`).

## Before you start (once)

| Need | Check |
|------|-------|
| Node.js 18+ | `node -v` |
| Python 3.10+ (stories 3–4) | `python3 -V` |
| A tunnel tool (stories 3–4) | `ngrok version` or `cloudflared --version` |
| Exotel account, KYC done, at least one ExoPhone | my.exotel.com → ExoPhones |
| API key + token | my.exotel.com → Settings → API Settings |

Install the CLI from this repo (until it is on npm):

```bash
cd ExotelMCP/cli
npm test                 # expect: tests 44, pass 44, fail 0
npm link                 # puts `exotel` on your PATH
exotel --version         # expect: 0.1.0
```

> If your shell exports `EXOTEL_API_KEY`, `EXOTEL_CQA_*` or similar, they override the CLI's saved login.
> Run `env | grep ^EXOTEL_` and unset anything that belongs to another account.

---

## Story 1 — Connect my coding agent in one command

*As a developer, I connect Claude Code (or Cursor/Codex) to Exotel without building JSON headers.*

```bash
mkdir -p ~/exotel-try && cd ~/exotel-try
exotel init --client claude-code --scope project --agent claude --project
exotel doctor
```

Expect:
- `init` prompts for Account SID, API key, API token (hidden), region; prints `MCP → claude-code: added` and `Skills: 10 installed`.
- `doctor` shows ✔ for credentials, exotel-api, mcp-server (118 tools).
- `ls -a` shows `.mcp.json` and `.claude/skills/exotel-*`. Do not commit `.mcp.json` (it holds credentials).

Then open Claude Code in this folder, run `/mcp` (expect `exotel` connected) and continue with story 2 by asking it.

---

## Story 2 — An Exotel-hosted VoiceBot from one prompt

*As a developer, I describe a bot, test it on my phone and read the transcript.*

**With the agent** (in Claude Code from story 1):

```text
Set up a VoiceBot that greets callers and test it on my number +91XXXXXXXXXX
```

Expect: it creates the bot, then **stops and asks** before calling (number, caller ID, recording).
Say yes, answer your phone, talk, hang up; it shows the transcript and suggests fixes.

**By hand** (same result):

```bash
exotel voicebot create --prompt "Friendly receptionist for Acme. Greet, ask name and reason, end politely." --wait
exotel numbers list                                        # pick an ExoPhone as caller ID
exotel voicebot test-call --bot-id <botId> --to +91XXXXXXXXXX --caller-id <ExoPhone> --record --wait
exotel voicebot transcript --call-sid <CallSid>
exotel voicebot debug --call-sid <CallSid>
```

Expect: `test-call` without `--yes` asks "Proceed? (y/N)"; transcript lists `assistant:` / `user:` turns;
`debug` explains the outcome (e.g. "Very short session … ended by user").

---

## Story 3 — My own AI bot on a phone call (Connect Voice AI)

*As a builder with my own voice agent, I connect a phone call to my WSS bot without any App Bazaar flow.*

Terminal 1 (bot):
```bash
cd ExotelMCP/examples/exotel-voice-agent
./start.sh                                      # echo only; for AI add OPENAI_API_KEY=sk-... before ./start.sh
```

Terminal 2 (self-test, then public URL):
```bash
cd ExotelMCP/examples/exotel-voice-agent
.venv/bin/python test_local.py                  # expect: PASS
.venv/bin/python test_ai_mock.py                # expect: 15/15 passed (no OpenAI key needed)
ngrok http 8080                                 # note the https host
```

Terminal 3 (call):
```bash
exotel voice-ai connect --to +91XXXXXXXXXX --stream-url "wss://<host>/echo?sample-rate=16000" --record --wait
```

Expect: your phone rings; you hear yourself (echo). Terminal 1 logs `start call=… first audio … ms`.
With an OpenAI key, repeat with `/booking`: the bot greets, books, reads back, and `records.jsonl` gets the booking.
Interrupt it while it talks: it stops (log shows `✋ barge-in`).

---

## Story 4 — Bot on a call with no flow at all (Legs API + ExoML)

*As a developer, I control the call myself: outbound and inbound on my DID, events to my endpoint.*

Needs from Exotel support: **an LP-enabled API key + token** and **an LP number (DID)**. Put them in your shell
(`export EXOTEL_API_KEY=… EXOTEL_API_TOKEN=…`) or run `exotel login` with them.

Terminal 1: `cd examples/exotel-voice-agent && PORT=8786 ./start.sh`
Terminal 2: `ngrok http 8787` → note `<host>`

Outbound (Terminal 3):
```bash
exotel voice-ai connect --via legs --exophone <LP number> --to +91XXXXXXXXXX \
  --stream-url "wss://<host>/echo?sample-rate=8000" --events "https://<host>/events" \
  --listen 8787 --ws-proxy 8786 --greeting "Connecting you"
```

Expect, in order: `leg_connecting → leg_ringing → leg_answered`, then `Say 202`, `StartStream 202`,
`stream_started`; you hear the greeting then yourself. Ends with `leg_terminated`.

Inbound: first ask Exotel support to attach the LP number to your HTTPS event endpoint (`exotel leg inbound --number … --events …` prints the request). Then:
```bash
exotel voice-ai inbound --number <LP number> --stream-url "wss://<host>/echo?sample-rate=8000" \
  --events "https://<host>/events" --listen 8787 --ws-proxy 8786
```
Call the LP number from your phone. Expect `leg_incoming → Answer 202 → leg_answered → StartStream 202`.

Single actions on a live leg: `exotel leg say|play|gather|record|hold|mute|dial|hangup --leg-sid <sid> …` (see `exotel leg --help`).

Troubleshooting: `401 code 1010` = key without Legs; `503 code 1012` = not an LP number. `exotel explain 1012`.

---

## Story 5 — Which API should I use, and what does this error mean?

*As a developer new to Exotel, I get the right API, command and docs without reading everything.*

```bash
exotel ask "inbound calls to my bot without app bazaar"
exotel ask "send OTP by SMS in India"
exotel ask "when do I get 408"
exotel explain no-answer
exotel explain --list | head -40
exotel docs search warm transfer
exotel docs show https://developer.exotel.com/docs/agentstream/connect-voice-ai
exotel ask "how do I connect my bot over websocket" --ai      # adds the developer portal's AI answer (logs the question)
```

Expect: a recommended route + command + gotchas, then pages from both developer.exotel.com and docs.exotel.com;
`408` explained for SIP and for event retries, each with its source page.

---

## Also worth trying

| Goal | Command |
|------|---------|
| ElevenLabs / Bolna agents | `exotel voice-ai setup --platform elevenlabs`, `exotel integrate elevenlabs status` |
| SIP trunk (dry run) | `exotel trunk setup --name demo --number +91… --ip 203.0.113.10 --uri sip.example.com --dry-run` |
| Check a Dynamic URL handler | `exotel applet test gather https://your-host/menu` |
| Validate ExoML | `echo '<Flow><Say>Hi</Say><Hangup/></Flow>' \| exotel exoml validate -` |
| Every command | `exotel --help`, `exotel <command> --help` |
