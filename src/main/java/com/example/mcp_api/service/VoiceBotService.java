package com.example.mcp_api.service;

import com.example.mcp_api.config.CredentialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * VoiceBot MCP Service — exposes Exotel VoiceBot platform APIs as MCP tools.
 *
 * VoiceBot management API:  https://voicebot.in.exotel.com/voicebot/api/v2
 * Outbound calls API:       https://api.in.exotel.com/v1 (Exotel Calls/connect with VoiceBotSid)
 *
 * Credentials are resolved in priority order:
 *   1. Authorization header (per-request, multi-tenant) — fields:
 *      voicebot_api_key, voicebot_api_token, voicebot_account_id,
 *      calls_api_key, calls_api_token, calls_account_id, calls_base_url
 *   2. CredentialStore (session-level override)
 *   3. application.properties defaults (@Value)
 */
@Service
public class VoiceBotService {

    private static final Logger logger = LoggerFactory.getLogger(VoiceBotService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024; // 5 MB cap
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F\\-]{1,64}$");
    private static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[+]?[0-9\\-\\s()]{4,20}$");
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^[0-9]{1,5}$");
    private static final Pattern CALL_SID_PATTERN = Pattern.compile("^[a-zA-Z0-9]{1,64}$");

    private RestTemplate restTemplate;

    @Value("${exotel.voicebot.ssl.verify:true}")
    private boolean sslVerify;

    @jakarta.annotation.PostConstruct
    void initRestTemplate() {
        this.restTemplate = createRestTemplate(sslVerify);
    }

