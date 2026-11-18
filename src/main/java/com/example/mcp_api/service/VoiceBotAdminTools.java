package com.example.mcp_api.service;

import com.example.mcp_api.auth.AuthContext;
import com.example.mcp_api.auth.AuthCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * VoiceBot admin tools — assistant read, personas, TTS catalogue.
 * These use admin credentials (admin_username/admin_password) when available,
 * falling back to voicebot API key/token.
 */
@Service
public class VoiceBotAdminTools {

    private static final Logger logger = LoggerFactory.getLogger(VoiceBotAdminTools.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F\\-]{1,64}$");
    private static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^[0-9]{1,5}$");

    private RestTemplate restTemplate;

    @Value("${exotel.voicebot.ssl.verify:true}")
    private boolean sslVerify;

    @Value("${exotel.voicebot.base.url:https://voicebot.in.exotel.com/voicebot/api/v2}")
    private String defaultBaseUrl;

    @Value("${exotel.voicebot.api.key:}")
    private String defaultApiKey;

    @Value("${exotel.voicebot.api.token:}")
    private String defaultApiToken;

    @Value("${exotel.voicebot.account.id:}")
    private String defaultAccountId;

    @jakarta.annotation.PostConstruct
    void init() {
        try {
            var connMgrBuilder = PoolingHttpClientConnectionManagerBuilder.create();
            if (!sslVerify) {
                SSLContext sslCtx = SSLContextBuilder.create()
                        .loadTrustMaterial(null, (chain, authType) -> true).build();
                connMgrBuilder.setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                        .setSslContext(sslCtx).setHostnameVerifier(NoopHostnameVerifier.INSTANCE).build());
            }
            var httpClient = HttpClients.custom().setConnectionManager(connMgrBuilder.build()).build();
            var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
            factory.setConnectTimeout(10_000);
            factory.setReadTimeout(30_000);
            this.restTemplate = new RestTemplate(factory);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create RestTemplate", e);
        }
    }

    // ======================== CREDENTIAL RESOLUTION ========================

    private String getAccountId() {
        AuthCredentials creds = AuthContext.current();
        String val = creds.getVoicebotAccountId();
        return val != null ? val : defaultAccountId;
    }

    private String getBaseUrl() {
        AuthCredentials creds = AuthContext.current();
        return creds.effectiveVoicebotBaseUrl(defaultBaseUrl);
    }

    private String getV1BaseUrl() {
        return getBaseUrl().replaceFirst("/api/v\\d+$", "/api/v1");
    }

    private HttpHeaders adminHeaders() {
        AuthCredentials creds = AuthContext.current();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        if (creds.hasAdminCredentials()) {
            headers.set("Authorization", "Basic " + creds.adminBasicToken());
        } else if (creds.hasVoicebotCredentials()) {
            headers.set("Authorization", "Basic " + creds.voicebotBasicToken());
        } else if (defaultApiKey != null && !defaultApiKey.isBlank()) {
            String token = Base64.getEncoder().encodeToString(
                (defaultApiKey + ":" + defaultApiToken).getBytes(StandardCharsets.UTF_8));
            headers.set("Authorization", "Basic " + token);
        }
        return headers;
    }

    private String requireAuth() {
        return AuthContext.requireVoicebot();
    }

    private void validateId(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (!UUID_PATTERN.matcher(value).matches())
            throw new IllegalArgumentException(name + " contains invalid characters");
    }

    private void validateNumericParam(String value, String name) {
        if (value != null && !value.isBlank() && !NUMERIC_PATTERN.matcher(value).matches())
            throw new IllegalArgumentException(name + " must be a number (1-99999)");
    }

    private String sanitize(String input) {
        if (input == null) return "null";
        return input.replaceAll("[\\r\\n\\t]", " ").substring(0, Math.min(input.length(), 200));
    }

    // ======================== ASSISTANT TOOLS ========================

    @Tool(name = "exotel_voicebot_assistant_get_config",
          description = "Gets the full configuration of a VoiceBot assistant. Returns agents, instructions, llm_config, mcp_tools, intent_detection_config. Use version='stable' (default) or specific like 'v1', 'v2'.")
    public String getAssistant(String assistantId, String version) {
        logger.info("Getting assistant: {} version={}", sanitize(assistantId), sanitize(version));
        try {
            String err = requireAuth(); if (err != null) return err;
            validateId(assistantId, "assistantId");
            String ver = (version != null && !version.isBlank()) ? version : "stable";
            String url = getBaseUrl() + "/accounts/" + getAccountId() + "/assistants/" + assistantId + "?version=" + ver;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_assistant_get_config", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_assistant_get_config", e);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    // ======================== PERSONA TOOLS ========================

    @Tool(name = "exotel_voicebot_persona_list",
          description = "Lists all personas defined for the account. Personas define bot personality, language, and gender prompts.")
    public String listPersonas(String limit, String offset) {
        logger.info("Listing personas");
        try {
            String err = requireAuth(); if (err != null) return err;
            validateNumericParam(limit, "limit");
            validateNumericParam(offset, "offset");
            String lim = (limit != null && !limit.isBlank()) ? limit : "50";
            String off = (offset != null && !offset.isBlank()) ? offset : "0";
            String url = getV1BaseUrl() + "/accounts/" + getAccountId() + "/personas?limit=" + lim + "&offset=" + off;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_persona_list", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_persona_list", e);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(name = "exotel_voicebot_persona_create",
          description = "Creates a new persona with instruction, language prompt, and gender prompt.")
    public String createPersona(String name, String instruction, String languagePrompt, String genderPrompt) {
        logger.info("Creating persona: {}", sanitize(name));
        try {
            String err = requireAuth(); if (err != null) return err;
            if (name == null || name.isBlank()) return "Error: name is required";

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("name", name);
            if (instruction != null && !instruction.isBlank()) payload.put("instruction", instruction);
            if (languagePrompt != null && !languagePrompt.isBlank()) payload.put("language_prompt", languagePrompt);
            if (genderPrompt != null && !genderPrompt.isBlank()) payload.put("gender_prompt", genderPrompt);

            String url = getV1BaseUrl() + "/accounts/" + getAccountId() + "/personas";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), adminHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_persona_create", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_persona_create", e);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(name = "exotel_voicebot_persona_update",
          description = "Updates an existing persona's instruction, language prompt, or gender prompt.")
    public String updatePersona(String personaId, String instruction, String languagePrompt, String genderPrompt) {
        logger.info("Updating persona: {}", sanitize(personaId));
        try {
            String err = requireAuth(); if (err != null) return err;
            validateId(personaId, "personaId");

            ObjectNode payload = objectMapper.createObjectNode();
            if (instruction != null && !instruction.isBlank()) payload.put("instruction", instruction);
            if (languagePrompt != null && !languagePrompt.isBlank()) payload.put("language_prompt", languagePrompt);
            if (genderPrompt != null && !genderPrompt.isBlank()) payload.put("gender_prompt", genderPrompt);
            if (payload.isEmpty()) return "Error: provide at least one field to update";

            String url = getV1BaseUrl() + "/accounts/" + getAccountId() + "/personas/" + personaId;
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), adminHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_persona_update", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_persona_update", e);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    // ======================== TTS TOOLS ========================

    @Tool(name = "exotel_voicebot_tts_list_providers",
          description = "Lists all TTS (Text-to-Speech) voice providers available (e.g. ElevenLabs, Azure, Sarvam). Use provider ID with exotel_voicebot_tts_list_voices.")
    public String listTtsProviders() {
        logger.info("Listing TTS providers");
        try {
            String err = requireAuth(); if (err != null) return err;
            // The API pages at 10 newest by default; ask for 100 so older providers are not silently missing.
            String url = getV1BaseUrl() + "/accounts/" + getAccountId() + "/voice-providers?limit=100";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_tts_list_providers", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_tts_list_providers", e);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(name = "exotel_voicebot_tts_list_voices",
          description = "Lists available TTS voices for a given provider. Returns voice IDs, names, gender, language. Params: providerId (UUID), limit (default 100).")
    public String listTtsVoices(String providerId, String limit) {
        logger.info("Listing TTS voices for provider={}", sanitize(providerId));
        try {
            String err = requireAuth(); if (err != null) return err;
            validateId(providerId, "providerId");
            validateNumericParam(limit, "limit");
            String lim = (limit != null && !limit.isBlank()) ? limit : "100";
            String url = getV1BaseUrl() + "/accounts/" + getAccountId() + "/voice-providers/" + providerId + "/voices?limit=" + lim;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_tts_list_voices", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_tts_list_voices", e);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    // ======================== HELPERS ========================


    private String safeBody(ResponseEntity<String> response) {
        String body = VoiceBotBuilderTools.redactJsonText(response.getBody());
        if (body == null) return "{}";
        if (body.length() > MAX_RESPONSE_BYTES) return body.substring(0, MAX_RESPONSE_BYTES);
        return body;
    }

    private String errorMsg(String method, HttpClientErrorException e) {
        logger.error("{} API error: {} - {}", method, e.getStatusCode(), e.getResponseBodyAsString());
        return "Error " + e.getStatusCode().value() + ": " + method + " request failed";
    }

    private String serverError(String method, HttpServerErrorException e) {
        logger.error("{} server error: {} - {}", method, e.getStatusCode(), e.getResponseBodyAsString());
        return "Error " + e.getStatusCode().value() + ": " + method + " — upstream service unavailable";
    }
}
