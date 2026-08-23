package com.example.mcp_api.service;

import com.example.mcp_api.auth.AuthContext;
import com.example.mcp_api.auth.AuthCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Engage MCP tools — SMS message campaigns via engage.exotel.com.
 *
 * Credentials reuse existing CPaaS / calls_* fields (no separate engage_* product).
 * Prefer calls_api_key + calls_api_token + calls_account_id (matches other telephony tools);
 * fall back to CPaaS token + account_sid.
 */
@Service
public class EngageService {

    private static final Logger logger = LoggerFactory.getLogger(EngageService.class);
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;

    private RestTemplate restTemplate;

    @Value("${exotel.voicebot.ssl.verify:true}")
    private boolean sslVerify;

    @Value("${exotel.engage.base.url:https://engage.exotel.com}")
    private String engageBaseUrl;

    @Value("${exotel.calls.api.key:}")
    private String defaultCallsApiKey;

    @Value("${exotel.calls.api.token:}")
    private String defaultCallsApiToken;

    @Value("${exotel.calls.account.sid:}")
    private String defaultCallsAccountId;

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

    // ======================== CREDENTIAL RESOLUTION ========================

    private String getCallsApiKey() {
        AuthCredentials creds = AuthContext.current();
        String val = creds.getCallsApiKey();
        if (val != null && !val.isBlank()) return val;
        if (defaultCallsApiKey != null && !defaultCallsApiKey.isBlank()) return defaultCallsApiKey;
        return null;
    }

    private String getCallsApiToken() {
        AuthCredentials creds = AuthContext.current();
        String val = creds.getCallsApiToken();
        if (val != null && !val.isBlank()) return val;
        if (defaultCallsApiToken != null && !defaultCallsApiToken.isBlank()) return defaultCallsApiToken;
        return null;
    }

    private String getAccountSid() {
        AuthCredentials creds = AuthContext.current();
        // Prefer calls_account_id (matches other telephony tools); fall back to CPaaS account_sid / env default
        String callsAccount = creds.getCallsAccountId();
        if (callsAccount != null && !callsAccount.isBlank()) return callsAccount;
        String cpaasSid = creds.getAccountSid();
        if (cpaasSid != null && !cpaasSid.isBlank()) return cpaasSid;
        if (defaultCallsAccountId != null && !defaultCallsAccountId.isBlank()) return defaultCallsAccountId;
        return null;
    }

    /**
     * Basic auth value for Engage: prefer calls_api_key + calls_api_token (matches other telephony
     * tools); fall back to CPaaS token (already Base64 of api_key:api_secret).
     */
    private String basicAuthValue() {
        String key = getCallsApiKey();
        String token = getCallsApiToken();
        if (key != null && !key.isBlank() && token != null && !token.isBlank()) {
            return Base64.getEncoder().encodeToString(
                    (key + ":" + token).getBytes(StandardCharsets.UTF_8));
        }
        String cpaasToken = AuthContext.current().getToken();
        if (cpaasToken != null && !cpaasToken.isBlank()) {
            return cpaasToken;
        }
        return null;
    }

    private String requireEngageCreds() {
        String accountSid = getAccountSid();
        String basic = basicAuthValue();
        if (accountSid == null || accountSid.isBlank() || basic == null || basic.isBlank()) {
            return "Missing credentials for Engage SMS campaigns.\n\n"
                    + "Provide either:\n"
                    + "  - calls_api_key, calls_api_token, calls_account_id\n"
                    + "or:\n"
                    + "  - token (Base64 of api_key:api_secret) and account_sid\n\n"
                    + "Get them from my.exotel.com → API Settings.\n"
                    + "For setup help, use the tool: exotel_setup_guide";
        }
        return null;
    }

    // ======================== TOOLS ========================

    @Tool(name = "exotel_engage_create_sms_campaign",
          description = "Create an Engage SMS message campaign (static content) via "
              + "POST /api/v1/accounts/{account_sid}/message-campaigns. "
              + "Required: name, lists (contact list SIDs), from (sender ID), message_type "
              + "(e.g. transactional), template (SMS body), dlt_entity_id, template_id. "
              + "Optional: schedule_start_time / schedule_end_time (ISO-8601), status_callback, "
              + "message_status_callback, sms_url_shortening, shorten_url_header, "
              + "click_url_event_enabled, click_tracking_callback_url. "
              + "Uses calls_* or CPaaS credentials from the Authorization header. "
              + "channel is fixed to sms and content_type to static.")
    public String createSmsCampaign(
            @ToolParam(description = "Campaign name") String name,
            @ToolParam(description = "Contact list SIDs") List<String> lists,
            @ToolParam(description = "Sender ID / from") String from,
            @ToolParam(description = "Message type, e.g. transactional") String messageType,
            @ToolParam(description = "SMS template body") String template,
            @ToolParam(description = "DLT entity ID") String dltEntityId,
            @ToolParam(description = "DLT template ID") String templateId,
            @ToolParam(required = false, description = "Optional schedule start_time (ISO-8601)") String scheduleStartTime,
            @ToolParam(required = false, description = "Optional schedule end_time (ISO-8601)") String scheduleEndTime,
            @ToolParam(required = false, description = "Optional campaign status callback URL") String statusCallback,
            @ToolParam(required = false, description = "Optional per-message status callback URL") String messageStatusCallback,
            @ToolParam(required = false, description = "Enable SMS URL shortening") Boolean smsUrlShortening,
            @ToolParam(required = false, description = "Shorten URL header / domain label") String shortenUrlHeader,
            @ToolParam(required = false, description = "Enable click URL events") Boolean clickUrlEventEnabled,
            @ToolParam(required = false, description = "Click tracking callback URL") String clickTrackingCallbackUrl) {

        logger.info("Creating Engage SMS campaign name={} listsCount={}",
                sanitizeForLog(name), lists != null ? lists.size() : 0);

        try {
            String authErr = requireEngageCreds();
            if (authErr != null) return authErr;

            String validationErr = validateRequired(name, lists, from, messageType, template, dltEntityId, templateId);
            if (validationErr != null) return validationErr;

            Map<String, Object> body = buildSmsCampaignBody(
                    name, lists, from, messageType, template, dltEntityId, templateId,
                    scheduleStartTime, scheduleEndTime, statusCallback, messageStatusCallback,
                    smsUrlShortening, shortenUrlHeader, clickUrlEventEnabled, clickTrackingCallbackUrl);

            String accountSid = getAccountSid();
            String url = trimTrailingSlash(engageBaseUrl)
                    + "/api/v1/accounts/" + accountSid + "/message-campaigns";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("Authorization", "Basic " + basicAuthValue());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) {
            return errorMsg("exotel_engage_create_sms_campaign", e);
        } catch (HttpServerErrorException e) {
            return serverErrorMsg("exotel_engage_create_sms_campaign", e);
        } catch (Exception e) {
            logger.error("Error creating Engage SMS campaign", e);
            return "Error creating SMS campaign: " + e.getMessage();
        }
    }

