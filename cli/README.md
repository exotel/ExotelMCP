# exotel-cli

Exotel from the terminal, built for coding agents. One command connects Claude Code, Cursor, Codex,
VS Code, Windsurf or Claude Desktop to your Exotel account and teaches the agent the Exotel workflows.

```bash
npx exotel-cli init
```

`init` asks for your Account SID, API key and API token (my.exotel.com → Settings → API Settings),
checks them, saves them to `~/.config/exotel/credentials.json` (mode 600), registers the Exotel MCP
server in every agent it finds (backing up each config file), and installs the Exotel skills.

Requires Node.js 20+. No dependencies.

## Install

| How | Command | Needs |
|-----|---------|-------|
| npm | `npm install -g exotel-cli` (or run once: `npx exotel-cli init`) | Node.js 20+ |
| Homebrew (macOS, Linux) | `brew install exotel/tap/exotel` | nothing else, includes tab-completion |
| Single file | download `exotel-<version>-<os>-<arch>` from the GitHub release, put `exotel` on your PATH | nothing else |

Tab-completion for npm installs: `exotel completion zsh > "${fpath[1]}/_exotel"` (or `bash`, `fish`; see `exotel help completion`).

Building the binary yourself: `npm run build:binary` → `dist/exotel` (Node 25.5+ to build; the result needs no Node).

## Your first call in one command

```bash
npm install -g exotel-cli
exotel init        # paste Account SID, API key, API token once
exotel call        # asks what should answer (VoiceBot, App Bazaar flow or your wss bot), who to call, then follows the call live
```

`exotel call` picks from your VoiceBots and ExoPhones, remembers your phone number and caller ID
(`exotel config`), asks before anything billed, and ends with what happened and the next command
(transcript, call record, `exotel explain <status>`). Every prompt has a flag, so scripts and agents
never get asked: `exotel call +9198XXXXXXXX --bot-id Asha --caller-id <ExoPhone> --yes`.

Output is a readable table in a terminal and JSON when piped or with `--json`. Typos get a
"did you mean". `NO_COLOR=1` turns colours off; `--quiet` hides progress.

## Not sure which API?

```bash
exotel ask "connect my OpenAI bot to a phone call"     # recommended API, command, gotchas + docs from both portals
exotel docs search warm transfer                       # search developer.exotel.com + docs.exotel.com (1,200+ pages)
exotel docs show https://developer.exotel.com/docs/agentstream/connect-voice-ai   # read a page as Markdown
```

## What you can do

| Product | Commands | Docs |
|---------|----------|------|
| Account | `exotel balance` | Voice v1 → Balance |
| Phone numbers (ExoPhones) | `exotel numbers available \| buy \| list \| get \| update \| release` | ExoPhones API |
| SIP trunking | `exotel trunk setup` (all steps at once), or `create \| map-number \| set-number-mode \| whitelist-ip \| destination \| credentials add\|list\|delete \| caller-id \| delete` | Dynamic SIP Trunking |
| **Your own voice bot (standard)** | `exotel voice-ai connect --to … --stream-url wss://…`, or `--app-id <app_id>` for an App Bazaar flow with a Voicebot applet | Connect Voice AI API |
| Exotel VoiceBot | `exotel voicebot list \| test-call \| transcript` | VoiceBot |
| Live call control (needs enablement by Exotel support) | `exotel leg create \| get \| events \| action`, `exotel bridge …`, `exotel events listen \| scaffold` | Legs API |
| ExoML | `exotel exoml validate flow.xml` (and `leg action` validates before sending) | ExoML |
| Flow webhooks | `exotel applet test connect\|gather\|dial\|passthru\|stream <url>` | Connect / Gather / Passthru / Stream applets |
| SMS, WhatsApp, calls, CQA, AI Assist, … | `exotel mcp tools`, `exotel mcp call <tool> key=value…` | MCP server tools |
| Anything else | `exotel api <METHOD> </path/with/{sid}>` | Any Exotel REST API |

`exotel <command> --help` lists every flag (for example `exotel trunk --help`).

Not covered: Voice v3 (`/v3/accounts/{sid}/calls`). Use `exotel api` for it if needed.

## Agent skills

| Skill | For |
|-------|-----|
| `exotel-setup` | **Start here**: connect, verify, and pick the right skill |
| `exotel-phone-numbers` | See and route numbers; balance (buying: via Exotel support) |
| `exotel-messaging-and-calls` | SMS (India DLT), WhatsApp, simple calls, click-to-call |
| `exotel-voicebot-quickstart` | Exotel-hosted AI voice bot: create → test call → transcript → fix |
| `exotel-connect-voice-ai` | Your own voice bot (OpenAI, ElevenLabs, Gemini, Sarvam, Pipecat…) on calls |
| `exotel-sip-trunking` | Connect a PBX / SBC / Voice AI platform over SIP; SIP error codes |
| `exotel-call-control` | Legs, bridges and ExoML actions on live calls (needs enablement) |
| `exotel-flow-webhooks` | Write and test Dynamic URL handlers for flow applets |
| `exotel-call-debugging` | Why a call or message failed |
| `exotel-conversation-quality` | Conversational Intelligence scoring |

