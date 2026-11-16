package com.example.mcp_api.service.setup;

import com.example.mcp_api.auth.AuthContext;
import com.example.mcp_api.auth.AuthCredentials;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/**
 * Setup and onboarding tools that work WITHOUT any credentials.
 * These help new users configure their MCP client to work with Exotel products.
 */
@Service
public class SetupTools {

    @Tool(name = "exotel_setup_guide",
          description = "Get setup instructions for connecting to Exotel products via MCP. "
              + "Works without any credentials — use this first if you're new to Exotel MCP. "
              + "Optional parameter 'product' to get targeted help: 'voicebot', 'cqa', 'cpaas', 'all'. "
              + "Returns step-by-step instructions for getting API keys and configuring your client.")
    public String getSetupGuide(String product) {
        String target = (product != null && !product.isBlank()) ? product.toLowerCase() : "all";

        StringBuilder guide = new StringBuilder();
        guide.append("# Exotel MCP Setup Guide\n\n");

        AuthCredentials creds = AuthContext.current();
        if (creds.isParsed()) {
            guide.append("## Current Status\n");
            guide.append("Products configured: ").append(creds.configuredProductsSummary()).append("\n\n");
        } else {
            guide.append("## Status: No credentials configured yet\n\n");
        }

        if (target.equals("all") || target.equals("voicebot")) {
            guide.append(voicebotGuide(creds));
        }
        if (target.equals("all") || target.equals("cqa")) {
            guide.append(cqaGuide(creds));
        }
        if (target.equals("all") || target.equals("memory")) {
            guide.append(memoryGuide(creds));
        }
        if (target.equals("all") || target.equals("cpaas")) {
            guide.append(cpaasGuide(creds));
        }

        guide.append(configSnippet());
        return guide.toString();
    }

