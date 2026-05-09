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
}
