---
name: exotel-setup
description: Start here for anything Exotel - connect a coding agent to Exotel, verify it works, and pick the right Exotel skill for the job (calls, SMS, WhatsApp, numbers, SIP trunking, VoiceBot, AgentStream, call control/ExoML, flow webhooks, debugging, quality). Use when the user wants to start using Exotel, is unsure which Exotel product fits, says Exotel tools or commands fail, or asks how to get Exotel credentials.
---

# Exotel setup

## Not sure which API?

Run `exotel ask "<the user's goal in plain words>" --json`: it returns the recommended Exotel API/route, the
exact CLI command, gotchas verified on live calls, and the most relevant pages from both
https://developer.exotel.com (API reference) and https://docs.exotel.com (product docs). For details,
`exotel docs search <words>` and `exotel docs show <url>` (pages come as Markdown). For any status or error code,
`exotel explain <code|word>`. Prefer these over guessing
from memory: the portals sometimes disagree, and `ask` records which one the live API follows.

## Which skill?

| The user wants to… | Skill | Main interface |
|--------------------|-------|----------------|
| Find, buy, route or release phone numbers; check balance | `exotel-phone-numbers` | `exotel numbers …`, `exotel balance` |
| SMS, WhatsApp, a simple call, click-to-call, connect a caller to a flow | `exotel-messaging-and-calls` | MCP tools |
| An AI voice bot hosted by Exotel | `exotel-voicebot-quickstart` | MCP tools |
| Their own voice bot (OpenAI, Gemini, Sarvam, Pipecat…) on calls | `exotel-connect-voice-ai` | `exotel voice-ai connect`, `exotel voice-ai setup` |
| ElevenLabs or Bolna agents on Exotel numbers | `exotel-connect-voice-ai` (path B) | `exotel voice-ai setup --platform …`, `exotel integrate elevenlabs\|bolna …` |
| Connect a PBX / SBC / Voice AI platform over SIP | `exotel-sip-trunking` | `exotel trunk …` |
| Real-time control of live calls, bot on a DID without App Bazaar, ExoML (needs an LP API key/token + LP number from Exotel support) | `exotel-call-control` | `exotel leg …`, `exotel voice-ai connect --via legs`, `exotel voice-ai inbound` |
| Dynamic IVR / routing webhooks (Gather, Passthru, Dial, Stream) | `exotel-flow-webhooks` | `exotel applet test …`, `exotel applet scaffold …` |
| Connect applet: route to numbers/agents/SIP trunk, Dynamic URL, playback, parallel ringing | `exotel-connect-applet` | `exotel applet scaffold connect`, `exotel applet test connect …` |
| Pass custom data: CustomField, bot custom params, SIP X- headers, Legs custom_param | `exotel-custom-data` | — |
| Complete programmable call flows (IVR, warm transfer, conference, voicemail, AMD) with ExoML | `exotel-exoml-flows` | `exotel leg …`, `exotel events scaffold` |
| Why a call or message failed | `exotel-call-debugging` | `exotel voicebot debug`, `exotel explain` |
| Call logs / reports / recordings (list, filter, download) | `exotel-call-records` | `exotel calls list \| get \| recording` |
| Outbound call campaigns (bulk dial, reminders, AI-agent campaigns) | `exotel-campaigns` | `exotel campaigns …` |
| In-app / browser calling (WebRTC, Android, iOS, Flutter, CRM WebSDK) | `exotel-voice-sdks` | docs only |
| Score call quality | `exotel-conversation-quality` | MCP tools |
| Any other Exotel REST API | — | `exotel api <METHOD> <path>` |

**CLI or MCP?** Both use the same Exotel login. `exotel init` sets up both. Use whichever the skill
names. MCP tools appear in the agent as `exotel_*`; CLI commands run in a terminal. If the CLI is not
installed, prefix commands with `npx exotel-cli`.

## Connecting

Exotel hosts an MCP server at `https://mcp.exotel.com/mcp` (Streamable HTTP). Its tools act on the
user's own Exotel account. Credentials travel in the `Authorization` header as a JSON object. There is
no OAuth: a client that tries an OAuth login gets `Dynamic Client Registration rejected (HTTP 404)`,
which means the header is missing, not that the server is down.

## 1. Check whether it is already connected

If tools named `exotel_*` are available to you, call `exotel_setup_guide` with `product: "all"`.
The "Current Status" line lists which products have credentials. If it does, skip to step 4.

## 2. Fastest path: the Exotel CLI

If Node.js 18+ is installed, have the user run this in their terminal (it prompts for secrets, so
they type them; you never see or handle them):

```bash
npx exotel-cli init
```

`init` asks for the API key, token and account SID, checks them against the Exotel API, saves them
to `~/.config/exotel/credentials.json` (mode 600), registers the MCP server in every agent it finds
(Claude Code, Cursor, Claude Desktop, VS Code, Windsurf, Codex), and installs these skills. The
client must then be restarted, or in Claude Code the user runs `/mcp`.

Never ask the user to paste API tokens into the chat. If they do, tell them to rotate the token in
the dashboard afterwards.

## 3. Manual path (no Node.js)

Where the user finds each value in `https://my.exotel.com`:

| Field | Where |
|-------|-------|
| `account_sid` | Settings → API Settings → Account SID |
| `token` | Base64 of `API_KEY:API_TOKEN` (same page) |
| `api_domain` | `https://api.in.exotel.com` for the Mumbai cluster, `https://api.exotel.com` for Singapore |
| `from_number`, `caller_id` | An ExoPhone (virtual number) on the account |
| `voicebot_api_key`, `voicebot_api_token`, `voicebot_account_id` | Usually the same key, token and SID |
| `calls_api_key`, `calls_api_token`, `calls_account_id` | Usually the same key, token and SID |
| `cqa_api_key`, `cqa_account_id`, `cqa_host` | Only for Conversational Intelligence |
| `whatsapp_from` | Only for WhatsApp: the WhatsApp-enabled number in E.164 with `+` (not the WABA id) |

Claude Code (the user runs it, with real values):

```bash
claude mcp add --transport http exotel https://mcp.exotel.com/mcp --scope user \
  --header 'Authorization: {"token":"…","account_sid":"…","api_domain":"https://api.in.exotel.com","from_number":"…","caller_id":"…","voicebot_api_key":"…","voicebot_api_token":"…","voicebot_account_id":"…","calls_api_key":"…","calls_api_token":"…","calls_account_id":"…"}'
```

For Cursor, Claude Desktop or VS Code, call `exotel_build_config` with `client` and `products` to get
a ready-to-fill snippet.

## 4. Verify

1. Call `exotel_voicebot_list_phone_numbers`. It must return at least one number. That number is the
   `callerId` for test calls.
2. Call `exotel_voicebot_list_all` with `status: ""`, `limit: "10"`, `offset: "0"`.
3. Report what is configured. Do not place a call or send a message as a "test" unless the user asks;
   both cost money.

## Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| No `exotel_*` tools; client shows "Failed to connect" with HTTP 404 on client registration | `Authorization` header missing from the client config. Re-add with the header. |
| HTTP 401 `Authorization header is required` / `could not be parsed` | Header is not valid JSON. Use double quotes inside, no trailing commas. |
| Tool returns a credential error for one product only | That product's fields are missing from the header. Other products still work. |
| Calls fail with 403 / "caller id" errors | `caller_id` / `callerId` is not an ExoPhone on this account. Use one from `exotel_voicebot_list_phone_numbers`. |
| Indian SMS rejected | DLT template ID and entity ID missing, or the text does not match the registered template exactly. |