    @Tool(name = "exotel_build_config",
          description = "Interactive config builder — generates a ready-to-paste mcp.json or claude_desktop_config.json "
              + "snippet based on the products you want to use. "
              + "Parameter 'products': comma-separated list of products to include (voicebot, cqa, cpaas, memory). "
              + "Parameter 'client': 'cursor' or 'claude' (determines config format). "
              + "Returns a JSON config template with placeholder values you fill in.")
    public String buildConfig(String products, String client) {
        String clientType = (client != null && !client.isBlank()) ? client.toLowerCase() : "cursor";
        String productList = (products != null && !products.isBlank()) ? products.toLowerCase() : "voicebot,cqa,cpaas";

        boolean includeVoicebot = productList.contains("voicebot");
        boolean includeCqa = productList.contains("cqa");
        boolean includeCpaas = productList.contains("cpaas");
        boolean includeMemory = productList.contains("memory");

        StringBuilder authFields = new StringBuilder();
        if (includeCpaas) {
            authFields.append("\"token\":\"BASE64_OF_API_KEY:API_SECRET\",");
            authFields.append("\"from_number\":\"YOUR_EXOTEL_VIRTUAL_NUMBER\",");
            authFields.append("\"whatsapp_from\":\"YOUR_WHATSAPP_SENDER_E164\",");
            authFields.append("\"account_sid\":\"YOUR_ACCOUNT_SID\",");
            authFields.append("\"api_domain\":\"https://api.exotel.com\",");
        }
        if (includeVoicebot) {
            authFields.append("\"voicebot_api_key\":\"YOUR_VOICEBOT_API_KEY\",");
            authFields.append("\"voicebot_api_token\":\"YOUR_VOICEBOT_API_TOKEN\",");
            authFields.append("\"voicebot_account_id\":\"YOUR_VOICEBOT_ACCOUNT_ID\",");
            authFields.append("\"calls_api_key\":\"YOUR_CALLS_API_KEY\",");
            authFields.append("\"calls_api_token\":\"YOUR_CALLS_API_TOKEN\",");
            authFields.append("\"calls_account_id\":\"YOUR_CALLS_ACCOUNT_ID\",");
        }
        if (includeCqa) {
            authFields.append("\"cqa_api_key\":\"YOUR_CQA_API_KEY\",");
            authFields.append("\"cqa_account_id\":\"YOUR_CQA_ACCOUNT_ID\",");
            authFields.append("\"cqa_host\":\"https://cqa-console.in.exotel.com\",");
        }

        if (includeMemory) {
            authFields.append("\"memory_api_key\":\"YOUR_MEMORY_API_KEY\",");
            authFields.append("\"memory_account_sid\":\"YOUR_MEMORY_ACCOUNT_SID\",");
        }

        String authJson = authFields.toString();
        if (authJson.endsWith(",")) authJson = authJson.substring(0, authJson.length() - 1);

        if (clientType.equals("claude")) {
            return "# Claude Desktop Configuration\n\n"
                + "Add to ~/Library/Application Support/Claude/claude_desktop_config.json:\n\n"
                + "```json\n"
                + "{\n"
                + "  \"mcpServers\": {\n"
                + "    \"exotel\": {\n"
                + "      \"command\": \"npx\",\n"
                + "      \"args\": [\"mcp-remote\", \"https://mcp.exotel.com/mcp\", \"--header\", \"Authorization:${AUTH_HEADER}\"],\n"
                + "      \"env\": {\n"
                + "        \"AUTH_HEADER\": \"{" + authJson.replace("\"", "'") + "}\"\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}\n"
                + "```\n\n"
                + "Replace all YOUR_* placeholders with your actual credentials.";
        }

        return "# Cursor MCP Configuration\n\n"
            + "Add to ~/.cursor/mcp.json:\n\n"
            + "```json\n"
            + "{\n"
            + "  \"mcpServers\": {\n"
            + "    \"exotel\": {\n"
            + "      \"url\": \"https://mcp.exotel.com/mcp\",\n"
            + "      \"headers\": {\n"
            + "        \"Authorization\": \"{" + authJson.replace("\"", "'") + "}\"\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "}\n"
            + "```\n\n"
            + "Replace all YOUR_* placeholders with your actual credentials.\n"
            + "After saving, restart Cursor for changes to take effect.";
    }

    private String voicebotGuide(AuthCredentials creds) {
        String status = creds.hasVoicebotCredentials() ? "CONFIGURED" : "NOT CONFIGURED";
        return "## VoiceBot [" + status + "]\n\n"
            + "Create AI-powered voice bots that handle phone conversations autonomously.\n\n"
            + "**Credentials needed:**\n"
            + "- `voicebot_api_key` — VoiceBot Dashboard → Settings → API Keys\n"
            + "- `voicebot_api_token` — Same page as above\n"
            + "- `voicebot_account_id` — Your VoiceBot account identifier\n"
            + "- `calls_api_key` — my.exotel.com → API Settings (for placing calls)\n"
            + "- `calls_api_token` — Same page as above\n"
            + "- `calls_account_id` — Your Exotel account SID\n\n"
            + "**What you can do:**\n"
            + "- Create and manage voice bots\n"
            + "- Place outbound AI-powered calls\n"
            + "- Configure bot personas, TTS voices, and specializations\n"
            + "- Get call transcripts and session details\n\n";
    }

    private String cqaGuide(AuthCredentials creds) {
        String status = creds.hasCqaCredentials() ? "CONFIGURED" : "NOT CONFIGURED";
        return "## Conversational Intelligence / CQA [" + status + "]\n\n"
            + "Analyze call recordings and transcripts for quality, compliance, and insights.\n\n"
            + "**Credentials needed:**\n"
            + "- `cqa_api_key` — CQA Console → Settings → API Keys\n"
            + "- `cqa_account_id` — Your CQA account identifier\n"
            + "- `cqa_host` — (optional) defaults to https://cqa-console.in.exotel.com\n\n"
            + "**What you can do:**\n"
            + "- Ingest call recordings for analysis\n"
            + "- Get quality scores and insights\n"
            + "- Track analysis jobs\n\n";
    }

    private String memoryGuide(AuthCredentials creds) {
        String status = creds.hasMemoryCredentials() ? "CONFIGURED" : "NOT CONFIGURED";
        return "## Exotel Memory [" + status + "]\n\n"
            + "Remember every contact across calls and chats, for any bot or agent.\n\n"
            + "**Credentials needed:**\n"
            + "- `memory_api_key` — Memory console → API keys (starts with cm_)\n"
            + "- `memory_account_sid` — shared by Exotel during Memory onboarding\n"
            + "- `memory_base_url` — (optional) defaults to https://memory.in.exotel.com\n\n"
            + "**What you can do:**\n"
            + "- Give any VoiceBot memory with one call: exotel_voicebot_memory_enable\n"
            + "- Read what Memory knows about a contact: exotel_memory_get\n"
            + "- Save calls and chats from any bot: exotel_memory_save_interaction\n"
            + "- Push CRM updates the bot sees on the next call: exotel_memory_add_note, exotel_memory_record_event\n\n";
    }

    private String cpaasGuide(AuthCredentials creds) {
        boolean hasCalls = creds.hasCallsCredentials();
        boolean hasCpaas = creds.hasCpaasCredentials();
        String status = (hasCalls || hasCpaas) ? "CONFIGURED" : "NOT CONFIGURED";
        return "## CPaaS / Calls - SMS, Voice & Engage Campaigns [" + status + "]\n\n"
            + "Send SMS messages, initiate voice calls, and create Engage SMS message campaigns.\n\n"
            + "**Credentials needed (CPaaS SMS/Voice):**\n"
            + "- `token` — Base64 encoded `api_key:api_secret` from my.exotel.com → API Settings\n"
            + "- `account_sid` — Your Exotel account SID\n"
            + "- `from_number` — Your Exotel virtual phone number (SMS/voice)\n"
            + "- `whatsapp_from` — (for WhatsApp) your WhatsApp-enabled sender number in E.164 with a leading +, "
            + "e.g. +919876500001. This is usually DIFFERENT from `from_number`, and is NOT the WABA id.\n"
            + "- `api_domain` — (optional) defaults to https://api.exotel.com; Mumbai accounts use https://api.in.exotel.com\n\n"
            + "**Credentials for Engage SMS campaigns (reuse calls_* preferred):**\n"
            + "- `calls_api_key` / `calls_api_token` / `calls_account_id` — my.exotel.com → API Settings\n"
            + "- Or fall back to CPaaS `token` + `account_sid` (same account)\n"
            + "- Optional server env: `ENGAGE_BASE_URL` (default https://engage.exotel.com)\n\n"
            + "**What you can do:**\n"
            + "- Send SMS (single and bulk)\n"
            + "- Initiate voice calls\n"
            + "- Connect two numbers\n"
            + "- Check delivery status\n"
            + "- Create Engage SMS campaigns (`exotel_engage_create_sms_campaign`)\n"
            + "- Send WhatsApp messages (`exotel_whatsapp_send_message`) — needs `whatsapp_from`\n\n";
    }

    @Tool(name = "exotel_events_endpoint_guide",
          description = "Helps build the endpoint that receives Exotel real-time call events (Legs / Bridges / ExoML). "
              + "Works without credentials. Parameters: 'transport' = 'https' (the only supported transport); "
              + "'language' = 'node' (default) or 'python'. For https returns the documented HTTP contract "
              + "(POST JSON, 2xx ack within ~1 s, retries, Basic Auth, source IPs, payload fields) plus a complete, "
              + "dependency-free starter server that acks, de-duplicates retries and calls the ExoML actions API. "
              + "Write the returned code to a file in the user's project. Pass the endpoint's public https URL as "
              + "leg_event_endpoint (Create Leg) or bridge_event_endpoint (Create Bridge). Read-only.")
    public String eventsEndpointGuide(String transport, String language) {
        String t = (transport == null || transport.isBlank()) ? "https" : transport.trim().toLowerCase();
        String lang = (language == null || language.isBlank()) ? "node" : language.trim().toLowerCase();
        if (!t.equals("https")) return "Error: Exotel call events are delivered over HTTPS only; use transport='https'";
        String file = lang.equals("python") ? "exotel_events.py" : lang.equals("node") ? "exotel-events.js" : null;
        if (file == null) return "Error: language must be 'node' or 'python'";
        String code;
        try (java.io.InputStream in = getClass().getResourceAsStream("/templates/events/" + file)) {
            if (in == null) return "Error: template not packaged: " + file;
            code = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "Error: could not read template: " + e.getMessage();
        }
        return "# HTTPS event endpoint for Exotel real-time call events\n\n"
            + "## Contract\n"
            + "- Exotel sends POST, Content-Type application/json, User-Agent Exotel-Hermix/<version>.\n"
            + "- Headers: X-Exotel-Event-Sid (= event_sid), X-Exotel-Request-Id (<event_sid>-<attempt 1..3>), "
            + "Authorization: Basic … only if Basic Auth was configured by Exotel support.\n"
            + "- Reply any 2xx within ~1 second. The body is ignored. Do slow work after acknowledging.\n"
            + "- Retries: up to 3 attempts on 408, 429, 5xx, timeouts, connection errors; then the event is dropped. "
            + "De-duplicate on event_sid. Do not use 429 for backpressure.\n"
            + "- Body: event_sid, event_type (leg_lifecycle_event | leg_action_event | bridge_lifecycle_event | "
            + "bridge_action_event), event_name (leg_ringing, leg_answered, leg_terminated, leg_failed_to_create, "
            + "gather_success, play_completed, recording_available, bridge_created, …), event_timestamp, action_sid, "
            + "action_custom_parameter, event_data (action/error details), data (leg_sid, account_sid, state, …).\n"
            + "- The next call step is NOT in the HTTP reply: send ExoML to POST "
            + "https://cpaas-api.in.exotel.com/v2/accounts/{account_sid}/legs/{leg_sid}/actions.\n"
            + "- Production: valid public TLS certificate. Optionally allow only Exotel source IPs "
            + "35.154.174.161, 13.233.217.51, 98.130.5.99.\n\n"
            + "## Register it\n"
            + "- Outbound / API calls: pass the https URL as leg_event_endpoint (Create Leg) or "
            + "bridge_event_endpoint (Create Bridge).\n"
            + "- Inbound calls on an ExoPhone: share the URL with Exotel support to attach it to the number.\n\n"
            + "## Local development\n"
            + "Run the server, expose it with an HTTPS tunnel (e.g. ngrok or cloudflared), and use that URL.\n\n"
            + "## Secure by default\n"
            + "The starter server refuses to start without a secret: set EXOTEL_EVENTS_TOKEN to a long random value and "
            + "register https://<host>/exotel/events/<token> as the endpoint, or set EXOTEL_EVENTS_USER and "
            + "EXOTEL_EVENTS_PASS and ask Exotel support to configure the same Basic Auth. ALLOW_UNAUTHENTICATED=1 is "
            + "for local tests only. It rejects malformed or oversized bodies and logs only event names and leg SIDs.\n\n"
            + "## Starter code: " + file + "\n\n```" + (lang.equals("python") ? "python" : "javascript") + "\n"
            + code + "```\n";
    }

    private String configSnippet() {
        return "---\n\n"
            + "## Quick Start\n\n"
            + "Use `exotel_build_config` to generate a ready-to-paste config for your client.\n"
            + "Example: ask me to \"build config for cursor with voicebot and cqa\"\n";
    }
}
