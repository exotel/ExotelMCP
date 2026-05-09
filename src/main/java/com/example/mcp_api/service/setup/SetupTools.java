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
        if (target.equals("all") || target.equals("cpaas")) {
            guide.append(cpaasGuide(creds));
        }

        guide.append(configSnippet());
        return guide.toString();
    }

    @Tool(name = "exotel_build_config",
          description = "Interactive config builder — generates a ready-to-paste mcp.json or claude_desktop_config.json "
              + "snippet based on the products you want to use. "
              + "Parameter 'products': comma-separated list of products to include (voicebot, cqa, cpaas). "
              + "Parameter 'client': 'cursor' or 'claude' (determines config format). "
              + "Returns a JSON config template with placeholder values you fill in.")
    public String buildConfig(String products, String client) {
        String clientType = (client != null && !client.isBlank()) ? client.toLowerCase() : "cursor";
        String productList = (products != null && !products.isBlank()) ? products.toLowerCase() : "voicebot,cqa,cpaas";

        boolean includeVoicebot = productList.contains("voicebot");
        boolean includeCqa = productList.contains("cqa");
        boolean includeCpaas = productList.contains("cpaas");

        StringBuilder authFields = new StringBuilder();
        if (includeCpaas) {
            authFields.append("\"token\":\"BASE64_OF_API_KEY:API_SECRET\",");
            authFields.append("\"from_number\":\"YOUR_EXOTEL_VIRTUAL_NUMBER\",");
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

    private String cpaasGuide(AuthCredentials creds) {
        String status = creds.hasCpaasCredentials() ? "CONFIGURED" : "NOT CONFIGURED";
        return "## CPaaS - SMS & Voice [" + status + "]\n\n"
            + "Send SMS messages and initiate voice calls via Exotel's CPaaS platform.\n\n"
            + "**Credentials needed:**\n"
            + "- `token` — Base64 encoded `api_key:api_secret` from my.exotel.com → API Settings\n"
            + "- `account_sid` — Your Exotel account SID\n"
            + "- `from_number` — Your Exotel virtual phone number\n"
            + "- `api_domain` — (optional) defaults to https://api.exotel.com\n\n"
            + "**What you can do:**\n"
            + "- Send SMS (single and bulk)\n"
            + "- Initiate voice calls\n"
            + "- Connect two numbers\n"
            + "- Check delivery status\n\n";
    }

    private String configSnippet() {
        return "---\n\n"
            + "## Quick Start\n\n"
            + "Use `exotel_build_config` to generate a ready-to-paste config for your client.\n"
            + "Example: ask me to \"build config for cursor with voicebot and cqa\"\n";
    }
}
