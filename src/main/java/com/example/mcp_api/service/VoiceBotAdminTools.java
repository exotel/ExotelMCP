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
 * VoiceBot admin tools — assistants, personas, TTS, specializations, transcripts.
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

    // ======================== ASSISTANT HELPERS ========================

    /** Rewrites one prompt location. agentCount is 0 for the top-level instruction. */
    public interface InstructionPatch { String apply(String current, String oldTopLevel, int agentCount); }

    /**
     * Accepts an assistant ID or a VoiceBot ID (what people usually have: exotel_voicebot_create returns the bot ID)
     * and returns the assistant ID.
     */
    public String resolveAssistantId(String id) throws Exception {
        validateId(id, "assistantId");
        try {
            JsonNode d = getAssistantData(id);
            if (d.has("id")) return id;
        } catch (HttpClientErrorException notAnAssistant) { /* try as a bot */ }
        String botUrl = getBaseUrl() + "/accounts/" + getAccountId() + "/voicebots/" + id;
        JsonNode root = objectMapper.readTree(safeBody(restTemplate.exchange(botUrl, HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class)));
        JsonNode bot = root.path("response").has("data") ? root.path("response").path("data") : root.path("response");
        String assistant = bot.path("assistant_config").path("id").asText("");
        if (assistant.isBlank()) throw new IllegalArgumentException(id + " is neither an assistant ID nor a VoiceBot ID on this account");
        return assistant;
    }

    /** The assistant's stable version: instruction, agents, stable_version. */
    public JsonNode getAssistantData(String assistantId) throws Exception {
        String url = getBaseUrl() + "/accounts/" + getAccountId() + "/assistants/" + assistantId + "?version=stable";
        JsonNode root = objectMapper.readTree(safeBody(restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class)));
        return root.path("response").path("data");
    }

    /** The full VoiceBot config (v1), including greeting_message and webhook_config. */
    public JsonNode getBotV1(String botId) throws Exception {
        validateId(botId, "voiceBotId");
        String url = getV1BaseUrl() + "/accounts/" + getAccountId() + "/voicebots/" + botId;
        JsonNode root = objectMapper.readTree(safeBody(restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class)));
        JsonNode d = root.path("response").path("data");
        if (!d.isObject()) d = root.path("response");
        if (!d.has("id")) throw new IllegalStateException("could not read VoiceBot " + botId);
        return d;
    }

    /**
     * Publishes a new stable assistant version with every prompt location rewritten: the top-level instruction and each
     * agent's instruction. Generated bots keep the same prompt in both places and the agent's copy is the one used on calls.
     * Returns {"version": "vN"} on success.
     */
    public JsonNode publishInstructions(String assistantId, InstructionPatch patch, String description) throws Exception {
        JsonNode data = getAssistantData(assistantId);
        String stable = data.path("stable_version").asText(data.path("latest_version").asText(""));
        String oldTop = data.path("instruction").asText("");
        JsonNode agents = data.path("agents");
        if (!agents.isArray() || agents.isEmpty()) agents = data.path("versions").path(stable).path("agents");
        int count = agents.isArray() ? agents.size() : 0;

        ObjectNode version = objectMapper.createObjectNode();
        if (!oldTop.isEmpty() || count == 0) version.put("instruction", patch.apply(oldTop, oldTop, 0));
        if (count > 0) {
            ArrayNode out = objectMapper.createArrayNode();
            for (JsonNode a : agents) {
                ObjectNode copy = (ObjectNode) a.deepCopy();
                copy.put("instruction", patch.apply(a.path("instruction").asText(""), oldTop, count));
                out.add(copy);
            }
            version.set("agents", out);
        }
        ObjectNode payload = objectMapper.createObjectNode();
        if (!stable.isBlank()) payload.put("source_version", stable);
        payload.put("mark_as_stable", "true");
        payload.put("version_description", description);
        payload.set("data", version);
        String postUrl = getBaseUrl() + "/accounts/" + getAccountId() + "/assistants/" + assistantId + "/versions";
        JsonNode res = objectMapper.readTree(safeBody(restTemplate.exchange(postUrl, HttpMethod.POST,
            new HttpEntity<>(objectMapper.writeValueAsString(payload), adminHeaders()), String.class)));
        ObjectNode out = objectMapper.createObjectNode();
        out.put("version", res.path("response").path("data").path("version").asText(""));
        out.put("assistantId", assistantId);
        out.put("promptLocations", 1 + count);
        return out;
    }

    // ======================== ASSISTANT TOOLS ========================

    @Tool(name = "exotel_voicebot_assistant_get_config",
          description = "Gets the full configuration of a VoiceBot assistant (agents, instructions, llm_config, tools, intent detection). "
              + "assistantId may be the assistant ID or the VoiceBot ID. version: 'stable' (default) or e.g. 'v2'.")
    public String getAssistant(String assistantId, String version) {
        logger.info("Getting assistant: {} version={}", sanitize(assistantId), sanitize(version));
        try {
            String err = requireAuth(); if (err != null) return err;
            assistantId = resolveAssistantId(assistantId);
            String ver = (version != null && !version.isBlank()) ? version : "stable";
            String url = getBaseUrl() + "/accounts/" + getAccountId() + "/assistants/" + assistantId + "?version=" + ver;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_assistant_get_config", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_assistant_get_config", e);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(name = "exotel_voicebot_assistant_update_prompt",
          description = "Replaces a VoiceBot's prompt and publishes it as the new stable version. assistantId may be the VoiceBot ID or "
              + "the assistant ID. Updates every place the prompt lives (the assistant's instruction and the agent that answers calls); "
              + "for multi-agent assistants only the agent that shares the main prompt is changed. Returns the new version.")
    public String updateBotPrompt(String assistantId, String newInstruction, String versionDescription) {
        logger.info("Updating prompt for assistant={}", sanitize(assistantId));
        try {
            String err = requireAuth(); if (err != null) return err;
            if (newInstruction == null || newInstruction.isBlank()) return "Error: newInstruction is required";
            if (newInstruction.length() > 50_000) return "Error: newInstruction too long (max 50,000 characters)";
            String aid = resolveAssistantId(assistantId);
            JsonNode out = publishInstructions(aid,
                (current, oldTop, agentCount) -> (agentCount <= 1 || current.isBlank() || current.equals(oldTop)) ? newInstruction : current,
                versionDescription != null && !versionDescription.isBlank() ? versionDescription : "Prompt updated via MCP");
            return objectMapper.writeValueAsString(((ObjectNode) out).put("ok", true));
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_assistant_update_prompt", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_assistant_update_prompt", e);
        } catch (Exception e) { return "Error updating prompt: " + e.getMessage(); }
    }

    @Tool(name = "exotel_voicebot_assistant_push_version",
          description = "Creates a new version of an assistant with custom data payload. For advanced updates: attaching tools, updating intent config, swapping agents, or the top-level instruction of a single-agent bot. dataJson must be valid JSON. sourceVersion defaults to the current stable version.")
    public String createAssistantVersion(String assistantId, String sourceVersion, String markAsStable, String versionDescription, String dataJson) {
        logger.info("Creating assistant version — assistant={}", sanitize(assistantId));
        try {
            String err = requireAuth(); if (err != null) return err;
            assistantId = resolveAssistantId(assistantId);
            if (dataJson == null || dataJson.isBlank()) return "Error: dataJson is required";
            JsonNode dataNode;
            try { dataNode = objectMapper.readTree(dataJson); }
            catch (Exception e) { return "Error: dataJson is not valid JSON — " + e.getMessage(); }

            ObjectNode payload = objectMapper.createObjectNode();
            if (sourceVersion == null || sourceVersion.isBlank()) {
                // The API needs the version the new one is based on; default to the assistant's current stable version.
                String getUrl = getBaseUrl() + "/accounts/" + getAccountId() + "/assistants/" + assistantId + "?version=stable";
                JsonNode cur = objectMapper.readTree(safeBody(restTemplate.exchange(getUrl, HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class)));
                JsonNode d = cur.path("response").path("data");
                sourceVersion = d.path("stable_version").asText(d.path("latest_version").asText(""));
            }
            if (sourceVersion != null && !sourceVersion.isBlank()) payload.put("source_version", sourceVersion);
            payload.put("mark_as_stable", markAsStable != null ? markAsStable : "true");
            payload.put("version_description", versionDescription != null && !versionDescription.isBlank() ? versionDescription : "Updated via MCP");
            payload.set("data", dataNode);

            String url = getBaseUrl() + "/accounts/" + getAccountId() + "/assistants/" + assistantId + "/versions";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), adminHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_assistant_push_version", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_assistant_push_version", e);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(name = "exotel_voicebot_assistant_create_multiagent",
          description = "Creates a new multi-agent assistant. Provide name, description, instruction (system prompt), and agentsJson (JSON array of agent objects with per-agent LLM config).")
    public String createAssistant(String name, String description, String instruction, String agentsJson) {
        logger.info("Creating multi-agent assistant: {}", sanitize(name));
        try {
            String err = requireAuth(); if (err != null) return err;
            if (name == null || name.isBlank()) return "Error: name is required";

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("name", name);
            if (description != null && !description.isBlank()) payload.put("description", description);
            if (instruction != null && !instruction.isBlank()) payload.put("instruction", instruction);
            if (agentsJson != null && !agentsJson.isBlank()) {
                try { payload.set("agents", objectMapper.readTree(agentsJson)); }
                catch (Exception e) { return "Error: agentsJson is not valid JSON — " + e.getMessage(); }
            }

            String url = getBaseUrl() + "/accounts/" + getAccountId() + "/assistants";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), adminHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_assistant_create_multiagent", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_assistant_create_multiagent", e);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    // ======================== TRANSCRIPT TOOL ========================

    @Tool(name = "exotel_voicebot_transcript_get",
          description = "Fetches the full conversation transcript for a VoiceBot session by its session UUID (not call SID).")
    public String getBotSessionTranscript(String sessionId) {
        logger.info("Getting transcript: {}", sanitize(sessionId));
        try {
            String err = requireAuth(); if (err != null) return err;
            validateId(sessionId, "sessionId");
            // /sessions/{id}/transcript returns 404 (verified live, Nov 2026); the conversation resource carries the transcript.
            String url = getBaseUrl() + "/accounts/" + getAccountId() + "/voicebot-conversations/" + sessionId + "?fields=transcript";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_transcript_get", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_transcript_get", e);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static final Pattern CALL_SID_PATTERN = Pattern.compile("^[a-zA-Z0-9]{8,64}$");

    @Tool(name = "exotel_voicebot_find_session_by_call_sid",
          description = "Finds the VoiceBot conversation session for a phone call, given the Call SID returned by "
              + "exotel_voicebot_place_call. Searches the most recent conversations (limit, default 50, max 200; offset for older pages). "
              + "Returns the matching conversation record including its session id - pass that id to exotel_voicebot_transcript_get. "
              + "Read-only.")
    public String findSessionByCallSid(String callSid, String limit, String offset) {
        logger.info("Finding session for call: {}", sanitize(callSid));
        try {
            String err = requireAuth(); if (err != null) return err;
            if (callSid == null || !CALL_SID_PATTERN.matcher(callSid.trim()).matches())
                return "Error: callSid is required and must be alphanumeric";
            validateNumericParam(limit, "limit");
            validateNumericParam(offset, "offset");
            int lim = (limit == null || limit.isBlank()) ? 50 : Math.min(Integer.parseInt(limit), 200);
            int off = (offset == null || offset.isBlank()) ? 0 : Integer.parseInt(offset);
            String sid = callSid.trim();

            String url = getBaseUrl() + "/accounts/" + getAccountId()
                + "/voicebot-conversations?limit=" + lim + "&offset=" + off;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
            JsonNode root = objectMapper.readTree(safeBody(response));

            JsonNode match = findObjectWithCallSid(root, sid, 0);
            if (match == null) {
                return "{\"found\":false,\"call_sid\":\"" + sid + "\",\"searched\":" + lim + ",\"offset\":" + off
                    + ",\"hint\":\"Not in this page. The call may still be in progress, or be older - retry with a larger offset.\"}";
            }
            ObjectNode compact = ((ObjectNode) match).deepCopy();
            compact.remove("transcript");
            ObjectNode out = objectMapper.createObjectNode();
            out.put("found", true);
            out.put("call_sid", sid);
            out.set("conversation", compact);
            out.put("next", "Pass the conversation's session id to exotel_voicebot_transcript_get.");
            return objectMapper.writeValueAsString(out);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_find_session_by_call_sid", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_find_session_by_call_sid", e);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    /** Depth-first search for the first JSON object that has a *call_sid* / *callSid* field equal to sid. */
    private JsonNode findObjectWithCallSid(JsonNode node, String sid, int depth) {
        if (node == null || depth > 8) return null;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var f = fields.next();
                String key = f.getKey().toLowerCase().replace("_", "");
                if (key.endsWith("callsid") && f.getValue().isTextual() && sid.equalsIgnoreCase(f.getValue().asText()))
                    return node;
            }
            for (JsonNode child : node) {
                JsonNode hit = findObjectWithCallSid(child, sid, depth + 1);
                if (hit != null) return hit;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode hit = findObjectWithCallSid(child, sid, depth + 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    // ======================== BOT CONFIG TOOL ========================

    @Tool(name = "exotel_voicebot_update_config",
          description = "Updates VoiceBot TTS, ASR, VAD, denoiser, webhook, or greeting config. configJson is the full or partial config object to PUT.")
    public String updateBotConfig(String botId, String configJson) {
        logger.info("Updating bot config: {}", sanitize(botId));
        try {
            String err = requireAuth(); if (err != null) return err;
            validateId(botId, "botId");
            if (configJson == null || configJson.isBlank()) return "Error: configJson is required";
            JsonNode patchNode;
            try { patchNode = objectMapper.readTree(configJson); }
            catch (Exception e) { return "Error: configJson is not valid JSON — " + e.getMessage(); }

            String url = getV1BaseUrl() + "/accounts/" + getAccountId() + "/voicebots/" + botId;

            // GET current full config, merge patch, then PUT back
            ResponseEntity<String> getResp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
            JsonNode current = objectMapper.readTree(safeBody(getResp));
            JsonNode dataNode = current.path("response").path("data");
            if (dataNode.isMissingNode() || !dataNode.isObject())
                return "Error: could not read current bot config — unexpected API response structure";
            ObjectNode fullConfig = (ObjectNode) dataNode.deepCopy();
            patchNode.fields().forEachRemaining(e -> fullConfig.set(e.getKey(), e.getValue()));

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(fullConfig), adminHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_update_config", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_update_config", e);
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
            String url = getV1BaseUrl() + "/accounts/" + getAccountId() + "/voice-providers";
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

    // ======================== SPECIALIZATION TOOLS ========================

    @Tool(name = "exotel_voicebot_specialization_list",
          description = "Lists all specializations (reusable config overrides like language switching, speed adjustments) for VoiceBots.")
    public String listSpecializations() {
        logger.info("Listing specializations");
        try {
            String err = requireAuth(); if (err != null) return err;
            String url = getV1BaseUrl() + "/accounts/" + getAccountId() + "/specializations";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_specialization_list", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_specialization_list", e);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(name = "exotel_voicebot_specialization_create",
          description = "Creates a new specialization. Params: name, type (e.g. 'language_switching'), configJson (JSON with assistant_override_config or voicebot_override_config).")
    public String createSpecialization(String name, String type, String configJson) {
        logger.info("Creating specialization: {} type={}", sanitize(name), sanitize(type));
        try {
            String err = requireAuth(); if (err != null) return err;
            if (name == null || name.isBlank()) return "Error: name is required";
            if (type == null || type.isBlank()) return "Error: type is required";

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("name", name);
            payload.put("type", type);
            if (configJson != null && !configJson.isBlank()) {
                try {
                    JsonNode configNode = objectMapper.readTree(configJson);
                    configNode.fields().forEachRemaining(e -> payload.set(e.getKey(), e.getValue()));
                } catch (Exception e) { return "Error: configJson is not valid JSON — " + e.getMessage(); }
            }

            String url = getV1BaseUrl() + "/accounts/" + getAccountId() + "/specializations";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), adminHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_specialization_create", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_specialization_create", e);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(name = "exotel_voicebot_specialization_update",
          description = "Updates an existing specialization's name or configuration. Params: specializationId (UUID), name (optional), configJson (optional JSON with override config).")
    public String updateSpecialization(String specializationId, String name, String configJson) {
        logger.info("Updating specialization: {}", sanitize(specializationId));
        try {
            String err = requireAuth(); if (err != null) return err;
            validateId(specializationId, "specializationId");

            ObjectNode payload = objectMapper.createObjectNode();
            if (name != null && !name.isBlank()) payload.put("name", name);
            if (configJson != null && !configJson.isBlank()) {
                try {
                    JsonNode configNode = objectMapper.readTree(configJson);
                    configNode.fields().forEachRemaining(e -> payload.set(e.getKey(), e.getValue()));
                } catch (Exception e) { return "Error: configJson is not valid JSON — " + e.getMessage(); }
            }
            if (payload.isEmpty()) return "Error: provide at least name or configJson to update";

            String url = getV1BaseUrl() + "/accounts/" + getAccountId() + "/specializations/" + specializationId;
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), adminHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_voicebot_specialization_update", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_voicebot_specialization_update", e);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    // ======================== HELPERS ========================

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

    private String serverError(String method, HttpServerErrorException e) {
        logger.error("{} server error: {} - {}", method, e.getStatusCode(), e.getResponseBodyAsString());
        return "Error " + e.getStatusCode().value() + ": " + method + " — upstream service unavailable";
    }
}
