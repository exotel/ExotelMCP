# Exotel MCP — Quick Start Guide

## Connecting to the MCP Server

### In Cursor IDE

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
| `calls_api_key` | Exotel dashboard → API credentials | Outbound calls |
| `calls_api_token` | Exotel dashboard → API credentials | Outbound calls |
| `calls_account_id` | Exotel dashboard → Account SID | Outbound calls |
| `calls_base_url` | Default: `https://api.exotel.com` | Outbound calls |
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
| `listVoiceBots` | List all bots in your account |
| `getVoiceBot` | Get details of a specific bot |
| `createVoiceBot` | Create a new bot |
| `deleteVoiceBot` | Delete a bot |
| `callWithBot` | Place an outbound call with a bot |
| `getBotCallDetails` | Get call status and recording |
| `listRecentBotCalls` | List recent calls |
| `listAccountPhoneNumbers` | List available caller IDs |
| `getBotGenerationStatus` | Check bot creation progress |
| `cqaIngestInteraction` | Submit a recording for analysis |
| `cqaIngestBatch` | Submit multiple recordings |
| `cqaIngestFile` | Submit a CSV file of recordings |
| `cqaGetInteraction` | Check interaction processing status |
| `cqaGetAnalysis` | Get quality scoring results |
| `cqaTrackJob` | Track batch/file job progress |
