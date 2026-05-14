package com.example.mcp_api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * Parsed credential envelope from the Authorization header.
 * Created once per request by AuthFilter and stored as a request attribute.
 *
 * Products read only the fields they need — if a field is null, that product's
 * credentials weren't supplied.
 */
public class AuthCredentials {

    private static final Logger logger = LoggerFactory.getLogger(AuthCredentials.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static final String REQUEST_ATTRIBUTE = "exotel.auth.credentials";

    // CPaaS
    private String token;
    private String fromNumber;
    private String callerId;
    private String apiDomain;
    private String accountSid;
    private String exotelPortalUrl;
    private String dltTemp;
    private String dltEntity;

    // VoiceBot
    private String voicebotApiKey;
    private String voicebotApiToken;
    private String voicebotAccountId;
    private String voicebotBaseUrl;

    // Calls API (for bot dialing)
    private String callsApiKey;
    private String callsApiToken;
    private String callsAccountId;
    private String callsBaseUrl;

    // VoiceBot Admin
    private String adminUsername;
    private String adminPassword;

    // CQA
    private String cqaApiKey;
    private String cqaAccountId;
    private String cqaHost;

    // Tools Server
    private String toolsServerApiKey;
    private String toolsServerApiToken;
    private String toolsServerTenantId;
    private String toolsServerBaseUrl;

    // Raw header for fallback
    private String rawHeader;
    private boolean parsed;

    private AuthCredentials() {}

    /**
     * Parse an Authorization header value into typed credentials.
     * Accepts: raw JSON, Bearer JSON, or Basic JSON.
     * Returns an empty (unparsed) AuthCredentials if header is null/blank.
     */
    public static AuthCredentials parse(String authHeader) {
        AuthCredentials creds = new AuthCredentials();
        creds.rawHeader = authHeader;

        if (authHeader == null || authHeader.isBlank()) {
            creds.parsed = false;
            return creds;
        }

        try {
            String json = authHeader.trim();
            if (json.startsWith("Bearer ")) json = json.substring(7).trim();
            else if (json.startsWith("Basic ")) json = json.substring(6).trim();

            json = json.replace('\'', '"');
            if (!json.startsWith("{")) json = "{" + json + "}";

            JsonNode root = objectMapper.readTree(json);

            // CPaaS
            creds.token = textField(root, "token");
            creds.fromNumber = textField(root, "from_number");
            creds.callerId = textField(root, "caller_id");
            creds.apiDomain = textField(root, "api_domain");
            creds.accountSid = textField(root, "account_sid");
            creds.exotelPortalUrl = textField(root, "exotel_portal_url");
            creds.dltTemp = textField(root, "dlt_temp");
            creds.dltEntity = textField(root, "dlt_entity");

            // VoiceBot
            creds.voicebotApiKey = textField(root, "voicebot_api_key");
            creds.voicebotApiToken = textField(root, "voicebot_api_token");
            creds.voicebotAccountId = textField(root, "voicebot_account_id");
            creds.voicebotBaseUrl = textField(root, "voicebot_base_url");

            // Calls
            creds.callsApiKey = textField(root, "calls_api_key");
            creds.callsApiToken = textField(root, "calls_api_token");
            creds.callsAccountId = textField(root, "calls_account_id");
            creds.callsBaseUrl = textField(root, "calls_base_url");

            // Admin
            creds.adminUsername = textField(root, "admin_username");
            creds.adminPassword = textField(root, "admin_password");

            // CQA
            creds.cqaApiKey = textField(root, "cqa_api_key");
            creds.cqaAccountId = textField(root, "cqa_account_id");
            creds.cqaHost = textField(root, "cqa_host");

            // Tools Server
            creds.toolsServerApiKey = textField(root, "tools_server_api_key");
            creds.toolsServerApiToken = textField(root, "tools_server_api_token");
            creds.toolsServerTenantId = textField(root, "tools_server_tenant_id");
            creds.toolsServerBaseUrl = textField(root, "tools_server_base_url");

            creds.parsed = true;
        } catch (Exception e) {
            logger.debug("Could not parse Authorization header as JSON: {}", e.getMessage());
            creds.parsed = false;
        }

        return creds;
    }

    private static String textField(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull()) return null;
        String text = node.asText();
        return (text != null && !text.isBlank()) ? text : null;
    }

    // ======================== PRODUCT CHECKS ========================

    public boolean hasCpaasCredentials() {
        return token != null && !token.isBlank();
    }

    public boolean hasVoicebotCredentials() {
        return voicebotApiKey != null && !voicebotApiKey.isBlank()
            && voicebotApiToken != null && !voicebotApiToken.isBlank()
            && voicebotAccountId != null && !voicebotAccountId.isBlank();
    }

