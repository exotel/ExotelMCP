package com.example.mcp_api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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

    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9+/=_-]+$");

    /** The sample placeholders shipped in claude.json / README / USAGE: YOUR_..., <...>, xxx (optionally behind https://). */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "^(?:https?://)?(?:your_[a-z0-9_]+|<[^<>]*>|x{3,})$", Pattern.CASE_INSENSITIVE);

    /**
     * A group of header fields that authenticates one Exotel product. Every set that is present in
     * the header must be complete and (where Exotel offers a safe check) verified at login.
     * {@code fieldList} is what error messages show; {@code fields} are all fields the set owns.
     */
    public enum CredentialSet {
        CORE("Core credentials", "token, account_sid", "token", "account_sid", "api_domain"),
        CALLS("Calls credentials", "calls_api_key, calls_api_token, calls_account_id",
                "calls_api_key", "calls_api_token", "calls_account_id", "calls_base_url"),
        VOICEBOT("VoiceBot credentials", "voicebot_api_key, voicebot_api_token, voicebot_account_id",
                "voicebot_api_key", "voicebot_api_token", "voicebot_account_id", "voicebot_base_url"),
        ADMIN("Admin credentials", "admin_username, admin_password, voicebot_account_id",
                "admin_username", "admin_password"),
        CQA("CQA credentials", "cqa_api_key, cqa_account_id", "cqa_api_key", "cqa_account_id", "cqa_host"),
        TOOLS_SERVER("Tools Server credentials", "tools_server_api_key, tools_server_tenant_id",
                "tools_server_api_key", "tools_server_api_token", "tools_server_tenant_id", "tools_server_base_url"),
        AI_ASSIST("AI Assist credentials",
                "ai_assist_base_url, ai_assist_account_sid and one auth mode: ai_assist_auth_key + ai_assist_auth_secret, "
                        + "ai_assist_auth_token, ai_assist_session_cookie or ai_assist_client_id + ai_assist_client_secret",
                "ai_assist_base_url", "ai_assist_context_path", "ai_assist_account_sid", "ai_assist_auth_token",
                "ai_assist_session_cookie", "ai_assist_user_id", "ai_assist_client_id", "ai_assist_client_secret",
                "ai_assist_auth_token_url", "ai_assist_auth_audience", "ai_assist_auth_key", "ai_assist_auth_secret");

        private final String label;
        private final String fieldList;
        private final List<String> fields;

        CredentialSet(String label, String fieldList, String... fields) {
            this.label = label;
            this.fieldList = fieldList;
            this.fields = List.of(fields);
        }

        public String label() { return label; }

        /** For example {@code Calls credentials (calls_api_key, calls_api_token, calls_account_id)}. */
        public String describe() { return label + " (" + fieldList + ")"; }
    }

    // CPaaS
    private String token;
    private String fromNumber;
    private String whatsappFrom;
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

    // AI Assist. Any ONE of these auth modes is sufficient:
    //   - Twilix Basic: ai_assist_auth_key + ai_assist_auth_secret (CPaaS API creds; per-tenant scope).
    //   - Manual JWT: ai_assist_auth_token.
    //   - Session cookie: ai_assist_session_cookie (from browser DevTools).
    //   - Auth0 M2M: ai_assist_client_id + ai_assist_client_secret (exchanged for bearer, cached).
    private String aiAssistBaseUrl;
    private String aiAssistContextPath;
    private String aiAssistAccountSid;
    private String aiAssistAuthToken;
    private String aiAssistSessionCookie;
    private String aiAssistUserId;
    private String aiAssistClientId;
    private String aiAssistClientSecret;
    private String aiAssistAuthTokenUrl;
    private String aiAssistAuthAudience;
    private String aiAssistAuthKey;
    private String aiAssistAuthSecret;

    /** Every known field the caller supplied, including blank ones (blank fields are read as absent). */
    private final Map<String, String> suppliedFields = new LinkedHashMap<>();

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
            creds.token = creds.textField(root, "token");
            creds.fromNumber = creds.textField(root, "from_number");
            creds.whatsappFrom = creds.textField(root, "whatsapp_from");
            creds.callerId = creds.textField(root, "caller_id");
            creds.apiDomain = creds.textField(root, "api_domain");
            creds.accountSid = creds.textField(root, "account_sid");
            creds.exotelPortalUrl = creds.textField(root, "exotel_portal_url");
            creds.dltTemp = creds.textField(root, "dlt_temp");
            creds.dltEntity = creds.textField(root, "dlt_entity");

            // VoiceBot
            creds.voicebotApiKey = creds.textField(root, "voicebot_api_key");
            creds.voicebotApiToken = creds.textField(root, "voicebot_api_token");
            creds.voicebotAccountId = creds.textField(root, "voicebot_account_id");
            creds.voicebotBaseUrl = creds.textField(root, "voicebot_base_url");

            // Calls
            creds.callsApiKey = creds.textField(root, "calls_api_key");
            creds.callsApiToken = creds.textField(root, "calls_api_token");
            creds.callsAccountId = creds.textField(root, "calls_account_id");
            creds.callsBaseUrl = creds.textField(root, "calls_base_url");

            // Admin
            creds.adminUsername = creds.textField(root, "admin_username");
            creds.adminPassword = creds.textField(root, "admin_password");

            // CQA
            creds.cqaApiKey = creds.textField(root, "cqa_api_key");
            creds.cqaAccountId = creds.textField(root, "cqa_account_id");
            creds.cqaHost = creds.textField(root, "cqa_host");

            // Tools Server
            creds.toolsServerApiKey = creds.textField(root, "tools_server_api_key");
            creds.toolsServerApiToken = creds.textField(root, "tools_server_api_token");
            creds.toolsServerTenantId = creds.textField(root, "tools_server_tenant_id");
            creds.toolsServerBaseUrl = creds.textField(root, "tools_server_base_url");

            // AI Assist
            creds.aiAssistBaseUrl       = creds.textField(root, "ai_assist_base_url");
            creds.aiAssistContextPath   = creds.textField(root, "ai_assist_context_path");
            creds.aiAssistAccountSid    = creds.textField(root, "ai_assist_account_sid");
            creds.aiAssistAuthToken     = creds.textField(root, "ai_assist_auth_token");
            creds.aiAssistSessionCookie = creds.textField(root, "ai_assist_session_cookie");
            creds.aiAssistUserId        = creds.textField(root, "ai_assist_user_id");
            creds.aiAssistClientId      = creds.textField(root, "ai_assist_client_id");
            creds.aiAssistClientSecret  = creds.textField(root, "ai_assist_client_secret");
            creds.aiAssistAuthTokenUrl  = creds.textField(root, "ai_assist_auth_token_url");
            creds.aiAssistAuthAudience  = creds.textField(root, "ai_assist_auth_audience");
            creds.aiAssistAuthKey       = creds.textField(root, "ai_assist_auth_key");
            creds.aiAssistAuthSecret    = creds.textField(root, "ai_assist_auth_secret");

            creds.parsed = true;
        } catch (Exception e) {
            logger.debug("Could not parse Authorization header as JSON: {}", e.getMessage());
            creds.parsed = false;
        }

        return creds;
    }

    private String textField(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull()) return null;
        String text = node.asText();
        suppliedFields.put(field, text == null ? "" : text);
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

    public boolean hasAiAssistCredentials() {
        boolean hasAuthMaterial =
                hasAiAssistBasicCredentials()
             || (aiAssistAuthToken != null && !aiAssistAuthToken.isBlank())
             || (aiAssistSessionCookie != null && !aiAssistSessionCookie.isBlank())
             || hasAiAssistClientCredentials();
        return aiAssistBaseUrl != null && !aiAssistBaseUrl.isBlank()
            && hasAuthMaterial
            && aiAssistAccountSid != null && !aiAssistAccountSid.isBlank();
    }

    public boolean hasAiAssistClientCredentials() {
        return aiAssistClientId != null && !aiAssistClientId.isBlank()
            && aiAssistClientSecret != null && !aiAssistClientSecret.isBlank()
            && aiAssistAccountSid != null && !aiAssistAccountSid.isBlank();
    }

    public boolean hasAiAssistBasicCredentials() {
        return aiAssistAuthKey != null && !aiAssistAuthKey.isBlank()
            && aiAssistAuthSecret != null && !aiAssistAuthSecret.isBlank()
            && aiAssistAccountSid != null && !aiAssistAccountSid.isBlank();
    }

    public boolean isParsed() { return parsed; }

    /**
     * Validates every caller-supplied host/base URL and every identifier that is interpolated
     * into a URL path. Throws ExotelHostGuard.InvalidCredentialException on the first bad field.
     */
    public void validateFields() {
        rejectPlaceholders();
        hostField("api_domain", apiDomain);
        hostField("exotel_portal_url", exotelPortalUrl);
        hostField("voicebot_base_url", voicebotBaseUrl);
        hostField("calls_base_url", callsBaseUrl);
        hostField("cqa_host", cqaHost);
        hostField("tools_server_base_url", toolsServerBaseUrl);
        hostField("ai_assist_base_url", aiAssistBaseUrl);
        hostField("ai_assist_auth_token_url", aiAssistAuthTokenUrl);
        if (aiAssistContextPath != null) ExotelHostGuard.requireSafePath("ai_assist_context_path", aiAssistContextPath);

        idField("account_sid", accountSid);
        idField("voicebot_account_id", voicebotAccountId);
        idField("calls_account_id", callsAccountId);
        idField("cqa_account_id", cqaAccountId);
        idField("tools_server_tenant_id", toolsServerTenantId);
        idField("ai_assist_account_sid", aiAssistAccountSid);

        if (token != null && !TOKEN_PATTERN.matcher(token).matches()) {
            throw new ExotelHostGuard.InvalidCredentialException("token contains invalid characters");
        }
    }

    /**
     * Rejects fields that are empty or still hold a sample placeholder, naming the field (and its set)
     * but never the value, so a half-edited sample header fails with a clear message instead of a generic one.
     */
    private void rejectPlaceholders() {
        for (Map.Entry<String, String> e : suppliedFields.entrySet()) {
            String value = e.getValue().trim();
            String problem;
            if (value.isEmpty()) {
                problem = "is empty";
            } else if (PLACEHOLDER.matcher(value).matches()) {
                problem = "still holds the sample placeholder";
            } else {
                continue;
            }
            CredentialSet set = setOwning(e.getKey());
            throw new ExotelHostGuard.InvalidCredentialException((set != null ? set.label() + ": " : "")
                    + e.getKey() + " " + problem + ". Remove this field or fill in your real value.");
        }
    }

    private static CredentialSet setOwning(String field) {
        for (CredentialSet set : CredentialSet.values()) {
            if (set.fields.contains(field)) return set;
        }
        return null;
    }

    private static void hostField(String name, String value) {
        if (value != null) ExotelHostGuard.requireAllowedBaseUrl(name, value);
    }

    private static void idField(String name, String value) {
        if (value != null) ExotelHostGuard.requireSafeId(name, value);
    }

    /** Core Exotel API credentials: Base64(api_key:api_token) plus the account sid. */
    public boolean hasCoreExotelCredentials() {
        return token != null && accountSid != null;
    }

    /**
     * Sets with at least one of their own credential fields in the header, complete or not.
     * voicebot_account_id alone does not make a set present: Calls, Tools Server and Admin share it.
     */
    public List<CredentialSet> presentSets() {
        List<CredentialSet> present = new ArrayList<>();
        if (token != null || accountSid != null) present.add(CredentialSet.CORE);
        if (callsApiKey != null || callsApiToken != null || callsAccountId != null) present.add(CredentialSet.CALLS);
        if (voicebotApiKey != null || voicebotApiToken != null) present.add(CredentialSet.VOICEBOT);
        if (adminUsername != null || adminPassword != null) present.add(CredentialSet.ADMIN);
        if (cqaApiKey != null || cqaAccountId != null) present.add(CredentialSet.CQA);
        if (toolsServerApiKey != null || toolsServerApiToken != null || toolsServerTenantId != null) {
            present.add(CredentialSet.TOOLS_SERVER);
        }
        if (aiAssistBaseUrl != null || aiAssistAccountSid != null || aiAssistAuthToken != null
                || aiAssistSessionCookie != null || aiAssistClientId != null || aiAssistClientSecret != null
                || aiAssistAuthKey != null || aiAssistAuthSecret != null) {
            present.add(CredentialSet.AI_ASSIST);
        }
        return present;
    }

    /** True if the given set has every field it needs. */
    public boolean isComplete(CredentialSet set) {
        return switch (set) {
            case CORE -> hasCoreExotelCredentials();
            case CALLS -> hasCallsCredentials();
            case VOICEBOT -> hasVoicebotCredentials();
            case ADMIN -> hasAdminCredentials() && voicebotAccountId != null;
            case CQA -> hasCqaCredentials();
            case TOOLS_SERVER -> hasToolsServerCredentials();
            case AI_ASSIST -> hasAiAssistCredentials();
        };
    }

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

    // Caller-supplied hosts must pass ExotelHostGuard; operator-set defaults are returned as-is.
    public String effectiveApiDomain(String defaultUrl) {
        return apiDomain != null ? ExotelHostGuard.requireApiHost("api_domain", apiDomain) : defaultUrl;
    }

    public String effectiveToolsServerBaseUrl(String defaultUrl) {
        return toolsServerBaseUrl != null
            ? ExotelHostGuard.requireAllowedBaseUrl("tools_server_base_url", toolsServerBaseUrl) : defaultUrl;
    }

    public String effectiveVoicebotBaseUrl(String defaultUrl) {
        return voicebotBaseUrl != null
            ? ExotelHostGuard.requireAllowedBaseUrl("voicebot_base_url", voicebotBaseUrl) : defaultUrl;
    }

    public String effectiveCallsBaseUrl(String defaultUrl) {
        return callsBaseUrl != null
            ? ExotelHostGuard.requireApiHost("calls_base_url", callsBaseUrl) : defaultUrl;
    }

    public String effectiveCallsAccountId() {
        return callsAccountId != null ? callsAccountId : voicebotAccountId;
    }

    public String effectiveCqaHost(String defaultHost) {
        return cqaHost != null ? ExotelHostGuard.requireAllowedBaseUrl("cqa_host", cqaHost) : defaultHost;
    }

    public String aiAssistBearerHeader() {
        return aiAssistAuthToken != null ? "Bearer " + aiAssistAuthToken : null;
    }

    /**
     * Constructs the "Basic <base64>" header for Twilix Basic (CPaaS auth_key:auth_token) mode.
     * Returns null if key/secret are not both set.
     */
    public String aiAssistBasicHeader() {
        if (aiAssistAuthKey == null || aiAssistAuthKey.isBlank()
                || aiAssistAuthSecret == null || aiAssistAuthSecret.isBlank()) {
            return null;
        }
        String encoded = Base64.getEncoder().encodeToString(
                (aiAssistAuthKey + ":" + aiAssistAuthSecret).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    public String effectiveAiAssistBaseUrl(String defaultUrl) {
        return aiAssistBaseUrl != null
            ? ExotelHostGuard.requireAllowedBaseUrl("ai_assist_base_url", aiAssistBaseUrl) : defaultUrl;
    }

    public String effectiveAiAssistContextPath(String defaultPath) {
        return aiAssistContextPath != null
            ? ExotelHostGuard.requireSafePath("ai_assist_context_path", aiAssistContextPath) : defaultPath;
    }

    // ======================== GETTERS ========================

    public String getToken() { return token; }
    public String getFromNumber() { return fromNumber; }
    public String getWhatsappFrom() { return whatsappFrom; }
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
    public String getAiAssistBaseUrl() { return aiAssistBaseUrl; }
    public String getAiAssistContextPath() { return aiAssistContextPath; }
    public String getAiAssistAccountSid() { return aiAssistAccountSid; }
    public String getAiAssistAuthToken() { return aiAssistAuthToken; }
    public String getAiAssistSessionCookie() { return aiAssistSessionCookie; }
    public String getAiAssistUserId() { return aiAssistUserId; }
    public String getAiAssistClientId() { return aiAssistClientId; }
    public String getAiAssistClientSecret() { return aiAssistClientSecret; }
    public String getAiAssistAuthTokenUrl() { return aiAssistAuthTokenUrl; }
    public String getAiAssistAuthAudience() { return aiAssistAuthAudience; }
    public String getAiAssistAuthKey() { return aiAssistAuthKey; }
    public String getAiAssistAuthSecret() { return aiAssistAuthSecret; }
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
        if (hasAiAssistCredentials()) sb.append("AIAssist ");
        return sb.length() > 0 ? sb.toString().trim() : "NONE";
    }
}
