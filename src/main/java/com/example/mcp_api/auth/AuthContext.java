package com.example.mcp_api.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Static helper to retrieve AuthCredentials from the current HTTP request.
 * Use this in any @Tool method to access parsed credentials.
 */
public final class AuthContext {

    private AuthContext() {}

    /**
     * Get the AuthCredentials for the current request.
     * Returns empty (unparsed) credentials if no request context or no header.
     */
    public static AuthCredentials current() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpServletRequest request = attrs.getRequest();
            Object creds = request.getAttribute(AuthCredentials.REQUEST_ATTRIBUTE);
            if (creds instanceof AuthCredentials ac) {
                return ac;
            }
        } catch (Exception ignored) {
        }
        return AuthCredentials.parse(null);
    }

    /**
     * Require that specific product credentials are present.
     * Returns a helpful setup message if not configured.
     */
    public static String requireVoicebot() {
        AuthCredentials creds = current();
        if (!creds.hasVoicebotCredentials()) {
            return "Missing VoiceBot credentials.\n\n"
                + "Add these fields to your Authorization header:\n"
                + "  - voicebot_api_key\n"
                + "  - voicebot_api_token\n"
                + "  - voicebot_account_id\n\n"
                + "Get them from the VoiceBot Dashboard → Settings → API Keys.\n"
                + "For setup help, use the tool: exotel_setup_guide";
        }
        return null;
    }

    public static String requireCalls() {
        AuthCredentials creds = current();
        if (!creds.hasCallsCredentials() && !creds.hasVoicebotCredentials()) {
            return "Missing Calls API credentials.\n\n"
                + "Add these fields to your Authorization header:\n"
                + "  - calls_api_key\n"
                + "  - calls_api_token\n"
                + "  - calls_account_id\n\n"
                + "Get them from my.exotel.com → API Settings.\n"
                + "For setup help, use the tool: exotel_setup_guide";
        }
        return null;
    }

    public static String requireCqa() {
        AuthCredentials creds = current();
        if (!creds.hasCqaCredentials()) {
            return "Missing CQA credentials.\n\n"
                + "Add these fields to your Authorization header:\n"
                + "  - cqa_api_key\n"
                + "  - cqa_account_id\n"
                + "  - cqa_host (optional, defaults to https://cqa-console.in.exotel.com)\n\n"
                + "Get them from the CQA Console → Settings → API Keys.\n"
                + "For setup help, use the tool: exotel_setup_guide";
        }
        return null;
    }

    public static String requireCpaas() {
        AuthCredentials creds = current();
        if (!creds.hasCpaasCredentials()) {
            return "Missing CPaaS credentials.\n\n"
                + "Add these fields to your Authorization header:\n"
                + "  - token (Base64 of api_key:api_secret)\n"
                + "  - account_sid\n"
                + "  - from_number (your Exotel virtual number)\n\n"
                + "Get them from my.exotel.com → API Settings.\n"
                + "For setup help, use the tool: exotel_setup_guide";
        }
        return null;
    }

    public static String requireAdmin() {
        AuthCredentials creds = current();
        if (!creds.hasAdminCredentials()) {
            return "Missing admin credentials.\n\n"
                + "Add these fields to your Authorization header:\n"
                + "  - admin_username\n"
                + "  - admin_password\n\n"
                + "Contact your Exotel account manager for admin access.\n"
                + "For setup help, use the tool: exotel_setup_guide";
        }
        return null;
    }

    public static String requireToolsServer() {
        AuthCredentials creds = current();
        if (!creds.hasToolsServerCredentials()) {
            return "Missing Tools Server credentials.\n\n"
                + "Add these fields to your Authorization header:\n"
                + "  - tools_server_api_key\n"
                + "  - tools_server_tenant_id\n"
                + "  - tools_server_api_token (optional)\n"
                + "  - tools_server_base_url (optional, defaults to https://tools-server-prod.mum1.exotel.com)\n\n"
                + "Note: voicebot_api_key / voicebot_api_token / voicebot_account_id are used automatically as fallback.\n"
                + "For setup help, use the tool: exotel_setup_guide";
        }
        return null;
    }

    public static String requireAiAssist() {
        AuthCredentials creds = current();
        if (!creds.hasAiAssistCredentials()) {
            return "Missing AI Assist credentials.\n\n"
                + "LLM WIZARD (do this once per user, then persist):\n"
                + "  1. Ask the user for the four values below \u2014 use AskQuestion.\n"
                + "  2. Write them into the user's Cursor mcp.json under this server's `headers.Authorization`\n"
                + "     as a single-line 'Bearer <json>' envelope. Example shape:\n"
                + "        \"headers\": {\n"
                + "          \"Authorization\": \"Bearer {\\\"ai_assist_base_url\\\":\\\"https://ai-assist.in.exotel.com\\\",\\\"ai_assist_account_sid\\\":\\\"<sid>\\\",\\\"ai_assist_auth_key\\\":\\\"<key>\\\",\\\"ai_assist_auth_secret\\\":\\\"<secret>\\\"}\"\n"
                + "        }\n"
                + "  3. Ask the user to reload MCP servers in Cursor (Cmd+Shift+P \u2192 'Reload MCP Servers').\n"
                + "  4. Retry the original tool call.\n\n"
                + "REQUIRED fields to collect from the user:\n"
                + "  - ai_assist_base_url        (prod: https://ai-assist.in.exotel.com)\n"
                + "  - ai_assist_account_sid     (e.g. your_tenant_sid; the AI Assist tenant ID)\n"
                + "  - ai_assist_auth_key        (CPaaS API key \u2014 Exotel dashboard \u2192 Settings \u2192 API \u2192 API Credentials)\n"
                + "  - ai_assist_auth_secret     (CPaaS API token \u2014 same page as above)\n\n"
                + "Alternate auth modes (only if the customer explicitly wants them \u2014 default to Twilix Basic above):\n"
                + "  B. Auth0 M2M client credentials (operator/internal use; broad scope):\n"
                + "     - ai_assist_client_id / ai_assist_client_secret\n"
                + "     Optional overrides: ai_assist_auth_token_url, ai_assist_auth_audience\n"
                + "  C. Manual bearer paste (short-lived): ai_assist_auth_token\n"
                + "  D. Session cookie paste (fallback for pre-M2M tenants): ai_assist_session_cookie\n\n"
                + "Optional:\n"
                + "  - ai_assist_context_path    (defaults to ai-assist/api)\n"
                + "  - ai_assist_user_id         (for audit)";
        }
        return null;
    }
}
