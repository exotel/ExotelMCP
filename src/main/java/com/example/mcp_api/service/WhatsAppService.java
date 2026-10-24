package com.example.mcp_api.service;

import com.example.mcp_api.auth.AuthContext;
import com.example.mcp_api.auth.AuthCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WhatsApp tools for the Exotel MCP.
 *
 * Sends a WhatsApp message through Exotel's WhatsApp API (Exotel acts as the
 * WhatsApp BSP). Authentication reuses the per-account CPaaS token, but the
 * WhatsApp SENDER is a dedicated auth field `whatsapp_from` — a customer's
 * WhatsApp-enabled number is usually different from their SMS/voice number, so
 * this does NOT reuse `from_number`.
 *
 * Contract:
 *   POST {api_domain}/v2/accounts/{account_sid}/messages
 *   Auth: HTTP Basic (api_key:api_token) — the CPaaS token is Base64(key:secret).
 *   Body (messages are wrapped under "whatsapp"):
 *     { "whatsapp": { "messages": [ {
 *         "from", "to",
 *         "content": { "recipient_type", "type", "text" | "template" }
 *     } ] } }
 *
 * Supports a pre-approved template (to start a conversation) or free text inside
 * the 24-hour customer-service window.
 */
@Service
public class WhatsAppService {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = buildRestTemplate();

    private static final String WHATSAPP_SEND_PATH = "/v2/accounts/%s/messages";

