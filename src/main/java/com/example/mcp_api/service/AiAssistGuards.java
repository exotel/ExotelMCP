package com.example.mcp_api.service;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Input validation for AI Assist MCP tools. ponytail: package-visible for unit tests. */
final class AiAssistGuards {

    static final Pattern ACCOUNT_SID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");
    static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]{1,128}$");
    static final Pattern AZURE_LOCALE_PATTERN = Pattern.compile("^[a-zA-Z]{2,3}-[a-zA-Z]{2,4}$");

    static final Set<String> VALID_SOURCES = Set.of("ameyo_6x", "ameyo_6_0", "exolite");

    private AiAssistGuards() {}

    static boolean isValidId(String id) {
        return id != null && !id.isBlank() && ID_PATTERN.matcher(id).matches();
    }

    static boolean isValidAccountSid(String sid) {
        return sid != null && !sid.isBlank() && ACCOUNT_SID_PATTERN.matcher(sid).matches();
    }

    static boolean isValidSource(String source) {
        return source != null && VALID_SOURCES.contains(source);
    }

    static boolean isValidAzureLocale(String locale) {
        return locale != null && AZURE_LOCALE_PATTERN.matcher(locale).matches();
    }

    /**
     * Reject non-https URLs and SSRF vectors (localhost, private/link-local ranges).
     * Returns null when the URL is allowed.
     */
    static String validateFetchUrl(String fileUrl) {
        URI uri;
        try {
            uri = URI.create(fileUrl);
        } catch (Exception e) {
            return "malformed URL";
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) {
            return "fileUrl must be https (got " + uri.getScheme() + ")";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) return "URL is missing a host";
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()
                        || addr.isMulticastAddress()) {
                    return "URL resolves to a non-public address (" + addr.getHostAddress() + ")";
                }
            }
        } catch (Exception e) {
            return "URL host does not resolve: " + e.getMessage();
        }
        return null;
    }

    /** Returns null when the write is allowed, otherwise a JSON error envelope. */
    static String writeGateError(Boolean confirm, String action) {
        if (!Boolean.TRUE.equals(confirm)) {
            return AiAssistJson.errorJson("confirm_required",
                    "Refusing to perform write '" + action + "' without confirm=true. "
                  + "Re-issue the tool call with confirm=true after reviewing the arguments.");
        }
        return null;
    }

    static String validateGeneralConfig(Map<String, Object> parsedConfig, String source) {
        String err = validatePiiRedaction(parsedConfig);
        if (err != null) return err;
        err = validateTranscriptAndSentimentToggles(parsedConfig);
        if (err != null) return err;
        err = validateSuggestions(parsedConfig);
        if (err != null) return err;
        return validateDispositionConfigForSource(parsedConfig, source);
    }

    private static String validatePiiRedaction(Map<String, Object> parsedConfig) {
        if (!(parsedConfig.get("mask_sensitive_data") instanceof Boolean)) {
            return AiAssistJson.errorJson("mask_sensitive_data_required",
                    "generalConfig.mask_sensitive_data (bool) is required. AI Assist UI label 'Mask Sensitive Data'. "
                  + "Ask the user; AI Assist UI default is TRUE. When TRUE, also collect pii_redaction_prompts.");
        }
        if (!(Boolean) parsedConfig.get("mask_sensitive_data")) return null;

        Object promptsObj = parsedConfig.get("pii_redaction_prompts");
        if (!(promptsObj instanceof List<?> prompts) || prompts.isEmpty()) {
            return AiAssistJson.errorJson("pii_redaction_prompts_required",
                    "mask_sensitive_data=TRUE, so generalConfig.pii_redaction_prompts (non-empty array of 10\u2013500 char strings) is required. "
                  + "ASK THE USER for concrete redaction rules. Examples: 'Redact 10-digit Indian phone numbers', "
                  + "'Redact 6-digit OTPs', 'Redact PAN and Aadhaar numbers'. Do NOT invent them silently.");
        }
        if (prompts.size() > 20) {
            return AiAssistJson.errorJson("pii_redaction_prompts_too_many",
                    "pii_redaction_prompts max 20 items (got " + prompts.size() + ").");
        }
        for (Object p : prompts) {
            if (!(p instanceof String s) || s.trim().length() < 10 || s.trim().length() > 500) {
                return AiAssistJson.errorJson("pii_redaction_prompt_invalid",
                        "Each pii_redaction_prompt must be a string 10\u2013500 chars. Bad entry: " + p);
            }
        }
        return null;
    }

    private static String validateTranscriptAndSentimentToggles(Map<String, Object> parsedConfig) {
        if (!(parsedConfig.get("live_transcript") instanceof Boolean)) {
            return AiAssistJson.errorJson("live_transcript_required",
                    "generalConfig.live_transcript (bool) is required. AI Assist UI label 'Real Time Transcription'. Default TRUE.");
        }
        if (!(parsedConfig.get("user_sentiment") instanceof Boolean)) {
            return AiAssistJson.errorJson("user_sentiment_required",
                    "generalConfig.user_sentiment (bool) is required. AI Assist UI label 'Real Time Sentiments'. Default TRUE.");
        }
        return null;
    }

    private static String validateSuggestions(Map<String, Object> parsedConfig) {
        if (!(parsedConfig.get("suggestions") instanceof Map<?, ?> sRaw)) {
            return AiAssistJson.errorJson("suggestions_required",
                    "generalConfig.suggestions object is required. Shape: "
                  + "{enabled: bool, max_words_per_reply: int \u226510, feedback_enabled: bool}. AI Assist UI default enabled=TRUE.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> suggestions = (Map<String, Object>) sRaw;
        if (!(suggestions.get("enabled") instanceof Boolean)) {
            return AiAssistJson.errorJson("suggestions_enabled_required",
                    "generalConfig.suggestions.enabled (bool) is required. AI Assist UI label 'Real Time Smart Reply'.");
        }
        if (!(Boolean) suggestions.get("enabled")) return null;

        Object mw = suggestions.get("max_words_per_reply");
        if (!(mw instanceof Number mwn) || mwn.intValue() < 10) {
            return AiAssistJson.errorJson("max_words_per_reply_required",
                    "suggestions.enabled=TRUE, so generalConfig.suggestions.max_words_per_reply (int \u2265 10) is required. "
                  + "ASK THE USER \u2014 AI Assist UI default is 20 but the user should get to choose.");
        }
        if (!(suggestions.get("feedback_enabled") instanceof Boolean)) {
            return AiAssistJson.errorJson("feedback_enabled_required",
                    "suggestions.enabled=TRUE, so generalConfig.suggestions.feedback_enabled (bool) is required. "
                  + "AI Assist UI label 'Smart Reply Feedback'. Default FALSE. When TRUE, feedback options default to "
                  + "['Irrelevant','Wrong','Outdated'] but can be overridden with up to 3 custom labels (\u2264 15 chars each).");
        }
        return null;
    }

    /** Ameyo sources REQUIRE disposition_config; exolite hides it in the AI Assist UI so we strip it. */
    private static String validateDispositionConfigForSource(Map<String, Object> parsedConfig, String source) {
        boolean sourceShowsDisposition = "ameyo_6x".equals(source) || "ameyo_6_0".equals(source);
        if (!sourceShowsDisposition) {
            parsedConfig.remove("disposition_config");
            return null;
        }
        if (!(parsedConfig.get("disposition_config") instanceof Map<?, ?> dcRaw)) {
            return AiAssistJson.errorJson("disposition_config_required",
                    "source='" + source + "' shows 'Disposition Suggestions' in the AI Assist UI \u2014 "
                  + "generalConfig.disposition_config object is required. Shape: "
                  + "{disposition: bool, notes: bool, allow_agent_edit_notes: bool}. All default FALSE.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> dc = (Map<String, Object>) dcRaw;
        for (String k : List.of("disposition", "notes", "allow_agent_edit_notes")) {
            if (!(dc.get(k) instanceof Boolean)) {
                return AiAssistJson.errorJson("disposition_config_" + k + "_required",
                        "disposition_config." + k + " (bool) is required for source " + source + ".");
            }
        }
        return null;
    }
}
