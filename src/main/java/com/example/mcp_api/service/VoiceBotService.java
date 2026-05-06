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

    @Value("${exotel.calls.requested.server.code:}")
    private String requestedServerCode;

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

    private String callsBasicToken() {
        return Base64.getEncoder().encodeToString(
            (getCallsApiKey() + ":" + getCallsApiToken()).getBytes(StandardCharsets.UTF_8));
    }

    private String getVoicebotSessionCookie() { return resolve("exotel.voicebot.session.cookie", defaultVoicebotSessionCookie); }
    private String getDeviceSessionCookie() { return resolve("exotel.voicebot.device.session.cookie", defaultDeviceSessionCookie); }

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

    @Tool(name = "listVoiceBots",
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

            StringBuilder url = new StringBuilder(voicebotBaseUrl)
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

    @Tool(name = "getVoiceBot",
          description = "Gets full details of a single VoiceBot by its ID. Returns name, status, supported_languages, assistant_config, voice_config, asr_config, post_session_insights, and version history.")
    public String getVoiceBot(String voiceBotId) {
        logger.info("Getting VoiceBot: {}", sanitizeForLog(voiceBotId));
        try {
            requireVoiceBotCredentials();
            validateId(voiceBotId, "voiceBotId");
            String url = voicebotBaseUrl + "/accounts/" + getAccountId() + "/voicebots/" + voiceBotId;
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

    @Tool(name = "deleteVoiceBot",
          description = "Deletes a VoiceBot permanently by its ID. This action cannot be undone. Always confirm with the user before calling this tool.")
    public String deleteVoiceBot(String voiceBotId) {
        logger.info("Deleting VoiceBot: {}", sanitizeForLog(voiceBotId));
        try {
            requireVoiceBotCredentials();
            validateId(voiceBotId, "voiceBotId");
            String url = voicebotBaseUrl + "/accounts/" + getAccountId() + "/voicebots/" + voiceBotId;
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

    @Tool(name = "createVoiceBot",
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

            String url = voicebotBaseUrl + "/accounts/" + getAccountId() + "/bot-generation";

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

    @Tool(name = "getBotGenerationStatus",
          description = "Checks the status of a VoiceBot generation request. Status progresses: pending -> in_progress -> completed. When completed, the bot appears in listVoiceBots. Params: generationId (the ID returned by createVoiceBot).")
    public String getBotGenerationStatus(String generationId) {
        logger.info("Checking bot generation status: {}", sanitizeForLog(generationId));
        try {
            requireVoiceBotCredentials();
            validateId(generationId, "generationId");
            String url = voicebotBaseUrl + "/accounts/" + getAccountId() + "/bot-generation/" + generationId;
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

    @Tool(name = "callWithBot",
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
            String dpBase = voicebotBaseUrl.replaceFirst("/api/v\\d+$", "/api/v1");
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
            form.add("From", effectiveCallerId);
            form.add("To", formatPhoneNumber(toNumber));
            form.add("CallerId", effectiveCallerId);
            if (customField != null && !customField.isBlank()) {
                form.add("CustomField", customField.substring(0, Math.min(customField.length(), 1000)));
            }
            form.add("__IgnoreServerStatus", "true");
            if (requestedServerCode != null && !requestedServerCode.isBlank()) {
                form.add("__RequestedServerCode", requestedServerCode);
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

    @Tool(name = "getBotCallDetails",
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

    @Tool(name = "listAccountPhoneNumbers",
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

    @Tool(name = "listRecentBotCalls",
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