```bash
exotel skill list
exotel skill install all                         # detected agents, user-wide
exotel skill install exotel-sip-trunking --agent claude --project
exotel skill install all --dir ./my-agent/skills
```

The skills also live in [`/skills`](https://github.com/exotel/ExotelMCP/tree/main/skills), so `npx skills add exotel/ExotelMCP` works too.

## Safety, built for agents

- **JSON output** when piped or with `--json`, errors included (with Exotel's error `code`, `message` and a `hint`).
- **`--dry-run`** on every command that sends, calls or writes: prints the exact request, never the credentials.
- **Confirmation before every change**: anything that changes your Exotel account, anything billed or
  that rings a real phone (`$`), anything irreversible (`!`) and anything that changes live call routing
  (`~`) needs `--yes` or an interactive "y". Without it the CLI exits with code 3 and prints what it
  would have done. This applies to `exotel mcp call` too: only read-only MCP tools run without asking.
  The check is enforced where requests are sent, so a command that forgets to ask cannot send.
- **Exit codes**: 0 ok, 1 error, 2 usage, 3 confirmation needed, 4 not logged in.
- **No secrets in argv**: the API token and SIP passwords come from a hidden prompt, an env var or
  `--…-stdin`. `whoami` masks secrets unless you pass `--reveal`.
- **One copy of your token**: it lives only in `~/.config/exotel/credentials.json` (mode 600). Coding
  agents are configured to run `exotel mcp proxy`, a local relay that adds your credentials to each
  request to the hosted MCP server, so their config files contain no token.
- **`exotel logout`** deletes that file, removes the Exotel entry from every agent config the CLI wrote
  (`--keep-agents` to skip) and blanks the token in old config backups.

## Two interfaces, one login

The CLI calls Exotel's REST APIs directly. The MCP server (`https://mcp.exotel.com/mcp`) gives agents
tools for VoiceBot, SMS, WhatsApp, Conversational Intelligence and more. `exotel init` sets up both with
the same credentials; the skills say which to use for each task. Agents reach the MCP server through
`exotel mcp proxy` (`exotel mcp config` prints the entry to add by hand).

## CI / non-interactive

```bash
export EXOTEL_ACCOUNT_SID=… EXOTEL_API_KEY=… EXOTEL_API_TOKEN=… EXOTEL_API_BASE_URL=https://api.in.exotel.com
exotel numbers list --json
exotel trunk setup --name ci_trunk --number +91… --ip 203.0.113.10 --dry-run
```

Environment variables override the saved file. `EXOTEL_AUTH_JSON` passes the full MCP header object;
`EXOTEL_MCP_URL` points at a self-hosted MCP server.
Credentials are only ever sent over HTTPS to `*.exotel.com` / `*.exotel.in`; for a self-hosted or test server, also set
`EXOTEL_ALLOW_CUSTOM_HOST=1`.

## Develop

```bash
cd cli && npm test           # unit + CLI tests, no network
node bin/exotel.js doctor    # live checks
npm pack                     # bundles ../skills into the package
```

## Security notes

- Event receiver (`events listen`, `voice-ai … --listen`, `voice-ai inbound`): listens on `127.0.0.1` only. Set
  `EXOTEL_EVENTS_USER` / `EXOTEL_EVENTS_PASS` to require Basic auth, and ask Exotel support to send those credentials
  on your event endpoint; otherwise anyone with your tunnel URL can post fake events.
- Generated starter code is locked by default: `exotel events scaffold` servers need `EXOTEL_EVENTS_TOKEN` (served at
  `/exotel/events/<token>`) or `EXOTEL_EVENTS_USER` / `EXOTEL_EVENTS_PASS`; `exotel applet scaffold` handlers need
  `APPLET_KEY` (Exotel calls `https://…/handler?key=<APPLET_KEY>`). They refuse to start without one
  (`ALLOW_UNAUTHENTICATED=1` only for local tests), reject bad input instead of crashing, and log no phone numbers.
- Stream URLs must be `wss://`: call audio is never sent unencrypted.
- Your bot's public wss URL: protect it with a shared secret (`BOT_TOKEN`, required in `examples/exotel-voice-agent`).
- `exotel calls recording` sends your Exotel credentials only to `*.exotel.com` / `*.exotel.in` over HTTPS.
- `exotel integrate elevenlabs setup` gives ElevenLabs your Exotel API key and token (ElevenLabs needs them to place
  calls); it asks first. Use a separate API key for it so you can revoke it on its own.
- Agent config files contain only `exotel mcp proxy`, never a token.