    // ======================== BODY BUILDING (package-visible for tests) ========================

    static Map<String, Object> buildSmsCampaignBody(
            String name,
            List<String> lists,
            String from,
            String messageType,
            String template,
            String dltEntityId,
            String templateId,
            String scheduleStartTime,
            String scheduleEndTime,
            String statusCallback,
            String messageStatusCallback,
            Boolean smsUrlShortening,
            String shortenUrlHeader,
            Boolean clickUrlEventEnabled,
            String clickTrackingCallbackUrl) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", "sms");
        body.put("name", name.trim());
        body.put("lists", lists.stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList()));
        body.put("content_type", "static");
        body.put("from", from.trim());
        body.put("message_type", messageType.trim());
        body.put("template", template);
        body.put("dlt_entity_id", dltEntityId.trim());
        body.put("template_id", templateId.trim());

        if (hasText(scheduleStartTime) || hasText(scheduleEndTime)) {
            Map<String, Object> schedule = new LinkedHashMap<>();
            if (hasText(scheduleStartTime)) schedule.put("start_time", scheduleStartTime.trim());
            if (hasText(scheduleEndTime)) schedule.put("end_time", scheduleEndTime.trim());
            body.put("schedule", schedule);
        }

        putIfText(body, "status_callback", statusCallback);
        putIfText(body, "message_status_callback", messageStatusCallback);
        if (smsUrlShortening != null) {
            body.put("sms_url_shortening", smsUrlShortening);
        }
        putIfText(body, "shorten_url_header", shortenUrlHeader);
        if (clickUrlEventEnabled != null) {
            body.put("click_url_event_enabled", clickUrlEventEnabled);
        }
        putIfText(body, "click_tracking_callback_url", clickTrackingCallbackUrl);

        return body;
    }

    private static String validateRequired(
            String name,
            List<String> lists,
            String from,
            String messageType,
            String template,
            String dltEntityId,
            String templateId) {
        if (!hasText(name)) return "Error: name is required";
        if (lists == null || lists.isEmpty() || lists.stream().noneMatch(EngageService::hasText)) {
            return "Error: lists is required (one or more contact list SIDs)";
        }
        if (!hasText(from)) return "Error: from (sender ID) is required";
        if (!hasText(messageType)) return "Error: message_type is required (e.g. transactional)";
        if (!hasText(template)) return "Error: template (SMS body) is required";
        if (!hasText(dltEntityId)) return "Error: dlt_entity_id is required";
        if (!hasText(templateId)) return "Error: template_id is required";
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void putIfText(Map<String, Object> body, String key, String value) {
        if (hasText(value)) {
            body.put(key, value.trim());
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) return "https://engage.exotel.com";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String sanitizeForLog(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[\\r\\n]", "");
        return cleaned.substring(0, Math.min(cleaned.length(), 80));
    }

    private String safeBody(ResponseEntity<String> response) {
        String body = response.getBody();
        if (body == null) return "{}";
        if (body.length() > MAX_RESPONSE_BYTES) return body.substring(0, MAX_RESPONSE_BYTES);
        return body;
    }

    private String errorMsg(String method, HttpClientErrorException e) {
        logger.error("{} API error: {} - {}", method, e.getStatusCode(), e.getResponseBodyAsString());
        return "Error " + e.getStatusCode().value() + ": " + method + " request failed — "
                + truncateForClient(e.getResponseBodyAsString());
    }

    private String serverErrorMsg(String method, HttpServerErrorException e) {
        logger.error("{} server error: {} - {}", method, e.getStatusCode(), e.getResponseBodyAsString());
        return "Error " + e.getStatusCode().value() + ": " + method + " — upstream service unavailable";
    }

    private static String truncateForClient(String body) {
        if (body == null || body.isBlank()) return "";
        return body.length() > 500 ? body.substring(0, 500) + "…" : body;
    }
}
