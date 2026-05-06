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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * VoiceBot MCP Service — exposes Exotel VoiceBot platform APIs as MCP tools.
 *
 * VoiceBot management API:  https://voicebot.in.exotel.com/voicebot/api/v2
 * Outbound calls API:       https://api.in.exotel.com/v1 (Exotel Calls/connect with VoiceBotSid)
 *
 * All APIs use the same VoiceBot account credentials (Basic auth).
 * Credentials are resolved in priority order:
 *   1. CredentialStore (set via setupExotelCredentials tool)
 *   2. application.properties defaults (@Value)
 */
@Service
public class VoiceBotService {

    private static final Logger logger = LoggerFactory.getLogger(VoiceBotService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final RestTemplate restTemplate = createRestTemplate();

    private static RestTemplate createRestTemplate() {
        try {
            // Trust-all SSL scoped only to this RestTemplate (for QA environments with self-signed certs).
            // Does NOT modify JVM-global SSL defaults.
            SSLContext sslCtx = SSLContextBuilder.create()
                    .loadTrustMaterial(null, (chain, authType) -> true)
                    .build();
            var connMgr = PoolingHttpClientConnectionManagerBuilder.create()
                    .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                            .setSslContext(sslCtx)
                            .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                            .build())
                    .build();
            var httpClient = HttpClients.custom().setConnectionManager(connMgr).build();
            var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
            factory.setConnectTimeout(10_000);
            return new RestTemplate(factory);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create RestTemplate", e);
        }
    }

    @Autowired
    private CredentialStore credentialStore;

    // VoiceBot account credentials — used for both management API and Calls API
    @Value("${exotel.voicebot.api.key:}")
    private String defaultApiKey;

    @Value("${exotel.voicebot.api.token:}")
    private String defaultApiToken;

    @Value("${exotel.voicebot.account.id:}")
    private String defaultAccountId;

    @Value("${exotel.voicebot.base.url:https://voicebot.in.exotel.com/voicebot/api/v2}")
    private String voicebotBaseUrl;

    @Value("${exotel.voicebot.calls.base.url:https://api.in.exotel.com}")
    private String callsBaseUrl;

    @Value("${exotel.calls.requested.server.code:}")
    private String requestedServerCode;

    @Value("${exotel.calls.account.sid:${exotel.voicebot.account.id:}}")
    private String defaultCallsAccountId;

    @Value("${exotel.calls.api.key:}")
    private String defaultCallsApiKey;

    @Value("${exotel.calls.api.token:}")
    private String defaultCallsApiToken;

    private String getCallsAccountId() { return resolve("exotel.calls.account.sid", defaultCallsAccountId); }
    private String getCallsApiKey()    { String v = resolve("exotel.calls.api.key", defaultCallsApiKey); return v.isBlank() ? getApiKey() : v; }
    private String getCallsApiToken()  { String v = resolve("exotel.calls.api.token", defaultCallsApiToken); return v.isBlank() ? getApiToken() : v; }
    private String callsBasicToken()   { return Base64.getEncoder().encodeToString((getCallsApiKey() + ":" + getCallsApiToken()).getBytes(StandardCharsets.UTF_8)); }

    @Value("${exotel.voicebot.default.caller.id:04446312776}")
    private String defaultCallerId;

    @Value("${exotel.voicebot.session.cookie:}")
    private String defaultVoicebotSessionCookie;

    @Value("${exotel.voicebot.device.session.cookie:}")
    private String defaultDeviceSessionCookie;

    // Credential resolvers
    private String getApiKey()    { return resolve("exotel.voicebot.api.key", defaultApiKey); }
    private String getApiToken()  { return resolve("exotel.voicebot.api.token", defaultApiToken); }
    private String getAccountId() { return resolve("exotel.voicebot.account.id", defaultAccountId); }
    private String getVoicebotSessionCookie() { return resolve("exotel.voicebot.session.cookie", defaultVoicebotSessionCookie); }
    private String getDeviceSessionCookie() { return resolve("exotel.voicebot.device.session.cookie", defaultDeviceSessionCookie); }

    private String resolve(String key, String fallback) {
        String val = credentialStore.get(key);
        return (val != null && !val.isBlank()) ? val : fallback;
    }

    public void setAuthHeaderForSession(String authHeader) {
        com.example.mcp_api.config.RequestAuthContext.set(authHeader);
    }

    public void setAuthHeaderForSessionKey(String sessionKey, String authHeader) {
        com.example.mcp_api.config.RequestAuthContext.set(authHeader);
    }

    // ======================== MCP TOOLS ========================