    // Same 10s connect / 30s read budget as the SMS and Engage tools, so a hung
    // upstream call cannot pin an mcp.exotel.com thread indefinitely.
    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        return new RestTemplate(factory);
    }

    @Tool(name = "exotel_whatsapp_send_message",
          description = "Send a WhatsApp message to a single recipient through Exotel (Exotel is the WhatsApp BSP). "
              + "Use messageType='template' with templateName (and templateParams for body variables) to start a new "
              + "conversation, or messageType='text' with body to reply inside the 24-hour customer-service window. "
              + "The WhatsApp sender comes from the 'whatsapp_from' header (your WhatsApp-enabled E.164 number with a "
              + "leading +, NOT the WABA id); account/token/api_domain are taken from the session.")
    public String sendWhatsAppMessage(
            @ToolParam(description = "Recipient phone number in E.164, e.g. +919876543210") String toNumber,
            @ToolParam(description = "Message type: 'template' or 'text'") String messageType,
            @ToolParam(required = false, description = "Template name (required when messageType='template')") String templateName,
            @ToolParam(required = false, description = "Template language code exactly as approved in Meta, e.g. 'en' or 'en_US' (template only)") String templateLanguage,
            @ToolParam(required = false, description = "Ordered body variables for the template, if any") List<String> templateParams,
            @ToolParam(required = false, description = "Free-text message body (required when messageType='text', 24h window only)") String body) {

        logger.info("Sending WhatsApp message (type: {})", messageType);

        AuthCredentials creds = AuthContext.current();
        String token = creds.getToken();
        String apiDomain = creds.getApiDomain();
        String accountSid = creds.getAccountSid();
        String whatsappFrom = creds.getWhatsappFrom(); // dedicated WhatsApp sender (NOT from_number)

        if (token == null || token.isBlank() || accountSid == null || accountSid.isBlank()
                || apiDomain == null || apiDomain.isBlank()) {
            return "{\"message\":\"Missing credentials. Provide token, account_sid and api_domain in the Authorization header.\"}";
        }
        if (whatsappFrom == null || whatsappFrom.isBlank()) {
            return "{\"message\":\"Missing 'whatsapp_from'. Add it to the Authorization header: your WhatsApp-enabled "
                + "sender number in E.164 with a leading + (e.g. +919876500001), not the WABA id.\"}";
        }
        if (toNumber == null || !toNumber.matches("\\+?[1-9]\\d{6,14}")) {
            return "{\"message\":\"toNumber must be a valid E.164 phone number, e.g. +919876543210.\"}";
        }
        if (!"template".equalsIgnoreCase(messageType) && !"text".equalsIgnoreCase(messageType)) {
            return "{\"message\":\"messageType must be 'template' or 'text'.\"}";
        }
        if ("template".equalsIgnoreCase(messageType) && (templateName == null || templateName.isBlank())) {
            return "{\"message\":\"templateName is required when messageType='template'.\"}";
        }
        if ("text".equalsIgnoreCase(messageType) && (body == null || body.isBlank())) {
            return "{\"message\":\"body is required when messageType='text' (only valid inside the 24h window).\"}";
        }

        try {
            String base = apiDomain.replaceAll("/+$", ""); // strip trailing slash(es)
            String url = base + String.format(WHATSAPP_SEND_PATH, accountSid);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // CPaaS token is Base64(api_key:api_token) — same Basic-auth scheme as the SMS/voice tools.
            headers.set("Authorization", "Basic " + token);

            Map<String, Object> requestBody = buildMessageBody(
                    whatsappFrom, toNumber, messageType, templateName, templateLanguage, templateParams, body);

            String json = objectMapper.writeValueAsString(requestBody);
            logger.info("WhatsApp send URL: {}", url);

            ResponseEntity<String> response =
                    restTemplate.postForEntity(url, new HttpEntity<>(json, headers), String.class);

            logger.info("WhatsApp response status: {}", response.getStatusCode());
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            // Pass the upstream error through so the agent sees the real reason
            // (e.g. template not found, 24h window, sender not WhatsApp-enabled).
            logger.error("WhatsApp send failed: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            String respBody = e.getResponseBodyAsString();
            if (respBody != null && !respBody.isBlank()) {
                return respBody; // Exotel returns a JSON error body
            }
            return "{\"message\":\"WhatsApp send failed with status " + e.getStatusCode().value() + ".\"}";
        } catch (Exception e) {
            logger.error("Error sending WhatsApp message", e);
            try {
                return "{\"message\":" + objectMapper.writeValueAsString(String.valueOf(e.getMessage())) + "}";
            } catch (Exception ignored) {
                return "{\"message\":\"Error sending WhatsApp message.\"}";
            }
        }
    }

    /**
     * Build the Exotel WhatsApp Send request body:
     *
     *   { "whatsapp": { "messages": [ {
     *       "from": ..., "to": ...,
     *       "content": {
     *          "recipient_type": "individual",
     *          "type": "text",     "text": { "body": ... }
     *          // or
     *          "type": "template", "template": { "name", "language": {"code","policy"},
     *                                            "components": [{ "type":"body",
     *                                              "parameters":[{"type":"text","text":<var>}] }] }
     *       }
     *   } ] } }
     *
     * Package-private static for unit testing.
     */
    static Map<String, Object> buildMessageBody(
            String from, String to, String messageType,
            String templateName, String templateLanguage, List<String> templateParams, String body) {

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("recipient_type", "individual");

        if ("template".equalsIgnoreCase(messageType)) {
            Map<String, Object> language = new LinkedHashMap<>();
            language.put("code", (templateLanguage == null || templateLanguage.isBlank()) ? "en" : templateLanguage);
            language.put("policy", "deterministic");

            Map<String, Object> template = new LinkedHashMap<>();
            template.put("name", templateName);
            template.put("language", language);

            if (templateParams != null && !templateParams.isEmpty()) {
                List<Map<String, Object>> parameters = new ArrayList<>();
                for (String p : templateParams) {
                    Map<String, Object> param = new LinkedHashMap<>();
                    param.put("type", "text");
                    param.put("text", p);
                    parameters.add(param);
                }
                Map<String, Object> bodyComponent = new LinkedHashMap<>();
                bodyComponent.put("type", "body");
                bodyComponent.put("parameters", parameters);
                template.put("components", List.of(bodyComponent));
            }

            content.put("type", "template");
            content.put("template", template);
        } else {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("body", body);
            content.put("type", "text");
            content.put("text", text);
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("from", from);
        message.put("to", to);
        message.put("content", content);

        Map<String, Object> whatsapp = new LinkedHashMap<>();
        whatsapp.put("messages", List.of(message));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("whatsapp", whatsapp);
        return root;
    }
}
