# Exotel MCP — Quick Start Guide

## Connecting to the MCP Server

### Local development (Cursor + Claude Desktop / CoWork)

Run the server from the repo:

```bash
./mvnw spring-boot:run
# http://localhost:8080/mcp
```

**Cursor** — `~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "exotel-local": {
      "url": "http://localhost:8080/mcp",
      "headers": {
        "Authorization": "{'calls_api_key':'YOUR_CALLS_API_KEY','calls_api_token':'YOUR_CALLS_API_TOKEN','calls_account_id':'YOUR_ACCOUNT_SID','token':'YOUR_EXOTEL_TOKEN','account_sid':'YOUR_ACCOUNT_SID'}"
      }
    }
  }
}
```

Reload the Cursor window after saving.

**Claude Desktop / CoWork** — CoWork uses `~/Library/Application Support/Claude/claude_desktop_config.json` (same file as Desktop). Fully quit Claude after editing:

```json
{
  "mcpServers": {
    "exotel-local": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://localhost:8080/mcp",
        "--allow-http",
        "--header",
        "Authorization:${AUTH_HEADER}"
      ],
      "env": {
        "AUTH_HEADER": "{'calls_api_key':'YOUR_CALLS_API_KEY','calls_api_token':'YOUR_CALLS_API_TOKEN','calls_account_id':'YOUR_ACCOUNT_SID','token':'YOUR_EXOTEL_TOKEN','account_sid':'YOUR_ACCOUNT_SID'}"
      }
    }
  }
}
```

### Cloud (production) — In Cursor IDE

Add to `~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "exotel": {
      "url": "https://mcp.exotel.com/mcp",
      "headers": {
        "Authorization": "{'voicebot_api_key':'YOUR_VOICEBOT_API_KEY','voicebot_api_token':'YOUR_VOICEBOT_API_TOKEN','voicebot_account_id':'YOUR_VOICEBOT_ACCOUNT_ID','voicebot_base_url':'https://voicebot.in.exotel.com/voicebot/api/v2','calls_api_key':'YOUR_CALLS_API_KEY','calls_api_token':'YOUR_CALLS_API_TOKEN','calls_account_id':'YOUR_CALLS_ACCOUNT_ID','calls_base_url':'https://api.exotel.com','cqa_api_key':'YOUR_CQA_API_KEY','cqa_account_id':'YOUR_CQA_ACCOUNT_ID','cqa_host':'https://cqa-console.in.exotel.com'}"
      }
    }
  }
}
```

After saving, reload the Cursor window (`Cmd+Shift+P` → "Developer: Reload Window"). The Exotel MCP should show as green under Settings → MCP.

### In Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows):

```json
{
  "mcpServers": {
    "exotel": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "https://mcp.exotel.com/mcp"],
      "env": {
        "AUTH_HEADER": "{'voicebot_api_key':'YOUR_VOICEBOT_API_KEY','voicebot_api_token':'YOUR_VOICEBOT_API_TOKEN','voicebot_account_id':'YOUR_VOICEBOT_ACCOUNT_ID','voicebot_base_url':'https://voicebot.in.exotel.com/voicebot/api/v2','calls_api_key':'YOUR_CALLS_API_KEY','calls_api_token':'YOUR_CALLS_API_TOKEN','calls_account_id':'YOUR_CALLS_ACCOUNT_ID','calls_base_url':'https://api.exotel.com','cqa_api_key':'YOUR_CQA_API_KEY','cqa_account_id':'YOUR_CQA_ACCOUNT_ID','cqa_host':'https://cqa-console.in.exotel.com'}"
      }
    }
  }
}
```

Restart Claude Desktop after saving.

---

## Credentials Reference

| Field | Where to get it | Used by |
|-------|----------------|---------|
| `voicebot_api_key` | VoiceBot platform admin | VoiceBot tools |
| `voicebot_api_token` | VoiceBot platform admin | VoiceBot tools |
| `voicebot_account_id` | VoiceBot platform (account ID) | VoiceBot management API |
| `voicebot_base_url` | Default: `https://voicebot.in.exotel.com/voicebot/api/v2` | VoiceBot management API |
| `calls_api_key` | Exotel dashboard → API credentials | Outbound calls + Engage SMS campaigns |
| `calls_api_token` | Exotel dashboard → API credentials | Outbound calls + Engage SMS campaigns |
| `calls_account_id` | Exotel dashboard → Account SID | Outbound calls + Engage SMS campaigns |
| `calls_base_url` | Default: `https://api.exotel.com` | Outbound calls |
| `token` / `account_sid` | Exotel dashboard → API Settings | CPaaS SMS/Voice; Engage fallback auth |
| `cqa_api_key` | CQA console → API Keys | Conversational Intelligence |
| `cqa_account_id` | CQA console → Account Settings | Conversational Intelligence |
| `cqa_host` | Default: `https://cqa-console.in.exotel.com` | Conversational Intelligence |

