# @exotel/cli

Exotel from the terminal, built for coding agents. One command connects Claude Code, Cursor, Codex,
VS Code, Windsurf or Claude Desktop to your Exotel account and teaches the agent the Exotel workflows.

```bash
npx @exotel/cli init
```

`init` asks for your Account SID, API key and API token (my.exotel.com → Settings → API Settings),
checks them, saves them to `~/.config/exotel/credentials.json` (mode 600), registers the Exotel MCP
server in every agent it finds (backing up each config file), and installs the Exotel skills.

Requires Node.js 18+. No dependencies.

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
| Phone numbers (ExoPhones) | `exotel numbers list \| get \| update` (buying/releasing: coming soon, via Exotel support today) | ExoPhones API |
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

The skills also live in [`/skills`](../skills), so `npx skills add exotel/ExotelMCP` works too.

## Safety, built for agents

- **JSON output** when piped or with `--json`, errors included (with Exotel's error `code`, `message` and a `hint`).
- **`--dry-run`** on every command that sends, calls or writes: prints the exact request, never the credentials.
- **Confirmation**: anything billed or that rings a real phone (`$`), anything irreversible (`!`) and
  anything that changes live call routing (`~`) needs `--yes` or an interactive "y". Without it the
  CLI exits with code 3 and prints what it would have done.
- **Exit codes**: 0 ok, 1 error, 2 usage, 3 confirmation needed, 4 not logged in.
- **No secrets in argv**: the API token and SIP passwords come from a hidden prompt, an env var or
  `--…-stdin`. `whoami` and `mcp config` mask secrets unless you pass `--reveal`.

## Two interfaces, one login

The CLI calls Exotel's REST APIs directly. The MCP server (`https://mcp.exotel.com/mcp`) gives agents
tools for VoiceBot, SMS, WhatsApp, Conversational Intelligence and more. `exotel init` sets up both with
the same credentials; the skills say which to use for each task.

## CI / non-interactive

```bash
export EXOTEL_ACCOUNT_SID=… EXOTEL_API_KEY=… EXOTEL_API_TOKEN=… EXOTEL_API_BASE_URL=https://api.in.exotel.com
exotel numbers list --json
exotel trunk setup --name ci_trunk --number +91… --ip 203.0.113.10 --dry-run
```

Environment variables override the saved file. `EXOTEL_AUTH_JSON` passes the full MCP header object;
`EXOTEL_MCP_URL` points at a self-hosted MCP server.

## Develop

```bash
cd cli && npm test           # unit + CLI tests, no network
node bin/exotel.js doctor    # live checks
npm pack                     # bundles ../skills into the package
```
