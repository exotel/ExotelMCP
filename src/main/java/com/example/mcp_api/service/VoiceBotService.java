package com.example.mcp_api.service;

import com.example.mcp_api.auth.AuthContext;
import com.example.mcp_api.auth.AuthCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * VoiceBot MCP tools — bot management and outbound calling.
 *
 * Credentials come from AuthCredentials (parsed once per request by AuthFilter).
 * Fallback to @Value defaults for local development only.
 */
@Service
public class VoiceBotService {

    private static final Logger logger = LoggerFactory.getLogger(VoiceBotService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
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

    @Value("${exotel.calls.account.sid:}")
    private String defaultCallsAccountId;

    @Value("${exotel.calls.api.key:}")
    private String defaultCallsApiKey;

    @Value("${exotel.calls.api.token:}")
    private String defaultCallsApiToken;

    @Value("${exotel.voicebot.default.caller.id:}")
    private String defaultCallerId;

    @Value("${exotel.voicebot.session.cookie:}")
    private String defaultVoicebotSessionCookie;

    @Value("${exotel.voicebot.device.session.cookie:}")
    private String defaultDeviceSessionCookie;

    // ======================== CREDENTIAL RESOLUTION ========================

    private String getApiKey() {
        AuthCredentials creds = AuthContext.current();
        String val = creds.getVoicebotApiKey();
        return val != null ? val : defaultApiKey;
    }

    private String getApiToken() {
        AuthCredentials creds = AuthContext.current();
        String val = creds.getVoicebotApiToken();
        return val != null ? val : defaultApiToken;
    }

    private String getAccountId() {
        AuthCredentials creds = AuthContext.current();
        String val = creds.getVoicebotAccountId();
        return val != null ? val : defaultAccountId;
    }

    private String getCallsApiKey() {
        AuthCredentials creds = AuthContext.current();
        String val = creds.getCallsApiKey();
        if (val != null) return val;
        if (defaultCallsApiKey != null && !defaultCallsApiKey.isBlank()) return defaultCallsApiKey;
        return getApiKey();
    }

    private String getCallsApiToken() {
        AuthCredentials creds = AuthContext.current();
        String val = creds.getCallsApiToken();
        if (val != null) return val;
        if (defaultCallsApiToken != null && !defaultCallsApiToken.isBlank()) return defaultCallsApiToken;
        return getApiToken();
    }

    private String getCallsAccountId() {
        AuthCredentials creds = AuthContext.current();
        String val = creds.getCallsAccountId();
        if (val != null) return val;
        if (defaultCallsAccountId != null && !defaultCallsAccountId.isBlank()) return defaultCallsAccountId;
        return getAccountId();
    }

    private String getCallsBaseUrl() {
        AuthCredentials creds = AuthContext.current();
        return creds.effectiveCallsBaseUrl(defaultCallsBaseUrl);
    }

    private String getVoicebotBaseUrl() {
        AuthCredentials creds = AuthContext.current();
        return creds.effectiveVoicebotBaseUrl(voicebotBaseUrl);
    }

    private String callsBasicToken() {
        return Base64.getEncoder().encodeToString(
            (getCallsApiKey() + ":" + getCallsApiToken()).getBytes(StandardCharsets.UTF_8));
    }

    private String requireVoiceBotCreds() {
        String error = AuthContext.requireVoicebot();
        if (error != null) {
            if (defaultApiKey != null && !defaultApiKey.isBlank()) return null;
            return error;
        }
        return null;
    }

    private String requireCallsCreds() {
        String error = AuthContext.requireCalls();
        if (error != null) {
            if (defaultCallsApiKey != null && !defaultCallsApiKey.isBlank()) return null;
            return error;
        }
        return null;
    }

    private void validateId(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " is required");
        if (!UUID_PATTERN.matcher(value).matches() && !CALL_SID_PATTERN.matcher(value).matches())
            throw new IllegalArgumentException(name + " contains invalid characters");
    }

