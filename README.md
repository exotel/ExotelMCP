# ExotelMCP

A Model Context Protocol (MCP) server that provides seamless integration between Claude AI and Exotel's communication APIs for SMS and voice calling services, plus quick audio tools.

## Features

- 📱 **SMS Services**: Send single, bulk, and dynamic SMS messages with DLT compliance
- 📨 **Engage SMS Campaigns**: Create static SMS message campaigns via Engage API
- ☎️ **Voice Calling**: Initiate voice calls, connect numbers, and integrate with call flows
- 📊 **Status Tracking**: Real-time delivery status and callback management
- 🎵 **Quick Audio Tools**: Audio playback links and web player access
- 🔍 **Conversational Intelligence**: AI-powered quality analysis of 100% of conversations across voice and digital channels
- 🤖 **VoiceBot**: Create, manage, and place AI-powered outbound calls with VoiceBots
- 🎧 **AI Assist**: Configure real-time agent assist (smart reply, transcription, KB) via MCP (temporarily switched off, see [AI Assist Services](#ai-assist-services))
- 🔐 **Secure Authentication**: Per-user token-based authentication via Authorization header
- 🤖 **Claude AI Integration**: Direct integration with Claude through MCP protocol

## Table of Contents

- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
- [Usage](#usage)
- [API Services](#api-services)
- [Authentication](#authentication)
  - [Login rules](#login-rules)
- [Support](#support)

## Prerequisites

Before using the Exotel MCP Server, ensure you have:

- **Claude Desktop App**: Latest version installed
- **Node.js and npm**: Required for MCP remote connection
- **Exotel Account**: Active account with API credentials
- **MCP Remote Package**: Install if not already available

### Installing MCP Remote

If you don't have `mcp-remote` installed, run:

```bash
npm install -g mcp-remote
```

Verify installation:
```bash
npm list -g mcp-remote
```


### Claude Desktop Configuration

To integrate ExotelMCP with Claude, add the following configuration to your Claude desktop settings:

**Location**: Claude Desktop → Settings → Developer → Edit Config

```json
{
  "mcpServers": {
    "exotel": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "https://mcp.exotel.com/mcp",
        "--header",
        "Authorization:${AUTH_HEADER}"
      ],
      "env": {
        "AUTH_HEADER": "{'token':'YOUR_EXOTEL_TOKEN','account_sid':'YOUR_ACCOUNT_SID'}"
      }
    }
  }
}
```

#### Optional product sets

Add only the products you use; every set you include is checked at login (CQA and AI Assist client credentials cannot be checked, so they need a verified set beside them). Each set must be complete, and a wrong or leftover sample value in any set rejects the whole header, so delete the sets you do not need.

```text
Core (above):  token, account_sid                      optional: from_number, whatsapp_from, caller_id, dlt_temp, dlt_entity, api_domain, exotel_portal_url
VoiceBot:      voicebot_api_key, voicebot_api_token, voicebot_account_id          optional: voicebot_base_url
Calls:         calls_api_key, calls_api_token, calls_account_id                   optional: calls_base_url
Admin:         admin_username, admin_password, voicebot_account_id
Tools Server:  tools_server_api_key, tools_server_tenant_id                       optional: tools_server_api_token, tools_server_base_url
CQA:           cqa_api_key, cqa_account_id                                        optional: cqa_host
AI Assist:     ai_assist_base_url, ai_assist_account_sid + one auth mode (see AI Assist Credentials)
```

For example, core plus VoiceBot:

```text
{'token':'YOUR_EXOTEL_TOKEN','account_sid':'YOUR_ACCOUNT_SID','voicebot_api_key':'YOUR_VOICEBOT_API_KEY','voicebot_api_token':'YOUR_VOICEBOT_API_TOKEN','voicebot_account_id':'YOUR_VOICEBOT_ACCOUNT_ID'}
```

See [Login rules](#login-rules) for what is checked.

### Required Credentials

Replace every `YOUR_...` placeholder with your actual Exotel credentials. A placeholder left in the header is rejected with a 400 naming the field.

#### **YOUR_EXOTEL_TOKEN**: Base64 Encoded API Credentials

This token is created by encoding your Exotel API Key and Secret in Base64 format.

**Step 1: Get your API credentials from Exotel Dashboard**
1. Log into your [Exotel Dashboard](https://my.exotel.com/)
2. Navigate to **Settings** → **API Settings**
3. Copy your **API Key** and **API Secret**

**Step 2: Create the Base64 token**

Format: `api_key:api_secret` (colon-separated)

**Example**: If your API Key is `abc123` and API Secret is `xyz789`, combine them as: `abc123:xyz789`

**Step 3: Encode to Base64**

You can use any of these methods:

**Online Tool:**
- Visit [base64encode.org](https://www.base64encode.org/)
- Enter your `api_key:api_secret` string
- Copy the encoded result

**Command Line (Mac/Linux):**
```bash
echo -n "your_api_key:your_api_secret" | base64
```

**Command Line (Windows):**
```powershell
[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("your_api_key:your_api_secret"))
```

#### **Other Values (optional, add only the ones you use):**

- **YOUR_FROM_NUMBER**: Your registered Exotel phone number (from Dashboard → Numbers)
- **YOUR_DLT_TEMPLATE**: DLT template ID for SMS compliance (from Dashboard → DLT)
- **YOUR_DLT_ENTITY**: DLT entity ID for SMS compliance (from Dashboard → DLT)
- **YOUR_CALLER_ID**: Your registered caller ID (from Dashboard → Numbers)
- **YOUR_ACCOUNT_SID**: Your Exotel account SID (from Dashboard → API Settings)
- **YOUR_SUB_DOMAIN**: Your Exotel account SubDomain (from Dashboard → API Settings)
- **YOUR_EXOTEL_DASHBOARD_BASE_URL**: Your Exotel Dashboard base url

#### **Conversational Intelligence Credentials:**

- **YOUR_CQA_API_KEY**: API key generated from the **Conversational Intelligence console**
- **YOUR_CQA_ACCOUNT_ID**: Your account ID (visible in the Conversational Intelligence console)
- **YOUR_CQA_HOST**: Host URL (default: `https://cqa-console.in.exotel.com`)

> **Note**: Conversational Intelligence credentials are only required if you plan to use the quality analysis tools. SMS and Voice tools work independently.

#### **VoiceBot Credentials:**

- **YOUR_VOICEBOT_API_KEY**: API key from the VoiceBot platform
- **YOUR_VOICEBOT_API_TOKEN**: API token from the VoiceBot platform
- **YOUR_VOICEBOT_ACCOUNT_ID**: Your VoiceBot account ID (e.g. `ameyo5m`)
- **YOUR_CALLS_API_KEY**: Exotel CPaaS API key for placing bot-powered calls
- **YOUR_CALLS_API_TOKEN**: Exotel CPaaS API token for placing bot-powered calls
- **YOUR_CALLS_ACCOUNT_ID**: Exotel CPaaS account SID for calls (can differ from VoiceBot account)
- **calls_base_url**: Exotel Calls API base URL (`https://api.exotel.com` or `https://api.in.exotel.com`)

> **Note**: VoiceBot credentials are only required if you plan to use the VoiceBot tools. The VoiceBot management API (list/create/delete bots) uses `voicebot_*` credentials, while placing actual calls uses `calls_*` credentials.

> **Base URLs**: leave `voicebot_base_url`, `tools_server_base_url` and `te_base_url` out. The server uses the bases its operator configured (`VOICEBOT_BASE_URL`, `TOOLS_SERVER_BASE_URL`, `TE_BASE_URL`), and accepts a caller value only when it equals one of them; any other value is refused with 400 and blocks every tool in the session. The Tools Extension tools stay off until the operator sets `TE_BASE_URL`.

#### **AI Assist Credentials:**

Required for all `exotel_aiassist_*` tools:

- **ai_assist_base_url**: AI Assist API host (prod: `https://ai-assist.in.exotel.com`)
- **ai_assist_account_sid**: Your AI Assist tenant ID (account SID)

Auth: provide **one** of these modes (Twilix Basic is the default for CPaaS tenants):

| Mode | Fields |
|------|--------|
| Twilix Basic (recommended) | `ai_assist_auth_key`, `ai_assist_auth_secret` |
| Auth0 M2M | `ai_assist_client_id`, `ai_assist_client_secret` |
| Manual bearer | `ai_assist_auth_token` |
| Session cookie | `ai_assist_session_cookie` |

Optional:

- **ai_assist_context_path**: API context path (default: `ai-assist/api`)
- **ai_assist_user_id**: User ID for audit trails

Example (Cursor `~/.cursor/mcp.json`):

```json
{
  "mcpServers": {
    "exotel": {
      "url": "https://mcp.exotel.com/mcp",
      "headers": {
        "Authorization": "Bearer {\"ai_assist_base_url\":\"https://ai-assist.in.exotel.com\",\"ai_assist_account_sid\":\"YOUR_ACCOUNT_SID\",\"ai_assist_auth_key\":\"YOUR_API_KEY\",\"ai_assist_auth_secret\":\"YOUR_API_TOKEN\"}"
      }
    }
  }
}
```

> **Note**: AI Assist credentials are only required for AI Assist tools. SMS, Voice, CQA, and VoiceBot tools work independently.

> **Write safety**: Create/update/publish/archive/delete tools require `confirm=true` on the tool call.

## Usage

Once configured, you can use Claude to interact with Exotel services using natural language commands. Simply describe what you want to do, and Claude will handle the API calls through the MCP server.

**Example Commands:**
- "Send an SMS to +919999999999 saying 'Hello from Claude!'"
- "Call +919999999999"
- "Check the delivery status of my last SMS"
- "Connect +919999999999 with +919888888888"

**Audio Commands:**
- "Open the audio player interface"

**Conversational Intelligence -- Setup Commands:**
- "Login to CQA as admin@exotel.com on tenant my_tenant"
- "Create a quality profile called 'Support Evaluation' with category 'Communication', sub-category 'Clarity', and KPIs: 'Clear Speech' (Yes/No, options: Yes=1/No=0), 'Professional Tone' (Yes/No, options: Yes=1/No=0)"
- "Create a CQA API key called 'my-api-key'"
- "Create an assignment rule that routes calls with source 'support' to quality profile {profile_id}"

**Conversational Intelligence -- Analysis Commands:**
- "Ingest this call recording for quality analysis: https://storage.example.com/call-001.wav"
- "Check the status of interaction call-2026-04-01-001"
- "Get the quality analysis results for analysis a1b2c3d4"
- "Submit this CSV file for bulk ingestion: https://s3.example.com/calls.csv"
- "Track the progress of ingestion job abc123"

**VoiceBot Commands:**
- "List all my VoiceBots"
- "Create a VoiceBot that handles customer support for order tracking"
- "Call +919999999999 using my customer support bot"
- "Get the status of the last bot call"
- "List available phone numbers on my account"

**AI Assist Commands:**
- "Run exotel_aiassist_whoami to verify my AI Assist credentials"
- "List all AI Assist assistants on my account"
- "Get assistant details for assistant ID abc123"
- "List ASR providers and LLM suggestion languages before creating an assistant"
- "Create an AI Assist assistant for Ameyo 6x with real-time transcription and smart reply enabled"
- "Attach a KB file from https://example.com/policy.pdf to assistant abc123, then wait for indexing"
- "Publish assistant abc123"
- "Smoke-test the chat stream for assistant abc123"

## API Services

### SMS Services

#### Send Individual SMS
Send a single SMS message to a specific phone number with DLT compliance.

**Example**: 
```
Send an SMS to +919999999999 with message "Hello from Claude!" using DLT template 1107160086208866373 and entity 1101428740000012125
```

#### Send Bulk SMS (Same Message)
Send the same message to multiple recipients at once.

**Example**: 
```
Send "Welcome to our service!" to these numbers: +919999999999, +919888888888, +919777777777
```

#### Send Dynamic Bulk SMS
Send personalized messages to multiple recipients with different content for each.

**Example**: 
```
Send personalized messages: "Hello John" to +919999999999 and "Hello Jane" to +919888888888
```

### Engage SMS Campaigns

Create a static SMS message campaign on Engage (`exotel_engage_create_sms_campaign`).

Uses the same `calls_api_key` / `calls_api_token` / `calls_account_id` (or CPaaS `token` + `account_sid`) as other telephony tools — no separate Engage credentials.

**Example**:
```
Create an Engage SMS campaign named "July promo" to list list_sid from EXOTEL,
transactional, template "Hello from Engage https://example.com/promo",
DLT entity 123456 and template id 789012, with URL shortening enabled
```

Optional schedule and callbacks:
```
Same campaign, scheduled 2026-08-10T18:00:00+05:30 to 2026-08-10T20:00:00+05:30,
with status_callback and message_status_callback URLs
```

### WhatsApp Services

Send a WhatsApp message through Exotel (Exotel is the WhatsApp BSP) with `exotel_whatsapp_send_message`.

Uses the CPaaS `token` + `account_sid` + `api_domain`, plus a dedicated **`whatsapp_from`** auth field — your WhatsApp-enabled sender number in E.164 with a leading `+` (usually different from `from_number`, and not the WABA id). Mumbai accounts use `api_domain=https://api.in.exotel.com`.

**Template (start a conversation)**:
```
Send a WhatsApp template "order_confirmation" (language en) to +919999999999
with body variables ["Rahul", "#ORD-12345"]
```

**Free text (reply inside the 24-hour window)**:
```
Send a WhatsApp text "Thanks, your request is noted" to +919999999999
```

**Notes (common failures):**
- **`token` must be base64 of `api_key:api_secret`** (not the secret alone). Build it with `printf '%s' 'API_KEY:API_SECRET' | base64`. A wrong or placeholder token is the usual cause of a `401`.
- **Template name** is the lowercase identifier (e.g. `sample_issue_resolution`), not the display title.
- **Language code must match Meta exactly** — `en` and `en_US` are different; a mismatch returns a `400`.
- **Free text works only inside the 24-hour window** — i.e. the recipient messaged your `whatsapp_from` number in the last 24h. To start a new conversation, use a template.
- A `202` response means Exotel **accepted** the message (returns a `sid`); it does not confirm WhatsApp delivery.

### Voice Services

#### Make Voice Call
Initiate a voice call to any phone number using your registered Exotel number.

**Example**: 
```
Call +919999999999 from my registered number
```

#### Connect Two Numbers
Bridge two phone numbers in a single call, connecting them together.

**Example**: 
```
Connect +919999999999 with +919888888888 in a conference call
```

#### Call Flow Integration
Connect a phone number to a predefined Exotel call flow or IVR system.

**Example**: 
```
Connect +919999999999 to call flow app_id 12345
```

### Status & Tracking Services

#### Check SMS Delivery Status
Get real-time delivery status and details for sent SMS messages.

**Example**: 
```
Check the delivery status of SMS sent to +919999999999
```

#### Check Voice Call Status
Retrieve call details, duration, and status for voice calls.

**Example**: 
```
Get call details for calls made to +919999999999
```

#### Get Call History
Retrieve bulk call details and history for analysis.

**Example**: 
```
Get all call details made from +919999999999
```

#### Number Information
Get metadata and information about phone numbers.

**Example**: 
```
Get information about phone number +919999999999
```

### Audio Services

#### Quick Audio Tools
Web audio player access.

##### Open Audio Player
Access the web-based audio player interface for full control.

**Example**: 
```
Open the audio player interface
```

### Conversational Intelligence Services

Exotel [Conversational Intelligence](https://exotel.com/products/conversation-quality-analysis/) analyzes 100% of customer conversations across voice and digital channels using AI, delivering real-time quality intelligence. The platform reviews thousands of conversations in minutes, maintaining over 90% accuracy in objective evaluations — replacing manual sampling with continuous, automated QA at scale.

**Authentication:** CQA tools use two authentication methods:
- **Setup tools** (login, quality profiles, API keys, assignment rules) use JWT authentication obtained via the login tool -- no pre-configured credentials needed
- **Ingestion & analysis tools** (ingest, status, results) use your `cqa_api_key` configured in the MCP auth header
- **Login:** the MCP auth header must also carry a set this server can verify, such as core `token` + `account_sid`; a CQA-only header is refused with a 401 (see Login rules)

**API key**: Generate your API key from the **Conversational Intelligence console** or via the `exotel_cqa_create_api_key` MCP tool, then provide it as `cqa_api_key` in the MCP configuration.

#### Login
Authenticate with CQA to get a JWT token required for setup operations (quality profiles, API keys, assignment rules).

**Example**:
```
Login to CQA as admin@exotel.com on tenant exotel_support
```

#### Create Quality Profile
Create a complete quality profile with categories, sub-categories, and KPIs in a single call. The profile is automatically created with AI analysis enabled.

**Example**:
```
Create a quality profile called "Support Evaluation" with category "Communication", sub-category "Clarity", and KPIs: "Clear Speech" (Yes/No, options: Yes=1/No=0), "Professional Tone" (Yes/No, options: Yes=1/No=0)
```

> **Supported KPI types:** Yes/No, Selection, Rating, Text
>
> **Options are REQUIRED** for Yes/No, Selection, and Rating types. Each option needs `label` (string) and `weightage` (integer). Rating options also need `type: "STAR"`. Text KPIs do not support options.
>
> **Additional optional KPI fields:** `is_scoring_allowed`, `is_critical`, `is_mandatory`, `is_comment_mandatory`, `is_dispute_allowed`, `criticality_level`. All fields provided in `categoriesJson` are passed through to the API.
>
> **Partial failure handling:** If a sub-step (category, sub-category, or KPI creation) fails, the tool returns a partial result containing the `profile_id` and any categories created before the failure, along with a recovery hint.

#### Create API Key
Generate an API key for programmatic ingestion and analysis. Keys are scoped to the account.

**Example**:
```
Create a CQA API key called "support-api-key"
```

> **Note**: There is a per-tenant limit on active API keys (currently 2). A 409 response means the limit is reached -- revoke an existing key first.

#### Create Assignment Rule
Create a rule that routes interactions to quality profiles based on metadata filters (e.g., route all calls with `source=support` to a specific quality profile).

**Example**:
```
Create an assignment rule that routes calls with source "support" to quality profile a1b2c3d4-e5f6-7890-abcd-ef1234567890 at 100% sampling
```

> **`filterGroupJson` is REQUIRED.** The tool will return an error if no filter is provided.
>
> **Supported filter operators:** IS, IS_NOT, CONTAINS, NOT_CONTAINS, GREATER_THAN, LESS_THAN, GREATER_OR_EQUAL, LESS_OR_EQUAL

#### Ingest Single Interaction
Submit a single call recording or transcript for quality analysis. Provide at least one of `audioUrl`, `transcriptUrl`, or inline `transcriptText` (max 200KB).

**Example**:
```
Ingest interaction call-001 (voice channel) with audio at https://s3.example.com/call-001.wav in English
```

#### Quality Profile / Rule / API Key Management
JWT setup tools also support list/get/update/delete/duplicate for profiles, list/update/delete for assignment rules, list/revoke for API keys, metadata config, and list analyses.

Destructive tools (`delete_*`, `revoke_api_key`) require `confirm=true`. Empty KPI wipe on profile update requires `confirmWipeKpis=true`. `accountId` must match `cqa_account_id` in the MCP auth header when that header field is set.

#### Ingest Batch
Submit up to 100 interactions as a single batch job.

**Example**:
```
Ingest a batch of 3 voice interactions for quality analysis
```

#### Ingest File
Submit a CSV file containing multiple interactions for bulk analysis.

**Example**:
```
Submit https://s3.example.com/calls.csv as a CSV file for quality analysis ingestion
```

#### Get Interaction Status
Check the processing status of an ingested interaction.

**Example**:
```
Check the status of interaction call-2026-04-01-001
```

#### Track Job
Monitor the progress of a batch or file ingestion job.

**Example**:
```
Track ingestion job d4f5a6b7-c8d9-4e0f-a1b2-c3d4e5f6a7b8
```

#### Get Analysis Results
Retrieve the full quality scoring breakdown for a completed analysis.

**Example**:
```
Get the quality analysis for analysis ID a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

### VoiceBot Services

Create and manage AI-powered VoiceBots that handle entire phone conversations autonomously.

For how-tos, rules and use-case playbooks, ask the agent to call `exotel_voicebot_guide` (topic `start`). The same pages are MCP resources at `exotel://voicebot/{topic}`.

#### Changes for existing VoiceBot clients (voicebot builder tools release)

| Change | What to do |
|---|---|
| The server lists 96 tools instead of 85: 9 VoiceBot / Tools Server tools removed, 20 added | Use the replacements: `exotel_voicebot_assistant_push_version` and `exotel_voicebot_assistant_update_prompt` → `exotel_voicebot_update_config` with `assistant_config`; `exotel_voicebot_assistant_create_multiagent` → `exotel_voicebot_assistant_create`; `exotel_voicebot_transcript_get` → `exotel_voicebot_conversations_list` with `fields=transcript`; `exotel_voicebot_specialization_list` / `_create` / `_update` and `exotel_tools_server_tenant_spawn` / `_terminate` have no replacement |
| `exotel_voicebot_update_config` now creates a new bot version at 0% traffic instead of editing the bot in place; live calls change only after `exotel_voicebot_bot_version_promote` or `exotel_voicebot_bot_traffic_set` | Promote or split traffic after the update; `markAsStable=true` is refused |
| `exotel_voicebot_update_config`: `configJson` is renamed `dataJson`; unknown keys are rejected with the allowed list; the result is a JSON envelope (`ok`, ids, version), not the raw upstream body | Rename the argument; read the envelope |
| `exotel_voicebot_get_details`: `voiceBotId` is renamed `botId`; optional `version`; the result is a JSON envelope with secrets redacted, and agents' `tools` field is left out (`mcp_tools` holds the tools) | Rename the argument |
| `exotel_voicebot_*` and `exotel_tools_server_*` failures are flagged `isError: true` (text starting `Error`, `Missing ` or `Tool execution failed`, or JSON with `ok: false`) | Clients can rely on `isError` instead of parsing text |
| A number or boolean sent for a `String` parameter is converted instead of failing (all tools) | None |
| `initialize` returns short server `instructions`; `resources/list` grows from 19 to 44 entries (25 guide pages) | None |
| `exotel_tools_server_corpus_documents_update` removes documents only with `confirm=true`; without it the result is a preview | Show the preview, then call again with `confirm=true` |
| The Tools Extension tools (`exotel_voicebot_te_*`) are on only when the operator sets `TE_BASE_URL`; a caller may send `te_base_url` only when it equals a configured base, or every request is refused with 400 | Operators: set `TE_BASE_URL`. Callers: leave `te_base_url` out |

#### List VoiceBots
List all VoiceBots on your account with pagination and status filtering.

**Example**:
```
List all my active VoiceBots
```

#### Create VoiceBot
Create a new VoiceBot by describing its personality and purpose in natural language.

**Example**:
```
Create a VoiceBot that handles customer support for Acme Corp. It should be friendly, help with order tracking, and offer to escalate to a human agent if needed.
```

#### Place Outbound Bot Call
Place a phone call powered by a VoiceBot — the bot handles the entire conversation.

**Example**:
```
Call +919999999999 using my order-confirmation bot
```

#### Get Call Details
Check the status, duration, and recording URL of a bot-powered call.

**Example**:
```
Get details of call abc123def456
```

#### List Phone Numbers
List all phone numbers (DIDs) available on the account for use as caller IDs.

**Example**:
```
What phone numbers are available on my account?
```

#### List Recent Calls
View recent call history with status and outcomes.

**Example**:
```
Show me the last 5 bot calls
```

### AI Assist Services

> **Temporarily unavailable:** the AI Assist tools are switched off in this server until a security fix (AIA-629) ships. They are not listed and cannot be called; the other products are unaffected.

Real-time agent assist: transcription, sentiment, smart replies, disposition hints, and knowledge-base grounding for contact-center agents. Tools are prefixed `exotel_aiassist_*`.

**Authentication:** Configure `ai_assist_base_url`, `ai_assist_account_sid`, and one auth mode in the Authorization header (see [AI Assist Credentials](#ai-assist-credentials)). Use `exotel_aiassist_whoami` to verify setup.

**Read tools:** list/get assistants, attachments, KB upload jobs, description templates, ASR providers, LLM languages, agents, interactions, settings, stream URLs.

**Write tools:** create/update/publish/archive/delete assistants and custom templates; detach KB files; update descriptions. Attaching files by URL is temporarily unavailable on the hosted MCP server; add documents in the AI Assist console. All writes require `confirm=true`.

**Testing:** `exotel_aiassist_smoke_test_chat` runs a WebSocket handshake against the assistant stream; `exotel_aiassist_wait_for_kb_ready` polls until KB indexing completes.

#### Verify Credentials

**Example**:
```
Run exotel_aiassist_whoami
```

#### List Assistants

**Example**:
```
List all AI Assist assistants on my account
```

#### Create and Publish Assistant

Create with `exotel_aiassist_create_assistant`, configure ASR/language via tool args, then publish with `exotel_aiassist_publish_assistant` (`confirm=true`).

**Example**:
```
Create an AI Assist assistant named "Support Copilot" for source ameyo_6x with English STT and smart reply enabled
```

#### Knowledge Base Upload

Attach files from a public HTTPS URL or local path, then wait for indexing.

**Example**:
```
Attach https://example.com/returns-policy.pdf to assistant abc123, then wait for KB to be ready
```

> **Security**: URL fetch only allows `https://` and blocks private/loopback addresses (SSRF guard).

## Authentication

The Exotel MCP Server uses secure token-based authentication. All your Exotel credentials are configured in the Claude desktop configuration and are used to authenticate with Exotel's APIs.

### Security Features

- **Secure Token Handling**: Your Exotel API tokens are securely processed
- **User Isolation**: Each user's data is kept separate and secure
- **DLT Compliance**: Built-in support for DLT (Distributed Ledger Technology) requirements for SMS in India

### Login rules

Every request is checked before any tool runs.

- **Allowed hosts**: `voicebot_base_url`, `cqa_host`, `tools_server_base_url`, `ai_assist_base_url` and `exotel_portal_url` must be `https://` on `exotel.com` (or a subdomain), port 443 only, with no user info, IP addresses or query. `api_domain` and `calls_base_url` must be exactly `https://api.exotel.com` or `https://api.in.exotel.com` (no path, no query). These checks run on every request whenever the field is present, whichever credential sets the header carries.
- **Known API bases**: `voicebot_base_url`, `tools_server_base_url`, `ai_assist_base_url` and `ai_assist_context_path` must match one of the Exotel API bases configured by the operator (defaults: `https://voicebot.in.exotel.com/voicebot/api/v2` (and the same base at `/api/v1`, used by Admin), `https://tools-server-prod.mum1.exotel.com`, `https://ai-assist.in.exotel.com/ai-assist/api`). Host case and trailing slash are ignored. This is checked whenever the field is present, even if the header has no credentials for that product; a mismatch is refused with a 400 naming the field (never the value). Leave the field out to use the default. Operators can change the lists with `VERIFY_VOICEBOT_API_BASES`, `VERIFY_ADMIN_API_BASES`, `VERIFY_TOOLS_SERVER_API_BASES` and `VERIFY_AI_ASSIST_API_BASES` (comma-separated). A check only passes if Exotel answers with a JSON body; any other 2xx is treated as unverifiable (503).
- **Tool parameters in URLs**: phone numbers passed to the number metadata and bulk call details tools must be digits with an optional leading `+` (5 to 20 digits); ids and filter values that go into a URL (tenant id, version, status, dates) are limited to letters, digits and `_ - . : +`.
- **Sets are verified**: each complete set is checked against Exotel with one read-only call (Core and Calls: list calls; VoiceBot: list bots; Admin: list personas; Tools Server: list tools; AI Assist: list assistants). Results are cached for 5 minutes (30 seconds if rejected).
- **At least one verified set**: a header is accepted only if at least one set passed its live check against Exotel. Sets that cannot be checked never log anyone in on their own; they work only next to a verified set (for example core `token` + `account_sid`):
  - **CQA** (`cqa_api_key` + `cqa_account_id`): the CQA API has no read-only call that works with the API key yet (CEC-1442), so a CQA set is not checked at login. A CQA-only header is refused with a 401; send CQA together with a verified set. CQA calls are still checked by CQA itself when a tool uses them.
  - **AI Assist with only `ai_assist_client_id` / `ai_assist_client_secret`**: the bearer comes from an external token endpoint, so it cannot be checked.
- **Every set you include must pass**: an incomplete set (for example `token` without `account_sid`) or a wrong set rejects the whole header. The error names the set, never a value.
- **Placeholders and empty values**: a field that is empty or still holds a sample value (`YOUR_...`, `<...>`, `xxx`) is rejected. Placeholder or empty headers never reach any tool.

| Status | Meaning |
|--------|---------|
| 400 | A field is empty, still a placeholder, or has an unsafe host or id. The message names the field. |
| 401 | Header missing or unreadable, a set is incomplete, Exotel rejected a set, or only unverifiable sets were sent. |
| 503 | Exotel could not be reached to verify a set. Retry shortly (`Retry-After: 5`). Nothing is accepted unchecked. |

### Getting Your Exotel Credentials

1. **Log into your [Exotel Dashboard](https://my.exotel.com/)**
2. **Navigate to Settings → API Settings** to get your API Key, API Secret, and Account SID
3. **Go to Numbers section** to note your registered phone numbers and caller IDs
4. **Visit DLT section** to get your DLT Template and Entity IDs (required for SMS compliance in India)
5. **Create your Base64 token** using the API Key and Secret as detailed in the [Configuration](#configuration) section

All these credentials should be properly formatted and added to your Claude configuration as shown above.

## Hosting & Deployment

### 🌐 **IMPORTANT: Public Domain Requirement**

**This application MUST be hosted with a public domain and HTTPS enabled** for the following reasons:

1. **MCP Remote Connection**: Claude Desktop connects to your server via the public internet
2. **Exotel Webhooks**: Exotel needs to send callback notifications to your server
3. **Security**: HTTPS is required for secure communication between Claude and your server
4. **Production Ready**: Ensures reliable service for SMS/voice status updates

### Deployment Option

##### **Docker Setup**
```dockerfile
FROM openjdk:21-jdk-slim

WORKDIR /app
COPY target/mcp_api-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

```bash
# Build and run
docker build -t exotel-mcp .
docker run -p 8080:8080 -e EXOTEL_BASE_URL=https://your-domain.com exotel-mcp
```

##### **Docker Compose with HTTPS**
```yaml
version: '3.8'
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - EXOTEL_BASE_URL=https://your-domain.com
  
  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf
      - ./ssl:/etc/ssl/certs
```


### Environment Configuration

#### Production Environment Variables
```bash
# Server Configuration
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=production

# Application URL (CRITICAL - must match your domain)
EXOTEL_BASE_URL=https://your-domain.com

# Security
SPRING_SECURITY_REQUIRE_SSL=true

# Logging
LOGGING_LEVEL_ROOT=WARN
LOGGING_LEVEL_COM_EXAMPLE_MCP_API=INFO
```

#### Update Claude Configuration
```json
{
  "mcpServers": {
    "exotel": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "https://mcp.exotel.com/mcp",
        "--header",
        "Authorization:${AUTH_HEADER}"
      ],
      "env": {
        "AUTH_HEADER": "{'token':'YOUR_EXOTEL_TOKEN','account_sid':'YOUR_ACCOUNT_SID'}"
      }
    }
  }
}
```

> **Note**: If your server is running on plain HTTP during local development, add `"--allow-http"` to the `args` array before `"--header"`.

### Running Locally (Cursor + Claude Desktop / CoWork)

Use a local server when developing or when cloud MCP is unavailable. Engage SMS campaigns and all other tools work the same as production.

```bash
# From the ExotelMCP repo root
./mvnw spring-boot:run
# MCP endpoint: http://localhost:8080/mcp
# Optional: ENGAGE_BASE_URL=https://engage.exotel.com (default)
```

Or with Docker Compose:

```bash
docker compose up --build
```

**Cursor** — add to `~/.cursor/mcp.json` (reload window after saving):

```json
{
  "mcpServers": {
    "exotel-local": {
      "url": "http://localhost:8080/mcp",
      "headers": {
        "Authorization": "{'token':'YOUR_EXOTEL_TOKEN','account_sid':'YOUR_ACCOUNT_SID'}"
      }
    }
  }
}
```

**Claude Desktop / CoWork** — CoWork loads the same `claude_desktop_config.json`. Bridge HTTP with `mcp-remote` and `--allow-http`:

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
        "AUTH_HEADER": "{'token':'YOUR_EXOTEL_TOKEN','account_sid':'YOUR_ACCOUNT_SID'}"
      }
    }
  }
}
```

Fully quit and reopen Claude Desktop so CoWork picks up the server. Keep the local Java process running while you orchestrate.

### Production Checklist

- [ ] **Domain**: Registered and DNS configured
- [ ] **HTTPS**: SSL certificate installed and working
- [ ] **Server**: Application running on port 8080
- [ ] **Firewall**: Ports 80, 443, and 8080 open
- [ ] **Monitoring**: Health checks and logging configured

#### Audio Service Requirements

**Quick Audio Tools:**
- ✅ Works on any server with internet access
- ✅ No special hardware needed
- ✅ Browser-based playback
- ✅ Supports all common audio formats (mp3, wav, ogg, etc.)

### Testing Your Deployment

#### 1. Test MCP Endpoint
```bash
curl https://your-domain.com/mcp
# Should return data: {"type": "connection_established"}
```


#### 2. Test with Claude
- Update Claude configuration with your domain
- Restart Claude Desktop
- Send test message: "Send a test SMS to +919999999999 using DltEntityId=XXXXXXX, From=EXOTEL,DltTemplateId=XXXXXXXXX"

### Troubleshooting Deployment

#### Connection Issues
- Check firewall settings
- Verify DNS propagation: `nslookup your-domain.com`
- Test HTTPS: `curl -I https://your-domain.com`

#### Application Issues
- Check application logs: `tail -f logs/mcp-server.log`
- Verify environment variables
- Test database connection

#### Callback Issues
- Ensure webhooks can reach your server
- Check Exotel callback URL configuration
- Verify HTTPS is working for callbacks

## Support

### Getting Help

- **Configuration Issues**: Double-check your Exotel credentials and Claude configuration
- **Deployment Issues**: Verify domain, HTTPS, and server accessibility
- **API Usage**: Refer to the [API Services](#api-services) section for usage examples
- **Exotel Account**: Contact Exotel support for account-related issues
- **DLT Compliance**: Ensure your DLT templates and entity IDs are properly registered

### Common Issues

#### "Connection Failed" Error
- Verify your domain is accessible: `curl https://your-domain.com/mcp`
- Check your `mcp-remote` installation: `npm list -g mcp-remote`
- Ensure HTTPS is properly configured
- Verify firewall allows incoming connections on port 443

#### "Authentication Failed" Error
- Verify your Exotel API token is correctly Base64 encoded from `api_key:api_secret` format
- Double-check your API Key and API Secret from Exotel Dashboard → Settings → API Settings
- Ensure the colon (`:`) separator is included between API Key and Secret before encoding
- Check that your account SID matches your Exotel account
- The error message names the credential set that failed; remove that set or correct it (see [Login rules](#login-rules))
- Ensure your phone numbers are properly registered with Exotel

#### SMS Not Delivered
- Verify DLT template and entity IDs are correct
- Check that your message content matches the registered DLT template
- Ensure the recipient number is valid and reachable
- Check callback webhook is receiving status updates

#### Webhook/Callback Issues
- Verify your server is accessible from the internet
- Check HTTPS certificate is valid
- Ensure callback URLs use your public domain
- Test webhook endpoints manually

### Resources

- **Exotel Documentation**: [https://developer.exotel.com/](https://developer.exotel.com/)
- **Conversational Intelligence**: [https://exotel.com/products/conversation-quality-analysis/](https://exotel.com/products/conversation-quality-analysis/)
- **Conversational Intelligence API Reference**: [https://docs.exotel.com/conversation-intelligence/api-reference-guide](https://docs.exotel.com/conversation-intelligence/api-reference-guide)
- **DLT Information**: [https://www.trai.gov.in/](https://www.trai.gov.in/)
- **Claude Desktop**: [https://claude.ai/](https://claude.ai/)
- **Spring Boot Deployment**: [https://docs.spring.io/spring-boot/docs/current/reference/html/deployment.html](https://docs.spring.io/spring-boot/docs/current/reference/html/deployment.html)
- **Let's Encrypt**: [https://letsencrypt.org/](https://letsencrypt.org/)

---

🌐 **Remember**: This application requires a public domain with HTTPS for production use. The domain is essential for Claude Desktop connectivity and Exotel webhook delivery.

🚀 **Ready to deploy?** Follow the hosting guide above and start sending SMS, making calls, and using audio tools through Claude AI!