---

## Making a VoiceBot Call

### Step 1: List available bots

**Prompt:**
> List all my voicebots

This calls `listVoiceBots` and returns bot names, IDs, and status.

### Step 2: Place a call

**Prompt:**
> Call +919876543210 using the Restaurant Reservation Bot

Or be explicit:
> Use callWithBot to call 9876543210 with voiceBotId 0576986f-25c1-449a-b7ca-129b64f4aa7c and callerId 02247789996

### Step 3: Check call status

**Prompt:**
> Check the status of call SID abc123def456

This calls `getBotCallDetails` and returns status, duration, and recording URL.

---

## Analysing a Call with Conversational Intelligence (CQA)

### Step 1: Ingest the recording

After a call completes and you have the recording URL:

**Prompt:**
> Analyse this call recording with CQA: https://recordings.exotel.com/path/to/recording.mp3

Or be explicit:
> Ingest this interaction into CQA — audio URL is https://recordings.exotel.com/rec.mp3, channel is voice, language is en

### Step 2: Wait for processing

**Prompt:**
> Check the status of interaction 6fb6026c-070e-45a6-a1f3-0c07639e8d11

The AI will poll until status is `completed`.

### Step 3: Get the analysis

**Prompt:**
> Get the quality analysis results for that interaction

This retrieves the full scoring breakdown with categories, KPIs, AI justifications, and suggestions.

---

## End-to-End Example

A single conversation flow:

```
You: Call 7000515158 using the Restaurant Reservation Bot

AI: [Places call, returns Call SID]

You: Check the status and get the recording

AI: [Returns completed status + recording URL]

You: Analyse this recording with CQA

AI: [Ingests into CQA, waits for processing, returns quality scores]
```

---

## Available Tools

| Tool | Description |
|------|-------------|
| `exotel_engage_create_sms_campaign` | Create Engage static SMS campaign (`lists`, DLT, optional schedule/callbacks) |
| `listVoiceBots` | List all bots in your account |
| `getVoiceBot` | Get details of a specific bot |
| `createVoiceBot` | Create a new bot |
| `deleteVoiceBot` | Delete a bot |
| `callWithBot` | Place an outbound call with a bot |
| `getBotCallDetails` | Get call status and recording |
| `listRecentBotCalls` | List recent calls |
| `listAccountPhoneNumbers` | List available caller IDs |
| `getBotGenerationStatus` | Check bot creation progress |
| `cqaIngestInteraction` | Submit recording/transcript (`audioUrl`, `transcriptUrl`, or `transcriptText`) |
| `cqaIngestBatch` | Submit multiple recordings |
| `cqaIngestFile` | Submit a CSV file of recordings |
| `cqaGetInteraction` | Check interaction processing status |
| `cqaGetAnalysis` | Get quality scoring results (API-key auth) |
| `cqaTrackJob` | Track batch/file job progress |
| `cqaLogin` | JWT for setup tools |
| `cqaCreateQualityProfile` / `cqaGetQualityProfile` / `cqaListQualityProfiles` / `cqaUpdateQualityProfile` / `cqaDeleteQualityProfile` / `cqaDuplicateQualityProfile` | Quality profile CRUD (JWT; deletes need `confirm=true`) |
| `cqaCreateApiKey` / `cqaListApiKeys` / `cqaRevokeApiKey` | API key management (revoke needs `confirm=true`) |
| `cqaCreateAssignmentRule` / `cqaListAssignmentRules` / `cqaUpdateAssignmentRule` / `cqaDeleteAssignmentRule` | Assignment rules (delete needs `confirm=true`) |
| `cqaConfigureMetadata` | List/create metadata mappings (`confirm=true` if access-control flag) |
| `cqaListAnalyses` | List scored analyses (JWT; detail via `cqaGetAnalysis` + API key) |