    private void validateNumericParam(String value, String name) {
        if (value != null && !value.isBlank() && !NUMERIC_PATTERN.matcher(value).matches())
            throw new IllegalArgumentException(name + " must be a number (1-99999)");
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
            String authErr = requireVoiceBotCreds();
            if (authErr != null) return authErr;
            validateNumericParam(limit, "limit");
            validateNumericParam(offset, "offset");
            if (status != null && !status.isBlank() && !Set.of("active", "inactive").contains(status.toLowerCase()))
                return "Error: status must be 'active' or 'inactive'";

            StringBuilder url = new StringBuilder(getVoicebotBaseUrl())
                    .append("/accounts/").append(getAccountId()).append("/voicebots?");
            if (limit != null && !limit.isBlank()) url.append("limit=").append(limit).append("&");
            else url.append("limit=20&");
            if (offset != null && !offset.isBlank()) url.append("offset=").append(offset).append("&");
            if (status != null && !status.isBlank()) url.append("status=").append(status.toLowerCase()).append("&");

            HttpEntity<Void> entity = new HttpEntity<>(jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url.toString(), HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_list_all", e);
        } catch (HttpServerErrorException e) { return serverErrorMsg("exotel_voicebot_list_all", e);
        } catch (IllegalStateException | IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error listing VoiceBots: " + e.getMessage(); }
    }

    @Tool(name = "exotel_voicebot_get_details",
          description = "Gets full details of a single VoiceBot by its ID. Returns name, status, supported_languages, assistant_config, voice_config, asr_config, and version history.")
    public String getVoiceBot(String voiceBotId) {
        logger.info("Getting VoiceBot: {}", sanitizeForLog(voiceBotId));
        try {
            String authErr = requireVoiceBotCreds();
            if (authErr != null) return authErr;
            validateId(voiceBotId, "voiceBotId");
            String url = getVoicebotBaseUrl() + "/accounts/" + getAccountId() + "/voicebots/" + voiceBotId;
            HttpEntity<Void> entity = new HttpEntity<>(jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_get_details", e);
        } catch (HttpServerErrorException e) { return serverErrorMsg("exotel_voicebot_get_details", e);
        } catch (IllegalStateException | IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error getting VoiceBot: " + e.getMessage(); }
    }

    @Tool(name = "exotel_voicebot_delete",
          description = "Deletes a VoiceBot permanently by its ID. This action cannot be undone. Always confirm with the user before calling this tool.")
    public String deleteVoiceBot(String voiceBotId) {
        logger.info("Deleting VoiceBot: {}", sanitizeForLog(voiceBotId));
        try {
            String authErr = requireVoiceBotCreds();
            if (authErr != null) return authErr;
            validateId(voiceBotId, "voiceBotId");
            String url = getVoicebotBaseUrl() + "/accounts/" + getAccountId() + "/voicebots/" + voiceBotId;
            HttpEntity<Void> entity = new HttpEntity<>(jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
            String body = response.getBody();
            return (body != null && !body.isBlank()) ? body : "VoiceBot " + voiceBotId + " deleted successfully.";
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_delete", e);
        } catch (HttpServerErrorException e) { return serverErrorMsg("exotel_voicebot_delete", e);
        } catch (IllegalStateException | IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error deleting VoiceBot: " + e.getMessage(); }
    }

    @Tool(name = "exotel_voicebot_create",
          description = "Creates a new VoiceBot using AI-powered bot generation. Provide a text description of the bot's personality, purpose, and behavior. The bot is generated asynchronously — use exotel_voicebot_creation_status to poll until complete.")
    public String createVoiceBot(String textContent) {
        logger.info("Creating VoiceBot — textLength={}", textContent != null ? textContent.length() : 0);
        try {
            String authErr = requireVoiceBotCreds();
            if (authErr != null) return authErr;
            if (textContent == null || textContent.isBlank())
                return "Error: textContent is required — describe the bot's personality and purpose";
            if (textContent.length() > 10_000)
                return "Error: textContent too long (max 10,000 characters)";

            String url = getVoicebotBaseUrl() + "/accounts/" + getAccountId() + "/bot-generation";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("Authorization", "Basic " + basicToken());

            org.springframework.util.LinkedMultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
            body.add("text_content", textContent);

            HttpEntity<org.springframework.util.LinkedMultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_create", e);
        } catch (HttpServerErrorException e) { return serverErrorMsg("exotel_voicebot_create", e);
        } catch (IllegalStateException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error creating VoiceBot: " + e.getMessage(); }
    }

    @Tool(name = "exotel_voicebot_creation_status",
          description = "Checks the status of a VoiceBot generation request. Status: pending -> in_progress -> completed. When completed, the bot appears in exotel_voicebot_list_all.")
    public String getBotGenerationStatus(String generationId) {
        logger.info("Checking bot generation: {}", sanitizeForLog(generationId));
        try {
            String authErr = requireVoiceBotCreds();
            if (authErr != null) return authErr;
            validateId(generationId, "generationId");
            String url = getVoicebotBaseUrl() + "/accounts/" + getAccountId() + "/bot-generation/" + generationId;
            HttpEntity<Void> entity = new HttpEntity<>(jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_creation_status", e);
        } catch (HttpServerErrorException e) { return serverErrorMsg("exotel_voicebot_creation_status", e);
        } catch (IllegalStateException | IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error checking status: " + e.getMessage(); }
    }

    @Tool(name = "exotel_voicebot_place_call",
          description = "Places an outbound call powered by a VoiceBot — the bot handles the conversation autonomously. "
              + "Requires: toNumber (phone to call), voiceBotId (UUID from exotel_voicebot_list_all). "
              + "Optional: callerId (display number), customField (metadata). "
              + "After calling, use exotel_voicebot_call_status with the returned CallSid to check status.")
    public String makeOutboundBotCall(String toNumber, String voiceBotId, String callerId, String customField) {
        logger.info("Outbound bot call — to={}, bot={}", sanitizeForLog(toNumber), sanitizeForLog(voiceBotId));
        try {
            String authErr = requireVoiceBotCreds();
            if (authErr != null) return authErr;
            String callsErr = requireCallsCreds();
            if (callsErr != null) return callsErr;
            validateId(voiceBotId, "voiceBotId");
            if (toNumber == null || toNumber.isBlank()) return "Error: toNumber is required";
            if (!PHONE_PATTERN.matcher(toNumber).matches()) return "Error: toNumber contains invalid characters";
            if (callerId != null && !callerId.isBlank() && !PHONE_PATTERN.matcher(callerId).matches())
                return "Error: callerId contains invalid characters";

            String accountId = getAccountId();
            String effectiveCallerId = (callerId != null && !callerId.isBlank()) ? callerId : defaultCallerId;

            // Step 1: Fetch WebSocket stream URL from bot dp-endpoint
            String dpBase = getVoicebotBaseUrl().replaceFirst("/api/v\\d+$", "/api/v1");
            String dpEndpointUrl = dpBase + "/accounts/" + accountId + "/bots/" + voiceBotId + "/dp-endpoint";

            HttpHeaders dpHeaders = new HttpHeaders();
            dpHeaders.set("Authorization", "Basic " + basicToken());
            dpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
            ResponseEntity<String> dpResponse = restTemplate.exchange(
                    dpEndpointUrl, HttpMethod.GET, new HttpEntity<>(dpHeaders), String.class);

            String streamUrl = extractStreamUrl(safeBody(dpResponse));
            if (streamUrl == null || streamUrl.isBlank())
                return "Error: Could not extract stream URL from VoiceBot dp-endpoint. Verify the bot ID is correct and active.";
            if (!streamUrl.startsWith("wss://"))
                return "Error: VoiceBot returned insecure WebSocket URL. Contact support.";

            // Step 2: POST to Calls connect.json
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
            if (customField != null && !customField.isBlank())
                form.add("CustomField", customField.substring(0, Math.min(customField.length(), 1000)));

            ResponseEntity<String> connectResponse = restTemplate.exchange(
                    connectUrl, HttpMethod.POST, new HttpEntity<>(form, connectHeaders), String.class);
            return safeBody(connectResponse);

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401) return "Error 401: Authentication failed. Check API credentials.";
            return errorMsg("exotel_voicebot_place_call", e);
        } catch (HttpServerErrorException e) { return serverErrorMsg("exotel_voicebot_place_call", e);
        } catch (IllegalStateException | IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error making outbound bot call: " + e.getMessage(); }
    }

    @Tool(name = "exotel_voicebot_call_status",
          description = "Gets details of a call by its Call SID. Returns status, duration, recording URL, timestamps. Use after exotel_voicebot_place_call.")
    public String getBotCallDetails(String callSid) {
        logger.info("Getting call details: {}", sanitizeForLog(callSid));
        try {
            String callsErr = requireCallsCreds();
            if (callsErr != null) return callsErr;
            validateId(callSid, "callSid");
            String url = getCallsBaseUrl() + "/v1/Accounts/" + getCallsAccountId() + "/Calls/" + callSid + ".json";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + callsBasicToken());
            headers.set("Accept", "application/json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_call_status", e);
        } catch (HttpServerErrorException e) { return serverErrorMsg("exotel_voicebot_call_status", e);
        } catch (IllegalStateException | IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error getting call details: " + e.getMessage(); }
    }

    @Tool(name = "exotel_voicebot_list_phone_numbers",
          description = "Lists all phone numbers (DIDs) available on the Exotel account. Use to find a valid callerId before placing calls.")
    public String listAccountPhoneNumbers() {
        logger.info("Listing account phone numbers");
        try {
            String callsErr = requireCallsCreds();
            if (callsErr != null) return callsErr;
            String url = getCallsBaseUrl() + "/v1/Accounts/" + getCallsAccountId() + "/IncomingPhoneNumbers.json";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + callsBasicToken());
            headers.set("Accept", "application/json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_list_phone_numbers", e);
        } catch (HttpServerErrorException e) { return serverErrorMsg("exotel_voicebot_list_phone_numbers", e);
        } catch (IllegalStateException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error listing phone numbers: " + e.getMessage(); }
    }


    // ======================== HELPERS ========================

    private String formatPhoneNumber(String number) {
        if (number == null || number.isBlank()) return number;
        String digits = number.replaceAll("[^0-9]", "");
        if (number.startsWith("+")) return number;
        if (digits.length() == 12 && digits.startsWith("91")) return "+" + digits;
        if (digits.length() == 11 && digits.startsWith("0")) return "+91" + digits.substring(1);
        if (digits.length() == 10) return "+91" + digits;
        return number;
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
        String voicebotCookie = sanitize(defaultVoicebotSessionCookie);
        String deviceCookie = sanitize(defaultDeviceSessionCookie);
        if (!voicebotCookie.isBlank() && !deviceCookie.isBlank()) {
            headers.set("Cookie", "voicebot_session_mum_prod=" + voicebotCookie
                    + "; exotel_device_session_in=" + deviceCookie);
        }
        return headers;
    }

    private String sanitize(String value) {
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
        if (body.length() > MAX_RESPONSE_BYTES) return body.substring(0, MAX_RESPONSE_BYTES);
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