    private static RestTemplate createRestTemplate(boolean verifySsl) {
        try {
            var connMgrBuilder = PoolingHttpClientConnectionManagerBuilder.create();
            if (!verifySsl) {
                SSLContext sslCtx = SSLContextBuilder.create()
                        .loadTrustMaterial(null, (chain, authType) -> true)
                        .build();
                connMgrBuilder.setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                        .setSslContext(sslCtx)
                        .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                        .build());
            }
            var connMgr = connMgrBuilder.build();
            var httpClient = HttpClients.custom().setConnectionManager(connMgr).build();
            var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
            factory.setConnectTimeout(10_000);
            factory.setReadTimeout(30_000);
            return new RestTemplate(factory);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create RestTemplate", e);
        }
    }

    @Autowired
    private CredentialStore credentialStore;

    // Default values from application.properties (lowest priority fallback)
    @Value("${exotel.voicebot.api.key:}")
    private String defaultApiKey;

    @Value("${exotel.voicebot.api.token:}")
    private String defaultApiToken;

    @Value("${exotel.voicebot.account.id:}")
    private String defaultAccountId;

    @Value("${exotel.voicebot.base.url:https://voicebot.in.exotel.com/voicebot/api/v2}")
    private String voicebotBaseUrl;

    @Value("${exotel.voicebot.calls.base.url:https://api.in.exotel.com}")
    private String defaultCallsBaseUrl;

    @Value("${exotel.voicebot.calls.force.http:false}")
    private boolean callsForceHttp;

    @Value("${exotel.calls.account.sid:${exotel.voicebot.account.id:}}")
    private String defaultCallsAccountId;

    @Value("${exotel.calls.api.key:}")
    private String defaultCallsApiKey;

    @Value("${exotel.calls.api.token:}")
    private String defaultCallsApiToken;

    @Value("${exotel.voicebot.default.caller.id:04446312776}")
    private String defaultCallerId;

    @Value("${exotel.voicebot.session.cookie:}")
    private String defaultVoicebotSessionCookie;

    @Value("${exotel.voicebot.device.session.cookie:}")
    private String defaultDeviceSessionCookie;

    @Value("${exotel.voicebot.admin.username:vb_admin}")
    private String defaultAdminUsername;

    @Value("${exotel.voicebot.admin.password:}")
    private String defaultAdminPassword;

    // ======================== AUTH HEADER PARSING ========================

    private JsonNode getAuthNode() {
        String header = getAuthHeader();
        if (header == null || header.isBlank()) return null;
        try {
            String json = header.trim();
            if (json.startsWith("Bearer ")) json = json.substring(7);
            else if (json.startsWith("Basic ")) json = json.substring(6);
            json = json.replace('\'', '"');
            if (!json.startsWith("{")) json = "{" + json + "}";
            return objectMapper.readTree(json);
        } catch (Exception e) {
            logger.debug("Could not parse Authorization header as JSON: {}", e.getMessage());
            return null;
        }
    }

    private String getAuthHeader() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpServletRequest request = attrs.getRequest();
            String h = request.getHeader("Authorization");
            if (h != null && !h.isBlank()) return h;
        } catch (Exception ignored) {
        }
        return null;
    }

    private String authField(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode val = node.path(field);
        if (val.isMissingNode() || val.isNull()) return null;
        String text = val.asText();
        return (text != null && !text.isBlank()) ? text : null;
    }

    // ======================== CREDENTIAL RESOLVERS ========================
    // Priority: 1) Auth header  2) CredentialStore  3) @Value default

    private String getApiKey() {
        JsonNode auth = getAuthNode();
        String val = authField(auth, "voicebot_api_key");
        if (val != null) return val;
        return resolve("exotel.voicebot.api.key", defaultApiKey);
    }

    private String getApiToken() {
        JsonNode auth = getAuthNode();
        String val = authField(auth, "voicebot_api_token");
        if (val != null) return val;
        return resolve("exotel.voicebot.api.token", defaultApiToken);
    }

    private String getAccountId() {
        JsonNode auth = getAuthNode();
        String val = authField(auth, "voicebot_account_id");
        if (val != null) return val;
        return resolve("exotel.voicebot.account.id", defaultAccountId);
    }

    private String getCallsApiKey() {
        JsonNode auth = getAuthNode();
        String val = authField(auth, "calls_api_key");
        if (val != null) return val;
        String stored = resolve("exotel.calls.api.key", defaultCallsApiKey);
        return stored.isBlank() ? getApiKey() : stored;
    }

    private String getCallsApiToken() {
        JsonNode auth = getAuthNode();
        String val = authField(auth, "calls_api_token");
        if (val != null) return val;
        String stored = resolve("exotel.calls.api.token", defaultCallsApiToken);
        return stored.isBlank() ? getApiToken() : stored;
    }

    private String getCallsAccountId() {
        JsonNode auth = getAuthNode();
        String val = authField(auth, "calls_account_id");
        if (val != null) return val;
        return resolve("exotel.calls.account.sid", defaultCallsAccountId);
    }

    private String getCallsBaseUrl() {
        JsonNode auth = getAuthNode();
        String val = authField(auth, "calls_base_url");
        if (val != null) return val;
        return defaultCallsBaseUrl;
    }

    private String getVoicebotBaseUrl() {
        JsonNode auth = getAuthNode();
        String val = authField(auth, "voicebot_base_url");
        if (val != null) return val;
        return voicebotBaseUrl;
    }

    private String callsBasicToken() {
        return Base64.getEncoder().encodeToString(
            (getCallsApiKey() + ":" + getCallsApiToken()).getBytes(StandardCharsets.UTF_8));
    }

    private String getVoicebotSessionCookie() { return resolve("exotel.voicebot.session.cookie", defaultVoicebotSessionCookie); }
    private String getDeviceSessionCookie() { return resolve("exotel.voicebot.device.session.cookie", defaultDeviceSessionCookie); }

    private String getAdminUsername() {
        JsonNode auth = getAuthNode();
        String val = authField(auth, "admin_username");
        if (val != null) return val;
        return resolve("exotel.voicebot.admin.username", defaultAdminUsername);
    }

    private String getAdminPassword() {
        JsonNode auth = getAuthNode();
        String val = authField(auth, "admin_password");
        if (val != null) return val;
        return resolve("exotel.voicebot.admin.password", defaultAdminPassword);
    }

    private String adminBasicToken() {
        return Base64.getEncoder().encodeToString(
                (getAdminUsername() + ":" + getAdminPassword()).getBytes(StandardCharsets.UTF_8));
    }

    /** v1 base URL — derived from the configured v2 base by swapping the version segment. */
    private String getVoicebotV1BaseUrl() {
        return getVoicebotBaseUrl().replaceFirst("/api/v2$", "/api/v1");
    }

    private String resolve(String key, String fallback) {
        String val = credentialStore.get(key);
        return (val != null && !val.isBlank()) ? val : fallback;
    }

    private void requireVoiceBotCredentials() {
        if (getApiKey().isBlank() || getApiToken().isBlank() || getAccountId().isBlank()) {
            throw new IllegalStateException(
                "VoiceBot credentials not configured. Pass voicebot_api_key, " +
                "voicebot_api_token, and voicebot_account_id in the Authorization header.");
        }
        if (!ACCOUNT_ID_PATTERN.matcher(getAccountId()).matches()) {
            throw new IllegalStateException("VoiceBot account ID contains invalid characters");
        }
    }

    private void requireCallsCredentials() {
        if (getCallsApiKey().isBlank() || getCallsApiToken().isBlank() || getCallsAccountId().isBlank()) {
            throw new IllegalStateException(
                "Calls API credentials not configured. Pass calls_api_key, " +
                "calls_api_token, and calls_account_id in the Authorization header.");
        }
        if (!ACCOUNT_ID_PATTERN.matcher(getCallsAccountId()).matches()) {
            throw new IllegalStateException("Calls account ID contains invalid characters");
        }
    }

    private void validateId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (!UUID_PATTERN.matcher(value).matches() && !CALL_SID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " contains invalid characters");
        }
    }

    private void validateNumericParam(String value, String name) {
        if (value != null && !value.isBlank() && !NUMERIC_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a number (1-99999)");
        }
    }

    private String sanitizeForLog(String input) {
        if (input == null) return "null";
        return input.replaceAll("[\\r\\n\\t]", " ").substring(0, Math.min(input.length(), 200));
    }

    // ======================== MCP TOOLS ========================

    @Tool(name = "exotel_voicebot_list_all",
          description = "Lists all VoiceBots on the Exotel account. Supports pagination (offset/limit) and filtering by status (active/inactive). Returns bot id, name, status, languages, voice config, and version info.")
    public String listVoiceBots(String status, String limit, String offset) {
        logger.info("Listing VoiceBots — status={}, limit={}, offset={}", sanitizeForLog(status), sanitizeForLog(limit), sanitizeForLog(offset));
        try {
            requireVoiceBotCredentials();
            validateNumericParam(limit, "limit");
            validateNumericParam(offset, "offset");
            if (status != null && !status.isBlank() && !Set.of("active", "inactive").contains(status.toLowerCase())) {
                return "Error: status must be 'active' or 'inactive'";
            }

            StringBuilder url = new StringBuilder(getVoicebotBaseUrl())
                    .append("/accounts/").append(getAccountId()).append("/voicebots?");

            if (limit != null && !limit.isBlank()) url.append("limit=").append(limit).append("&");
            else url.append("limit=20&");

            if (offset != null && !offset.isBlank()) url.append("offset=").append(offset).append("&");
            if (status != null && !status.isBlank()) url.append("status=").append(status.toLowerCase()).append("&");

            HttpEntity<Void> entity = new HttpEntity<>(jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(
                    url.toString(), HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("listVoiceBots", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("listVoiceBots", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error listing VoiceBots: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_voicebot_get_details",
          description = "Gets full details of a single VoiceBot by its ID. Returns name, status, supported_languages, assistant_config, voice_config, asr_config, post_session_insights, and version history.")
    public String getVoiceBot(String voiceBotId) {
        logger.info("Getting VoiceBot: {}", sanitizeForLog(voiceBotId));
        try {
            requireVoiceBotCredentials();
            validateId(voiceBotId, "voiceBotId");
            String url = getVoicebotBaseUrl() + "/accounts/" + getAccountId() + "/voicebots/" + voiceBotId;
            HttpEntity<Void> entity = new HttpEntity<>(jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("getVoiceBot", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("getVoiceBot", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error getting VoiceBot: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_voicebot_delete",
          description = "Deletes a VoiceBot permanently by its ID. This action cannot be undone. Always confirm with the user before calling this tool.")
    public String deleteVoiceBot(String voiceBotId) {
        logger.info("Deleting VoiceBot: {}", sanitizeForLog(voiceBotId));
        try {
            requireVoiceBotCredentials();
            validateId(voiceBotId, "voiceBotId");
            String url = getVoicebotBaseUrl() + "/accounts/" + getAccountId() + "/voicebots/" + voiceBotId;
            HttpEntity<Void> entity = new HttpEntity<>(jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
            String body = response.getBody();
            return (body != null && !body.isBlank()) ? body : "VoiceBot " + voiceBotId + " deleted successfully.";
        } catch (HttpClientErrorException e) {
            return errorMsg("deleteVoiceBot", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("deleteVoiceBot", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error deleting VoiceBot: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_voicebot_create_from_description",
          description = "Creates a new VoiceBot using AI-powered bot generation. Provide a text description of the bot's personality, purpose, and behavior. The bot is generated asynchronously — use getBotGenerationStatus to poll until status is 'completed'. Params: textContent (description of the bot, e.g. 'You are a friendly customer support agent for Acme Corp. Help customers with orders and returns.').")
    public String createVoiceBot(String textContent) {
        logger.info("Creating VoiceBot via bot-generation — textLength={}", textContent != null ? textContent.length() : 0);
        try {
            requireVoiceBotCredentials();
            if (textContent == null || textContent.isBlank()) {
                return "Error: textContent is required — describe the bot's personality and purpose";
            }
            if (textContent.length() > 10_000) {
                return "Error: textContent too long (max 10,000 characters)";
            }

            String url = getVoicebotBaseUrl() + "/accounts/" + getAccountId() + "/bot-generation";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("Authorization", "Basic " + basicToken());

            org.springframework.util.LinkedMultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
            body.add("text_content", textContent);

            HttpEntity<org.springframework.util.LinkedMultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            logger.info("createVoiceBot status: {}", response.getStatusCode());
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("createVoiceBot", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("createVoiceBot", e);
        } catch (IllegalStateException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error creating VoiceBot: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_voicebot_get_creation_status",
          description = "Checks the status of a VoiceBot generation request. Status progresses: pending -> in_progress -> completed. When completed, the bot appears in listVoiceBots. Params: generationId (the ID returned by createVoiceBot).")
    public String getBotGenerationStatus(String generationId) {
        logger.info("Checking bot generation status: {}", sanitizeForLog(generationId));
        try {
            requireVoiceBotCredentials();
            validateId(generationId, "generationId");
            String url = getVoicebotBaseUrl() + "/accounts/" + getAccountId() + "/bot-generation/" + generationId;
            HttpEntity<Void> entity = new HttpEntity<>(jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("getBotGenerationStatus", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("getBotGenerationStatus", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error checking bot generation status: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_voicebot_place_outbound_call",
          description = "THE primary tool for making any phone call. Places an outbound call powered by a VoiceBot — " +
                        "the bot handles the entire conversation autonomously. Use this for ALL calling requests. " +
                        "Requires: toNumber (phone to call, e.g. 09720454250), voiceBotId (UUID from listVoiceBots). " +
                        "Optional: callerId (Exotel phone number, defaults to configured default), customField (metadata for the bot). " +
                        "After calling, use getBotCallDetails with the returned CallSid to check status and get recording URL.")
    public String makeOutboundBotCall(String toNumber, String voiceBotId, String callerId, String customField) {
        logger.info("Outbound bot call — to={}, bot={}, callerId={}", sanitizeForLog(toNumber), sanitizeForLog(voiceBotId), sanitizeForLog(callerId));
        try {
            requireVoiceBotCredentials();
            requireCallsCredentials();
            validateId(voiceBotId, "voiceBotId");
            if (toNumber == null || toNumber.isBlank()) {
                return "Error: toNumber is required";
            }
            if (!PHONE_PATTERN.matcher(toNumber).matches()) {
                return "Error: toNumber contains invalid characters";
            }
            if (callerId != null && !callerId.isBlank() && !PHONE_PATTERN.matcher(callerId).matches()) {
                return "Error: callerId contains invalid characters";
            }

            String accountId = getAccountId();
            String effectiveCallerId = (callerId != null && !callerId.isBlank()) ? callerId : defaultCallerId;

            // Step 1: Fetch the WebSocket stream URL from the bot's dp-endpoint
            String dpBase = getVoicebotBaseUrl().replaceFirst("/api/v\\d+$", "/api/v1");
            String dpEndpointUrl = dpBase + "/accounts/" + accountId + "/bots/" + voiceBotId + "/dp-endpoint";

            HttpHeaders dpHeaders = new HttpHeaders();
            dpHeaders.set("Authorization", "Basic " + basicToken());
            dpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
            ResponseEntity<String> dpResponse = restTemplate.exchange(
                    dpEndpointUrl, HttpMethod.GET, new HttpEntity<>(dpHeaders), String.class);

            String streamUrl = extractStreamUrl(safeBody(dpResponse));
            if (streamUrl == null || streamUrl.isBlank()) {
                logger.error("callWithBot: failed to extract stream URL from dp-endpoint");
                return "Error: Could not extract stream URL from VoiceBot dp-endpoint. Verify the bot ID is correct and active.";
            }
            if (!streamUrl.startsWith("wss://")) {
                logger.error("callWithBot: dp-endpoint returned non-secure WebSocket URL");
                return "Error: VoiceBot returned insecure WebSocket URL. Contact support.";
            }
            logger.info("callWithBot stream URL obtained for bot={}", sanitizeForLog(voiceBotId));

            // Step 2: POST to Calls connect
            String resolvedCallsBase = getCallsBaseUrl();
            String baseUrl = callsForceHttp ? resolvedCallsBase.replaceFirst("^https://", "http://") : resolvedCallsBase;
            String connectUrl = baseUrl + "/v1/Accounts/" + getCallsAccountId() + "/Calls/connect.json";

            HttpHeaders connectHeaders = new HttpHeaders();
            connectHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
            connectHeaders.set("Authorization", "Basic " + callsBasicToken());

            org.springframework.util.LinkedMultiValueMap<String, String> form = new org.springframework.util.LinkedMultiValueMap<>();
            form.add("StreamType", "bidirectional");
            form.add("StreamUrl", streamUrl);
            form.add("From", formatPhoneNumber(toNumber));
            form.add("CallerId", effectiveCallerId);
            if (customField != null && !customField.isBlank()) {
                form.add("CustomField", customField.substring(0, Math.min(customField.length(), 1000)));
            }

            ResponseEntity<String> connectResponse = restTemplate.exchange(
                    connectUrl, HttpMethod.POST, new HttpEntity<>(form, connectHeaders), String.class);
            logger.info("callWithBot connect status: {}", connectResponse.getStatusCode());
            return safeBody(connectResponse);

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401) {
                return "Error 401: Authentication failed. Check API credentials.";
            }
            return errorMsg("callWithBot", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("callWithBot", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error making outbound bot call: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_voicebot_call_get_status",
          description = "Gets details of a specific call by its Call SID. Returns status, duration, recording URL, timestamps, and direction. Use after makeOutboundBotCall to check if the call connected.")
    public String getBotCallDetails(String callSid) {
        logger.info("Getting call details: {}", sanitizeForLog(callSid));
        try {
            requireCallsCredentials();
            validateId(callSid, "callSid");
            String url = getCallsBaseUrl() + "/v1/Accounts/" + getCallsAccountId() + "/Calls/" + callSid + ".json";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + callsBasicToken());
            headers.set("Accept", "application/json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("getBotCallDetails", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("getBotCallDetails", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error getting call details: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_account_list_phone_numbers",
          description = "Lists all phone numbers (DIDs) available on the Exotel account. Use this to find a valid callerId before placing outbound bot calls.")
    public String listAccountPhoneNumbers() {
        logger.info("Listing account phone numbers");
        try {
            requireCallsCredentials();
            String url = getCallsBaseUrl() + "/v1/Accounts/" + getCallsAccountId() + "/IncomingPhoneNumbers.json";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + callsBasicToken());
            headers.set("Accept", "application/json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("listAccountPhoneNumbers", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("listAccountPhoneNumbers", e);
        } catch (IllegalStateException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error listing phone numbers: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_voicebot_list_recent_calls",
          description = "Lists recent calls on the account. Supports limit (default 10) and sortBy (DateCreated:desc). Use to review call history and outcomes.")
    public String listRecentBotCalls(String limit) {
        logger.info("Listing recent calls — limit={}", sanitizeForLog(limit));
        try {
            requireCallsCredentials();
            validateNumericParam(limit, "limit");
            String lim = (limit != null && !limit.isBlank()) ? limit : "10";
            String url = getCallsBaseUrl() + "/v1/Accounts/" + getCallsAccountId()
                    + "/Calls.json?Limit=" + lim + "&SortBy=DateCreated:desc";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + callsBasicToken());
            headers.set("Accept", "application/json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("listRecentBotCalls", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("listRecentBotCalls", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error listing recent calls: " + e.getMessage();
        }
    }

    // ======================== ASSISTANT TOOLS ========================

    @Tool(name = "exotel_assistant_get_config",
          description = "Gets the full configuration of a VoiceBot assistant by its ID. " +
                        "Use version='stable' (default) or a specific version like 'v1', 'v2', etc. " +
                        "Returns agents, instructions, llm_config, mcp_tools, intent_detection_config.")
    public String getAssistant(String assistantId, String version) {
        logger.info("Getting assistant: {} version={}", sanitizeForLog(assistantId), sanitizeForLog(version));
        try {
            requireVoiceBotCredentials();
            validateId(assistantId, "assistantId");
            String ver = (version != null && !version.isBlank()) ? version : "stable";
            String url = getVoicebotBaseUrl() + "/accounts/" + getAccountId()
                    + "/assistants/" + assistantId + "?version=" + ver;
            HttpEntity<Void> entity = new HttpEntity<>(adminJsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("getAssistant", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("getAssistant", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error getting assistant: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_assistant_update_prompt",
          description = "Updates the instruction/prompt of a VoiceBot assistant by creating a new version. " +
                        "Fetches the current stable config, replaces the instruction on the first agent, " +
                        "and pushes it as a new stable version. " +
                        "Params: assistantId (UUID of the assistant inside the bot config), " +
                        "newInstruction (the full updated system prompt), " +
                        "versionDescription (optional label for this version).")
    public String updateBotPrompt(String assistantId, String newInstruction, String versionDescription) {
        logger.info("Updating bot prompt for assistant={}", sanitizeForLog(assistantId));
        try {
            requireVoiceBotCredentials();
            validateId(assistantId, "assistantId");
            if (newInstruction == null || newInstruction.isBlank()) {
                return "Error: newInstruction is required";
            }
            if (newInstruction.length() > 50_000) {
                return "Error: newInstruction too long (max 50,000 characters)";
            }

            // Step 1: fetch current stable config
            String getUrl = getVoicebotBaseUrl() + "/accounts/" + getAccountId()
                    + "/assistants/" + assistantId + "?version=stable";
            ResponseEntity<String> currentResp = restTemplate.exchange(
                    getUrl, HttpMethod.GET, new HttpEntity<>(adminJsonHeaders()), String.class);
            JsonNode current = objectMapper.readTree(safeBody(currentResp));

            // Step 2: extract current version label and agents list
            String sourceVersion = null;
            JsonNode versionNode = current.path("version");
            if (!versionNode.isMissingNode()) sourceVersion = versionNode.asText();
            if (sourceVersion == null || sourceVersion.isBlank()) sourceVersion = "v1";

            // Build agents array: keep IDs, inject updated instruction into first agent
            com.fasterxml.jackson.databind.node.ArrayNode agentsArray =
                    objectMapper.createArrayNode();
            JsonNode existingAgents = current.path("agents");
            if (existingAgents.isArray() && existingAgents.size() > 0) {
                boolean first = true;
                for (JsonNode agent : existingAgents) {
                    com.fasterxml.jackson.databind.node.ObjectNode agentNode =
                            (com.fasterxml.jackson.databind.node.ObjectNode) agent.deepCopy();
                    if (first) {
                        agentNode.put("instruction", newInstruction);
                        first = false;
                    }
                    agentsArray.add(agentNode);
                }
            } else {
                // No agents found — create minimal agent placeholder
                com.fasterxml.jackson.databind.node.ObjectNode agent =
                        objectMapper.createObjectNode();
                agent.put("instruction", newInstruction);
                agentsArray.add(agent);
            }

            // Step 3: build version payload
            com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
            payload.put("source_version", sourceVersion);
            payload.put("mark_as_stable", "true");
            payload.put("version_description",
                    versionDescription != null && !versionDescription.isBlank()
                            ? versionDescription : "Prompt updated via MCP");
            com.fasterxml.jackson.databind.node.ObjectNode data = objectMapper.createObjectNode();
            data.set("agents", agentsArray);
            payload.set("data", data);

            // Step 4: POST new version
            String postUrl = getVoicebotBaseUrl() + "/accounts/" + getAccountId()
                    + "/assistants/" + assistantId + "/versions";
            HttpHeaders headers = adminJsonHeaders();
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), headers);
            ResponseEntity<String> response = restTemplate.exchange(postUrl, HttpMethod.POST, entity, String.class);
            logger.info("updateBotPrompt status: {}", response.getStatusCode());
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("updateBotPrompt", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("updateBotPrompt", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error updating bot prompt: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_assistant_push_new_version",
          description = "Creates a new version of an assistant with a custom data payload. " +
                        "Use this for advanced updates: attaching tools, updating intent_detection_config, " +
                        "or swapping agents. The dataJson must be a valid JSON string with the fields to update " +
                        "(e.g. '{\"agents\":[{\"id\":\"...\"}], \"intent_detection_config\":{...}}'). " +
                        "Params: assistantId, sourceVersion (e.g. 'v4' or 'stable'), " +
                        "markAsStable ('true'/'false'), versionDescription, dataJson.")
    public String createAssistantVersion(String assistantId, String sourceVersion,
                                         String markAsStable, String versionDescription, String dataJson) {
        logger.info("Creating assistant version — assistant={}, source={}", sanitizeForLog(assistantId), sanitizeForLog(sourceVersion));
        try {
            requireVoiceBotCredentials();
            validateId(assistantId, "assistantId");
            if (dataJson == null || dataJson.isBlank()) {
                return "Error: dataJson is required";
            }

            // Validate dataJson is valid JSON
            JsonNode dataNode;
            try {
                dataNode = objectMapper.readTree(dataJson);
            } catch (Exception e) {
                return "Error: dataJson is not valid JSON — " + e.getMessage();
            }

            com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
            if (sourceVersion != null && !sourceVersion.isBlank()) payload.put("source_version", sourceVersion);
            payload.put("mark_as_stable", markAsStable != null ? markAsStable : "true");
            payload.put("version_description",
                    versionDescription != null && !versionDescription.isBlank()
                            ? versionDescription : "Updated via MCP");
            payload.set("data", dataNode);

            String url = getVoicebotBaseUrl() + "/accounts/" + getAccountId()
                    + "/assistants/" + assistantId + "/versions";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), adminJsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            logger.info("createAssistantVersion status: {}", response.getStatusCode());
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("createAssistantVersion", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("createAssistantVersion", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error creating assistant version: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_voicebot_get_session_transcript",
          description = "Fetches the full conversation transcript for a VoiceBot session using its session UUID. " +
                        "Note: requires the internal session UUID (not the call SID). " +
                        "Params: sessionId (UUID of the voicebot session).")
    public String getBotSessionTranscript(String sessionId) {
        logger.info("Getting bot session transcript: {}", sanitizeForLog(sessionId));
        try {
            requireVoiceBotCredentials();
            validateId(sessionId, "sessionId");
            String url = getVoicebotBaseUrl() + "/accounts/" + getAccountId()
                    + "/voicebot-sessions/" + sessionId + "?transcriptRequired=true";
            HttpEntity<Void> entity = new HttpEntity<>(adminJsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("getBotSessionTranscript", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("getBotSessionTranscript", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error getting session transcript: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_voicebot_update_tts_asr_vad_config",
          description = "Updates a VoiceBot's configuration including TTS (voice_config), ASR (asr_config), " +
                        "VAD (vad_config), denoiser, webhook_config, greeting, and call settings. " +
                        "Provide a configJson with only the fields you want to change — the bot's existing " +
                        "config is merged with your updates. " +
                        "Example configJson for changing TTS voice: " +
                        "'{\"voice_config\":{\"voice\":{\"id\":\"<voice-id>\"}}}'. " +
                        "Example for denoiser: '{\"audio_processing\":{\"pstn\":{\"denoiser_config\":{\"enabled\":true}}}}'. " +
                        "Params: botId (UUID of the VoiceBot), configJson (JSON fields to update).")
    public String updateBotConfig(String botId, String configJson) {
        logger.info("Updating bot config for bot={}", sanitizeForLog(botId));
        try {
            requireVoiceBotCredentials();
            validateId(botId, "botId");
            if (configJson == null || configJson.isBlank()) {
                return "Error: configJson is required";
            }

            // Validate JSON
            try {
                objectMapper.readTree(configJson);
            } catch (Exception e) {
                return "Error: configJson is not valid JSON — " + e.getMessage();
            }

            String url = getVoicebotV1BaseUrl() + "/accounts/" + getAccountId() + "/voicebots/" + botId;
            HttpEntity<String> entity = new HttpEntity<>(configJson, adminJsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
            logger.info("updateBotConfig status: {}", response.getStatusCode());
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("updateBotConfig", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("updateBotConfig", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error updating bot config: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_assistant_create_multiagent",
          description = "Creates a new assistant (multi-agent orchestrator) for a VoiceBot. " +
                        "An assistant can have one or more agents, each with its own instruction and llm_config. " +
                        "Params: name (assistant name), description (what it does), " +
                        "instruction (top-level system prompt), " +
                        "agentsJson (optional JSON array of agent objects, each with name/description/instruction/llm_config). " +
                        "Returns the created assistant's ID which can be linked to a VoiceBot via updateBotConfig.")
    public String createAssistant(String name, String description, String instruction, String agentsJson) {
        logger.info("Creating assistant: {}", sanitizeForLog(name));
        try {
            requireVoiceBotCredentials();
            if (name == null || name.isBlank()) return "Error: name is required";

            com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
            payload.put("name", name);
            if (description != null && !description.isBlank()) payload.put("description", description);
            if (instruction != null && !instruction.isBlank()) payload.put("instruction", instruction);

            if (agentsJson != null && !agentsJson.isBlank()) {
                try {
                    JsonNode agents = objectMapper.readTree(agentsJson);
                    payload.set("agents", agents);
                } catch (Exception e) {
                    return "Error: agentsJson is not valid JSON — " + e.getMessage();
                }
            }

            String url = getVoicebotBaseUrl() + "/accounts/" + getAccountId() + "/assistants";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), adminJsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            logger.info("createAssistant status: {}", response.getStatusCode());
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("createAssistant", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("createAssistant", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error creating assistant: " + e.getMessage();
        }
    }

    // ======================== PERSONA TOOLS ========================

    @Tool(name = "exotel_persona_list",
          description = "Lists all personas (system prompt templates) defined for the account. " +
                        "Personas define the bot's personality, language behaviour, and gender characteristics. " +
                        "Params: limit (default 20), offset (for pagination).")
    public String listPersonas(String limit, String offset) {
        logger.info("Listing personas — limit={}, offset={}", sanitizeForLog(limit), sanitizeForLog(offset));
        try {
            requireVoiceBotCredentials();
            validateNumericParam(limit, "limit");
            validateNumericParam(offset, "offset");
            String lim = (limit != null && !limit.isBlank()) ? limit : "20";
            String off = (offset != null && !offset.isBlank()) ? offset : "0";
            String url = getVoicebotV1BaseUrl() + "/accounts/" + getAccountId()
                    + "/personas?limit=" + lim + "&offset=" + off;
            HttpEntity<Void> entity = new HttpEntity<>(adminJsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("listPersonas", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("listPersonas", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error listing personas: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_persona_create",
          description = "Creates a new persona (system prompt template) for the account. " +
                        "Params: name (unique persona name), instruction (main system prompt), " +
                        "languagePrompt (optional language-switching instructions with {{languages[0]}} placeholders), " +
                        "genderPrompt (optional gender/voice style instructions with {{gender}} placeholder).")
    public String createPersona(String name, String instruction, String languagePrompt, String genderPrompt) {
        logger.info("Creating persona: {}", sanitizeForLog(name));
        try {
            requireVoiceBotCredentials();
            if (name == null || name.isBlank()) return "Error: name is required";
            if (instruction == null || instruction.isBlank()) return "Error: instruction is required";

            com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
            payload.put("name", name);
            payload.put("instruction", instruction);
            if (languagePrompt != null && !languagePrompt.isBlank()) payload.put("language_prompt", languagePrompt);
            if (genderPrompt != null && !genderPrompt.isBlank()) payload.put("gender_prompt", genderPrompt);

            String url = getVoicebotV1BaseUrl() + "/accounts/" + getAccountId() + "/personas";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), adminJsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            logger.info("createPersona status: {}", response.getStatusCode());
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("createPersona", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("createPersona", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error creating persona: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_persona_update",
          description = "Updates an existing persona's instruction, language prompt, or gender prompt. " +
                        "Params: personaId (UUID of the persona to update), instruction (new system prompt), " +
                        "languagePrompt (optional, updated language-switching instructions), " +
                        "genderPrompt (optional, updated gender/voice style instructions).")
    public String updatePersona(String personaId, String instruction, String languagePrompt, String genderPrompt) {
        logger.info("Updating persona: {}", sanitizeForLog(personaId));
        try {
            requireVoiceBotCredentials();
            validateId(personaId, "personaId");
            if (instruction == null || instruction.isBlank()) return "Error: instruction is required";

            com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
            payload.put("instruction", instruction);
            if (languagePrompt != null && !languagePrompt.isBlank()) payload.put("language_prompt", languagePrompt);
            if (genderPrompt != null && !genderPrompt.isBlank()) payload.put("gender_prompt", genderPrompt);

            String url = getVoicebotV1BaseUrl() + "/accounts/" + getAccountId() + "/personas/" + personaId;
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), adminJsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
            logger.info("updatePersona status: {}", response.getStatusCode());
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("updatePersona", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("updatePersona", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error updating persona: " + e.getMessage();
        }
    }

    // ======================== TTS / VOICE PROVIDER TOOLS ========================

    @Tool(name = "exotel_tts_list_providers",
          description = "Lists all TTS (Text-to-Speech) voice providers available on the account. " +
                        "Returns provider IDs and names (e.g. ElevenLabs, Azure, Sarvam). " +
                        "Use the provider ID with listTtsVoices to browse available voices.")
    public String listTtsProviders() {
        logger.info("Listing TTS providers");
        try {
            requireVoiceBotCredentials();
            String url = getVoicebotV1BaseUrl() + "/accounts/" + getAccountId() + "/voice-providers";
            HttpEntity<Void> entity = new HttpEntity<>(adminJsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("listTtsProviders", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("listTtsProviders", e);
        } catch (IllegalStateException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error listing TTS providers: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_tts_list_voices_for_provider",
          description = "Lists all available TTS voices for a given voice provider. " +
                        "Use listTtsProviders first to get provider IDs. " +
                        "Returns voice IDs and metadata (name, gender, language, etc.). " +
                        "Params: providerId (UUID of the voice provider), limit (default 100).")
    public String listTtsVoices(String providerId, String limit) {
        logger.info("Listing TTS voices for provider={}", sanitizeForLog(providerId));
        try {
            requireVoiceBotCredentials();
            validateId(providerId, "providerId");
            validateNumericParam(limit, "limit");
            String lim = (limit != null && !limit.isBlank()) ? limit : "100";
            String url = getVoicebotV1BaseUrl() + "/accounts/" + getAccountId()
                    + "/voice-providers/" + providerId + "/voices?limit=" + lim;
            HttpEntity<Void> entity = new HttpEntity<>(adminJsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("listTtsVoices", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("listTtsVoices", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error listing TTS voices: " + e.getMessage();
        }
    }

    // ======================== SPECIALIZATION TOOLS ========================

    @Tool(name = "exotel_specialization_list",
          description = "Lists all specializations defined for the account. " +
                        "Specializations are reusable configuration overrides (e.g. language switching, speed adjustments) " +
                        "that can be applied to VoiceBots.")
    public String listSpecializations() {
        logger.info("Listing specializations");
        try {
            requireVoiceBotCredentials();
            String url = getVoicebotV1BaseUrl() + "/accounts/" + getAccountId() + "/specializations";
            HttpEntity<Void> entity = new HttpEntity<>(adminJsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("listSpecializations", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("listSpecializations", e);
        } catch (IllegalStateException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error listing specializations: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_specialization_create",
          description = "Creates a new specialization (reusable config override) for the account. " +
                        "Types include 'language_switching' and others. " +
                        "Params: name (unique specialization name), type (e.g. 'language_switching'), " +
                        "configJson (JSON object for assistant_override_config or voicebot_override_config, " +
                        "e.g. '{\"assistant_override_config\":{\"prompt\":\"...\"}}'). ")
    public String createSpecialization(String name, String type, String configJson) {
        logger.info("Creating specialization: {} type={}", sanitizeForLog(name), sanitizeForLog(type));
        try {
            requireVoiceBotCredentials();
            if (name == null || name.isBlank()) return "Error: name is required";
            if (type == null || type.isBlank()) return "Error: type is required";

            com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
            payload.put("name", name);
            payload.put("type", type);

            if (configJson != null && !configJson.isBlank()) {
                try {
                    JsonNode configNode = objectMapper.readTree(configJson);
                    // Merge config fields into the top-level payload
                    configNode.fields().forEachRemaining(e -> payload.set(e.getKey(), e.getValue()));
                } catch (Exception e) {
                    return "Error: configJson is not valid JSON — " + e.getMessage();
                }
            }

            String url = getVoicebotV1BaseUrl() + "/accounts/" + getAccountId() + "/specializations";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), adminJsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            logger.info("createSpecialization status: {}", response.getStatusCode());
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("createSpecialization", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("createSpecialization", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error creating specialization: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_specialization_update",
          description = "Updates an existing specialization's name or configuration. " +
                        "Params: specializationId (UUID), name (optional new name), " +
                        "configJson (JSON object with the config fields to update, " +
                        "e.g. '{\"voicebot_override_config\":{\"voice_config\":{\"provider_config\":{\"speed\":1.2}}}}').")
    public String updateSpecialization(String specializationId, String name, String configJson) {
        logger.info("Updating specialization: {}", sanitizeForLog(specializationId));
        try {
            requireVoiceBotCredentials();
            validateId(specializationId, "specializationId");

            com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
            if (name != null && !name.isBlank()) payload.put("name", name);

            if (configJson != null && !configJson.isBlank()) {
                try {
                    JsonNode configNode = objectMapper.readTree(configJson);
                    configNode.fields().forEachRemaining(e -> payload.set(e.getKey(), e.getValue()));
                } catch (Exception e) {
                    return "Error: configJson is not valid JSON — " + e.getMessage();
                }
            }

            if (payload.isEmpty()) return "Error: provide at least name or configJson to update";

            String url = getVoicebotV1BaseUrl() + "/accounts/" + getAccountId()
                    + "/specializations/" + specializationId;
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), adminJsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
            logger.info("updateSpecialization status: {}", response.getStatusCode());
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("updateSpecialization", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("updateSpecialization", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error updating specialization: " + e.getMessage();
        }
    }

    // ======================== HELPERS ========================

    /**
     * Normalises an Indian mobile/landline number to +91XXXXXXXXXX format.
     * Leaves numbers already starting with + untouched.
     */
    private String formatPhoneNumber(String number) {
        if (number == null || number.isBlank()) return number;
        String digits = number.replaceAll("[^0-9]", "");
        if (number.startsWith("+")) return number;           // already E.164
        if (digits.length() == 12 && digits.startsWith("91")) return "+" + digits; // 919XXXXXXXXX
        if (digits.length() == 11 && digits.startsWith("0")) return "+91" + digits.substring(1); // 09XXXXXXXXX
        if (digits.length() == 10) return "+91" + digits;   // 9XXXXXXXXX
        return number; // return as-is for non-Indian formats
    }

    private String extractStreamUrl(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            for (String field : new String[]{"stream_url", "streamUrl", "url", "websocket_url"}) {
                JsonNode node = root.path(field);
                if (!node.isMissingNode() && !node.isNull()) {
                    String val = node.asText();
                    if (val.startsWith("wss://")) return val;
                }
            }
            Iterator<JsonNode> elements = root.elements();
            while (elements.hasNext()) {
                String val = elements.next().asText();
                if (val.startsWith("wss://")) return val;
            }
        } catch (Exception e) {
            logger.warn("JSON parse failed for dp-endpoint response, scanning for wss:// URL");
        }
        int idx = responseBody.indexOf("wss://");
        if (idx >= 0) {
            int end = responseBody.indexOf('"', idx);
            return end > idx ? responseBody.substring(idx, end) : responseBody.substring(idx).split("\\s")[0];
        }
        return null;
    }

    private HttpHeaders adminJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        // Use admin credentials if configured, otherwise fall back to voicebot API key/token
        String adminUser = getAdminUsername();
        String adminPass = getAdminPassword();
        if (!adminUser.isBlank() && !adminPass.isBlank()) {
            headers.set("Authorization", "Basic " + adminBasicToken());
        } else {
            headers.set("Authorization", "Basic " + basicToken());
        }
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("Authorization", "Basic " + basicToken());
        String voicebotCookie = sanitizeHeaderValue(getVoicebotSessionCookie());
        String deviceCookie = sanitizeHeaderValue(getDeviceSessionCookie());
        if (!voicebotCookie.isBlank() && !deviceCookie.isBlank()) {
            headers.set("Cookie",
                    "voicebot_session_mum_prod=" + voicebotCookie +
                    "; exotel_device_session_in=" + deviceCookie);
        }
        return headers;
    }

    private String sanitizeHeaderValue(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\r\\n]", "");
    }

    private String basicToken() {
        return Base64.getEncoder().encodeToString(
                (getApiKey() + ":" + getApiToken()).getBytes(StandardCharsets.UTF_8));
    }

    private String safeBody(ResponseEntity<String> response) {
        String body = response.getBody();
        if (body == null) return "{}";
        if (body.length() > MAX_RESPONSE_BYTES) {
            logger.warn("Response body truncated: {} bytes", body.length());
            return body.substring(0, MAX_RESPONSE_BYTES);
        }
        return body;
    }

    private String errorMsg(String method, HttpClientErrorException e) {
        logger.error("{} API error: {} - {}", method, e.getStatusCode(), e.getResponseBodyAsString());
        return "Error " + e.getStatusCode().value() + ": " + method + " request failed";
    }

    private String serverErrorMsg(String method, HttpServerErrorException e) {
        logger.error("{} server error: {} - {}", method, e.getStatusCode(), e.getResponseBodyAsString());
        return "Error " + e.getStatusCode().value() + ": " + method + " — upstream service unavailable";
    }
}