    public boolean hasCallsCredentials() {
        return callsApiKey != null && !callsApiKey.isBlank()
            && callsApiToken != null && !callsApiToken.isBlank()
            && callsAccountId != null && !callsAccountId.isBlank();
    }

    public boolean hasAdminCredentials() {
        return adminUsername != null && !adminUsername.isBlank()
            && adminPassword != null && !adminPassword.isBlank();
    }

    public boolean hasCqaCredentials() {
        return cqaApiKey != null && !cqaApiKey.isBlank()
            && cqaAccountId != null && !cqaAccountId.isBlank();
    }

    public boolean hasToolsServerCredentials() {
        return toolsServerApiKey != null && !toolsServerApiKey.isBlank()
            && toolsServerTenantId != null && !toolsServerTenantId.isBlank();
    }

    public boolean isParsed() { return parsed; }

    // ======================== DERIVED VALUES ========================

    public String voicebotBasicToken() {
        return Base64.getEncoder().encodeToString(
            (voicebotApiKey + ":" + voicebotApiToken).getBytes(StandardCharsets.UTF_8));
    }

    public String callsBasicToken() {
        String key = callsApiKey != null ? callsApiKey : voicebotApiKey;
        String tok = callsApiToken != null ? callsApiToken : voicebotApiToken;
        return Base64.getEncoder().encodeToString(
            (key + ":" + tok).getBytes(StandardCharsets.UTF_8));
    }

    public String adminBasicToken() {
        return Base64.getEncoder().encodeToString(
            (adminUsername + ":" + adminPassword).getBytes(StandardCharsets.UTF_8));
    }

    public String toolsServerBasicToken() {
        String tok = toolsServerApiToken != null ? toolsServerApiToken : "";
        return Base64.getEncoder().encodeToString(
            (toolsServerApiKey + ":" + tok).getBytes(StandardCharsets.UTF_8));
    }

    public String effectiveToolsServerBaseUrl(String defaultUrl) {
        return toolsServerBaseUrl != null ? toolsServerBaseUrl : defaultUrl;
    }

    public String effectiveVoicebotBaseUrl(String defaultUrl) {
        return voicebotBaseUrl != null ? voicebotBaseUrl : defaultUrl;
    }

    public String effectiveCallsBaseUrl(String defaultUrl) {
        return callsBaseUrl != null ? callsBaseUrl : defaultUrl;
    }

    public String effectiveCallsAccountId() {
        return callsAccountId != null ? callsAccountId : voicebotAccountId;
    }

    public String effectiveCqaHost(String defaultHost) {
        return cqaHost != null ? cqaHost : defaultHost;
    }

    // ======================== GETTERS ========================

    public String getToken() { return token; }
    public String getFromNumber() { return fromNumber; }
    public String getCallerId() { return callerId; }
    public String getApiDomain() { return apiDomain; }
    public String getAccountSid() { return accountSid; }
    public String getExotelPortalUrl() { return exotelPortalUrl; }
    public String getDltTemp() { return dltTemp; }
    public String getDltEntity() { return dltEntity; }
    public String getVoicebotApiKey() { return voicebotApiKey; }
    public String getVoicebotApiToken() { return voicebotApiToken; }
    public String getVoicebotAccountId() { return voicebotAccountId; }
    public String getVoicebotBaseUrl() { return voicebotBaseUrl; }
    public String getCallsApiKey() { return callsApiKey; }
    public String getCallsApiToken() { return callsApiToken; }
    public String getCallsAccountId() { return callsAccountId; }
    public String getCallsBaseUrl() { return callsBaseUrl; }
    public String getAdminUsername() { return adminUsername; }
    public String getAdminPassword() { return adminPassword; }
    public String getCqaApiKey() { return cqaApiKey; }
    public String getCqaAccountId() { return cqaAccountId; }
    public String getCqaHost() { return cqaHost; }
    public String getToolsServerApiKey() { return toolsServerApiKey; }
    public String getToolsServerApiToken() { return toolsServerApiToken; }
    public String getToolsServerTenantId() { return toolsServerTenantId; }
    public String getToolsServerBaseUrl() { return toolsServerBaseUrl; }
    public String getRawHeader() { return rawHeader; }

    /**
     * Returns a summary of which products have credentials configured.
     * Safe for logging (no secrets exposed).
     */
    public String configuredProductsSummary() {
        StringBuilder sb = new StringBuilder();
        if (hasCpaasCredentials()) sb.append("CPaaS ");
        if (hasVoicebotCredentials()) sb.append("VoiceBot ");
        if (hasCallsCredentials()) sb.append("Calls ");
        if (hasAdminCredentials()) sb.append("Admin ");
        if (hasCqaCredentials()) sb.append("CQA ");
        if (hasToolsServerCredentials()) sb.append("ToolsServer ");
        return sb.length() > 0 ? sb.toString().trim() : "NONE";
    }
}