    @Tool(name = "listVoiceBots",
          description = "Lists all VoiceBots on the Exotel account. Supports pagination (offset/limit) and filtering by status (active/inactive). Returns bot id, name, status, languages, voice config, and version info.")
    public String listVoiceBots(String status, String limit, String offset) {
        logger.info("Listing VoiceBots — status={}, limit={}, offset={}", status, limit, offset);
        try {
            StringBuilder url = new StringBuilder(voicebotBaseUrl)
                    .append("/accounts/").append(getAccountId()).append("/voicebots?");

            if (limit != null && !limit.isBlank()) url.append("limit=").append(limit).append("&");
            else url.append("limit=20&");

            if (offset != null && !offset.isBlank()) url.append("offset=").append(offset).append("&");
            if (status != null && !status.isBlank()) url.append("status=").append(status).append("&");

            HttpEntity<Void> entity = new HttpEntity<>(jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(
                    url.toString(), HttpMethod.GET, entity, String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            return errorMsg("listVoiceBots", e);
        } catch (Exception e) {
            return "Error listing VoiceBots: " + e.getMessage();
        }
    }

    @Tool(name = "getVoiceBot",
          description = "Gets full details of a single VoiceBot by its ID. Returns name, status, supported_languages, assistant_config, voice_config, asr_config, post_session_insights, and version history.")
    public String getVoiceBot(String voiceBotId) {
        logger.info("Getting VoiceBot: {}", voiceBotId);
        try {
            String url = voicebotBaseUrl + "/accounts/" + getAccountId() + "/voicebots/" + voiceBotId;
            HttpEntity<Void> entity = new HttpEntity<>(jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            return errorMsg("getVoiceBot", e);
        } catch (Exception e) {
            return "Error getting VoiceBot: " + e.getMessage();
        }
    }

    @Tool(name = "deleteVoiceBot",
          description = "Deletes a VoiceBot permanently by its ID. This action cannot be undone. Always confirm with the user before calling this tool.")
    public String deleteVoiceBot(String voiceBotId) {
        logger.info("Deleting VoiceBot: {}", voiceBotId);
        try {
            String url = voicebotBaseUrl + "/accounts/" + getAccountId() + "/voicebots/" + voiceBotId;
            HttpEntity<Void> entity = new HttpEntity<>(jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
            String body = response.getBody();
            return body != null ? body : "VoiceBot " + voiceBotId + " deleted successfully.";
        } catch (HttpClientErrorException e) {
            return errorMsg("deleteVoiceBot", e);
        } catch (Exception e) {
            return "Error deleting VoiceBot: " + e.getMessage();
        }
    }

    @Tool(name = "createVoiceBot",
          description = "Creates a new VoiceBot using AI-powered bot generation. Provide a text description of the bot's personality, purpose, and behavior. The bot is generated asynchronously — use getBotGenerationStatus to poll until status is 'completed'. Params: textContent (description of the bot, e.g. 'You are a friendly customer support agent for Acme Corp. Help customers with orders and returns.').")
    public String createVoiceBot(String textContent) {
        logger.info("Creating VoiceBot via bot-generation — text={}", textContent);
        try {
            String url = voicebotBaseUrl + "/accounts/" + getAccountId() + "/bot-generation";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("Authorization", "Basic " + basicToken());

            org.springframework.util.LinkedMultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
            body.add("text_content", textContent);

            HttpEntity<org.springframework.util.LinkedMultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            logger.info("createVoiceBot status: {}", response.getStatusCode());
            return response.getBody();
        } catch (HttpClientErrorException e) {
            return errorMsg("createVoiceBot", e);
        } catch (Exception e) {
            return "Error creating VoiceBot: " + e.getMessage();
        }
    }

    @Tool(name = "getBotGenerationStatus",
          description = "Checks the status of a VoiceBot generation request. Status progresses: pending -> in_progress -> completed. When completed, the bot appears in listVoiceBots. Params: generationId (the ID returned by createVoiceBot).")
    public String getBotGenerationStatus(String generationId) {
        logger.info("Checking bot generation status: {}", generationId);
        try {
            String url = voicebotBaseUrl + "/accounts/" + getAccountId() + "/bot-generation/" + generationId;
            HttpEntity<Void> entity = new HttpEntity<>(jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            return errorMsg("getBotGenerationStatus", e);
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
        logger.info("Outbound bot call — to={}, bot={}, callerId={}", toNumber, voiceBotId, callerId);
        try {
            String accountId = getAccountId();
            String effectiveCallerId = (callerId != null && !callerId.isBlank()) ? callerId : defaultCallerId;

            // Step 1: Fetch the WebSocket stream URL from the bot's dp-endpoint
            String dpEndpointUrl = "https://voicebot.in.exotel.com/voicebot/api/v1/accounts/"
                    + accountId + "/bots/" + voiceBotId + "/dp-endpoint";

            HttpHeaders dpHeaders = new HttpHeaders();
            dpHeaders.set("Authorization", "Basic " + basicToken());
            dpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
            ResponseEntity<String> dpResponse = restTemplate.exchange(
                    dpEndpointUrl, HttpMethod.GET, new HttpEntity<>(dpHeaders), String.class);

            String streamUrl = extractStreamUrl(dpResponse.getBody());
            if (streamUrl == null || streamUrl.isBlank()) {
                return "Error: Could not extract stream URL from dp-endpoint response: " + dpResponse.getBody();
            }
            logger.info("callWithBot stream URL: {}", streamUrl);

            // Step 2: POST to Calls connect — use http:// to match QA env, auth via header
            String baseUrl = callsBaseUrl.replaceFirst("^https://", "http://");
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
            form.add("__IgnoreServerStatus", "true");
            if (requestedServerCode != null && !requestedServerCode.isBlank()) {
                form.add("__RequestedServerCode", requestedServerCode);
            }

            ResponseEntity<String> connectResponse = restTemplate.exchange(
                    connectUrl, HttpMethod.POST, new HttpEntity<>(form, connectHeaders), String.class);
            logger.info("callWithBot connect status: {}", connectResponse.getStatusCode());
            return connectResponse.getBody();

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401) {
                return "Error 401: Authentication failed. Check your API key and token in application-local.properties.";
            }
            return errorMsg("callWithBot", e);
        } catch (Exception e) {
            return "Error making outbound bot call: " + e.getMessage();
        }
    }

    @Tool(name = "getBotCallDetails",
          description = "Gets details of a specific call by its Call SID. Returns status, duration, recording URL, timestamps, and direction. Use after makeOutboundBotCall to check if the call connected.")
    public String getBotCallDetails(String callSid) {
        logger.info("Getting call details: {}", callSid);
        try {
            String url = callsBaseUrl + "/v1/Accounts/" + getAccountId() + "/Calls/" + callSid + ".json";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + basicToken());
            headers.set("Accept", "application/json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            return errorMsg("getBotCallDetails", e);
        } catch (Exception e) {
            return "Error getting call details: " + e.getMessage();
        }
    }

    @Tool(name = "listAccountPhoneNumbers",
          description = "Lists all phone numbers (DIDs) available on the Exotel account. Use this to find a valid callerId before placing outbound bot calls.")
    public String listAccountPhoneNumbers() {
        logger.info("Listing account phone numbers");
        try {
            String url = callsBaseUrl + "/v1/Accounts/" + getAccountId() + "/IncomingPhoneNumbers.json";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + basicToken());
            headers.set("Accept", "application/json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            return errorMsg("listAccountPhoneNumbers", e);
        } catch (Exception e) {
            return "Error listing phone numbers: " + e.getMessage();
        }
    }

    @Tool(name = "listRecentBotCalls",
          description = "Lists recent calls on the account. Supports limit (default 10) and sortBy (DateCreated:desc). Use to review call history and outcomes.")
    public String listRecentBotCalls(String limit) {
        logger.info("Listing recent calls — limit={}", limit);
        try {
            String lim = (limit != null && !limit.isBlank()) ? limit : "10";
            String url = callsBaseUrl + "/v1/Accounts/" + getAccountId()
                    + "/Calls.json?Limit=" + lim + "&SortBy=DateCreated:desc";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + basicToken());
            headers.set("Accept", "application/json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            return errorMsg("listRecentBotCalls", e);
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
                if (!node.isMissingNode() && !node.isNull()) return node.asText();
            }
            Iterator<JsonNode> elements = root.elements();
            while (elements.hasNext()) {
                String val = elements.next().asText();
                if (val.startsWith("wss://")) return val;
            }
        } catch (Exception e) {
            logger.warn("JSON parse failed for dp-endpoint response, scanning for wss:// URL");
        }
        for (String prefix : new String[]{"wss://", "ws://"}) {
            int idx = responseBody.indexOf(prefix);
            if (idx >= 0) {
                int end = responseBody.indexOf('"', idx);
                return end > idx ? responseBody.substring(idx, end) : responseBody.substring(idx);
            }
        }
        return null;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("Authorization", "Basic " + basicToken());
        String voicebotCookie = getVoicebotSessionCookie();
        String deviceCookie = getDeviceSessionCookie();
        if (voicebotCookie != null && !voicebotCookie.isBlank() &&
                deviceCookie != null && !deviceCookie.isBlank()) {
            headers.set("Cookie",
                    "voicebot_session_mum_prod=" + voicebotCookie +
                    "; exotel_device_session_in=" + deviceCookie);
        }
        return headers;
    }

    private String basicToken() {
        return Base64.getEncoder().encodeToString(
                (getApiKey() + ":" + getApiToken()).getBytes(StandardCharsets.UTF_8));
    }

    private String errorMsg(String method, HttpClientErrorException e) {
        logger.error("{} API error: {} - {}", method, e.getStatusCode(), e.getResponseBodyAsString());
        return "Error " + e.getStatusCode() + ": " + e.getResponseBodyAsString();
    }
}
