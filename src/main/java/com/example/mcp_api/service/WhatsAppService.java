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
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WhatsApp tools for the Exotel MCP.
 *
 * Exposes a "send WhatsApp message" tool that sends a message through Exotel's
 * WhatsApp API (Exotel acts as the WhatsApp BSP), authenticated with the same
 * per-account CPaaS token used by the SMS/voice tools (AuthContext).
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
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String WHATSAPP_SEND_PATH = "/v2/accounts/%s/messages";

    @Tool(name = "exotel_whatsapp_send_message",
          description = "Send a WhatsApp message to a single recipient through Exotel (Exotel is the WhatsApp BSP). "
              + "Use messageType='template' with templateName (and templateParams for body variables) to start a new "
              + "conversation, or messageType='text' with body to reply inside the 24-hour customer-service window. "
              + "Authentication (account, API token, WhatsApp from-number) is taken automatically from the session.")
    public String sendWhatsAppMessage(
            @ToolParam(description = "Recipient phone number in E.164, e.g. +919876543210") String toNumber,
            @ToolParam(description = "Message type: 'template' or 'text'") String messageType,
            @ToolParam(required = false, description = "Template name (required when messageType='template')") String templateName,
            @ToolParam(required = false, description = "Template language code, e.g. 'en' / 'hi' (template only; default 'en')") String templateLanguage,
            @ToolParam(required = false, description = "Ordered body variables for the template, if any") List<String> templateParams,
            @ToolParam(required = false, description = "Free-text message body (required when messageType='text', 24h window only)") String body) {

        logger.info("Sending WhatsApp message to: {} (type: {})", toNumber, messageType);

        AuthCredentials creds = AuthContext.current();
        String token = creds.getToken();
        String apiDomain = creds.getApiDomain();
        String accountSid = creds.getAccountSid();
        String fromNumber = creds.getFromNumber(); // WhatsApp sender number for this account

        if (token == null || token.isBlank() || accountSid == null || accountSid.isBlank()
                || apiDomain == null || apiDomain.isBlank() || fromNumber == null || fromNumber.isBlank()) {
            return "{\"message\":\"Missing WhatsApp credentials. Provide token, account_sid, api_domain and "
                + "from_number (your WhatsApp sender number) in the Authorization header.\"}";
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
            String url = apiDomain + String.format(WHATSAPP_SEND_PATH, accountSid);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // CPaaS token is Base64(api_key:api_token) — same Basic-auth scheme as the SMS/voice tools.
            headers.set("Authorization", "Basic " + token);

            Map<String, Object> requestBody = buildMessageBody(
                    fromNumber, toNumber, messageType, templateName, templateLanguage, templateParams, body);

            String json = objectMapper.writeValueAsString(requestBody);
            logger.info("WhatsApp send URL: {}", url);

            ResponseEntity<String> response =
                    restTemplate.postForEntity(url, new HttpEntity<>(json, headers), String.class);

            logger.info("WhatsApp response status: {}", response.getStatusCode());
            return response.getBody();
        } catch (Exception e) {
            logger.error("Error sending WhatsApp message", e);
            return "{\"message\":\"" + e.getMessage() + "\"}";
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
     */
    private Map<String, Object> buildMessageBody(
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
