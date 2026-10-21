package com.example.mcp_api.service;

import com.example.mcp_api.auth.AuthContext;
import com.example.mcp_api.auth.AuthCredentials;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bot-builder tools: create and version Gen2 voicebots, promote and split traffic,
 * read every field back, list the LLM / ASR / VAD catalogues and list conversations.
 *
 * <p>Credentials come only from the caller's envelope (voicebot_api_key, voicebot_api_token,
 * voicebot_account_id, optional voicebot_base_url) and go out as HTTP Basic, the same token
 * VoiceBotService builds. No server-side default key or session cookie is ever attached.
 *
 * <p>Every tool returns a JSON string. Failures share one shape:
 * {ok:false, error_code, human_readable_reason, suggested_action, http_status, body}.
 * Validation helpers are static and package-visible so the unit tests need no network.
 */
@Service
public class VoiceBotBuilderTools {

    private static final Logger logger = LoggerFactory.getLogger(VoiceBotBuilderTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Same path-safe id pattern as VoiceBotService / VoiceBotAdminTools. */
    static final Pattern ID_PATTERN = Pattern.compile("^[0-9a-fA-F\\-]{1,64}$");
    static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");
    static final Pattern VERSION_PATTERN = Pattern.compile("^v[0-9]{1,6}$");
    /** What Jsoup (Safelist.none, used by @SanitizeInput) parses as markup: tag, end tag, comment, declaration. */
    static final Pattern TAG_PATTERN = Pattern.compile("<[!?]|</?[A-Za-z]");
    /** Response keys whose values are credentials. The key must end with the term, so max_tokens is not matched. */
    static final Pattern SECRET_KEY_PATTERN = Pattern.compile(
            "(?i)^(?:.*[-_])?(?:api[-_]?key|api[-_]?secret|api[-_]?token|secret|secret[-_]?key|client[-_]?secret"
          + "|password|passwd|auth[-_]?token|access[-_]?token|refresh[-_]?token|id[-_]?token|token|private[-_]?key"
          + "|encryption[-_]?key|auth[-_]?config|auth[-_]?details|auth[-_]?data|authorization|cookie"
          + "|service[-_]?account(?:[-_]?json)?|credentials?|encryption[-_]?metadata)$");
    /** A whole string value that is an HTTP auth header value. */
    static final Pattern AUTH_VALUE_PATTERN = Pattern.compile("(?i)^\\s*(?:basic|bearer)\\s+[A-Za-z0-9+/=._~-]{8,}\\s*$");
    private static final Pattern AUTH_IN_TEXT_PATTERN = Pattern.compile("(?i)\\b(basic|bearer)\\s+[A-Za-z0-9+/=._~-]{12,}");
    static final String REDACTED = "<redacted>";

    static final int ERROR_BODY_MAX_CHARS = 2048;
    static final int MAX_INPUT_JSON_CHARS = 1_000_000;
    static final int MAX_RESPONSE_CHARS = 5 * 1024 * 1024;
    static final int MAX_TAG_FINDINGS = 20;
    static final int MAX_CATALOG_PROVIDERS = 60;
    static final int MAX_AGENTS = 10;
    static final int VERSIONS_DEFAULT_LIMIT = 50;
    static final int VERSIONS_MAX_LIMIT = 100;
    static final int CONVERSATIONS_DEFAULT_LIMIT = 20;
    static final int CONVERSATIONS_MAX_LIMIT = 100;

    /**
     * Top-level keys of the v1 create body (voicebot_application dto/BotConfigDTO.java) a caller may set.
     */
    static final Set<String> BOT_CREATE_ALLOWED_KEYS = orderedSet(
            "name", "supported_languages", "assistant_config", "voice_config", "asr_config", "stt_config",
            "vad_config", "audio_processing", "audio_logging", "filler_config", "nudge_config", "pii_masking_config",
            "greeting_message", "greeting_message_mode", "enable_greeting_interruption", "enable_interruption",
            "interruption_strategy", "user_speech_timeout_seconds", "enable_call_threshold",
            "call_duration_threshold_sec", "max_duration_message", "webhook_config", "post_session_insights",
            "conversation_assistant_id", "insights_assistant_id", "specializations", "variants", "repeated_calls",
            "low_latency_bot", "bot_gen", "cues", "bot_config");

    /** BotConfigDTO keys the API sets itself: ids, timestamps, lifecycle, freeze and App Bazaar flow. */
    static final Set<String> BOT_CREATE_SERVER_MANAGED_KEYS = orderedSet(
            "id", "bot_id", "account_id", "account_type", "active", "status", "version", "date_created",
            "date_updated", "frozen", "frozen_at", "frozen_by", "frozen_by_name", "ccm_flow_id");

    /** Keys VoicebotV2Mapper.mergeForVersionUpdate reads from a version POST's data (voicebot_application pu). */
    static final Set<String> VERSION_DATA_ALLOWED_KEYS = orderedSet(
            "name", "supported_languages", "assistant_config", "voice_config", "asr_config", "stt_config",
            "vad_config", "audio_processing", "audio_logging", "filler_config", "nudge_config", "pii_masking_config",
            "greeting_message", "greeting_message_mode", "enable_greeting_interruption", "enable_interruption",
            "interruption_strategy", "user_speech_timeout_seconds", "enable_call_threshold",
            "call_duration_threshold_sec", "max_duration_message", "webhook_config", "post_session_insights",
            "conversation_assistant_id", "insights_assistant_id", "specializations", "variants", "repeated_calls",
            "low_latency_bot", "bot_gen", "cues", "bot_config");

    /** VoicebotV2Mapper.ASSISTANT_VERSION_CONTENT_FIELDS: any of these in data.assistant_config forks an assistant version. */
    static final Set<String> ASSISTANT_FORK_FIELDS = orderedSet(
            "instruction", "llm_config", "fallback_llm_config", "intent_detection_config", "agents",
            "mcp_servers", "name", "description", "persona_id");

    /** Top-level keys of CreateAssistantRequestDTO. pre_agent_hooks is refused separately: hooks belong on agents. */
    static final Set<String> ASSISTANT_CREATE_ALLOWED_KEYS = orderedSet(
            "name", "description", "instruction", "persona_id", "llm_config", "fallback_llm_config",
            "mcp_servers", "intent_detection_config", "agents");

    /** Constants.SANITIZATION_EXCLUDED_FIELDS: @SanitizeInput skips these leaf keys and everything under them. */
    static final Set<String> SANITIZE_EXEMPT_KEYS = Set.of(
            "password", "api_key", "api_secret", "auth_token", "access_token", "secret_key", "client_secret",
            "private_key", "encryption_key", "webhook_url", "callback_url", "endpoint", "auth_config", "auth_data",
            "auth_details", "service_account");

    static final Set<String> CONVERSATION_FIELDS = orderedSet("transcript", "insights");

    /** Conversation keys kept in a compact row. Caller numbers, recording URLs and custom parameters are left out. */
    static final List<String> CONVERSATION_ROW_KEYS = List.of(
            "conversation_id", "session_id", "call_sid", "external_id", "bot_id", "bot_name", "bot_version",
            "assistant_version", "start_time", "end_time", "call_duration", "outcome", "disconnected_by",
            "network_type");

    private static final DateTimeFormatter JAVA_API_INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private RestTemplate restTemplate;

    @Value("${exotel.voicebot.ssl.verify:true}")
    private boolean sslVerify;

    @Value("${exotel.voicebot.base.url:https://voicebot.in.exotel.com/voicebot/api/v2}")
    private String defaultBaseUrl;

    @Value("${exotel.voicebot.builder.allowed-host-suffixes:.exotel.com,.exotel.in}")
    private String allowedHostSuffixesCsv;

    private List<String> allowedHostSuffixes = List.of(".exotel.com", ".exotel.in");

    @jakarta.annotation.PostConstruct
    void init() {
        this.restTemplate = createRestTemplate(sslVerify);
        if (allowedHostSuffixesCsv != null && !allowedHostSuffixesCsv.isBlank()) {
            List<String> parsed = new ArrayList<>();
            for (String s : allowedHostSuffixesCsv.split(",")) {
                String t = s.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) parsed.add(t);
            }
            if (!parsed.isEmpty()) allowedHostSuffixes = List.copyOf(parsed);
        }
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
            var httpClient = HttpClients.custom().setConnectionManager(connMgrBuilder.build()).build();
            var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
            factory.setConnectTimeout(10_000);
            factory.setReadTimeout(30_000);
            return new RestTemplate(factory);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create RestTemplate", e);
        }
    }

    /** Resolved per call from the envelope. toString never prints the Authorization value. */
    record Conn(String v1, String v2, String account, String authHeader) {
        @Override
        public String toString() {
            return "Conn[v1=" + v1 + ", v2=" + v2 + ", account=" + account + ", auth=<hidden>]";
        }
    }

    record Upstream(int status, String body, JsonNode json) {}

    /** Thrown inside a tool to stop with a structured error payload. */
    static final class ToolError extends RuntimeException {
        final transient Map<String, Object> payload;

        ToolError(Map<String, Object> payload) {
            super(String.valueOf(payload.get("error_code")), null, false, false);
            this.payload = payload;
        }
    }

    // ============================== BOT TOOLS ==============================

    @Tool(name = "exotel_voicebot_bot_create",
          description = "Create a Gen2 voicebot from a full v1 bot config: POST {v1}/accounts/{account}/voicebots. "
              + "Prefer this to exotel_voicebot_create: the v2 create cannot choose a TTS provider (it always takes the "
              + "platform default) or an ASR provider, and cannot set greeting, nudges, PII masking, interruption, webhook "
              + "actions or LCI ids. The v1 create makes v1 the bot's stable version at once; this tool promotes nothing else. "
              + "Unknown or server-managed top-level keys are rejected before the call, with allowed_keys. "
              + "Required: name, supported_languages, assistant_config {id, version, input {modality, mime_type}, "
              + "output {modality, mime_type}} (no input = Java 500). Text input (ASR cascade) also needs asr_config "
              + "{provider:{id}, model:{id}}. voice_config uses the v1 shape {provider:{id}, model:{id}, voice:{id}}; "
              + "vad_config is {provider_id, config_id}. Ids come from the exotel_voicebot_catalog_* tools and "
              + "exotel_voicebot_assistant_create. The API strips <tag>-shaped text; the result lists where. "
              + "Counts against the account's bot cap (429 when full).")
    public String botCreate(
            @ToolParam(description = "The v1 bot config as a JSON object (a JSON-encoded string also works), e.g. "
                + "{\"name\":\"Support bot\",\"supported_languages\":[\"en\"],\"assistant_config\":{\"id\":\"<assistant uuid>\","
                + "\"version\":\"stable\",\"input\":{\"modality\":\"text\",\"mime_type\":\"text/plain\"},"
                + "\"output\":{\"modality\":\"text\",\"mime_type\":\"text/plain\"}},\"voice_config\":{\"provider\":{\"id\":\"...\"},"
                + "\"model\":{\"id\":\"...\"},\"voice\":{\"id\":\"...\"}},\"asr_config\":{\"provider\":{\"id\":\"...\"},"
                + "\"model\":{\"id\":\"...\"}},\"greeting_message\":{\"text\":\"Hello\",\"enabled\":true}}")
            Object configJson) {
        return run("exotel_voicebot_bot_create", () -> {
            JsonNode cfg = parseJsonArg(configJson, "configJson");
            Map<String, Object> invalid = validateBotCreateConfig(cfg);
            if (invalid != null) return invalid;
            List<String> warnings = new ArrayList<>(tagWarnings(cfg));
            warnings.addAll(botCreateWarnings(cfg));

            Conn conn = connection();
            Upstream up = send(HttpMethod.POST,
                    buildUri(conn.v1(), null, "accounts", conn.account(), "voicebots"), conn, cfg);
            if (up.status() >= 400) return withWarnings(upstreamError(up.status(), up.body()), warnings);

            JsonNode data = unwrap(up.json());
            Map<String, Object> out = ok(up.status());
            out.put("bot_id", textOrNull(data, "id"));
            out.put("version", textOr(data, "version", "v1"));
            out.put("stable_version", "v1");
            out.put("next_steps", List.of(
                    "Read every stored field back with exotel_voicebot_bot_get_full(botId).",
                    "Change config later with exotel_voicebot_bot_version_create; new versions start at 0% traffic."));
            putWarnings(out, warnings);
            putData(out, data);
            return out;
        });
    }

    @Tool(name = "exotel_voicebot_bot_get_full",
          description = "Read a bot back with every field. Without version: GET {v1}/accounts/{account}/voicebots/{botId}, "
              + "the stable version in v1 shape and the only read that returns greeting_message, filler/nudge/PII config, "
              + "interruption settings, the full webhook_config and the LCI ids (conversation_assistant_id, "
              + "insights_assistant_id). With version (vN): GET {v2}/accounts/{account}/voicebots/{botId}/versions/{version}, "
              + "that version's status, is_stable, traffic_percentage, assistant_version and full config including the "
              + "resolved assistant. Secret values (API keys, auth configs, tokens) come back as <redacted>.")
    public String botGetFull(
            @ToolParam(description = "Bot id (UUID).") String botId,
            @ToolParam(required = false, description = "Optional version such as v3. Omit it to read the stable version "
                + "through the v1 API.") String version) {
        return run("exotel_voicebot_bot_get_full", () -> {
            requireId(botId, "botId");
            String v = trimToNull(version);
            if (v != null && !VERSION_PATTERN.matcher(v).matches()) {
                throw invalidParam("version must look like v1, v2, ... The version endpoint does not accept stable or "
                        + "latest; omit version to read the stable version.");
            }
            Conn conn = connection();
            URI target = v == null
                    ? buildUri(conn.v1(), null, "accounts", conn.account(), "voicebots", botId.trim())
                    : buildUri(conn.v2(), null, "accounts", conn.account(), "voicebots", botId.trim(), "versions", v);
            Upstream up = send(HttpMethod.GET, target, conn, null);
            if (up.status() >= 400) return upstreamError(up.status(), up.body());

            Map<String, Object> out = ok(up.status());
            out.put("bot_id", botId.trim());
            out.put("read_via", v == null
                    ? "GET v1 bot: the stable version with every stored field"
                    : "GET v2 version " + v + ": version metadata plus its full config");
            putData(out, unwrap(up.json()));
            return out;
        });
    }

    @Tool(name = "exotel_voicebot_bot_version_create",
          description = "Create a new bot version = source version + deep-merged data: "
              + "POST {v2}/accounts/{account}/voicebots/{botId}/versions. Always sends mark_as_stable=false, so the new "
              + "version gets 0% of traffic until exotel_voicebot_bot_version_promote or exotel_voicebot_bot_traffic_set; "
              + "markAsStable=true is refused. dataJson keys must be patchable bot fields (allowlist from "
              + "VoicebotV2Mapper.mergeForVersionUpdate); anything else is rejected before the call because Java would drop "
              + "it silently. A key set to null clears it; nudge_config is replaced whole. If data.assistant_config carries "
              + "instruction, agents, llm_config, fallback_llm_config, intent_detection_config, mcp_servers, persona_id, name "
              + "or description, Java forks a NEW (not stable) assistant version and pins this bot version to it; a sent "
              + "agents list is the complete list. A v2-shape voice_config keeps the current TTS vendor; send the v1 shape "
              + "{provider:{id}, model:{id}, voice:{id}} to switch. The API strips <tag>-shaped text; the result warns where.")
    public String botVersionCreate(
            @ToolParam(description = "Bot id (UUID).") String botId,
            @ToolParam(description = "Fields to change, as a JSON object (a JSON-encoded string also works), e.g. "
                + "{\"greeting_message\":{\"text\":\"Hi, this is Asha\",\"enabled\":true},\"user_speech_timeout_seconds\":1.2,"
                + "\"assistant_config\":{\"instruction\":\"...\"}}. Pass only the data object, not the whole request body.")
            Object dataJson,
            @ToolParam(required = false, description = "Version to copy: latest (default), stable or vN. An older vN "
                + "loses every change made after it.") String sourceVersion,
            @ToolParam(required = false, description = "Optional note shown in the version list (max 1000 characters).")
            String versionDescription,
            @ToolParam(required = false, description = "Leave unset. true is refused: check the new version, then promote it "
                + "with exotel_voicebot_bot_version_promote.") Boolean markAsStable) {
        return run("exotel_voicebot_bot_version_create", () -> {
            requireId(botId, "botId");
            if (Boolean.TRUE.equals(markAsStable)) return markAsStableRefused("markAsStable=true");
            String source = trimToNull(sourceVersion) == null ? "latest" : sourceVersion.trim();
            if (!isValidSourceVersion(source)) {
                throw invalidParam("sourceVersion must be latest, stable or a version such as v3.");
            }
            String description = trimToNull(versionDescription);
            if (description != null && description.length() > 1000) {
                throw invalidParam("versionDescription is longer than 1000 characters.");
            }
            JsonNode data = parseJsonArg(dataJson, "dataJson");
            Map<String, Object> invalid = validateVersionData(data, false);
            if (invalid != null) return invalid;

            ObjectNode body = MAPPER.createObjectNode();
            body.put("source_version", source);
            if (description != null) body.put("version_description", description);
            body.put("mark_as_stable", false);
            body.set("data", data);

            List<String> warnings = new ArrayList<>(tagWarnings(body));
            warnings.addAll(versionDataWarnings(data, source));

            Conn conn = connection();
            Upstream up = send(HttpMethod.POST,
                    buildUri(conn.v2(), null, "accounts", conn.account(), "voicebots", botId.trim(), "versions"),
                    conn, body);
            if (up.status() >= 400) return withWarnings(upstreamError(up.status(), up.body()), warnings);

            JsonNode created = unwrap(up.json());
            String newVersion = textOrNull(created, "version");
            Map<String, Object> out = ok(up.status());
            out.put("bot_id", botId.trim());
            out.put("version", newVersion);
            out.put("source_version", source);
            out.put("mark_as_stable", false);
            out.put("traffic", "0% until promoted or given a share with exotel_voicebot_bot_traffic_set");
            out.put("next_steps", List.of(
                    "Check it: exotel_voicebot_bot_get_full(botId, version=\"" + (newVersion == null ? "vN" : newVersion) + "\").",
                    "Go live: exotel_voicebot_bot_version_promote(botId, version, confirm=true) after the user approves, "
                  + "or split traffic with exotel_voicebot_bot_traffic_set."));
            putWarnings(out, warnings);
            putData(out, created);
            return out;
        });
    }

    @Tool(name = "exotel_voicebot_bot_versions_list",
          description = "List a bot's versions: GET {v2}/accounts/{account}/voicebots/{botId}/versions. Returns "
              + "stable_version, traffic_allocation and, per version, status, is_stable, traffic_percentage, "
              + "assistant_version and config_diff_fields. Page with next_offset.")
    public String botVersionsList(
            @ToolParam(description = "Bot id (UUID).") String botId,
            @ToolParam(required = false, description = "Offset, default 0.") Integer offset,
            @ToolParam(required = false, description = "Page size, default 50, max 100.") Integer limit) {
        return run("exotel_voicebot_bot_versions_list", () -> {
            requireId(botId, "botId");
            int off = offset == null ? 0 : offset;
            int lim = limit == null ? VERSIONS_DEFAULT_LIMIT : limit;
            if (off < 0) throw invalidParam("offset cannot be negative.");
            if (lim < 1 || lim > VERSIONS_MAX_LIMIT) throw invalidParam("limit must be 1-" + VERSIONS_MAX_LIMIT + ".");

            Conn conn = connection();
            Upstream up = send(HttpMethod.GET, versionsUri(conn, botId.trim(), off, lim), conn, null);
            if (up.status() >= 400) return upstreamError(up.status(), up.body());

            JsonNode data = unwrap(up.json());
            int count = data != null && data.path("versions").isArray() ? data.path("versions").size() : 0;
            int total = data != null && data.path("total").canConvertToInt() ? data.path("total").asInt() : -1;
            Map<String, Object> out = ok(up.status());
            out.put("bot_id", botId.trim());
            out.put("stable_version", textOrNull(data, "stable_version"));
            out.put("traffic_allocation", data == null ? null : data.get("traffic_allocation"));
            out.put("next_offset", nextOffset(off, lim, count, total));
            putData(out, data);
            return out;
        });
    }

    @Tool(name = "exotel_voicebot_bot_version_promote",
          description = "Make a bot version the stable version AND send it 100% of traffic: "
              + "PUT {v2}/accounts/{account}/voicebots/{botId}/versions/{version} with {\"is_stable\": true}. Java also marks "
              + "the assistant version that this bot version pins as that assistant's stable version, which changes every "
              + "other bot using the assistant at version \"stable\". Requires confirm=true. Without it nothing changes and "
              + "the result is a preview with the current stable version and traffic split.")
    public String botVersionPromote(
            @ToolParam(description = "Bot id (UUID).") String botId,
            @ToolParam(description = "Version to make stable, e.g. v3.") String version,
            @ToolParam(required = false, description = "Must be true to promote. Without it the tool only returns a "
                + "preview; get the user's approval of that preview first.") Boolean confirm) {
        return run("exotel_voicebot_bot_version_promote", () -> {
            requireId(botId, "botId");
            String v = requireVersion(version, "version");
            String bot = botId.trim();
            if (!Boolean.TRUE.equals(confirm)) {
                Map<String, Object> preview = promotePreview(bot, v);
                attachCurrentState(preview, bot, v);
                return confirmGate(confirm, "promote " + v + " of bot " + bot + " to stable with 100% traffic", preview);
            }
            Conn conn = connection();
            ObjectNode body = MAPPER.createObjectNode().put("is_stable", true);
            Upstream up = send(HttpMethod.PUT,
                    buildUri(conn.v2(), null, "accounts", conn.account(), "voicebots", bot, "versions", v), conn, body);
            if (up.status() >= 400) return upstreamError(up.status(), up.body());

            JsonNode data = unwrap(up.json());
            Map<String, Object> out = ok(up.status());
            out.put("bot_id", bot);
            out.put("promoted_version", v);
            out.put("stable_version", textOrNull(data, "stable_version"));
            out.put("traffic_allocation", data == null ? null : data.get("traffic_allocation"));
            putData(out, data);
            return out;
        });
    }

    @Tool(name = "exotel_voicebot_bot_traffic_set",
          description = "Split live traffic across bot versions: "
              + "PUT {v2}/accounts/{account}/voicebots/{botId}/versions/traffic-allocation with {\"allocation\": {...}}. "
              + "Percentages must be whole numbers 0-100 that sum to exactly 100; versions left out get 0%; archived "
              + "versions cannot get traffic. Does not change stable_version. Requires confirm=true. Without it nothing "
              + "changes and the result is a preview with the current split.")
    public String botTrafficSet(
            @ToolParam(description = "Bot id (UUID).") String botId,
            @ToolParam(description = "JSON object of version to percent, e.g. {\"v2\": 90, \"v3\": 10} "
                + "({\"allocation\": {...}} and a JSON-encoded string also work).") Object allocationJson,
            @ToolParam(required = false, description = "Must be true to apply. Without it the tool only returns a preview; "
                + "get the user's approval of that preview first.") Boolean confirm) {
        return run("exotel_voicebot_bot_traffic_set", () -> {
            requireId(botId, "botId");
            String bot = botId.trim();
            JsonNode raw = parseJsonArg(allocationJson, "allocationJson");
            Map<String, Integer> allocation = new LinkedHashMap<>();
            Map<String, Object> invalid = parseTrafficAllocation(raw, allocation);
            if (invalid != null) return invalid;
            if (!Boolean.TRUE.equals(confirm)) {
                Map<String, Object> preview = trafficPreview(bot, allocation);
                attachCurrentState(preview, bot, null);
                return confirmGate(confirm, "set the traffic split of bot " + bot + " to " + allocation, preview);
            }
            Conn conn = connection();
            ObjectNode body = MAPPER.createObjectNode();
            body.set("allocation", MAPPER.valueToTree(allocation));
            Upstream up = send(HttpMethod.PUT,
                    buildUri(conn.v2(), null, "accounts", conn.account(), "voicebots", bot, "versions", "traffic-allocation"),
                    conn, body);
            if (up.status() >= 400) return upstreamError(up.status(), up.body());

            JsonNode data = unwrap(up.json());
            Map<String, Object> out = ok(up.status());
            out.put("bot_id", bot);
            out.put("stable_version", textOrNull(data, "stable_version"));
            out.put("traffic_allocation", data == null ? null : data.get("traffic_allocation"));
            putData(out, data);
            return out;
        });
    }

    // ============================== ASSISTANT TOOL ==============================

    @Tool(name = "exotel_voicebot_assistant_create",
          description = "Create a conversation assistant (prompt, LLM, agents, MCP tools): POST {v2}/accounts/{account}/assistants. "
              + "Returns assistant_id for a bot's assistant_config.id; the new assistant's v1 is its stable version. "
              + "Checked before the call, because Java either fails with a 500 or commits a half-built assistant that still "
              + "counts against the assistant cap: unknown keys, assistant-level pre_agent_hooks, missing name, description "
              + "or instruction, 1-10 agents, every agent needs a name and an llm_config ({} inherits the assistant LLM), "
              + "unique agent and MCP server names. Get llm_config.id from exotel_voicebot_catalog_llm_models. The API "
              + "strips <tag>-shaped text; the result warns where.")
    public String assistantCreate(
            @ToolParam(description = "The assistant as a JSON object (a JSON-encoded string also works), e.g. "
                + "{\"name\":\"Support assistant\",\"description\":\"...\",\"instruction\":\"...\",\"llm_config\":{\"id\":"
                + "\"<LLM model id>\"},\"agents\":[{\"name\":\"main\",\"description\":\"...\",\"instruction\":\"...\","
                + "\"llm_config\":{}}]}") Object assistantJson) {
        return run("exotel_voicebot_assistant_create", () -> {
            JsonNode assistant = parseJsonArg(assistantJson, "assistantJson");
            Map<String, Object> invalid = validateAssistantCreate(assistant);
            if (invalid != null) return invalid;
            List<String> warnings = new ArrayList<>(tagWarnings(assistant));
            if (!assistant.hasNonNull("llm_config")) {
                warnings.add("DEFAULT_LLM: llm_config is omitted, so Java uses the account's default LLM mapping.");
            }

            Conn conn = connection();
            Upstream up = send(HttpMethod.POST,
                    buildUri(conn.v2(), null, "accounts", conn.account(), "assistants"), conn, assistant);
            if (up.status() >= 400) return withWarnings(upstreamError(up.status(), up.body()), warnings);

            JsonNode data = unwrap(up.json());
            if (data instanceof ObjectNode obj) obj.remove("versions"); // repeats the same content per version
            Map<String, Object> out = ok(up.status());
            out.put("assistant_id", textOrNull(data, "id"));
            out.put("stable_version", textOrNull(data, "stable_version"));
            out.put("latest_version", textOrNull(data, "latest_version"));
            out.put("next_steps", List.of(
                    "Use it in a bot: assistant_config {\"id\": assistant_id, \"version\": \"stable\", \"input\": {...}, \"output\": {...}}.",
                    "Read it back with exotel_voicebot_assistant_get_config(assistantId)."));
            putWarnings(out, warnings);
            putData(out, data);
            return out;
        });
    }

    // ============================== CATALOGUE TOOLS ==============================

    @Tool(name = "exotel_voicebot_catalog_llm_models",
          description = "List LLM models usable as an assistant or agent llm_config.id. Calls "
              + "GET {v1}/accounts/{account}/llm/providers?limit=100 (account and default providers; the API pages at 10 "
              + "otherwise), then GET {v1}/accounts/{account}/llm/providers/{providerId}/models per provider. Returns compact "
              + "rows {id, name, provider, provider_id, model}; use as llm_config {\"id\": id}.")
    public String catalogLlmModels(
            @ToolParam(required = false, description = "Only list this provider's models (provider UUID).") String providerId,
            @ToolParam(required = false, description = "Include the platform default providers (default true).")
            Boolean includeDefault) {
        return run("exotel_voicebot_catalog_llm_models", () -> providerModelCatalog("llm", providerId, includeDefault,
                "assistant or agent llm_config {\"id\": id}"));
    }

    @Tool(name = "exotel_voicebot_catalog_asr_models",
          description = "List ASR (speech-to-text) models for text-input bots. Calls "
              + "GET {v1}/accounts/{account}/asr/providers?limit=100, then GET {v1}/accounts/{account}/asr/providers/{providerId}/models "
              + "per provider. Returns compact rows {id, name, provider, provider_id, model}; use as bot asr_config "
              + "{\"provider\":{\"id\": provider_id}, \"model\":{\"id\": id}}. Audio-input bots need no ASR: the LLM transcribes.")
    public String catalogAsrModels(
            @ToolParam(required = false, description = "Only list this provider's models (provider UUID).") String providerId,
            @ToolParam(required = false, description = "Include the platform default providers (default true).")
            Boolean includeDefault) {
        return run("exotel_voicebot_catalog_asr_models", () -> providerModelCatalog("asr", providerId, includeDefault,
                "bot asr_config {\"provider\":{\"id\": provider_id}, \"model\":{\"id\": id}}"));
    }

    @Tool(name = "exotel_voicebot_catalog_vad_configs",
          description = "List VAD presets (account and default): GET {v1}/accounts/{account}/vad-configs?limit=100&active=true. "
              + "Returns compact rows {id, name, provider, provider_id, type, is_default}; use as bot vad_config "
              + "{\"provider_id\": provider_id, \"config_id\": id}. Send the preset's own provider_id or Java rejects it.")
    public String catalogVadConfigs(
            @ToolParam(required = false, description = "Only presets of this VAD provider (provider UUID).") String providerId,
            @ToolParam(required = false, description = "Only presets of this type.") String type) {
        return run("exotel_voicebot_catalog_vad_configs", () -> {
            String provider = trimToNull(providerId);
            if (provider != null) requireId(provider, "providerId");
            String presetType = trimToNull(type);
            if (presetType != null && !presetType.matches("^[A-Za-z0-9_\\-]{1,64}$")) {
                throw invalidParam("type may contain only letters, digits, _ and - (max 64).");
            }
            Conn conn = connection();
            Map<String, String> query = new LinkedHashMap<>();
            query.put("offset", "0");
            query.put("limit", "100");
            query.put("active", "true");
            query.put("provider_id", provider);
            query.put("type", presetType);
            Upstream up = send(HttpMethod.GET,
                    buildUri(conn.v1(), query, "accounts", conn.account(), "vad-configs"), conn, null);
            if (up.status() >= 400) return upstreamError(up.status(), up.body());

            ArrayNode rows = MAPPER.createArrayNode();
            for (JsonNode item : listItems(up.json())) {
                ObjectNode row = rows.addObject();
                row.put("id", textOrNull(item, "id"));
                row.put("name", firstNonNull(textOrNull(item, "name"), textOrNull(item, "display_name")));
                row.put("provider", textOrNull(item, "provider_name"));
                row.put("provider_id", textOrNull(item, "provider_id"));
                row.put("type", textOrNull(item, "type"));
                if (item.has("is_default") && !item.get("is_default").isNull()) row.put("is_default", item.get("is_default").asBoolean());
            }
            Map<String, Object> out = ok(up.status());
            out.put("account_id", conn.account());
            out.put("count", rows.size());
            out.put("use_as", "bot vad_config {\"provider_id\": provider_id, \"config_id\": id}");
            putWarnings(out, truncationWarning(up.json(), "presets"));
            out.put("rows", rows);
            return out;
        });
    }

    // ============================== CONVERSATIONS TOOL ==============================

    @Tool(name = "exotel_voicebot_conversations_list",
          description = "List conversations: GET {v2}/accounts/{account}/voicebot-conversations. Filters: botId, startDate and "
              + "endDate (YYYY-MM-DD as a UTC day, or an ISO-8601 instant; at most 90 days back), outcome. offset default 0; "
              + "limit default 20, max 100; page with next_offset. fields: omit for metadata only, or transcript, insights, "
              + "or transcript,insights. Each row with transcript is about 16 KB, so keep limit small when asking for "
              + "transcripts. Rows leave out caller numbers, recording URLs and custom parameters.")
    public String conversationsList(
            @ToolParam(required = false, description = "Only this bot's conversations (bot UUID).") String botId,
            @ToolParam(required = false, description = "From this time: YYYY-MM-DD (start of that UTC day) or an ISO-8601 "
                + "instant such as 2026-10-21T09:00:00Z. At most 90 days back.") String startDate,
            @ToolParam(required = false, description = "Up to this time: YYYY-MM-DD (end of that UTC day) or an ISO-8601 instant.")
            String endDate,
            @ToolParam(required = false, description = "Only conversations with this outcome.") String outcome,
            @ToolParam(required = false, description = "Offset, default 0.") Integer offset,
            @ToolParam(required = false, description = "Page size, default 20, max 100.") Integer limit,
            @ToolParam(required = false, description = "Extra fields: transcript, insights or transcript,insights. Omit for "
                + "metadata only.") String fields) {
        return run("exotel_voicebot_conversations_list", () -> {
            String bot = trimToNull(botId);
            if (bot != null) requireId(bot, "botId");
            int off = offset == null ? 0 : offset;
            int lim = limit == null ? CONVERSATIONS_DEFAULT_LIMIT : limit;
            if (off < 0) throw invalidParam("offset cannot be negative.");
            if (lim < 1 || lim > CONVERSATIONS_MAX_LIMIT) throw invalidParam("limit must be 1-" + CONVERSATIONS_MAX_LIMIT + ".");
            String start = normalizeDate(startDate, false);
            String end = normalizeDate(endDate, true);
            if (start != null && end != null && start.compareTo(end) > 0) throw invalidParam("startDate is after endDate.");
            String outcomeFilter = trimToNull(outcome);
            if (outcomeFilter != null && (outcomeFilter.length() > 200 || outcomeFilter.chars().anyMatch(Character::isISOControl))) {
                throw invalidParam("outcome must be at most 200 printable characters.");
            }
            String fieldList = normalizeFields(fields);

            Conn conn = connection();
            Map<String, String> query = new LinkedHashMap<>();
            query.put("offset", String.valueOf(off));
            query.put("limit", String.valueOf(lim));
            query.put("botId", bot);
            query.put("startDate", start);
            query.put("endDate", end);
            query.put("outcome", outcomeFilter);
            query.put("fields", fieldList);
            Upstream up = send(HttpMethod.GET,
                    buildUri(conn.v2(), query, "accounts", conn.account(), "voicebot-conversations"), conn, null);
            if (up.status() >= 400) return upstreamError(up.status(), up.body());

            Set<String> wanted = fieldList == null ? Set.of() : new HashSet<>(Arrays.asList(fieldList.split(",")));
            ArrayNode rows = MAPPER.createArrayNode();
            JsonNode response = up.json() == null ? null : up.json().get("response");
            if (response != null && response.isArray()) {
                for (JsonNode item : response) rows.add(conversationRow(item, wanted));
            }
            int redacted = redactSecrets(rows);
            JsonNode meta = up.json() == null ? null : up.json().get("metadata");
            int total = meta != null && meta.path("total").canConvertToInt() ? meta.path("total").asInt() : -1;

            Map<String, Object> out = ok(up.status());
            out.put("account_id", conn.account());
            out.put("total", total >= 0 ? total : null);
            out.put("count", rows.size());
            out.put("offset", off);
            out.put("limit", lim);
            out.put("next_offset", nextOffset(off, lim, rows.size(), total));
            if (wanted.contains("transcript")) {
                putWarnings(out, List.of("Each row with transcript is about 16 KB; page with a small limit."));
            }
            if (redacted > 0) out.put("redacted_secret_fields", redacted);
            out.put("rows", rows);
            return out;
        });
    }

    // ============================== CALL PLUMBING ==============================

    private String run(String tool, Supplier<Map<String, Object>> action) {
        Map<String, Object> result;
        try {
            result = action.get();
        } catch (ToolError e) {
            result = e.payload;
        } catch (IllegalArgumentException e) {
            result = error("INVALID_PARAMETER", e.getMessage(), "Fix the parameter and call the tool again.", null, null);
        } catch (Exception e) {
            logger.error("{} failed unexpectedly: {}", tool, e.toString());
            result = error("INTERNAL_ERROR", tool + " failed: " + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + truncate(e.getMessage(), 300)),
                    "Retry once. If it fails again, report the tool name and the time.", null, null);
        }
        return toJson(result);
    }

    /** Credentials, account and base URLs from the caller's envelope only. */
    private Conn connection() {
        AuthCredentials creds = AuthContext.current();
        if (!creds.hasVoicebotCredentials()) {
            throw new ToolError(error("MISSING_CREDENTIALS",
                    "The Authorization envelope has no voicebot_api_key, voicebot_api_token and voicebot_account_id. "
                  + "These tools use only the caller's own credentials, never server defaults.",
                    "Add voicebot_api_key, voicebot_api_token and voicebot_account_id to the envelope, plus voicebot_base_url "
                  + "for a non-prod stamp (e.g. https://<stamp>.exotel.com/voicebot/api/v2).", null, null));
        }
        String account = creds.getVoicebotAccountId().trim();
        if (!ACCOUNT_ID_PATTERN.matcher(account).matches()) {
            throw new ToolError(error("INVALID_ACCOUNT_ID",
                    "voicebot_account_id may contain only letters, digits, _ and - (max 64).",
                    "Fix voicebot_account_id in the envelope; it is the case-sensitive account SID.", null, null));
        }
        String base = creds.effectiveVoicebotBaseUrl(defaultBaseUrl);
        String problem = validateBaseUrl(base, allowedHostSuffixes);
        if (problem != null) {
            throw new ToolError(error("INVALID_BASE_URL", problem + ".",
                    "Set voicebot_base_url to an https Exotel voicebot URL ending in /api/v2, "
                  + "e.g. https://voicebot.in.exotel.com/voicebot/api/v2.", null, null));
        }
        return new Conn(apiBase(base, 1), apiBase(base, 2), account, "Basic " + creds.voicebotBasicToken());
    }

    private Upstream send(HttpMethod method, URI target, Conn conn, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.AUTHORIZATION, conn.authHeader());
        HttpEntity<String> entity;
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
            entity = new HttpEntity<>(toJson(body), headers);
        } else {
            entity = new HttpEntity<>(headers);
        }
        long started = System.currentTimeMillis();
        int status;
        String text;
        try {
            ResponseEntity<String> response = restTemplate.exchange(target, method, entity, String.class);
            status = response.getStatusCode().value();
            text = response.getBody();
        } catch (RestClientResponseException e) {
            status = e.getStatusCode().value();
            text = e.getResponseBodyAsString();
        } catch (ResourceAccessException e) {
            logger.warn("voicebot-builder {} {} unreachable: {}", method, target.getPath(), e.getMostSpecificCause().toString());
            throw new ToolError(error("UPSTREAM_UNREACHABLE",
                    "Could not reach the voicebot API at " + target.getHost() + ": " + e.getMostSpecificCause().getMessage(),
                    "Check voicebot_base_url and network access, then retry.", null, null));
        }
        logger.info("voicebot-builder {} {} -> {} ({} ms)", method, target.getPath(), status,
                System.currentTimeMillis() - started);
        if (text != null && text.length() > MAX_RESPONSE_CHARS) {
            throw new ToolError(error("RESPONSE_TOO_LARGE",
                    "The voicebot API answered with more than " + MAX_RESPONSE_CHARS + " characters.",
                    "Narrow the request: a smaller limit or fewer fields.", status, null));
        }
        JsonNode json = tryParse(text);
        // Java mirrors the status in the body; trust the body when it reports a failure under a 2xx.
        if (status < 400 && json != null && json.path("http_code").canConvertToInt()
                && json.path("http_code").asInt() >= 400) {
            status = json.path("http_code").asInt();
        }
        return new Upstream(status, text, json);
    }

    private static URI versionsUri(Conn conn, String botId, int offset, int limit) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("offset", String.valueOf(offset));
        query.put("limit", String.valueOf(limit));
        return buildUri(conn.v2(), query, "accounts", conn.account(), "voicebots", botId, "versions");
    }

    /** Best effort: adds the bot's current stable version and split to a confirm preview. Never throws. */
    private void attachCurrentState(Map<String, Object> preview, String botId, String targetVersion) {
        try {
            Conn conn = connection();
            Upstream up = send(HttpMethod.GET, versionsUri(conn, botId, 0, VERSIONS_MAX_LIMIT), conn, null);
            if (up.status() >= 400) {
                preview.put("current_state_error", upstreamError(up.status(), up.body()).get("human_readable_reason"));
                return;
            }
            JsonNode data = unwrap(up.json());
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("stable_version", textOrNull(data, "stable_version"));
            current.put("traffic_allocation", data == null ? null : data.get("traffic_allocation"));
            if (targetVersion != null) {
                JsonNode match = null;
                if (data != null) {
                    for (JsonNode item : data.path("versions")) {
                        if (targetVersion.equals(textOrNull(item, "version"))) {
                            match = item;
                            break;
                        }
                    }
                }
                if (match == null) {
                    current.put("target_version", targetVersion + " is not among the bot's first " + VERSIONS_MAX_LIMIT + " versions");
                } else {
                    Map<String, Object> target = new LinkedHashMap<>();
                    for (String key : List.of("version", "status", "is_stable", "traffic_percentage", "assistant_version",
                            "version_description", "config_diff_fields")) {
                        if (match.has(key)) target.put(key, match.get(key));
                    }
                    current.put("target_version", target);
                }
            }
            preview.put("current", current);
        } catch (ToolError e) {
            preview.put("current_state_error", e.payload.get("human_readable_reason"));
        } catch (Exception e) {
            preview.put("current_state_error", e.getClass().getSimpleName());
        }
    }

    /** Providers (limit=100, account + default) and then each provider's model mappings, as compact rows. */
    private Map<String, Object> providerModelCatalog(String kind, String providerId, Boolean includeDefault, String useAs) {
        String onlyProvider = trimToNull(providerId);
        if (onlyProvider != null) requireId(onlyProvider, "providerId");
        Conn conn = connection();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("offset", "0");
        query.put("limit", "100");
        query.put("active", "true");
        query.put("includeDefault", Boolean.FALSE.equals(includeDefault) ? "false" : "true");
        Upstream up = send(HttpMethod.GET,
                buildUri(conn.v1(), query, "accounts", conn.account(), kind, "providers"), conn, null);
        if (up.status() >= 400) return upstreamError(up.status(), up.body());

        List<String> warnings = new ArrayList<>(truncationWarning(up.json(), "providers"));
        ArrayNode rows = MAPPER.createArrayNode();
        int providersRead = 0;
        boolean providerFound = false;
        for (JsonNode provider : listItems(up.json())) {
            String pid = textOrNull(provider, "id");
            if (pid == null) continue;
            if (onlyProvider != null && !onlyProvider.equalsIgnoreCase(pid)) continue;
            providerFound = true;
            if (++providersRead > MAX_CATALOG_PROVIDERS) {
                warnings.add("Stopped after " + MAX_CATALOG_PROVIDERS + " providers; pass providerId to list the others.");
                break;
            }
            String providerName = firstNonNull(textOrNull(provider, "name"), textOrNull(provider, "display_name"));
            Upstream models = send(HttpMethod.GET,
                    buildUri(conn.v1(), null, "accounts", conn.account(), kind, "providers", pid, "models"), conn, null);
            if (models.status() >= 400) {
                warnings.add("Models of provider " + providerName + " (" + pid + ") could not be read: HTTP "
                        + models.status() + ".");
                continue;
            }
            for (JsonNode model : listItems(models.json())) {
                ObjectNode row = rows.addObject();
                row.put("id", textOrNull(model, "id"));
                row.put("name", firstNonNull(textOrNull(model, "name"), textOrNull(model, "display_name")));
                row.put("provider", providerName);
                row.put("provider_id", pid);
                row.put("model", extractModelName(model, provider.get("config_details")));
            }
        }
        if (onlyProvider != null && !providerFound) {
            warnings.add("providerId " + onlyProvider + " is not among the account's active "
                    + kind.toUpperCase(Locale.ROOT) + " providers.");
        }
        Map<String, Object> out = ok(200);
        out.put("account_id", conn.account());
        out.put("count", rows.size());
        out.put("use_as", useAs);
        putWarnings(out, warnings);
        out.put("rows", rows);
        return out;
    }

    // ============================== VALIDATION (pure, unit-tested) ==============================

    /** v1 create: top-level allowlist, then the fields Java needs to avoid a 500 or a silent default. Null when valid. */
    static Map<String, Object> validateBotCreateConfig(JsonNode cfg) {
        if (cfg == null || !cfg.isObject()) {
            return error("INVALID_CONFIG", "configJson must be a JSON object holding the v1 bot config.",
                    "Pass an object with keys such as name, supported_languages, assistant_config and voice_config.", null, null);
        }
        List<String> unknown = new ArrayList<>();
        List<String> serverManaged = new ArrayList<>();
        cfg.fieldNames().forEachRemaining(key -> {
            if (BOT_CREATE_SERVER_MANAGED_KEYS.contains(key)) serverManaged.add(key);
            else if (!BOT_CREATE_ALLOWED_KEYS.contains(key)) unknown.add(key);
        });
        if (!unknown.isEmpty() || !serverManaged.isEmpty()) {
            StringBuilder reason = new StringBuilder("configJson has top-level keys the v1 create does not take");
            if (!unknown.isEmpty()) reason.append("; unknown (Java would drop them silently): ").append(unknown);
            if (!serverManaged.isEmpty()) reason.append("; set by the API, not the caller: ").append(serverManaged);
            Map<String, Object> e = error("UNKNOWN_KEYS", reason.append('.').toString(),
                    "Remove those keys and call again. allowed_keys lists every top-level key the v1 create accepts.",
                    null, null);
            e.put("unknown_keys", unknown);
            e.put("server_managed_keys", serverManaged);
            e.put("allowed_keys", BOT_CREATE_ALLOWED_KEYS);
            return e;
        }
        List<String> missing = new ArrayList<>();
        if (!hasText(cfg, "name")) missing.add("name");
        JsonNode languages = cfg.get("supported_languages");
        if (languages == null || !languages.isArray() || languages.isEmpty()) missing.add("supported_languages");
        JsonNode assistant = cfg.get("assistant_config");
        if (assistant == null || !assistant.isObject()) {
            missing.add("assistant_config");
        } else {
            if (!hasText(assistant, "id")) missing.add("assistant_config.id");
            if (!hasText(assistant.get("input"), "modality")) missing.add("assistant_config.input.modality");
        }
        if (!missing.isEmpty()) {
            Map<String, Object> e = error("MISSING_REQUIRED_FIELDS",
                    "configJson is missing " + missing + ". Without assistant_config.input the v1 create fails with a 500.",
                    "Add them, e.g. \"supported_languages\":[\"en\"], \"assistant_config\":{\"id\":\"<assistant uuid>\","
                  + "\"version\":\"stable\",\"input\":{\"modality\":\"text\",\"mime_type\":\"text/plain\"},"
                  + "\"output\":{\"modality\":\"text\",\"mime_type\":\"text/plain\"}}.", null, null);
            e.put("missing_fields", missing);
            return e;
        }
        JsonNode voice = cfg.get("voice_config");
        if (voice != null && voice.isObject()
                && (voice.has("model_id") || voice.has("voice_id") || voice.has("provider_id"))) {
            return error("V1_SHAPE_REQUIRED",
                    "voice_config uses v2 keys (provider_id/model_id/voice_id). The v1 create ignores them and silently "
                  + "applies the platform default TTS provider, model and voice.",
                    "Send voice_config as {\"provider\":{\"id\":\"...\"},\"model\":{\"id\":\"...\"},\"voice\":{\"id\":\"...\"}}.",
                    null, null);
        }
        JsonNode asr = cfg.get("asr_config");
        if (asr != null && asr.isObject() && (asr.has("model_id") || asr.has("provider_id"))) {
            return error("V1_SHAPE_REQUIRED", "asr_config uses v2 keys (provider_id/model_id), which the v1 create ignores.",
                    "Send asr_config as {\"provider\":{\"id\":\"...\"},\"model\":{\"id\":\"...\"}}.", null, null);
        }
        String modality = assistant.path("input").path("modality").asText("");
        if ("text".equalsIgnoreCase(modality)
                && (asr == null || !hasText(asr.get("provider"), "id") || !hasText(asr.get("model"), "id"))) {
            return error("ASR_CONFIG_REQUIRED",
                    "assistant_config.input.modality is text (ASR cascade), so asr_config {provider:{id}, model:{id}} is "
                  + "required; without asr_config the v1 create fails with a 500.",
                    "Pick a model with exotel_voicebot_catalog_asr_models and send \"asr_config\":{\"provider\":{\"id\":"
                  + "\"<provider_id>\"},\"model\":{\"id\":\"<id>\"}}, or use audio input when the LLM transcribes.", null, null);
        }
        return null;
    }

    static List<String> botCreateWarnings(JsonNode cfg) {
        List<String> warnings = new ArrayList<>();
        if (!cfg.hasNonNull("voice_config")) {
            warnings.add("DEFAULT_TTS: voice_config is omitted, so Java fills the platform default TTS provider, model and voice.");
        }
        String modality = cfg.path("assistant_config").path("input").path("modality").asText("");
        if (!"text".equalsIgnoreCase(modality) && cfg.hasNonNull("asr_config")) {
            warnings.add("ASR_IGNORED: assistant_config.input.modality is " + modality
                    + ", so the LLM transcribes and Java replaces asr_config with an empty object.");
        }
        return warnings;
    }

    /** Version POST data: mark_as_stable refusal, then the patchable-field allowlist. Null when valid. */
    static Map<String, Object> validateVersionData(JsonNode data, Boolean markAsStable) {
        if (Boolean.TRUE.equals(markAsStable)) return markAsStableRefused("markAsStable=true");
        if (data == null || !data.isObject()) {
            return error("INVALID_DATA", "dataJson must be a JSON object of bot fields to change.",
                    "Pass only the data object, e.g. {\"user_speech_timeout_seconds\": 1.2}.", null, null);
        }
        for (String key : List.of("mark_as_stable", "is_stable")) {
            if (data.has(key) && data.get(key).asBoolean(false)) return markAsStableRefused("data." + key + "=true");
        }
        List<String> unknown = new ArrayList<>();
        data.fieldNames().forEachRemaining(key -> {
            if (!VERSION_DATA_ALLOWED_KEYS.contains(key)) unknown.add(key);
        });
        if (!unknown.isEmpty()) {
            boolean wrapped = data.has("data") || data.has("source_version");
            Map<String, Object> e = error("UNKNOWN_DATA_KEYS",
                    "dataJson has keys that are not patchable bot fields (Java would drop them silently): " + unknown + ".",
                    wrapped
                            ? "Pass only the inner data object; sourceVersion, versionDescription and markAsStable are separate parameters."
                            : "Remove or rename those keys. allowed_keys lists every patchable top-level field.",
                    null, null);
            e.put("unknown_keys", unknown);
            e.put("allowed_keys", VERSION_DATA_ALLOWED_KEYS);
            return e;
        }
        return null;
    }

    static Map<String, Object> markAsStableRefused(String where) {
        return error("MARK_AS_STABLE_REFUSED",
                "This tool always creates versions with mark_as_stable=false; " + where + " was refused. "
              + "A new version gets 0% of traffic until it is promoted.",
                "Create the version without it, check it with exotel_voicebot_bot_get_full(botId, version), then call "
              + "exotel_voicebot_bot_version_promote(botId, version, confirm=true) for stable at 100% traffic, or "
              + "exotel_voicebot_bot_traffic_set for a split.", null, null);
    }

    static boolean isValidSourceVersion(String version) {
        return "latest".equals(version) || "stable".equals(version)
                || (version != null && VERSION_PATTERN.matcher(version).matches());
    }

    static List<String> versionDataWarnings(JsonNode data, String sourceVersion) {
        List<String> warnings = new ArrayList<>();
        if (data.isEmpty()) {
            warnings.add("EMPTY_DATA: dataJson is empty, so the new version is an unchanged copy of " + sourceVersion + ".");
        }
        JsonNode assistant = data.get("assistant_config");
        if (assistant != null && assistant.isObject()) {
            List<String> forkFields = new ArrayList<>();
            for (String field : ASSISTANT_FORK_FIELDS) {
                if (assistant.hasNonNull(field)) forkFields.add(field);
            }
            if (!forkFields.isEmpty()) {
                warnings.add("ASSISTANT_FORK: data.assistant_config contains " + forkFields + ", so Java creates a NEW "
                        + "assistant version (not stable) and pins this bot version to it. The assistant's stable version "
                        + "changes only when this bot version is promoted.");
            }
            if (assistant.hasNonNull("agents")) {
                warnings.add("AGENTS_FULL_LIST: assistant_config.agents is taken as the complete agent list; an existing "
                        + "agent left out is dropped from the new assistant version. Include every agent to keep, with its id.");
            }
        }
        if (data.has("nudge_config")) {
            warnings.add("NUDGE_REPLACED: nudge_config replaces the whole stored object; include every nudge field to keep.");
        }
        List<String> silentNull = new ArrayList<>();
        for (String field : List.of("filler_config", "pii_masking_config", "variants")) {
            if (data.hasNonNull(field)) silentNull.add(field);
        }
        if (!silentNull.isEmpty()) {
            warnings.add("SILENT_NULL: if Java cannot parse " + silentNull + " it stores null without an error; read "
                    + "the version back with exotel_voicebot_bot_get_full(botId, version).");
        }
        JsonNode voice = data.get("voice_config");
        if (voice != null && voice.isObject() && !voice.has("provider") && (voice.has("model_id") || voice.has("voice_id"))) {
            warnings.add("TTS_VENDOR_KEPT: voice_config uses the v2 shape (model_id/voice_id), which keeps the current TTS "
                    + "provider; to switch vendor send {\"provider\":{\"id\":...},\"model\":{\"id\":...},\"voice\":{\"id\":...}}.");
        }
        if (sourceVersion != null && VERSION_PATTERN.matcher(sourceVersion).matches()) {
            warnings.add("OLD_SOURCE: fields not in data are copied from " + sourceVersion
                    + "; changes made in later versions are not carried over.");
        } else if ("stable".equals(sourceVersion)) {
            warnings.add("STABLE_SOURCE: fields not in data come from the stable version, so changes in newer, "
                    + "unpromoted versions are not carried over.");
        }
        return warnings;
    }

    /** Fills {@code out} with version to percent. Null when valid, else a structured error. */
    static Map<String, Object> parseTrafficAllocation(JsonNode node, Map<String, Integer> out) {
        JsonNode allocation = node;
        if (allocation != null && allocation.isObject() && allocation.size() == 1 && allocation.has("allocation")) {
            allocation = allocation.get("allocation");
        }
        if (allocation == null || !allocation.isObject() || allocation.isEmpty()) {
            return error("INVALID_ALLOCATION",
                    "allocationJson must be a non-empty JSON object of version to percent, e.g. {\"v1\": 90, \"v2\": 10}.",
                    "Pass whole-number percentages per version that sum to 100.", null, null);
        }
        List<String> problems = new ArrayList<>();
        int sum = 0;
        for (Map.Entry<String, JsonNode> entry : allocation.properties()) {
            String version = entry.getKey();
            JsonNode value = entry.getValue();
            if (!VERSION_PATTERN.matcher(version).matches()) {
                problems.add(version + ": version keys look like v1, v2");
            } else if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
                problems.add(version + ": percentage must be a whole number");
            } else if (value.intValue() < 0 || value.intValue() > 100) {
                problems.add(version + ": percentage must be 0-100");
            } else {
                sum += value.intValue();
                out.put(version, value.intValue());
            }
        }
        if (!problems.isEmpty()) {
            Map<String, Object> e = error("INVALID_ALLOCATION", "allocationJson is invalid: " + problems + ".",
                    "Use keys such as v1, v2 with whole-number percentages 0-100 that sum to 100.", null, null);
            e.put("problems", problems);
            return e;
        }
        if (sum != 100) {
            Map<String, Object> e = error("TRAFFIC_SUM_NOT_100",
                    "Traffic percentages must sum to 100; these sum to " + sum + ".",
                    "Adjust the percentages to add up to exactly 100, e.g. {\"v1\": 90, \"v2\": 10}.", null, null);
            e.put("sum", sum);
            return e;
        }
        return null;
    }

    /** Null when confirm is true; otherwise CONFIRM_REQUIRED carrying the preview. */
    static Map<String, Object> confirmGate(Boolean confirm, String action, Map<String, Object> preview) {
        if (Boolean.TRUE.equals(confirm)) return null;
        Map<String, Object> e = error("CONFIRM_REQUIRED",
                "Refusing to " + action + " without confirm=true. Nothing was changed.",
                "Show the preview to the user; after they approve, call the tool again with the same arguments and confirm=true.",
                null, null);
        e.put("preview", preview);
        return e;
    }

    static Map<String, Object> promotePreview(String botId, String version) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("request", "PUT {v2}/accounts/{account}/voicebots/" + botId + "/versions/" + version
                + " {\"is_stable\": true}");
        preview.put("effects", List.of(
                version + " becomes the bot's stable version.",
                "traffic_allocation becomes {\"" + version + "\": 100}: every new call uses " + version
                        + " and any existing split is replaced.",
                "The assistant version that " + version + " pins (assistant_version) becomes that assistant's stable "
                        + "version, which also changes other bots using the assistant at version \"stable\".",
                "The previous stable version stays active (not archived) and can be promoted back."));
        return preview;
    }

    static Map<String, Object> trafficPreview(String botId, Map<String, Integer> allocation) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("request", "PUT {v2}/accounts/{account}/voicebots/" + botId + "/versions/traffic-allocation");
        preview.put("body", Map.of("allocation", allocation));
        preview.put("effects", List.of(
                "New calls are split " + allocation + "; versions not listed get 0%.",
                "stable_version does not change.",
                "Java rejects the split if a listed version with more than 0% is archived or does not exist."));
        return preview;
    }

    /** Assistant create checks for the failures Java turns into a 500 or a half-built assistant. Null when valid. */
    static Map<String, Object> validateAssistantCreate(JsonNode assistant) {
        if (assistant == null || !assistant.isObject()) {
            return error("INVALID_ASSISTANT", "assistantJson must be a JSON object.",
                    "Pass {name, description, instruction, llm_config, agents: [...]}.", null, null);
        }
        if (assistant.has("pre_agent_hooks")) {
            return error("ASSISTANT_LEVEL_HOOKS",
                    "pre_agent_hooks is not accepted on the assistant; Java rejects it there because hooks belong to an agent.",
                    "Move the hooks to agents[].pre_agent_hooks.", null, null);
        }
        List<String> unknown = new ArrayList<>();
        assistant.fieldNames().forEachRemaining(key -> {
            if (!ASSISTANT_CREATE_ALLOWED_KEYS.contains(key)) unknown.add(key);
        });
        if (!unknown.isEmpty()) {
            Map<String, Object> e = error("UNKNOWN_KEYS",
                    "assistantJson has top-level keys the assistant create does not take (Java would drop them silently): "
                  + unknown + ".",
                    "Remove those keys. allowed_keys lists every accepted top-level key.", null, null);
            e.put("unknown_keys", unknown);
            e.put("allowed_keys", ASSISTANT_CREATE_ALLOWED_KEYS);
            return e;
        }
        List<String> missing = new ArrayList<>();
        for (String field : List.of("name", "description", "instruction")) {
            if (!hasText(assistant, field)) missing.add(field);
        }
        if (!missing.isEmpty()) {
            Map<String, Object> e = error("MISSING_REQUIRED_FIELDS", "assistantJson is missing " + missing + ".",
                    "Add a non-empty name, description and instruction.", null, null);
            e.put("missing_fields", missing);
            return e;
        }
        if (assistant.has("llm_config") && !assistant.get("llm_config").isObject()) {
            return error("INVALID_LLM_CONFIG",
                    "llm_config must be an object such as {\"id\": \"<LLM model id>\"}; null makes Java fail with a 500.",
                    "Send {\"id\": \"<id from exotel_voicebot_catalog_llm_models>\"} or leave llm_config out to use the default.",
                    null, null);
        }
        JsonNode agents = assistant.get("agents");
        if (agents == null || !agents.isArray() || agents.isEmpty() || agents.size() > MAX_AGENTS) {
            return error("INVALID_AGENTS", "agents must be an array of 1-" + MAX_AGENTS + " agent objects.",
                    "Send agents: [{\"name\":\"main\",\"description\":\"...\",\"instruction\":\"...\",\"llm_config\":{}}].",
                    null, null);
        }
        List<String> unnamed = new ArrayList<>();
        List<String> withoutLlm = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < agents.size(); i++) {
            JsonNode agent = agents.get(i);
            String label = "agents[" + i + "]";
            if (agent == null || !agent.isObject()) {
                unnamed.add(label);
                continue;
            }
            String name = textOrNull(agent, "name");
            if (name == null) {
                unnamed.add(label);
            } else {
                label = label + " (" + name + ")";
                if (!seen.add(name)) duplicates.add(name);
            }
            JsonNode llm = agent.get("llm_config");
            if (llm == null || !llm.isObject()) withoutLlm.add(label);
        }
        if (!unnamed.isEmpty()) {
            return error("AGENT_NAME_MISSING", "Every agent must be an object with a name; missing on " + unnamed + ".",
                    "Give each agent a unique name.", null, null);
        }
        if (!withoutLlm.isEmpty()) {
            Map<String, Object> e = error("AGENT_LLM_CONFIG_MISSING",
                    "Every agent needs an llm_config object; without it Java fails with a 500 and can leave a half-built "
                  + "assistant. Missing on " + withoutLlm + ".",
                    "Add \"llm_config\": {} to inherit the assistant LLM, or {\"id\": \"<LLM model id>\"} to pick one.",
                    null, null);
            e.put("agents_missing_llm_config", withoutLlm);
            return e;
        }
        if (!duplicates.isEmpty()) {
            return error("DUPLICATE_AGENT_NAMES", "Agent names must be unique; repeated: " + duplicates + ".",
                    "Rename the repeated agents.", null, null);
        }
        JsonNode servers = assistant.get("mcp_servers");
        if (servers != null && !servers.isNull()) {
            if (!servers.isArray()) {
                return error("INVALID_MCP_SERVERS", "mcp_servers must be an array of {name, url, headers}.",
                        "Send mcp_servers as an array.", null, null);
            }
            Set<String> names = new HashSet<>();
            List<String> repeated = new ArrayList<>();
            for (JsonNode server : servers) {
                String name = textOrNull(server, "name");
                if (name != null && !names.add(name)) repeated.add(name);
            }
            if (!repeated.isEmpty()) {
                return error("DUPLICATE_MCP_SERVER_NAMES",
                        "MCP server names must be unique; repeated: " + repeated + ". Java would commit a half-built assistant.",
                        "Rename or merge the repeated MCP servers.", null, null);
            }
        }
        return null;
    }

    // ============================== TAGS AND SECRETS ==============================

    /** One warning listing every string @SanitizeInput would strip markup from (exempt keys skipped, as in Java). */
    static List<String> tagWarnings(JsonNode root) {
        List<String> findings = findTagPaths(root);
        if (findings.isEmpty()) return List.of();
        return List.of("TAG_STRIPPED: the voicebot API removes <tag>-shaped text before saving (Jsoup via @SanitizeInput), "
                + "so these values will be stored without it: " + findings
                + ". Write placeholders as {customer_name} or [customer_name] instead.");
    }

    static List<String> findTagPaths(JsonNode root) {
        List<String> out = new ArrayList<>();
        collectTags(root, "", out, 0);
        return out;
    }

    private static void collectTags(JsonNode node, String path, List<String> out, int depth) {
        if (node == null || depth > 64 || out.size() >= MAX_TAG_FINDINGS) return;
        if (node.isTextual()) {
            Matcher m = TAG_PATTERN.matcher(node.asText());
            if (m.find()) out.add((path.isEmpty() ? "(value)" : path) + ": \"" + snippet(node.asText(), m.start()) + "\"");
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String key = entry.getKey();
                String child = path.isEmpty() ? key : path + "." + key;
                if (TAG_PATTERN.matcher(key).find()) out.add(child + " (key name)");
                if (SANITIZE_EXEMPT_KEYS.contains(key)) continue;
                collectTags(entry.getValue(), child, out, depth + 1);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) collectTags(node.get(i), path + "[" + i + "]", out, depth + 1);
        }
    }

    private static String snippet(String text, int at) {
        int from = Math.max(0, at - 10);
        int to = Math.min(text.length(), at + 40);
        return (from > 0 ? "..." : "") + text.substring(from, to).replaceAll("\\s+", " ") + (to < text.length() ? "..." : "");
    }

    /**
     * Redacts credential values inside a JSON response body (used by the older VoiceBot tools too, whose
     * bodies can carry an assistant's llm_config auth_config / decrypted_auth_config). Non-JSON text is
     * returned unchanged.
     */
    static String redactJsonText(String text) {
        if (text == null || text.isBlank()) return text;
        try {
            JsonNode node = MAPPER.readTree(text);
            if (node == null || !(node.isObject() || node.isArray())) return text;
            return redactSecrets(node) > 0 ? MAPPER.writeValueAsString(node) : text;
        } catch (Exception e) {
            return text;
        }
    }

    /** Replaces credential values in place (keys such as api_key, auth_config, token; Basic/Bearer strings). */
    static int redactSecrets(JsonNode node) {
        int[] count = {0};
        redact(node, count, 0);
        return count[0];
    }

    private static void redact(JsonNode node, int[] count, int depth) {
        if (node == null || depth > 64) return;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> keys = new ArrayList<>();
            object.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode value = object.get(key);
                if (value == null || value.isNull() || isEmptyValue(value)) continue;
                if (SECRET_KEY_PATTERN.matcher(key).matches()
                        || (value.isTextual() && AUTH_VALUE_PATTERN.matcher(value.asText()).matches())) {
                    object.put(key, REDACTED);
                    count[0]++;
                } else {
                    redact(value, count, depth + 1);
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value != null && value.isTextual() && AUTH_VALUE_PATTERN.matcher(value.asText()).matches()) {
                    array.set(i, TextNode.valueOf(REDACTED));
                    count[0]++;
                } else {
                    redact(value, count, depth + 1);
                }
            }
        }
    }

    private static boolean isEmptyValue(JsonNode value) {
        return (value.isContainerNode() && value.isEmpty()) || (value.isTextual() && value.asText().isBlank());
    }

    // ============================== UPSTREAM ERRORS ==============================

    static Map<String, Object> upstreamError(int status, String rawBody) {
        JsonNode parsed = tryParse(rawBody);
        String[] upstream = findUpstreamError(parsed);
        String detail = upstream[1] == null ? "." : ": " + truncate(upstream[1], 500);
        String code;
        String reason;
        String action;
        switch (status) {
            case 400 -> {
                code = "UPSTREAM_VALIDATION_ERROR";
                reason = "The voicebot API rejected the request" + detail;
                action = "Fix the field named in the message and call again.";
            }
            case 401 -> {
                code = "UNAUTHORIZED";
                reason = "The voicebot API did not accept the credentials" + detail;
                action = "Check voicebot_api_key and voicebot_api_token, and that voicebot_account_id is the exact "
                        + "(case-sensitive) account SID for that key.";
            }
            case 403 -> {
                code = "FORBIDDEN";
                reason = "The credentials are valid but may not use this endpoint" + detail;
                action = "An Exotel API key (Twilix) has no voicebot role on the bot, version, traffic and provider-catalogue "
                        + "routes (Casbin). Ask Exotel to grant the key a voicebot role, or use credentials that have one.";
            }
            case 404 -> {
                code = "NOT_FOUND";
                reason = "The voicebot API found nothing there" + detail;
                action = "Check the id and that it belongs to voicebot_account_id.";
            }
            case 409 -> {
                code = "CONFLICT";
                reason = "The voicebot API reported a conflict" + detail;
                action = "Read the current state, then retry with a different name or version.";
            }
            case 422 -> {
                code = "UNPROCESSABLE";
                reason = "The voicebot API refused the operation" + detail;
                action = "The bot may be inactive or archived; restore it or use another bot.";
            }
            case 423 -> {
                code = "BOT_FROZEN";
                reason = "The bot is frozen" + detail;
                action = "Unfreeze the bot in the console before changing it.";
            }
            case 429 -> {
                code = "RATE_LIMITED_OR_CAP_REACHED";
                reason = "Too many requests, or the account's bot or assistant cap is reached" + detail;
                action = "If the message names a cap, remove unused bots or assistants or ask for a higher cap; "
                        + "otherwise wait a minute and retry.";
            }
            default -> {
                if (status >= 500) {
                    code = "UPSTREAM_ERROR";
                    reason = "The voicebot API failed with HTTP " + status + detail;
                    action = "Retry once. Java answers 500 to some malformed payloads (for example an agent without "
                            + "llm_config); check the payload, and after a failed assistant create look for a half-built "
                            + "assistant before retrying.";
                } else {
                    code = "UPSTREAM_HTTP_" + status;
                    reason = "The voicebot API answered HTTP " + status + detail;
                    action = "Read body for the details.";
                }
            }
        }
        Map<String, Object> e = error(code, reason, action, status, safeErrorBody(rawBody, parsed));
        if (upstream[0] != null) e.put("upstream_error_code", upstream[0]);
        return e;
    }

    /** {code, message} from the first object in the error body that carries a message or description. */
    static String[] findUpstreamError(JsonNode root) {
        if (root == null) return new String[]{null, null};
        Deque<JsonNode> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 200) {
            JsonNode node = queue.poll();
            if (node.isObject()) {
                String message = firstNonNull(textOrNull(node, "message"), textOrNull(node, "description"),
                        textOrNull(node, "error_message"));
                if (message != null) {
                    return new String[]{firstNonNull(textOrNull(node, "error_code"), textOrNull(node, "code")), message};
                }
                node.elements().forEachRemaining(queue::add);
            } else if (node.isArray()) {
                node.elements().forEachRemaining(queue::add);
            }
        }
        return new String[]{null, null};
    }

    static String safeErrorBody(String raw, JsonNode parsed) {
        if (parsed != null) {
            JsonNode copy = parsed.deepCopy();
            redactSecrets(copy);
            return truncate(toJson(copy), ERROR_BODY_MAX_CHARS);
        }
        if (raw == null || raw.isBlank()) return null;
        return truncate(AUTH_IN_TEXT_PATTERN.matcher(raw).replaceAll("$1 " + REDACTED), ERROR_BODY_MAX_CHARS);
    }

    // ============================== SMALL HELPERS ==============================

    static Map<String, Object> error(String code, String reason, String action, Integer httpStatus, String body) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ok", false);
        e.put("error_code", code);
        e.put("human_readable_reason", reason);
        e.put("suggested_action", action);
        e.put("http_status", httpStatus);
        e.put("body", body == null ? null : truncate(body, ERROR_BODY_MAX_CHARS));
        return e;
    }

    static Map<String, Object> ok(int httpStatus) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("http_status", httpStatus);
        return out;
    }

    private static Map<String, Object> withWarnings(Map<String, Object> result, List<String> warnings) {
        putWarnings(result, warnings);
        return result;
    }

    private static void putWarnings(Map<String, Object> out, List<String> warnings) {
        if (warnings != null && !warnings.isEmpty()) out.put("warnings", warnings);
    }

    private static void putData(Map<String, Object> out, JsonNode data) {
        int redacted = redactSecrets(data);
        if (redacted > 0) out.put("redacted_secret_fields", redacted);
        out.put("data", data);
    }

    static ToolError invalidParam(String message) {
        return new ToolError(error("INVALID_PARAMETER", message, "Fix the parameter and call the tool again.", null, null));
    }

    static void requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ToolError(error("MISSING_PARAMETER", name + " is required.", "Pass the " + name + " UUID.", null, null));
        }
        if (!ID_PATTERN.matcher(value.trim()).matches()) {
            throw new ToolError(error("INVALID_PARAMETER", name + " must be a UUID (hex digits and dashes).",
                    "Pass the " + name + " exactly as the API returned it.", null, null));
        }
    }

    static String requireVersion(String value, String name) {
        String version = trimToNull(value);
        if (version == null) {
            throw new ToolError(error("MISSING_PARAMETER", name + " is required, e.g. v3.",
                    "Pass a version from exotel_voicebot_bot_versions_list.", null, null));
        }
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw new ToolError(error("INVALID_PARAMETER", name + " must look like v1, v2, ...",
                    "Pass a version from exotel_voicebot_bot_versions_list.", null, null));
        }
        return version;
    }

    /** Accepts a JSON object, or a JSON-encoded string (double-encoded strings are unwrapped once). */
    static JsonNode parseJsonArg(Object arg, String name) {
        if (arg == null) {
            throw new ToolError(error("MISSING_PARAMETER", name + " is required.",
                    "Pass " + name + " as a JSON object (a JSON-encoded string also works).", null, null));
        }
        if (arg instanceof JsonNode node) return node;
        if (arg instanceof CharSequence chars) {
            String text = chars.toString().trim();
            if (text.isEmpty()) {
                throw new ToolError(error("MISSING_PARAMETER", name + " is empty.", "Pass " + name + " as a JSON object.",
                        null, null));
            }
            if (text.length() > MAX_INPUT_JSON_CHARS) {
                throw new ToolError(error("INPUT_TOO_LARGE", name + " is longer than " + MAX_INPUT_JSON_CHARS + " characters.",
                        "Send a smaller payload.", null, null));
            }
            JsonNode parsed = readJson(text, name);
            if (parsed.isTextual()) {
                String inner = parsed.asText().trim();
                if (inner.startsWith("{") || inner.startsWith("[")) parsed = readJson(inner, name);
            }
            return parsed;
        }
        return MAPPER.valueToTree(arg);
    }

    private static JsonNode readJson(String text, String name) {
        try {
            JsonNode node = MAPPER.readTree(text);
            if (node == null || node.isMissingNode()) {
                throw new ToolError(error("INVALID_JSON", name + " holds no JSON value.", "Send a JSON object.", null, null));
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new ToolError(error("INVALID_JSON", name + " is not valid JSON: " + e.getOriginalMessage(),
                    "Send well-formed JSON: double-quoted keys and strings, no trailing commas.", null, null));
        }
    }

    /** Null when the base URL is acceptable: https, an allowed Exotel host, a path ending in /api/vN. */
    static String validateBaseUrl(String baseUrl, List<String> allowedHostSuffixes) {
        if (baseUrl == null || baseUrl.isBlank()) return "voicebot_base_url is empty";
        URI uri;
        try {
            uri = new URI(baseUrl.trim());
        } catch (URISyntaxException e) {
            return "voicebot_base_url is not a valid URL";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) return "voicebot_base_url must use https";
        String host = uri.getHost();
        if (host == null || host.isBlank()) return "voicebot_base_url has no host";
        String lowerHost = host.toLowerCase(Locale.ROOT);
        boolean allowed = allowedHostSuffixes.stream().anyMatch(suffix ->
                lowerHost.endsWith(suffix) || (suffix.startsWith(".") && lowerHost.equals(suffix.substring(1))));
        if (!allowed) {
            return "voicebot_base_url host " + host + " is not an allowed Exotel host (" + allowedHostSuffixes + ")";
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return "voicebot_base_url must not carry user info, a query or a fragment";
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
        if (!path.matches(".*/api/v\\d+")) {
            return "voicebot_base_url must end with /api/v2, e.g. https://voicebot.in.exotel.com/voicebot/api/v2";
        }
        return null;
    }

    /** The base URL with its trailing /api/vN swapped for /api/v{version} (as VoiceBotAdminTools derives v1). */
    static String apiBase(String baseUrl, int version) {
        return baseUrl.trim().replaceAll("/+$", "").replaceFirst("/api/v\\d+$", "/api/v" + version);
    }

    static URI buildUri(String base, Map<String, String> query, String... segments) {
        StringBuilder url = new StringBuilder(base);
        for (String segment : segments) {
            url.append('/').append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        if (query != null) {
            StringJoiner joiner = new StringJoiner("&", "?", "");
            joiner.setEmptyValue("");
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (entry.getValue() == null) continue;
                joiner.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
            url.append(joiner);
        }
        return URI.create(url.toString());
    }

    /** YYYY-MM-DD (UTC day start, or end with endOfDay) or an ISO-8601 instant, as yyyy-MM-ddTHH:mm:ss.SSSZ. */
    static String normalizeDate(String value, boolean endOfDay) {
        String text = trimToNull(value);
        if (text == null) return null;
        Instant instant;
        try {
            if (text.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate day = LocalDate.parse(text);
                instant = endOfDay
                        ? day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1)
                        : day.atStartOfDay(ZoneOffset.UTC).toInstant();
            } else {
                instant = OffsetDateTime.parse(text).toInstant();
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Date '" + text
                    + "' is not YYYY-MM-DD or an ISO-8601 instant such as 2026-10-21T09:00:00Z.");
        }
        return JAVA_API_INSTANT.format(instant);
    }

    /** Null for none; otherwise a de-duplicated comma list of transcript / insights. */
    static String normalizeFields(String fields) {
        String text = trimToNull(fields);
        if (text == null) return null;
        Set<String> out = new LinkedHashSet<>();
        for (String part : text.split(",")) {
            String field = part.trim().toLowerCase(Locale.ROOT);
            if (field.isEmpty()) continue;
            if (!CONVERSATION_FIELDS.contains(field)) {
                throw new IllegalArgumentException("fields may contain only transcript and insights (got '" + part.trim() + "').");
            }
            out.add(field);
        }
        return out.isEmpty() ? null : String.join(",", out);
    }

    static Integer nextOffset(int offset, int limit, int count, int total) {
        if (total >= 0) return offset + count < total ? offset + count : null;
        return count >= limit ? offset + count : null;
    }

    static JsonNode unwrap(JsonNode root) {
        if (root == null) return null;
        JsonNode response = root.get("response");
        if (response == null || response.isNull()) return root;
        if (response.isObject() && response.has("http_code") && response.has("data")) return response.get("data");
        return response;
    }

    static List<JsonNode> listItems(JsonNode root) {
        List<JsonNode> items = new ArrayList<>();
        JsonNode response = root == null ? null : root.get("response");
        if (response == null || !response.isArray()) return items;
        for (JsonNode item : response) {
            JsonNode data = item.has("data") ? item.get("data") : item;
            if (data != null && data.isObject()) items.add(data);
        }
        return items;
    }

    static List<String> truncationWarning(JsonNode root, String what) {
        JsonNode meta = root == null ? null : root.get("metadata");
        if (meta == null || !meta.isObject()) return List.of();
        int total = meta.path("total").asInt(-1);
        int count = meta.path("count").asInt(-1);
        if (total > 0 && count >= 0 && total > count) {
            return List.of("Only the first " + count + " of " + total + " " + what + " were read.");
        }
        return List.of();
    }

    static ObjectNode conversationRow(JsonNode item, Set<String> fields) {
        ObjectNode row = MAPPER.createObjectNode();
        JsonNode data = item != null && item.has("data") ? item.get("data") : item;
        if (data == null || !data.isObject()) {
            row.set("error", item == null ? null : item.get("error_data"));
            return row;
        }
        for (String key : CONVERSATION_ROW_KEYS) {
            JsonNode value = data.get(key);
            if (value != null && !value.isNull()) row.set(key, value);
        }
        for (String key : fields) {
            JsonNode value = data.get(key);
            if (value != null && !value.isNull()) row.set(key, value);
        }
        return row;
    }

    /**
     * The model a mapping calls, the way Java resolves it: the mapping's override model_config replaces the provider's
     * model_config as a whole (LLMUtils coalesce), then its model; override model_name only as a last resort.
     * The models GET serialises the entity, so the override is camelCase overrideConfig for LLM, override_config for ASR.
     */
    static String extractModelName(JsonNode mapping, JsonNode providerConfig) {
        JsonNode override = mapping == null ? null
                : (mapping.has("overrideConfig") ? mapping.get("overrideConfig") : mapping.get("override_config"));
        JsonNode modelConfig = override != null && override.isObject() && override.hasNonNull("model_config")
                ? override.get("model_config")
                : (providerConfig == null ? null : providerConfig.get("model_config"));
        String name = textOrNull(modelConfig, "model");
        return name != null ? name : textOrNull(override, "model_name");
    }

    static boolean hasText(JsonNode node, String field) {
        return textOrNull(node, field) != null;
    }

    static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) return null;
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String text = textOrNull(node, field);
        return text != null ? text : fallback;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) return value;
        }
        return null;
    }

    static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        String marker = "...[truncated]";
        return value.substring(0, Math.max(0, max - marker.length())) + marker;
    }

    static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"ok\":false,\"error_code\":\"SERIALIZATION_ERROR\",\"human_readable_reason\":\"Could not serialise "
                    + "the tool result.\",\"suggested_action\":\"Retry.\",\"http_status\":null,\"body\":null}";
        }
    }

    static JsonNode tryParse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static Set<String> orderedSet(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }
}
