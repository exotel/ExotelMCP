package com.example.mcp_api.service;

import com.example.mcp_api.auth.AuthContext;
import com.example.mcp_api.auth.AuthCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Assist MCP tools (read + write).
 *
 * Auth: credentials from the Authorization header envelope ({@link AuthCredentials}).
 * URLs: {ai_assist_base_url}/{ai_assist_context_path}/v1/accounts/{sid}/...
 */
@Service
public class AiAssistService {

    private static final Logger logger = LoggerFactory.getLogger(AiAssistService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // LLM suggestion languages the AI Assist UI offers in the "Assistant suggestions language" dropdown.
    // These are the DISPLAY NAMES sent on the wire (llm_language). Regenerate when the UI adds locales.
    private static final java.util.LinkedHashSet<String> LLM_LANGUAGE_NAMES =
            new java.util.LinkedHashSet<>(List.of(
                "Afrikaans", "Albanian", "Amharic", "Arabic", "Armenian", "Assamese", "Azerbaijani",
                "Basque", "Bengali", "Bosnian", "Bulgarian", "Burmese", "Catalan",
                "Chinese", "Chinese (Cantonese)", "Chinese (Wu)",
                "Croatian", "Czech", "Danish", "Dutch", "English", "Estonian", "Filipino", "Finnish",
                "French", "Galician", "Georgian", "German", "Greek", "Gujarati", "Hebrew", "Hindi",
                "Hungarian", "Icelandic", "Indonesian", "Irish", "isiZulu", "Italian", "Japanese",
                "Javanese", "Kannada", "Kazakh", "Khmer", "Kiswahili", "Korean", "Lao", "Latvian",
                "Lithuanian", "Macedonian", "Malay", "Malayalam", "Maltese", "Marathi", "Mongolian",
                "Nepali", "Norwegian Bokm\u00e5l", "Odia", "Pashto", "Persian", "Polish", "Portuguese",
                "Punjabi", "Romanian", "Russian", "Serbian", "Sinhala", "Slovak", "Slovenian",
                "Somali", "Spanish", "Swedish", "Tamil", "Telugu", "Thai", "Turkish", "Ukrainian",
                "Urdu", "Uzbek", "Vietnamese", "Welsh"
            ));

    // AI Assist UI defaults for smart-reply bad-feedback options when the user enables feedback but
    // provides no list. Matches AI Assist UI defaults when feedback is enabled without custom options.
    private static final List<String> DEFAULT_BAD_FEEDBACK_OPTIONS =
            List.of("Irrelevant", "Wrong", "Outdated");

    // Fixed infra constants \u2014 not per-user, not env-specific. Base URL and account SID
    // come from the Authorization header (see mcp.json / AuthContext.requireAiAssist()).
    private static final String CONTEXT_PATH     = "ai-assist/api";
    private static final String AUTH0_TOKEN_URL  = "https://id.accounts.in.exotel.com/oauth/token";
    private static final String AUTH0_AUDIENCE   = "exotel-api";

    private RestTemplate restTemplate;

    // Chat smoke-test (Data Pipe handshake) timeouts. Mirror the AI Assist UI Test Chat timers.
    private static final int SMOKE_HTTP_CONNECT_TIMEOUT_SEC = 10;
    private static final int SMOKE_WS_CONNECT_TIMEOUT_SEC = 15;
    private static final int SMOKE_START_ACK_TIMEOUT_SEC = 20;
    // Brief pause after 'connected' before 'start', matching the AI Assist UI's stream-setup sequencing.
    private static final long SMOKE_CONNECTED_SETTLE_MS = 50L;

    @jakarta.annotation.PostConstruct
    void init() {
        this.restTemplate = createRestTemplate(true);
    }

    // ---- Auth0 M2M token cache (per client_id|account_sid key) ----
    // ponytail: ConcurrentHashMap + coarse lock; fine for MCP's low tenant count.

    private record CachedBearer(String token, long expiresAtEpochMs) {}

    private final ConcurrentHashMap<String, CachedBearer> bearerCache = new ConcurrentHashMap<>();
    private final Object bearerLock = new Object();

    private String mintOrReuseBearer(String clientId, String clientSecret, String accountSid) {
        String key = clientId + "|" + accountSid;
        long now = System.currentTimeMillis();
        CachedBearer cached = bearerCache.get(key);
        if (cached != null && cached.expiresAtEpochMs - now > 60_000) {
            return cached.token;
        }
        synchronized (bearerLock) {
            cached = bearerCache.get(key);
            if (cached != null && cached.expiresAtEpochMs - now > 60_000) {
                return cached.token;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("client_id", clientId);
            body.put("client_secret", clientSecret);
            body.put("audience", AUTH0_AUDIENCE);
            body.put("grant_type", "client_credentials");
            body.put("account_sid", accountSid);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            logger.info("AI Assist: minting M2M token for account_sid={}", accountSid);
            ResponseEntity<String> resp = restTemplate.postForEntity(AUTH0_TOKEN_URL, entity, String.class);
            try {
                JsonNode root = objectMapper.readTree(resp.getBody());
                String token = root.path("access_token").asText(null);
                long   ttl   = root.path("expires_in").asLong(3600);
                if (token == null || token.isBlank()) {
                    throw new IllegalStateException("Auth server response missing access_token");
                }
                bearerCache.put(key, new CachedBearer(token, now + ttl * 1000L));
                logger.info("AI Assist: minted token for account_sid={}, ttl={}s", accountSid, ttl);
                return token;
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse token response: " + e.getMessage(), e);
            }
        }
    }

    // ---- Effective credentials (all sourced from the Authorization header envelope) ----

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private String effectiveAccountSid(AuthCredentials creds) {
        return nullIfBlank(creds.getAiAssistAccountSid());
    }

    private String effectiveClientId(AuthCredentials creds) {
        return nullIfBlank(creds.getAiAssistClientId());
    }

    private String effectiveClientSecret(AuthCredentials creds) {
        return nullIfBlank(creds.getAiAssistClientSecret());
    }

    private boolean hasEffectiveClientCredentials(AuthCredentials creds) {
        return effectiveClientId(creds) != null
            && effectiveClientSecret(creds) != null
            && effectiveAccountSid(creds) != null;
    }

    /**
     * Thin wrapper over {@link AuthContext#requireAiAssist()}. Kept as a separate helper so the
     * call sites stay unchanged if we ever reintroduce fallback logic. Returns null when the
     * request carries enough to make an authenticated AI Assist call, otherwise the hint string.
     */
    private String requireEffectiveAiAssist() {
        return AuthContext.requireAiAssist();
    }

    // ======================== TOOLS ========================

    @Tool(name = "exotel_aiassist_whoami",
          description = "Echoes back which AI Assist credentials the MCP server sees in the current request. "
                      + "Use this to verify your Authorization header wiring before calling other AI Assist tools.")
    public String whoami() {
        AuthCredentials creds = AuthContext.current();

        Map<String, Object> aiAssist = new LinkedHashMap<>();
        boolean hasCreds = requireEffectiveAiAssist() == null;
        aiAssist.put("has_credentials", hasCreds);
        aiAssist.put("base_url", creds.effectiveAiAssistBaseUrl(null));
        aiAssist.put("context_path", creds.effectiveAiAssistContextPath(CONTEXT_PATH));
        aiAssist.put("account_sid", effectiveAccountSid(creds));
        aiAssist.put("user_id", creds.getAiAssistUserId());

        boolean hasBasic  = creds.hasAiAssistBasicCredentials();
        boolean hasToken  = creds.getAiAssistAuthToken() != null && !creds.getAiAssistAuthToken().isBlank();
        boolean hasCookie = creds.getAiAssistSessionCookie() != null && !creds.getAiAssistSessionCookie().isBlank();
        boolean hasClient = hasEffectiveClientCredentials(creds);
        String authMode;
        if (hasBasic)       authMode = "twilix_basic";
        else if (hasToken)  authMode = "bearer_manual";
        else if (hasClient) authMode = "client_credentials";
        else if (hasCookie) authMode = "session_cookie";
        else                authMode = "none";

        aiAssist.put("auth_mode", authMode);
        aiAssist.put("has_basic_credentials", hasBasic);
        aiAssist.put("auth_key_prefix", AiAssistJson.maskToken(creds.getAiAssistAuthKey()));
        aiAssist.put("has_token", hasToken);
        aiAssist.put("token_prefix", AiAssistJson.maskToken(creds.getAiAssistAuthToken()));
        aiAssist.put("has_session_cookie", hasCookie);
        aiAssist.put("cookie_prefix", AiAssistJson.maskToken(creds.getAiAssistSessionCookie()));
        aiAssist.put("has_client_credentials", hasClient);
        aiAssist.put("client_id_prefix", AiAssistJson.maskToken(effectiveClientId(creds)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parsed", creds.isParsed());
        body.put("products_configured", creds.configuredProductsSummary());
        body.put("ai_assist", aiAssist);

        String effHint = requireEffectiveAiAssist();
        if (effHint != null) {
            body.put("hint", effHint);
        }

        return AiAssistJson.toJson(body);
    }

    @Tool(name = "exotel_aiassist_list_assistants",
          description = "List AI Assist assistants for the account. "
                      + "Optional query params: limit (int, default 20), offset (int, default 0). "
                      + "The API does not support server-side name search; filter the returned list client-side. "
                      + "Returns the JSON payload from GET /ai-assist/api/v1/accounts/{sid}/ai-assistants.")
    public String listAssistants(Integer limit, Integer offset) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        AuthCredentials creds = AuthContext.current();
        Map<String, String> qp = new LinkedHashMap<>();
        if (limit != null) qp.put("limit", String.valueOf(limit));
        if (offset != null) qp.put("offset", String.valueOf(offset));
        return getJson(creds, "/ai-assistants", qp.isEmpty() ? null : qp);
    }

    @Tool(name = "exotel_aiassist_get_assistant",
          description = "Get one AI Assist assistant by id. "
                      + "GET /ai-assist/api/v1/accounts/{sid}/ai-assistants/{aiAssistantId}.")
    public String getAssistant(String aiAssistantId) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (!AiAssistGuards.isValidId(aiAssistantId)) return AiAssistJson.errorJson("invalid_argument", "aiAssistantId must be alphanumeric/dash/underscore, max 128 chars");
        AuthCredentials creds = AuthContext.current();
        return getJson(creds, "/ai-assistants/" + aiAssistantId, null);
    }

    @Tool(name = "exotel_aiassist_list_attachments",
          description = "List knowledge-base attachments on an assistant. "
                      + "GET /ai-assist/api/v1/accounts/{sid}/ai-assistants/{aiAssistantId}/attachments.")
    public String listAttachments(String aiAssistantId) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (!AiAssistGuards.isValidId(aiAssistantId)) return AiAssistJson.errorJson("invalid_argument", "aiAssistantId invalid");
        AuthCredentials creds = AuthContext.current();
        return getJson(creds, "/ai-assistants/" + aiAssistantId + "/attachments", null);
    }

    @Tool(name = "exotel_aiassist_list_kb_upload_jobs",
          description = "List knowledge-base upload/indexing jobs for an assistant. "
                      + "Each job carries per-file status (PENDING | PROCESSING | COMPLETED | FAILED), "
                      + "file metadata, and the per-file description saved via exotel_aiassist_update_kb_file_descriptions. "
                      + "Use this to check whether a recently attached KB file has finished chunking + embedding "
                      + "on the backend \u2014 the assistant is only ready to serve KB grounded replies once every job is COMPLETED. "
                      + "GET /ai-assist/api/v1/accounts/{sid}/ai-assistants/{aiAssistantId}/upload-jobs.")
    public String listKbUploadJobs(String aiAssistantId) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (!AiAssistGuards.isValidId(aiAssistantId)) return AiAssistJson.errorJson("invalid_argument", "aiAssistantId invalid");
        AuthCredentials creds = AuthContext.current();
        return getJson(creds, "/ai-assistants/" + aiAssistantId + "/upload-jobs", null);
    }

    @Tool(name = "exotel_aiassist_wait_for_kb_ready",
          description = "Poll the KB upload/indexing jobs for an assistant until they all reach COMPLETED, "
                      + "OR any of them FAILS / ends PARTIAL_SUCCESS, OR the timeout elapses. "
                      + "Jobs move QUEUED \u2192 UPLOADING \u2192 PROCESSING \u2192 COMPLETED; the in-flight states keep this polling. "
                      + "Blocks the tool call while polling, so the LLM can call this immediately after "
                      + "attach_attachment_from_file/_from_url and be sure the assistant is truly ready.\n\n"
                      + "Required: aiAssistantId. "
                      + "Optional: timeoutSeconds (default 120, max 600), pollIntervalSeconds (default 3, min 1, max 30).\n\n"
                      + "Returns JSON with:\n"
                      + "  - status: 'ready' | 'failed' | 'partial_success' | 'timeout'\n"
                      + "  - elapsed_seconds, poll_count\n"
                      + "  - summary: {total, completed, processing, pending, queued, uploading, partial_success, failed}\n"
                      + "  - jobs: [{file_name, status, description}]\n\n"
                      + "This is a READ-only convenience \u2014 no confirm=true needed, no write gate.")
    public String waitForKbReady(String aiAssistantId, Integer timeoutSeconds, Integer pollIntervalSeconds) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (!AiAssistGuards.isValidId(aiAssistantId)) return AiAssistJson.errorJson("invalid_argument", "aiAssistantId invalid");

        int timeout = clamp(timeoutSeconds == null ? 120 : timeoutSeconds, 1, 600);
        int interval = clamp(pollIntervalSeconds == null ? 3 : pollIntervalSeconds, 1, 30);

        AuthCredentials creds = AuthContext.current();
        long deadline = System.currentTimeMillis() + timeout * 1000L;
        int pollCount = 0;
        long start = System.currentTimeMillis();

        while (true) {
            pollCount++;
            String raw = getJson(creds, "/ai-assistants/" + aiAssistantId + "/upload-jobs", null);
            KbJobsSummary summary = summarizeKbJobs(raw);
            if (summary == null) {
                // Upstream error \u2014 pass it back with polling context.
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", "error");
                body.put("elapsed_seconds", (System.currentTimeMillis() - start) / 1000);
                body.put("poll_count", pollCount);
                body.put("upstream_response_snippet", AiAssistJson.safeBodySnippet(raw));
                return AiAssistJson.toJson(body);
            }
            // Terminal states, in priority order. QUEUED/UPLOADING/PROCESSING/PENDING are
            // in-flight so they keep the loop polling; only an all-COMPLETED batch is "ready".
            String terminal = null;
            if (summary.failed > 0) terminal = "failed";
            else if (summary.partialSuccess > 0) terminal = "partial_success";
            else if (summary.total > 0 && summary.completed == summary.total) terminal = "ready";
            if (terminal != null || System.currentTimeMillis() >= deadline) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", terminal != null ? terminal : "timeout");
                body.put("elapsed_seconds", (System.currentTimeMillis() - start) / 1000);
                body.put("poll_count", pollCount);
                body.put("summary", Map.of(
                        "total", summary.total,
                        "completed", summary.completed,
                        "processing", summary.processing,
                        "pending", summary.pending,
                        "queued", summary.queued,
                        "uploading", summary.uploading,
                        "partial_success", summary.partialSuccess,
                        "failed", summary.failed));
                body.put("jobs", summary.jobs);
                return AiAssistJson.toJson(body);
            }
            try {
                Thread.sleep(interval * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return AiAssistJson.errorJson("interrupted", "wait_for_kb_ready interrupted");
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static final class KbJobsSummary {
        int total, completed, processing, pending, queued, uploading, failed, partialSuccess;
        List<Map<String, Object>> jobs = new java.util.ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private KbJobsSummary summarizeKbJobs(String rawJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(rawJson, Map.class);
            // upload-jobs returns { response: { data: [ ...jobs ] } }
            Object resp = root.get("response");
            if (resp instanceof Map) {
                Object arr = ((Map<String, Object>) resp).get("data");
                if (arr instanceof List) return countJobs((List<Map<String, Object>>) arr);
            }
            return null;
        } catch (Exception e) {
            logger.debug("summarizeKbJobs parse error: {}", e.getMessage());
            return null;
        }
    }

    private KbJobsSummary countJobs(List<Map<String, Object>> jobs) {
        KbJobsSummary s = new KbJobsSummary();
        s.total = jobs.size();
        for (Map<String, Object> j : jobs) {
            String status = String.valueOf(j.getOrDefault("status", "")).toUpperCase();
            // Mirrors backend KbUploadStatus: PENDING, QUEUED, UPLOADING, PROCESSING, COMPLETED,
            // FAILED, PARTIAL_SUCCESS. QUEUED/UPLOADING are in-flight states that must NOT be
            // treated as "done" (older code dropped them, so the poller could never reconcile
            // total == completed and would time out while jobs were still moving).
            switch (status) {
                case "COMPLETED":       s.completed++; break;
                case "PROCESSING":      s.processing++; break;
                case "PENDING":         s.pending++; break;
                case "QUEUED":          s.queued++; break;
                case "UPLOADING":       s.uploading++; break;
                case "FAILED":          s.failed++; break;
                case "PARTIAL_SUCCESS": s.partialSuccess++; break;
                default: break;
            }
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("file_name", j.get("file_name"));
            compact.put("status", status);
            compact.put("description", j.get("description"));
            s.jobs.add(compact);
        }
        return s;
    }

    @Tool(name = "exotel_aiassist_list_templates",
          description = "List all AI Assist description/prompt templates (system + custom) for the account. "
                      + "GET /ai-assist/api/v1/accounts/{sid}/ai-assistants/description-templates.")
    public String listTemplates() {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        AuthCredentials creds = AuthContext.current();
        return getJson(creds, "/ai-assistants/description-templates", null);
    }

    @Tool(name = "exotel_aiassist_list_custom_templates",
          description = "List custom (user-created) AI Assist description/prompt templates for the account. "
                      + "GET /ai-assist/api/v1/accounts/{sid}/ai-assistants/description-templates/custom.")
    public String listCustomTemplates() {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        AuthCredentials creds = AuthContext.current();
        return getJson(creds, "/ai-assistants/description-templates/custom", null);
    }

    @Tool(name = "exotel_aiassist_get_stream_urls",
          description = "Build the stream-urls endpoint that you wire into a CPaaS Stream applet for an assistant. "
                      + "This does NOT call the endpoint \u2014 it just constructs and returns the URL string. That URL "
                      + "IS what goes into the applet; CPaaS itself calls it at connect time and appends runtime custom "
                      + "params (call_sid, etc.), so there is nothing to GET here (a server-side GET would 400 with "
                      + "CUSTOM_PARAM_VALUE_REQUIRED because call_sid is only known at call time).\n"
                      + "The URL differs per source (ameyo_6x / ameyo_6_0 / exolite) \u2014 pass the matching source or the "
                      + "tool looks it up from the assistant.\n"
                      + "Shape: {ai_assist_base_url}/ai-assist/api/v1/accounts/{sid}/ai-assistants/{aiAssistantId}/stream-urls?source={source}. "
                      + "Optional args: source (ameyo_6x | ameyo_6_0 | exolite; if omitted, looked up from the assistant); "
                      + "channel ('voice' default, or 'chat' to append &channel=chat for chat wiring).")
    public String getStreamUrls(String aiAssistantId, String source, String channel) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (!AiAssistGuards.isValidId(aiAssistantId)) return AiAssistJson.errorJson("invalid_argument", "aiAssistantId invalid");
        AuthCredentials creds = AuthContext.current();

        String effectiveSource = resolveSource(creds, aiAssistantId, source);
        if (effectiveSource != null && !AiAssistGuards.VALID_SOURCES.contains(effectiveSource)) {
            return AiAssistJson.errorJson("invalid_argument",
                    "source '" + effectiveSource + "' is not one of: ameyo_6x, ameyo_6_0, exolite.");
        }

        boolean isChat = channel != null && channel.trim().equalsIgnoreCase("chat");
        String streamUrl = buildStreamUrl(creds, aiAssistantId, effectiveSource, isChat);
        if (streamUrl == null) {
            return AiAssistJson.errorJson("invalid_credentials", "ai_assist_account_sid failed validation");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ai_assistant_id", aiAssistantId);
        if (effectiveSource != null) out.put("source", effectiveSource);
        out.put("channel", isChat ? "chat" : "voice");
        out.put("stream_url", streamUrl);
        out.put("note", "Wire this URL into your CPaaS Stream applet as-is. Do not GET it here \u2014 CPaaS calls it "
                + "at connect time and supplies runtime custom params (call_sid, etc.).");
        return AiAssistJson.toJson(out);
    }

    /**
     * Build (not call) the stream-urls endpoint that gets wired into a CPaaS Stream applet.
     * The URL varies by source (exolite / ameyo_6x / ameyo_6_0). Returns null if the account sid is invalid.
     * We never GET this URL server-side: CPaaS calls it at connect time and supplies runtime custom params
     * (call_sid, etc.), so a server-side GET would 400 with CUSTOM_PARAM_VALUE_REQUIRED.
     */
    /**
     * Resolve the host source for an assistant: use the caller value if given, else look it up from the
     * assistant. Returns a normalized (trimmed, lower-cased) source, or null if neither is available.
     * Best-effort lookup \u2014 a failed GET yields null rather than throwing, so callers decide how to react.
     */
    private String resolveSource(AuthCredentials creds, String aiAssistantId, String source) {
        if (source != null && !source.isBlank()) return source.trim().toLowerCase();
        try {
            Map<String, Object> data = unwrapResponseData(
                    parseJsonObject(getJson(creds, "/ai-assistants/" + aiAssistantId, null)));
            if (data != null && data.get("source") != null) {
                return data.get("source").toString().trim().toLowerCase();
            }
        } catch (Exception ignored) {
            // best-effort: caller handles a null source
        }
        return null;
    }

    private String buildStreamUrl(AuthCredentials creds, String aiAssistantId, String source, boolean chat) {
        String accountSid = effectiveAccountSid(creds);
        if (!AiAssistGuards.ACCOUNT_SID_PATTERN.matcher(accountSid).matches()) return null;
        String baseUrl = creds.effectiveAiAssistBaseUrl(null);
        String contextPath = creds.effectiveAiAssistContextPath(CONTEXT_PATH);
        UriComponentsBuilder ub = UriComponentsBuilder
                .fromHttpUrl(baseUrl)
                .pathSegment(contextPath.split("/"))
                .path("/v1/accounts/" + accountSid + "/ai-assistants/" + aiAssistantId + "/stream-urls");
        if (source != null && !source.isBlank()) ub.queryParam("source", source);
        if (chat) ub.queryParam("channel", "chat");
        return ub.build().toUriString();
    }

    @Tool(name = "exotel_aiassist_smoke_test_chat",
          description = "Transport-level smoke test for an assistant over the CHAT Data Pipe. It resolves the Data Pipe "
                      + "WebSocket via stream-urls?source=<source>&channel=chat (channel=chat skips the voice custom_params "
                      + "contract so no call_sid is needed), opens the socket, sends 'connected' then a 'start' envelope "
                      + "(channel=chat, is_test=true), and waits for the backend's start_ack.\n"
                      + "On success it reports start_ack: ready with session_id / interaction_id \u2014 proving stream-urls + "
                      + "Data Pipe + the assistant session all work end-to-end at the transport layer.\n"
                      + "LIMIT: it does NOT read suggestions \u2014 that needs the conversation-events socket, which requires an "
                      + "AI Assist UI access token this tool doesn't mint. For a full functional test (suggestions), use the AI Assist UI Test Chat.\n"
                      + "The assistant should be LIVE and have 'chat' in supported_channels. "
                      + "Args: aiAssistantId (required); source (optional ameyo_6x | ameyo_6_0 | exolite; looked up from the assistant if omitted).")
    public String smokeTestChat(String aiAssistantId, String source) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (!AiAssistGuards.isValidId(aiAssistantId)) return AiAssistJson.errorJson("invalid_argument", "aiAssistantId invalid");
        AuthCredentials creds = AuthContext.current();

        String effectiveSource = resolveSource(creds, aiAssistantId, source);
        if (effectiveSource == null) {
            return AiAssistJson.errorJson("invalid_argument", "source required (could not infer from assistant)");
        }
        if (!AiAssistGuards.VALID_SOURCES.contains(effectiveSource)) {
            return AiAssistJson.errorJson("invalid_argument", "source '" + effectiveSource + "' is not one of: ameyo_6x, ameyo_6_0, exolite.");
        }

        // Resolve the Data Pipe WS URL. channel=chat means the backend won't demand call_sid.
        String wss;
        try {
            Map<String, String> qp = new LinkedHashMap<>();
            qp.put("source", effectiveSource);
            qp.put("channel", "chat");
            String raw = getJson(creds, "/ai-assistants/" + aiAssistantId + "/stream-urls", qp);
            Map<String, Object> parsed = parseJsonObject(raw);
            if (parsed != null && parsed.containsKey("error")) return raw;
            Map<String, Object> data = unwrapResponseData(parsed);
            wss = (data != null && data.get("url") != null) ? data.get("url").toString() : null;
        } catch (Exception e) {
            return AiAssistJson.errorJson("stream_url_error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (wss == null || wss.isBlank()) {
            return AiAssistJson.errorJson("stream_url_missing", "stream-urls returned no Data Pipe url");
        }

        return runChatHandshake(creds, aiAssistantId, effectiveSource, wss);
    }

    /**
     * Drive the Data Pipe chat handshake: connected -> start(channel=chat, is_test) -> await start_ack.
     * Mirrors the AI Assist UI Test Chat contract. Returns a JSON report; never throws.
     */
    private String runChatHandshake(AuthCredentials creds, String aiAssistantId, String source, String wss) {
        long t0 = System.currentTimeMillis();
        String accountSid = effectiveAccountSid(creds);
        String streamSid = "mcp-smoke-" + t0;
        CompletableFuture<Map<String, Object>> ackFuture = new CompletableFuture<>();
        StringBuilder buf = new StringBuilder();
        WebSocket ws = null;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .sslContext(trustAllSslContext())
                    .connectTimeout(Duration.ofSeconds(SMOKE_HTTP_CONNECT_TIMEOUT_SEC))
                    .build();

            WebSocket.Listener listener = new WebSocket.Listener() {
                @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                    buf.append(data);
                    socket.request(1);
                    if (last) {
                        String frame = buf.toString();
                        buf.setLength(0);
                        handleStartAckFrame(frame, streamSid, ackFuture);
                    }
                    return null;
                }
                @Override public void onError(WebSocket socket, Throwable error) {
                    if (!ackFuture.isDone()) ackFuture.completeExceptionally(error);
                }
                @Override public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
                    if (!ackFuture.isDone()) {
                        ackFuture.completeExceptionally(new IllegalStateException(
                                "Data Pipe closed before start_ack (" + statusCode + " " + reason + ")"));
                    }
                    return null;
                }
            };

            ws = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(SMOKE_WS_CONNECT_TIMEOUT_SEC))
                    .buildAsync(URI.create(wss), listener)
                    .get(SMOKE_WS_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS);

            ws.sendText(AiAssistJson.toJson(Map.of("event", "connected")), true);
            Thread.sleep(SMOKE_CONNECTED_SETTLE_MS);
            ws.sendText(AiAssistJson.toJson(buildChatStartEnvelope(streamSid, accountSid, source)), true);

            Map<String, Object> ack = ackFuture.get(SMOKE_START_ACK_TIMEOUT_SEC, TimeUnit.SECONDS);

            try { ws.sendText(AiAssistJson.toJson(Map.of("event", "stop")), true); } catch (Exception ignored) { }
            safeCloseWs(ws);

            return buildSmokeReport(aiAssistantId, source, wss, ack, System.currentTimeMillis() - t0);
        } catch (TimeoutException te) {
            safeCloseWs(ws);
            return AiAssistJson.errorJson("start_ack_timeout",
                    "No start_ack within timeout. Assistant may not be LIVE, or 'chat' channel is not enabled.",
                    Map.of("data_pipe_url", wss, "elapsed_ms", System.currentTimeMillis() - t0));
        } catch (Exception e) {
            safeCloseWs(ws);
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            return AiAssistJson.errorJson("handshake_error", cause.getClass().getSimpleName() + ": " + cause.getMessage(),
                    Map.of("data_pipe_url", wss));
        }
    }

    private void handleStartAckFrame(String frame, String expectedStreamSid,
                                     CompletableFuture<Map<String, Object>> ackFuture) {
        if (ackFuture.isDone()) return;
        Map<String, Object> parsed;
        try {
            parsed = parseJsonObject(frame);
        } catch (Exception ignored) {
            return; // non-JSON / partial noise
        }
        if (parsed == null || !"start_ack".equals(String.valueOf(parsed.get("event")))) return;
        Object ackObj = parsed.get("start_ack");
        if (!(ackObj instanceof Map)) {
            ackFuture.completeExceptionally(new IllegalStateException("start_ack body missing"));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> ack = (Map<String, Object>) ackObj;
        Object sid = ack.get("stream_sid");
        if (expectedStreamSid != null && sid != null && !expectedStreamSid.equals(sid.toString())) {
            ackFuture.completeExceptionally(new IllegalStateException(
                    "start_ack stream_sid mismatch (expected " + expectedStreamSid + ")"));
            return;
        }
        ackFuture.complete(ack);
    }

    private static void safeCloseWs(WebSocket ws) {
        if (ws != null) {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "smoke-abort"); } catch (Exception ignored) { }
        }
    }

    /** Trust-all SSL context for the Data Pipe WebSocket smoke test. */
    private static SSLContext trustAllSslContext() throws Exception {
        return SSLContextBuilder.create().loadTrustMaterial(null, (chain, authType) -> true).build();
    }

    /** The Data Pipe 'start' envelope for a chat test session (mirrors the AI Assist UI Test Chat contract). */
    private static Map<String, Object> buildChatStartEnvelope(String streamSid, String accountSid, String source) {
        Map<String, Object> customParameters = new LinkedHashMap<>();
        customParameters.put("source", source);
        customParameters.put("origin", "test_chat");
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("channel", "chat");
        start.put("is_test", true);
        start.put("stream_sid", streamSid);
        start.put("account_sid", accountSid);
        start.put("from", "mcp-smoke-agent");
        start.put("to", "mcp-smoke-customer");
        start.put("custom_parameters", customParameters);
        return Map.of("event", "start", "start", start);
    }

    private static String buildSmokeReport(String aiAssistantId, String source, String dataPipeUrl,
                                           Map<String, Object> ack, long elapsedMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", "ready".equals(String.valueOf(ack.get("status"))));
        out.put("ai_assistant_id", aiAssistantId);
        out.put("source", source);
        out.put("channel", "chat");
        out.put("data_pipe_url", dataPipeUrl);
        out.put("start_ack", ack);
        out.put("elapsed_ms", elapsedMs);
        out.put("note", "Transport-level proof only. Suggestions require the conversation-events socket "
                + "(AI Assist UI Test Chat). If ok=false, inspect start_ack.status/message.");
        return AiAssistJson.toJson(out);
    }

    @Tool(name = "exotel_aiassist_list_asr_providers",
          description = "List ASR (speech-to-text) providers available to this account. "
                      + "The AI Assist UI calls this to populate the 'Transcript Model' dropdown in Advance Configuration. "
                      + "Only providers with slug 'elevenlabs' or 'azure' are shown in the AI Assist UI; the wizard should filter to those. "
                      + "GET /ai-assist/api/v1/accounts/{sid}/asr/providers?includeDefault=true&active=true. "
                      + "Returns a list of providers with id (UUID), name, and vendor. "
                      + "AI Assist UI default is 'elevenlabs' \u2014 pick that one when the user says 'default' or doesn't specify.")
    public String listAsrProviders() {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        AuthCredentials creds = AuthContext.current();
        Map<String, String> qp = new LinkedHashMap<>();
        qp.put("includeDefault", "true");
        qp.put("active", "true");
        return getJson(creds, "/asr/providers", qp);
    }

    @Tool(name = "exotel_aiassist_list_llm_suggestion_languages",
          description = "Return the 80 languages the AI Assist UI offers in the 'Assistant suggestions language' dropdown. "
                      + "Use this BEFORE asking the user which language to pick \u2014 present the list, take their answer, "
                      + "and pass the chosen display name as llmLanguage on exotel_aiassist_update_assistant. "
                      + "If the user picks something not in this list (e.g. 'Klingon'), suggest the closest match from the list "
                      + "or ask them to pick again. Do NOT invent a language name. "
                      + "Static snapshot of the AI Assist UI suggestion-language dropdown (no network call).")
    public String listLlmSuggestionLanguages() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "success");
        out.put("count", LLM_LANGUAGE_NAMES.size());
        out.put("languages", new java.util.ArrayList<>(LLM_LANGUAGE_NAMES));
        out.put("default", "English");
        out.put("hint", "Send the display name verbatim as llmLanguage on update_assistant. Case-insensitive.");
        return AiAssistJson.toJson(out);
    }

    /**
     * Resolve an asr_provider_id (UUID) to its vendor slug (elevenlabs / azure / ...).
     * Used by updateAssistant to cross-validate sttLanguage against the picked provider.
     * Returns null if the id isn't in the account's provider list.
     * Not cached — the provider list is small and updateAssistant is rare.
     */
    private String resolveAsrProviderVendor(AuthCredentials creds, String asrProviderId) {
        try {
            Map<String, String> qp = new LinkedHashMap<>();
            qp.put("includeDefault", "true");
            qp.put("active", "true");
            String raw = getJson(creds, "/asr/providers", qp);
            Map<String, Object> parsed = parseJsonObject(raw);
            if (parsed == null) return null;
            Object responseNode = parsed.get("response");
            List<?> items = null;
            if (responseNode instanceof List<?> l) {
                items = l;
            } else if (responseNode instanceof Map<?, ?> m) {
                Object data = m.get("data");
                if (data instanceof List<?> l2) items = l2;
            }
            if (items == null) return null;
            for (Object it : items) {
                if (!(it instanceof Map<?, ?> row)) continue;
                // Each row is shaped {http_code, data: {id, name, vendor, ...}, error_data}.
                // Fall back to the top-level shape in case a future backend version flattens it.
                Map<?, ?> fields = row;
                Object nested = row.get("data");
                if (nested instanceof Map<?, ?> nm) fields = nm;
                Object id = fields.get("id");
                if (id != null && asrProviderId.equalsIgnoreCase(id.toString())) {
                    Object vendor = fields.get("vendor");
                    if (vendor == null) vendor = fields.get("name");
                    return vendor == null ? null : vendor.toString();
                }
            }
        } catch (Exception ignored) {
            // Fail-open would let bad configs through; treat lookup failure as "unknown provider".
        }
        return null;
    }

    // ======================== updateAssistant validators ========================
    /** Overwrite ASR/language fields on parsedConfig from tool args so the LLM can't inject conflicting values. */
    private void overrideAsrAndLanguageFields(Map<String, Object> parsedConfig, String asrProviderId,
                                              String sttLanguage, String llmLanguage, String asrModelId) {
        parsedConfig.put("asr_provider_id", asrProviderId);
        parsedConfig.put("stt_language",    sttLanguage);
        parsedConfig.put("llm_language",    llmLanguage);
        if (asrModelId != null && !asrModelId.isBlank()) parsedConfig.put("asr_model_id", asrModelId.trim());
        else parsedConfig.remove("asr_model_id");
    }

    /** AI Assist UI silently fills confidence_threshold=70 and default bad_feedback_options; mirror that here. */
    private void applySpaAutofills(Map<String, Object> parsedConfig) {
        if (!(parsedConfig.get("suggestions") instanceof Map<?, ?> sRaw)) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> suggestions = (Map<String, Object>) sRaw;
        if (suggestions.get("confidence_threshold") == null) suggestions.put("confidence_threshold", 70);
        if (Boolean.TRUE.equals(suggestions.get("feedback_enabled"))) {
            Object opts = suggestions.get("bad_feedback_options");
            if (!(opts instanceof List<?> l) || l.isEmpty()) {
                suggestions.put("bad_feedback_options", DEFAULT_BAD_FEEDBACK_OPTIONS);
            }
        }
    }

    @Tool(name = "exotel_aiassist_list_agents",
          description = "List agents (call-centre users) for the account. "
                      + "GET /ai-assist/api/v1/accounts/{sid}/agents.")
    public String listAgents() {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        AuthCredentials creds = AuthContext.current();
        return getJson(creds, "/agents", null);
    }

    @Tool(name = "exotel_aiassist_get_agent",
          description = "Get one agent by id. "
                      + "GET /ai-assist/api/v1/accounts/{sid}/agents/{agentId}.")
    public String getAgent(String agentId) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (!AiAssistGuards.isValidId(agentId)) return AiAssistJson.errorJson("invalid_argument", "agentId invalid");
        AuthCredentials creds = AuthContext.current();
        return getJson(creds, "/agents/" + agentId, null);
    }

    @Tool(name = "exotel_aiassist_list_interactions",
          description = "List recent AI Assist interactions (conversations) for the account. "
                      + "Optional query params: page (int), page_size (int), from (ISO date), to (ISO date), assistant_id, agent_id. "
                      + "GET /ai-assist/api/v1/accounts/{sid}/interactions.")
    public String listInteractions(Integer page, Integer pageSize, String from, String to, String assistantId, String agentId) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        AuthCredentials creds = AuthContext.current();
        Map<String, String> qp = new LinkedHashMap<>();
        if (page != null) qp.put("page", String.valueOf(page));
        if (pageSize != null) qp.put("page_size", String.valueOf(pageSize));
        if (from != null && !from.isBlank()) qp.put("from", from);
        if (to != null && !to.isBlank()) qp.put("to", to);
        if (assistantId != null && !assistantId.isBlank()) {
            if (!AiAssistGuards.isValidId(assistantId)) return AiAssistJson.errorJson("invalid_argument", "assistantId invalid");
            qp.put("assistant_id", assistantId);
        }
        if (agentId != null && !agentId.isBlank()) {
            if (!AiAssistGuards.isValidId(agentId)) return AiAssistJson.errorJson("invalid_argument", "agentId invalid");
            qp.put("agent_id", agentId);
        }
        return getJson(creds, "/interactions", qp);
    }

    @Tool(name = "exotel_aiassist_interaction_timeline",
          description = "Get the full detail / timeline for one AI Assist interaction: transcript, "
                      + "suggestions, sentiments, intents, dispositions, summary, session_start_time / "
                      + "session_end_time, custom_param_values, assistant + channel metadata. "
                      + "Use this to debug what actually happened in a single call. "
                      + "GET /ai-assist/api/v1/accounts/{sid}/interactions/{interactionId}. "
                      + "Get the interactionId from list_interactions first.")
    public String interactionTimeline(String interactionId) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (interactionId == null || interactionId.isBlank()) {
            return AiAssistJson.errorJson("invalid_argument", "interactionId is required");
        }
        if (!AiAssistGuards.isValidId(interactionId)) {
            return AiAssistJson.errorJson("invalid_argument", "interactionId invalid (expected 1-128 chars, [a-zA-Z0-9_-])");
        }
        AuthCredentials creds = AuthContext.current();
        return getJson(creds, "/interactions/" + interactionId, null);
    }

    @Tool(name = "exotel_aiassist_get_settings",
          description = "Get AI Assist account settings (flags, config). "
                      + "GET /ai-assist/api/v1/accounts/{sid}/settings.")
    public String getSettings() {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        AuthCredentials creds = AuthContext.current();
        return getJson(creds, "/settings", null);
    }

    // ======================== WRITE TOOLS (Phase 3, gated) ========================

    @Tool(name = "exotel_aiassist_create_assistant",
          description = "STEP 1 of the AI Assist UI's 3-step create wizard (\u201cBasic details\u201d).\n\n"
                      + "The AI Assist UI (https://ai-assist.in.exotel.com) has EXACTLY 3 steps, matched by 3 MCP tools:\n"
                      + "  STEP 1 (this tool)                  \u2014 Basic details\n"
                      + "  STEP 2 exotel_aiassist_update_assistant  \u2014 Configuration (Platform / General / Advanced)\n"
                      + "  STEP 3 exotel_aiassist_publish_assistant \u2014 Deploy & Publish (LIVE or DRAFT)\n"
                      + "You MUST walk the user through ALL 3 steps. Do NOT stop after create + update and silently leave it as DRAFT \u2014 always ask the user in STEP 3 whether to publish or keep as draft.\n\n"
                      + "PRODUCTION-ONLY: this MCP is already bound to a single Exotel AI Assist tenant and environment via the configured credentials (ai_assist_base_url / ai_assist_account_sid in mcp.json). There is NO environment or platform choice to make. Do NOT ask the user 'which environment', 'UAT or Production', or 'which platform/product' \u2014 go straight to STEP 0 / Q1.\n\n"
                      + "STRICT RULE: only fields that exist in the AI Assist UI form are supported. Do NOT invent capabilities like 'summary' or 'topic_detection' \u2014 those are not in the AI Assist UI schema and will be silently dropped by the backend. The wizard DOES support Transcript Model (asr_provider_id), Transcription language (stt_language), Assistant suggestions language (llm_language) \u2014 see STEP 2 (update_assistant) Section C.\n\n"
                      + "STEP 0 (do this BEFORE Q1). Call exotel_aiassist_list_templates to fetch the AI Assist UI's built-in prompt templates. Present the returned template names (with a short blurb each) and ask:\n"
                      + "  \u201cStart from a template, or write your own objective from scratch?\u201d\n"
                      + "  \u2022 If user picks a template: capture its id and pass it VERBATIM as templateId on this call. Standard templates use slug IDs like 'generic', 'inbound-customer-support', 'outbound-collections-bfsi', 'outbound-sales-credit-card', 'blended-sales-support'; custom templates use UUIDs. Both are valid \u2014 do NOT try to convert slugs to UUIDs or vice-versa. The template's content is the AI Assist UI's suggested starting objective; still let the user tweak the wording in Q2.\n"
                      + "  \u2022 If user picks 'from scratch' / 'skip': leave templateId null and go straight to Q1.\n\n"
                      + "BEFORE calling this tool, ASK the user these 4 questions IN ORDER \u2014 you MUST ask every one of them, do NOT skip any even if the AI Assist UI marks it optional. Prior LLM runs have repeatedly forgotten Q4 (attachments); do not repeat that mistake.\n\n"
                      + "  Q1. NAME (required). Letters, digits, spaces, _ and - only. Max 255.\n"
                      + "  Q2. OBJECTIVE / description (required, \u2265 20 chars, \u2264 3000). If STEP 0 selected a template, pre-fill this with the template content and ask the user to review / edit; otherwise ask fresh. Three sub-questions the AI Assist UI shows as helper text:\n"
                      + "        \u2022 Who are the agents this assistant supports, and what should they accomplish with it?\n"
                      + "        \u2022 What types of calls or customer intents should this assistant handle?\n"
                      + "        \u2022 What must this assistant never say, suggest, or do?\n"
                      + "  Q3. INTERACTION CHANNELS (required, \u2265 1). Voice, chat, or both. AI Assist UI default: [voice].\n"
                      + "  Q4. ATTACH DOCUMENTS? YES / NO. MUST ASK EXPLICITLY even if the user didn't mention KB \u2014 the AI Assist UI has an 'Add documents' section on this step and it is easy to forget.\n"
                      + "        \u2022 If YES: collect a list of file paths AND a short (\u2264 500 chars) description per file.\n"
                      + "        \u2022 If NO: continue \u2014 KB can also be attached later via exotel_aiassist_attach_attachment_from_file / _from_url.\n\n"
                      + "Only call this tool once Q1\u2013Q4 (and STEP 0) are answered. Sent in this call: name, description, and optional templateId. Q3 (channels) is sent in STEP 2 and Q4 (attachments) is handled by the attach tools.\n\n"
                      + "After this tool returns successfully, IMMEDIATELY continue the same conversation with:\n"
                      + "  1) exotel_aiassist_attach_attachment_from_file / _from_url \u2014 once per file collected in Q4 (skip if Q4=NO)\n"
                      + "  2) exotel_aiassist_update_assistant \u2014 STEP 2 (Configuration). Its description lists the 3 AI Assist UI sub-sections you MUST walk (Platform / General / Advanced \u2014 including ASR language and LLM model, which are commonly skipped).\n"
                      + "  3) exotel_aiassist_publish_assistant \u2014 STEP 3 (Deploy & Publish). MUST ask the user \u201cpublish now (LIVE) or keep as DRAFT?\u201d before calling.\n"
                      + "  4) POST-PUBLISH ONLY \u2014 for source=exolite assistants that were published LIVE, exotel_aiassist_streaming_applet_template can generate a paste-ready CPaaS App (Stream \u2192 Connect(group) \u2192 Hangup). DO NOT ask about applets / dial destination / group during Q1\u2013Q4 or STEP 2 \u2014 that belongs after STEP 3 succeeds and is a separate ask.\n\n"
                      + "POST /ai-assist/api/v1/accounts/{sid}/ai-assistants. Required args on this call: name, description (\u2265 20 chars). Optional: templateId. Requires confirm=true. Returns the created assistant JSON with its id.")
    public String createAssistant(String name, String description, String templateId, Boolean confirm) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (name == null || name.isBlank()) {
            return AiAssistJson.errorJson("invalid_argument", "name is required");
        }
        if (description == null || description.isBlank()) {
            return AiAssistJson.errorJson("description_required",
                    "description is required. It should explain in plain English what the assistant does. "
                  + "If the user didn't specify one, ASK them before retrying \u2014 don't invent it.");
        }
        if (description.trim().length() < 20) {
            return AiAssistJson.errorJson("description_too_short",
                    "description must be at least 20 characters. Ask the user for a fuller sentence "
                  + "describing what the assistant should do.");
        }
        AuthCredentials creds = AuthContext.current();
        String gate = requireWritePermission(creds, confirm, "create_assistant");
        if (gate != null) return gate;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name.trim());
        body.put("description", description.trim());
        if (templateId != null && !templateId.isBlank()) body.put("template_id", templateId.trim());

        String result = sendJson(creds, HttpMethod.POST, "/ai-assistants", null, body);
        if (looksLikeNameConflict(result)) {
            logger.info("create_assistant name '{}' triggered name_conflict rewrite", name);
            return AiAssistJson.errorJson("name_conflict",
                    "AI Assist rejected the name (likely conflict with an existing or archived assistant of the same name). "
                  + "Ask the user for a different name and retry. Note: archived assistants also reserve their name.",
                    Map.of("original_name", name.trim(), "backend_hint", "500 rollback / unique constraint"));
        }
        return result;
    }

    /**
     * The backend returns a generic 500 "Transaction silently rolled back" when a create hits the
     * unique-name constraint (including names locked by archived rows). Detect that shape and
     * rewrite into a friendlier code the LLM can act on without paging a human.
     */
    private static boolean looksLikeNameConflict(String body) {
        if (body == null) return false;
        if (!body.contains("\"error\":\"http_500\"")) return false;
        return body.contains("rollback-only")
            || body.contains("could not execute statement")
            || body.contains("duplicate")
            || body.contains("unique constraint");
    }

    @Tool(name = "exotel_aiassist_update_assistant",
          description = "STEP 2 of the AI Assist UI's 3-step create wizard (\u201cConfiguration\u201d). Also used for standalone edits. Full PUT.\n\n"
                      + "\u2699\uFE0F SECTIONED WIZARD \u2014 mirror the AI Assist UI's Configuration sub-sections. Walk the user through THREE short grouped asks IN ORDER and WAIT for each before the next. You MAY state a recommended default per field, but the user must actively confirm or adjust each section \u2014 every field ends up user-decided.\n"
                      + "\u274C DO NOT dump all fields as one giant 'apply as-is' bundle. \u274C DO NOT silently apply defaults or silently inherit the create-time source. \u2705 DO group related fields so it's a few focused questions, not 15 atomic ones.\n"
                      + "  SECTION 1 \u2014 Platform: ask A (source) + B (channels). source MUST be an explicit user choice every time \u2014 never inherit the create default without asking.\n"
                      + "  SECTION 2 \u2014 Language: ask C (transcript model) + D (transcription language) + E (suggestions language). Call the relevant list_* tools first so you show real options.\n"
                      + "  SECTION 3 \u2014 Behavior (generalConfig F): present the toggles below as a compact list with recommended defaults; the user confirms or edits any. Group naturally (e.g. Smart Reply ON \u2192 then max words + feedback; Mask ON \u2192 then PII rules).\n"
                      + "  FINAL REVIEW (before this PUT): echo a compact summary of the FULL resolved config (A\u2013F) and get ONE explicit go-ahead. On standalone edits to a LIVE assistant, also show what changes vs the current state.\n"
                      + "The tool enforces each field server-side \u2014 skipping any returns a *_required error and you must go back and ask.\n\n"
                      + "FIELD REFERENCE (grouped into the sections above):\n"
                      + "  A. source              \u2014 AI Assist UI \u201cPlatform Integration\u201d dropdown. Present the user these THREE labelled options (mirror the AI Assist UI \u2014 do NOT show wire values to the end user):\n"
                      + "                            \u2022 Exotel Platform      \u2192 pass source=\"exolite\"\n"
                      + "                            \u2022 Ameyo 6.x (LEGS)     \u2192 pass source=\"ameyo_6x\"\n"
                      + "                            \u2022 Ameyo 6.0            \u2192 pass source=\"ameyo_6_0\"\n"
                      + "                            You MUST pass the wire value (exolite / ameyo_6x / ameyo_6_0), never the label. Determines whether Disposition Suggestions is shown (see F4).\n"
                      + "  B. supportedChannels   \u2014 \"voice\", \"chat\", \"both\", or explicit list. Tool expands \"both\" \u2192 voice+chat.\n"
                      + "  C. asrProviderId       \u2014 AI Assist UI \u201cTranscript Model\u201d. FIRST call exotel_aiassist_list_asr_providers, show ElevenLabs/Azure, ask user. Default: ElevenLabs UUID.\n"
                      + "  D. sttLanguage         \u2014 AI Assist UI \u201cTranscription language\u201d, provider-dependent:\n"
                      + "                            \u2022 ElevenLabs \u2192 AUTO | ENG | HIN | ARA (default ENG).\n"
                      + "                            \u2022 Azure \u2192 locale 'll-CC' e.g. en-in, hi-in (default en-in).\n"
                      + "                            Mixing (e.g. Azure + 'ENG') returns stt_language_invalid_for_provider.\n"
                      + "  E. llmLanguage         \u2014 AI Assist UI \u201cAssistant suggestions language\u201d. 80 options. FIRST call exotel_aiassist_list_llm_suggestion_languages, show user, take their pick (default English).\n\n"
                      + "F. generalConfig JSON \u2014 EVERY sub-field below is enforced. If any is missing the tool returns *_required and refuses the PUT.\n"
                      + "  {\n"
                      + "    \"mask_sensitive_data\":   bool           // REQUIRED. AI Assist UI \u201cMask Sensitive Data\u201d. Default TRUE. If TRUE \u2192 pii_redaction_prompts REQUIRED.\n"
                      + "    \"pii_redaction_prompts\": [strings]      // REQUIRED when mask_sensitive_data=TRUE. 1\u201320 items, 10\u2013500 chars each. ASK THE USER for concrete rules (e.g. \u201cRedact 10-digit Indian phone numbers\u201d).\n"
                      + "    \"live_transcript\":       bool           // REQUIRED. AI Assist UI \u201cReal Time Transcription\u201d. Default TRUE.\n"
                      + "    \"user_sentiment\":        bool           // REQUIRED. AI Assist UI \u201cReal Time Sentiments\u201d. Default TRUE.\n"
                      + "    \"suggestions\": {\n"
                      + "        \"enabled\":               bool,       // REQUIRED. AI Assist UI \u201cReal Time Smart Reply\u201d. Default TRUE.\n"
                      + "        \"max_words_per_reply\":   int \u226510,   // REQUIRED when enabled=TRUE. ASK the user (AI Assist UI default 20 \u2014 offer as default, don\u2019t apply silently).\n"
                      + "        \"feedback_enabled\":      bool,       // REQUIRED when enabled=TRUE. AI Assist UI \u201cSmart Reply Feedback\u201d. Default FALSE.\n"
                      + "        \"bad_feedback_options\":  [strings]   // Optional. When feedback_enabled=TRUE and this is omitted/empty, tool auto-fills ['Irrelevant','Wrong','Outdated']. Max 3 items, \u226415 chars each. ASK the user first if they want custom labels.\n"
                      + "        // confidence_threshold: NOT USER-FACING in the AI Assist UI. Do NOT ask. Tool auto-fills 70.\n"
                      + "    },\n"
                      + "    \"disposition_config\": {                 // REQUIRED only when source \u2208 {ameyo_6x, ameyo_6_0}. Tool STRIPS this for exolite (AI Assist UI hides it).\n"
                      + "        \"disposition\":            bool,     // Default FALSE.\n"
                      + "        \"notes\":                  bool,     // Default FALSE.\n"
                      + "        \"allow_agent_edit_notes\": bool      // Default FALSE.\n"
                      + "    },\n"
                      + "    \"llm_model\":             \"gemma4\" | \"gpt-4o-mini\"   // AI Assist UI \u201cAssist Suggestion Model\u201d, default \"gemma4\".\n"
                      + "  }\n\n"
                      + "AI Assist UI publish rule: at least ONE of {live_transcript, suggestions.enabled, user_sentiment} must be TRUE at LIVE flip.\n"
                      + "Standalone edits: FIRST call exotel_aiassist_get_assistant, reuse current values, change only what the user asked.\n"
                      + "After success, continue to STEP 3 (exotel_aiassist_publish_assistant) \u2014 ASK the user whether to publish LIVE or keep DRAFT.\n\n"
                      + "PUT /ai-assist/api/v1/accounts/{sid}/ai-assistants/{aiAssistantId}.\n"
                      + "Required args: aiAssistantId, name, description (\u226520 chars), source, supportedChannels, generalConfig, status (DRAFT|LIVE|DEACTIVATED), asrProviderId, sttLanguage, llmLanguage, confirm=true.\n"
                      + "Optional args: templateId, asrModelId. connectEndpoint is backend-derived \u2014 leave null.")
    public String updateAssistant(String aiAssistantId,
                                  String name,
                                  String description,
                                  String source,
                                  String supportedChannels,
                                  String generalConfig,
                                  String status,
                                  String templateId,
                                  String connectEndpoint,
                                  String asrProviderId,
                                  String sttLanguage,
                                  String llmLanguage,
                                  String asrModelId,
                                  Boolean confirm) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (!AiAssistGuards.isValidId(aiAssistantId)) return AiAssistJson.errorJson("invalid_argument", "aiAssistantId invalid");
        if (name == null || name.isBlank()) return AiAssistJson.errorJson("invalid_argument", "name is required");
        if (source == null || source.isBlank()) return AiAssistJson.errorJson("invalid_argument", "source is required");
        if (!AiAssistGuards.VALID_SOURCES.contains(source)) {
            return AiAssistJson.errorJson("invalid_argument",
                    "source '" + source + "' is not supported. Pick one of: "
                  + "ameyo_6x (Ameyo 6.x LEGS), ameyo_6_0 (Ameyo 6.0), exolite (Exotel Platform).");
        }
        if (status == null || !status.matches("DRAFT|LIVE|DEACTIVATED")) {
            return AiAssistJson.errorJson("invalid_argument", "status must be one of DRAFT, LIVE, DEACTIVATED");
        }
        if (description == null || description.isBlank()) {
            return AiAssistJson.errorJson("description_required",
                    "description is required. update_assistant is a full PUT \u2014 sending blank would wipe the "
                  + "existing description. If you don't have one, first call get_assistant, reuse its description, "
                  + "or ask the user for a new one.");
        }
        if (description.trim().length() < 20) {
            return AiAssistJson.errorJson("description_too_short",
                    "description must be at least 20 characters. Ask the user for a fuller sentence "
                  + "describing what the assistant should do.");
        }
        List<String> channels = parseCsvOrJsonArray(supportedChannels);
        // AI Assist UI UX shortcut: users say "both" for voice+chat. Backend only accepts the two literal channels.
        if (channels.size() == 1 && "both".equalsIgnoreCase(channels.get(0))) {
            channels = List.of("voice", "chat");
        }
        if (channels.isEmpty()) return AiAssistJson.errorJson("invalid_argument", "supportedChannels must be a non-empty list. Pass \"voice\", \"chat\", \"both\", \"voice,chat\", or [\"voice\",\"chat\"].");
        java.util.Set<String> validChannels = java.util.Set.of("voice", "chat");
        for (String ch : channels) {
            if (!validChannels.contains(ch.toLowerCase())) {
                return AiAssistJson.errorJson("invalid_argument",
                        "supportedChannels: '" + ch + "' is not a valid channel. Only 'voice' and 'chat' are accepted. Use \"both\" as shorthand for voice+chat.");
            }
        }

        if (asrProviderId == null || asrProviderId.isBlank()) {
            return AiAssistJson.errorJson("asr_provider_required",
                    "asrProviderId is required. The AI Assist UI labels this 'Transcript Model'. Call exotel_aiassist_list_asr_providers, "
                  + "show the user ElevenLabs vs Azure, and pass the chosen provider's UUID here.");
        }
        if (!AiAssistGuards.isValidId(asrProviderId.trim())) {
            return AiAssistJson.errorJson("invalid_argument",
                    "asrProviderId must be a UUID from exotel_aiassist_list_asr_providers (do not invent one). Got: " + asrProviderId);
        }
        if (sttLanguage == null || sttLanguage.isBlank()) {
            return AiAssistJson.errorJson("stt_language_required",
                    "sttLanguage is required. AI Assist UI label 'Transcription language'. For ElevenLabs: AUTO | ENG | HIN | ARA. "
                  + "For Azure: locale like en-in, en-us, hi-in, ta-in.");
        }
        if (llmLanguage == null || llmLanguage.isBlank()) {
            return AiAssistJson.errorJson("llm_language_required",
                    "llmLanguage is required. AI Assist UI label 'Assistant suggestions language'. Call exotel_aiassist_list_llm_suggestion_languages "
                  + "for the full 80-language dropdown, show the user, and pass the chosen display NAME (e.g. English, Hindi, Telugu, Tamil).");
        }
        // Whitelist against the AI Assist UI's derived LLM_SUGGESTIONS_LANGUAGES snapshot. Case-insensitive lookup.
        String llmLangNorm = null;
        for (String allowed : LLM_LANGUAGE_NAMES) {
            if (allowed.equalsIgnoreCase(llmLanguage.trim())) { llmLangNorm = allowed; break; }
        }
        if (llmLangNorm == null) {
            // Suggest 3 closest matches by lowercase substring so the LLM can re-ask the user.
            String q = llmLanguage.trim().toLowerCase();
            List<String> suggestions = new java.util.ArrayList<>();
            for (String allowed : LLM_LANGUAGE_NAMES) {
                if (allowed.toLowerCase().startsWith(q) || allowed.toLowerCase().contains(q)) {
                    suggestions.add(allowed);
                    if (suggestions.size() == 5) break;
                }
            }
            return AiAssistJson.errorJson("llm_language_not_in_dropdown",
                    "llmLanguage '" + llmLanguage + "' is not one of the 80 options in the AI Assist UI 'Assistant suggestions language' dropdown. "
                  + (suggestions.isEmpty()
                        ? "Call exotel_aiassist_list_llm_suggestion_languages, show the user the full list, and ask them to pick one."
                        : "Did the user mean one of: " + String.join(", ", suggestions) + " ? Otherwise call exotel_aiassist_list_llm_suggestion_languages and show the full list.")
                  );
        }
        if (asrModelId != null && !asrModelId.isBlank() && !AiAssistGuards.isValidId(asrModelId.trim())) {
            return AiAssistJson.errorJson("invalid_argument", "asrModelId, if provided, must be a UUID. Got: " + asrModelId);
        }

        AuthCredentials creds = AuthContext.current();

        // Cross-validate sttLanguage against the picked asr_provider's vendor. AI Assist UI rule:
        //   ElevenLabs  → sttLanguage ∈ {AUTO, ENG, HIN, ARA}   (uppercase 3-letter codes + AUTO)
        //   Azure       → sttLanguage matches Azure locale format 'll-CC' (~100 options)
        // Must match AI Assist UI supported STT locale codes.
        String vendor = resolveAsrProviderVendor(creds, asrProviderId.trim());
        if (vendor == null) {
            return AiAssistJson.errorJson("asr_provider_not_found",
                    "asrProviderId '" + asrProviderId + "' was not found under /asr/providers for this account. "
                  + "Call exotel_aiassist_list_asr_providers and pick a real one.");
        }
        String vendorLower = vendor.toLowerCase();
        String sttTrim = sttLanguage.trim();
        if ("elevenlabs".equals(vendorLower)) {
            String upper = sttTrim.toUpperCase();
            if (!java.util.Set.of("AUTO", "ENG", "HIN", "ARA").contains(upper)) {
                return AiAssistJson.errorJson("stt_language_invalid_for_provider",
                        "sttLanguage '" + sttLanguage + "' is not valid for ElevenLabs. "
                      + "ElevenLabs accepts exactly one of: AUTO | ENG | HIN | ARA (uppercase 3-letter codes; AUTO = auto-detect). "
                      + "AI Assist UI default is ENG. If the user wants an Azure locale like 'en-in', they need to switch asrProviderId to the Azure UUID first.");
            }
            sttTrim = upper;
        } else {
            if (!AiAssistGuards.AZURE_LOCALE_PATTERN.matcher(sttTrim).matches()) {
                return AiAssistJson.errorJson("stt_language_invalid_for_provider",
                        "sttLanguage '" + sttLanguage + "' is not a valid Azure locale. "
                      + "Azure accepts locales in the form 'll-CC' (e.g. en-in, en-us, hi-in, ta-in, te-in, bn-in, mr-in). "
                      + "AI Assist UI default is 'en-in'. If the user wants an ElevenLabs code like 'ENG', they need to switch asrProviderId to the ElevenLabs UUID first.");
            }
        }

        Map<String, Object> parsedConfig;
        try {
            parsedConfig = parseJsonObject(generalConfig);
        } catch (Exception e) {
            return AiAssistJson.errorJson("invalid_argument", "generalConfig must be a JSON object: " + e.getMessage());
        }
        if (parsedConfig == null) parsedConfig = new LinkedHashMap<>();

        String configErr = AiAssistGuards.validateGeneralConfig(parsedConfig, source.trim());
        if (configErr != null) return configErr;

        overrideAsrAndLanguageFields(parsedConfig, asrProviderId.trim(), sttTrim, llmLangNorm, asrModelId);
        applySpaAutofills(parsedConfig);

        String gate = requireWritePermission(creds, confirm, "update_assistant");
        if (gate != null) return gate;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name.trim());
        body.put("description", description.trim());
        body.put("source", source.trim());
        body.put("supported_channels", channels);
        body.put("general_config", parsedConfig);
        body.put("status", status);
        if (templateId != null && !templateId.isBlank()) body.put("template_id", templateId.trim());
        if (connectEndpoint != null && !connectEndpoint.isBlank()) body.put("connect_url", connectEndpoint.trim());

        return sendJson(creds, HttpMethod.PUT, "/ai-assistants/" + aiAssistantId, null, body);
    }

    @Tool(name = "exotel_aiassist_archive_assistant",
          description = "Archive an AI Assist assistant. This is what the AI Assist UI's 'Delete' button actually does. "
                      + "POST /ai-assist/api/v1/accounts/{sid}/ai-assistants/{aiAssistantId}/archive. "
                      + "STRICT PRECONDITION: assistant must be in DRAFT status. "
                      + "If status is LIVE or DEACTIVATED, backend returns 400 'Cannot archive assistant. Only assistants in Draft status can be archived. Please pause the assistant first.' "
                      + "→ In that case, FIRST call exotel_aiassist_update_assistant with status='DRAFT' (a full PUT preserving everything else), THEN retry archive. "
                      + "Archived assistants disappear from the main list but remain visible in filter dropdowns. "
                      + "Required: aiAssistantId. Requires confirm=true. "
                      + "Prefer this over exotel_aiassist_delete_assistant — the DELETE endpoint currently no-ops.")
    public String archiveAssistant(String aiAssistantId, Boolean confirm) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (!AiAssistGuards.isValidId(aiAssistantId)) return AiAssistJson.errorJson("invalid_argument", "aiAssistantId invalid");

        AuthCredentials creds = AuthContext.current();
        String gate = requireWritePermission(creds, confirm, "archive_assistant");
        if (gate != null) return gate;

        return sendJson(creds, HttpMethod.POST, "/ai-assistants/" + aiAssistantId + "/archive", null, Map.of());
    }

    @Tool(name = "exotel_aiassist_delete_assistant",
          description = "Attempt a DELETE on the assistant endpoint. "
                      + "WARNING: this can return 200 without actually removing the record — prefer "
                      + "exotel_aiassist_archive_assistant, which is what the AI Assist UI's Delete button uses. "
                      + "DELETE /ai-assist/api/v1/accounts/{sid}/ai-assistants/{aiAssistantId}. "
                      + "Required: aiAssistantId. Requires confirm=true.")
    public String deleteAssistant(String aiAssistantId, Boolean confirm) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (!AiAssistGuards.isValidId(aiAssistantId)) return AiAssistJson.errorJson("invalid_argument", "aiAssistantId invalid");

        AuthCredentials creds = AuthContext.current();
        String gate = requireWritePermission(creds, confirm, "delete_assistant");
        if (gate != null) return gate;

        return sendJson(creds, HttpMethod.DELETE, "/ai-assistants/" + aiAssistantId, null, null);
    }

    @Tool(name = "exotel_aiassist_publish_assistant",
          description = "STEP 3 of the AI Assist UI's 3-step create wizard (\u201cDeploy and Publish\u201d): promote a DRAFT assistant to LIVE.\n\n"
                      + "BEFORE calling this tool, MUST explicitly ask the user:\n"
                      + "    \u201cReady to publish and go LIVE, or save as DRAFT for now?\u201d\n"
                      + "Do NOT skip this question and do NOT default to publishing. Prior LLM runs have silently left assistants as DRAFT (no ask) or silently published without confirming; both are wrong.\n"
                      + "  \u2022 If user picks DRAFT: do NOT call this tool. Instead, call exotel_aiassist_get_stream_urls with the same aiAssistantId and give the user BOTH the ai_assistant_id AND the stream URL as the 'Assistant URL' to wire into their dialer. Mention they can publish later via this tool.\n"
                      + "  \u2022 If user picks LIVE:  call this tool. On success, this tool AUTO-APPENDS a 'stream_urls' block to the response \u2014 read stream_urls.url and hand it to the user verbatim as the 'Assistant URL' to wire into their dialer/CPaaS flow. Also confirm the assistant is now LIVE.\n\n"
                      + "AFTER a successful LIVE publish:\n"
                      + "  \u2022 If source=exolite: ask the user \u201cWant me to generate the paste-ready CPaaS App template (Stream \u2192 Connect \u2192 Hangup)? I'll need the CPaaS group name you want to dial.\u201d Only then call exotel_aiassist_streaming_applet_template. Do NOT ask this earlier in the wizard.\n"
                      + "  \u2022 If source=ameyo_6x or ameyo_6_0: tell the user \u201cThe assistant is LIVE. For the Ameyo Deploy steps (Config App / Map Campaigns / Setup Appflow / Agent Assist), open the AI Assist Deploy stage: <deploy_url>\u201d. You can call exotel_aiassist_streaming_applet_template to get the deploy_url quickly, but DO NOT try to walk the user through the Ameyo dashboard yourself \u2014 that flow lives entirely in the AI Assist UI.\n\n"
                      + "Only call this after exotel_aiassist_update_assistant has set source, supportedChannels, and generalConfig \u2014 publishing a bare draft returns 400 (missing supported_channels). connect_url is backend-derived; don't worry about it.\n\n"
                      + "Internally: GET the current assistant, override status='LIVE', PUT the whole body back, then GET /stream-urls and merge into the response. Same underlying endpoint as update_assistant.\n\n"
                      + "Required: aiAssistantId. Requires confirm=true.")
    public String publishAssistant(String aiAssistantId, Boolean confirm) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (!AiAssistGuards.isValidId(aiAssistantId)) return AiAssistJson.errorJson("invalid_argument", "aiAssistantId invalid");

        AuthCredentials creds = AuthContext.current();
        String gate = requireWritePermission(creds, confirm, "publish_assistant");
        if (gate != null) return gate;

        // Fetch current state so we can construct a full PUT body.
        String currentRaw = getJson(creds, "/ai-assistants/" + aiAssistantId, null);
        Map<String, Object> current;
        try {
            Map<String, Object> parsed = parseJsonObject(currentRaw);
            Object resp = parsed.get("response");
            if (!(resp instanceof Map)) return AiAssistJson.errorJson("upstream_shape_error", "GET response missing 'response' object");
            Object data = ((Map<?, ?>) resp).get("data");
            if (!(data instanceof Map)) return AiAssistJson.errorJson("upstream_shape_error", "GET response missing 'response.data' object");
            @SuppressWarnings("unchecked")
            Map<String, Object> dataMap = (Map<String, Object>) data;
            current = dataMap;
        } catch (Exception e) {
            return AiAssistJson.errorJson("upstream_parse_error", "Failed to parse current assistant JSON: " + e.getMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", current.get("name"));
        body.put("description", current.getOrDefault("description", ""));
        body.put("source", current.get("source"));
        body.put("supported_channels", current.getOrDefault("supported_channels", List.of()));
        body.put("general_config", current.getOrDefault("general_config", new LinkedHashMap<>()));
        body.put("status", "LIVE");
        if (current.get("template_id") != null) body.put("template_id", current.get("template_id"));
        if (current.get("connect_url") != null)  body.put("connect_url",  current.get("connect_url"));

        String publishResult = sendJson(creds, HttpMethod.PUT, "/ai-assistants/" + aiAssistantId, null, body);

        // ALWAYS append a stream_urls block so the LLM can never forget to give the user the wiring URL.
        // We BUILD the URL (per source) rather than GET it \u2014 CPaaS calls it at connect time with runtime
        // custom params (call_sid, etc.); a server-side GET would 400 with CUSTOM_PARAM_VALUE_REQUIRED.
        Map<String, Object> publishParsed = parseJsonObject(publishResult);
        if (publishParsed == null || publishParsed.containsKey("error")) {
            return publishResult;
        }
        Object src = current.get("source");
        String srcStr = (src != null && !src.toString().isBlank()) ? src.toString() : null;
        Map<String, Object> streamBlock = new LinkedHashMap<>();
        String streamUrl = buildStreamUrl(creds, aiAssistantId, srcStr, false);
        if (streamUrl != null) {
            streamBlock.put("url", streamUrl);
            if (srcStr != null) streamBlock.put("source", srcStr);
            streamBlock.put("hint", "REQUIRED: give this URL to the user verbatim as the 'Assistant URL' to wire into "
                    + "their dialer / CPaaS Stream applet. Do NOT call it \u2014 CPaaS invokes it at connect time.");
        } else {
            streamBlock.put("error", "could not build stream url (invalid account sid)");
            streamBlock.put("fallback", "Call exotel_aiassist_get_stream_urls with aiAssistantId="
                    + aiAssistantId + (srcStr != null ? " source=" + srcStr : "") + " and give the returned url to the user.");
        }
        publishParsed.put("stream_urls", streamBlock);
        return AiAssistJson.toJson(publishParsed);
    }

    // ---- Custom description templates ----

    @Tool(name = "exotel_aiassist_create_custom_template",
          description = "Create a custom AI Assist description/prompt template. "
                      + "POST /ai-assist/api/v1/accounts/{sid}/ai-assistants/description-templates/custom. "
                      + "Required: name, content. Optional: category. Requires confirm=true.")
    public String createCustomTemplate(String name, String content, String category, Boolean confirm) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (name == null || name.isBlank()) return AiAssistJson.errorJson("invalid_argument", "name is required");
        if (content == null || content.isBlank()) return AiAssistJson.errorJson("invalid_argument", "content is required");

        AuthCredentials creds = AuthContext.current();
        String gate = requireWritePermission(creds, confirm, "create_custom_template");
        if (gate != null) return gate;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name.trim());
        body.put("content", content);
        if (category != null && !category.isBlank()) body.put("category", category.trim());

        return sendJson(creds, HttpMethod.POST, "/ai-assistants/description-templates/custom", null, body);
    }

    @Tool(name = "exotel_aiassist_update_custom_template",
          description = "Update a custom AI Assist description/prompt template. "
                      + "PUT /ai-assist/api/v1/accounts/{sid}/ai-assistants/description-templates/custom/{templateId}. "
                      + "Required: templateId. At least one of: name, category, content. Requires confirm=true.")
    public String updateCustomTemplate(String templateId, String name, String content, String category, Boolean confirm) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (!AiAssistGuards.isValidId(templateId)) return AiAssistJson.errorJson("invalid_argument", "templateId invalid");
        boolean anyField = (name != null && !name.isBlank())
                        || (content != null && !content.isBlank())
                        || (category != null && !category.isBlank());
        if (!anyField) return AiAssistJson.errorJson("invalid_argument", "Provide at least one of name/content/category to update");

        AuthCredentials creds = AuthContext.current();
        String gate = requireWritePermission(creds, confirm, "update_custom_template");
        if (gate != null) return gate;

        Map<String, Object> body = new LinkedHashMap<>();
        if (name != null && !name.isBlank()) body.put("name", name.trim());
        if (content != null && !content.isBlank()) body.put("content", content);
        if (category != null && !category.isBlank()) body.put("category", category.trim());

        return sendJson(creds, HttpMethod.PUT, "/ai-assistants/description-templates/custom/" + templateId, null, body);
    }

    @Tool(name = "exotel_aiassist_delete_custom_template",
          description = "Delete a custom AI Assist description/prompt template. "
                      + "DELETE /ai-assist/api/v1/accounts/{sid}/ai-assistants/description-templates/custom/{templateId}. "
                      + "Required: templateId. Requires confirm=true. "
                      + "Consider calling exotel_aiassist_list_custom_templates first to review, and check usage-count "
                      + "before deleting a template that assistants may reference.")
    public String deleteCustomTemplate(String templateId, Boolean confirm) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (!AiAssistGuards.isValidId(templateId)) return AiAssistJson.errorJson("invalid_argument", "templateId invalid");

        AuthCredentials creds = AuthContext.current();
        String gate = requireWritePermission(creds, confirm, "delete_custom_template");
        if (gate != null) return gate;

        return sendJson(creds, HttpMethod.DELETE, "/ai-assistants/description-templates/custom/" + templateId, null, null);
    }

    // ---- Utility: CPaaS Stream applet template ----

    @Tool(name = "exotel_aiassist_streaming_applet_template",
          description = "POST-PUBLISH helper. Generate a ready-to-paste CPaaS App template for an AI Assist assistant.\n\n"
                      + "\u26A0\uFE0F STRICT ORDERING: only call this AFTER the assistant is fully created (STEP 1 create_assistant \u2192 STEP 2 update_assistant \u2192 STEP 3 publish_assistant all succeeded, status=LIVE). Do NOT ask about applets / group / dial destination during the create wizard \u2014 those belong to a separate post-publish conversation. If the user is still answering create-wizard questions, do NOT bring this tool up.\n\n"
                      + "When to offer it: after publish_assistant returns success AND assistant.source=exolite, ask the user \u201cWant me to generate the CPaaS App template (Stream \u2192 Connect \u2192 Hangup) you can paste into App Bazaar?\u201d Only then call this tool. For ameyo_6x / ameyo_6_0 assistants, offering is optional \u2014 those tenants route calls in the Ameyo dashboard, not CPaaS.\n\n"
                      + "\u2022 assistant.source = exolite: emits a FULL 3-applet flow \u2014 Stream \u2192 Connect(dial-to-group) \u2192 Hangup. Ask the user for the group name AT THIS STEP (not earlier). User types it exactly as it appears in CPaaS Portal \u2192 Users & Groups.\n"
                      + "\u2022 assistant.source = ameyo_6x / ameyo_6_0: MCP cannot replicate the Ameyo Deploy flow (Config App / Map Campaigns / Setup Appflow / Agent Assist are Ameyo dashboard steps). The tool returns a deploy_url pointing at the AI Assist UI's Deploy stage for this assistant. TELL THE USER: open that URL and follow the on-screen steps. DO NOT try to paste the stream URL manually \u2014 the AI Assist UI generates the correct Ameyo package.\n\n"
                      + "Required: aiAssistantId. Optional:\n"
                      + "  \u2022 appletName          \u2014 defaults to \"AI Assist - <assistant name>\"\n"
                      + "  \u2022 groupName           \u2014 CPaaS Group name to dial after the AI Assist stream (exolite only). If omitted, template uses \"<REPLACE_WITH_GROUP_NAME>\" as a placeholder and the LLM MUST tell the user to fill it in.\n"
                      + "  \u2022 dialTimeoutSeconds  \u2014 how long to ring the group before falling through (exolite only, default 30).\n\n"
                      + "Non-mutating: this tool does NOT create the App \u2014 CPaaS App Bazaar has no self-serve create API today. This produces the paste-ready payload; user pastes it into App Bazaar \u2192 Create App.")
    public String streamingAppletTemplate(String aiAssistantId,
                                          String appletName,
                                          String groupName,
                                          Integer dialTimeoutSeconds) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (aiAssistantId == null || aiAssistantId.isBlank()) {
            return AiAssistJson.errorJson("invalid_argument", "aiAssistantId is required");
        }
        if (!AiAssistGuards.isValidId(aiAssistantId)) {
            return AiAssistJson.errorJson("invalid_argument", "aiAssistantId invalid (expected 1-128 chars, [a-zA-Z0-9_-])");
        }
        AuthCredentials creds = AuthContext.current();

        String assistantName;
        String wssUrl;
        String source;
        String status;
        try {
            String assistantRaw = getJson(creds, "/ai-assistants/" + aiAssistantId, null);
            Map<String, Object> assistantData = unwrapResponseData(parseJsonObject(assistantRaw));
            assistantName = assistantData != null && assistantData.get("name") != null
                    ? assistantData.get("name").toString()
                    : aiAssistantId;
            source = (assistantData != null && assistantData.get("source") != null)
                    ? assistantData.get("source").toString()
                    : null;
            status = (assistantData != null && assistantData.get("status") != null)
                    ? assistantData.get("status").toString()
                    : null;

            // Gate: this tool is a POST-publish helper. Refuse if the assistant isn't LIVE yet \u2014
            // otherwise the LLM might jump ahead into the applet flow while the create wizard is
            // still mid-conversation.
            if (status != null && !"LIVE".equalsIgnoreCase(status)) {
                return AiAssistJson.errorJson("assistant_not_live",
                        "Assistant status is '" + status + "', not LIVE. This is a post-publish helper \u2014 "
                      + "finish STEP 1 (create) \u2192 STEP 2 (update_assistant) \u2192 STEP 3 (publish_assistant) first, "
                      + "then come back and offer the applet template. Do NOT ask the user about group / dial destination "
                      + "while the assistant is still being configured.");
            }

        } catch (Exception e) {
            return AiAssistJson.errorJson("backend_parse_error", "Failed to parse assistant response: " + e.getMessage());
        }
        // Build (don't GET) the stream-urls endpoint, matching the assistant's source. CPaaS calls it at
        // connect time with runtime custom params (call_sid, etc.), so we never GET it here.
        wssUrl = buildStreamUrl(creds, aiAssistantId, source, false);
        if (wssUrl == null || wssUrl.isBlank()) {
            return AiAssistJson.errorJson("stream_url_missing",
                    "Could not build stream URL for assistant " + aiAssistantId + " (invalid account sid).");
        }

        String appName = (appletName == null || appletName.isBlank())
                ? "AI Assist - " + assistantName
                : appletName;

        boolean isExolite = "exolite".equalsIgnoreCase(source);
        int timeout = (dialTimeoutSeconds != null && dialTimeoutSeconds > 0) ? dialTimeoutSeconds : 30;
        String effectiveGroup = (groupName != null && !groupName.isBlank())
                ? groupName.trim()
                : "<REPLACE_WITH_GROUP_NAME>";

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("assistant_id", aiAssistantId);
        resp.put("assistant_name", assistantName);
        resp.put("source", source);
        resp.put("stream_url", wssUrl);

        if (isExolite) {
            // Full 3-applet flow: Stream \u2192 Connect(dial-to-group) \u2192 Hangup.
            Map<String, Object> streamStep = new LinkedHashMap<>();
            streamStep.put("step", 1);
            streamStep.put("type", "stream_applet");
            streamStep.put("name", appName + " - Stream");
            streamStep.put("url", wssUrl);
            streamStep.put("record", true);
            streamStep.put("notes", "Streams the live call audio to AI Assist over the WebSocket. Runs in parallel with downstream applets \u2014 does NOT block the call.");

            Map<String, Object> connectStep = new LinkedHashMap<>();
            connectStep.put("step", 2);
            connectStep.put("type", "connect_applet");
            connectStep.put("name", appName + " - Connect to Group");
            connectStep.put("destination_type", "group");
            connectStep.put("destination", effectiveGroup);
            connectStep.put("timeout_seconds", timeout);
            connectStep.put("record", true);
            connectStep.put("notes", "Dials the CPaaS group '" + effectiveGroup + "'. First available agent picks up. If nobody answers within " + timeout + "s the call falls through to the next applet.");

            Map<String, Object> hangupStep = new LinkedHashMap<>();
            hangupStep.put("step", 3);
            hangupStep.put("type", "hangup");
            hangupStep.put("name", appName + " - Hangup");
            hangupStep.put("notes", "Terminates the call cleanly if the group did not answer.");

            Map<String, Object> appTemplate = new LinkedHashMap<>();
            appTemplate.put("app_name", appName);
            appTemplate.put("applets", List.of(streamStep, connectStep, hangupStep));
            resp.put("app_template", appTemplate);
            resp.put("group_name", effectiveGroup);
            resp.put("dial_timeout_seconds", timeout);

            List<String> instructions = new java.util.ArrayList<>();
            instructions.add("Open CPaaS Portal (app.exotel.com or the tenant subdomain).");
            instructions.add("Navigate: App Bazaar \u2192 Create App.");
            instructions.add("Set App name to app_template.app_name.");
            instructions.add("Add applets in order: Stream (step 1) \u2192 Connect (step 2) \u2192 Hangup (step 3). Paste values from each applets[i] entry into its panel.");
            if (effectiveGroup.startsWith("<")) {
                instructions.add("REQUIRED: replace destination='" + effectiveGroup + "' with the actual CPaaS Group name from CPaaS Portal \u2192 Users & Groups before saving.");
            }
            instructions.add("Wire the App into the phone-number flow for numbers this assistant should serve (CPaaS Portal \u2192 ExoPhones \u2192 Assign App).");
            instructions.add("Test with a call to that number \u2192 confirm interaction shows up via exotel_aiassist_list_interactions AND the call reached a group agent.");
            resp.put("instructions", instructions);
        } else {
            // Ameyo tenants (ameyo_6x / ameyo_6_0): don't try to auto-generate a call flow.
            // The AI Assist UI's Deploy stage has source-specific steps (Config App / Map Campaigns / Setup Appflow / Agent Assist)
            // that MCP cannot replicate without duplicating a lot of Ameyo dashboard logic. Redirect to the AI Assist UI.
            String accountSid = effectiveAccountSid(creds);
            String baseUrl = creds.effectiveAiAssistBaseUrl(null);
            // baseUrl is the AI Assist API host (e.g. https://ai-assist.in.exotel.com) \u2014 same host serves the AI Assist UI at /ai-assist/*.
            String spaHost = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "https://ai-assist.in.exotel.com";
            String deployUrl = spaHost + "/ai-assist/" + accountSid + "/" + aiAssistantId + "/edit-assistant";

            String stepsSummary = "ameyo_6_0".equalsIgnoreCase(source)
                    ? "Setup Appflow, then wire Agent Assist"
                    : "Config App, Map Campaigns, then wire Agent Assist";

            resp.put("deploy_url", deployUrl);
            resp.put("stream_url_for_reference", wssUrl);
            resp.put("instructions", List.of(
                    "source='" + source + "' (Ameyo tenant). The MCP does NOT auto-generate the deploy flow for Ameyo \u2014 the AI Assist UI has a dedicated Deploy stage per source with steps MCP cannot replicate.",
                    "TELL THE USER: open " + deployUrl + " and follow the Deploy stage steps (" + stepsSummary + "). The AI Assist UI already knows the assistant's stream URL and generates the correct package for the Ameyo dashboard.",
                    "The stream URL above is provided only for reference / manual paste \u2014 do NOT use it in place of the AI Assist UI's Deploy flow for Ameyo."
            ));
            resp.put("notes", List.of(
                    "For CPaaS App Bazaar (dial-to-group) flows, re-create the assistant with source=exolite and re-run this tool \u2014 that path is fully paste-ready from MCP.",
                    "The stream URL already carries ?source=" + source + "; do not append another source param."
            ));
            return AiAssistJson.toJson(resp);
        }
        resp.put("notes", List.of(
                "CPaaS App Bazaar has no self-serve create API today \u2014 paste is the only path.",
                "The stream URL already carries ?source=<platform>; do not append another source param.",
                "Regenerate this template if the assistant is re-published (version number is baked into the URL path)."
        ));
        return AiAssistJson.toJson(resp);
    }

    // ---- Utility (AI-assisted, non-mutating on our side) ----

    @Tool(name = "exotel_aiassist_improve_description",
          description = "Ask the AI Assist backend to improve a draft assistant description. "
                      + "POST /ai-assist/api/v1/accounts/{sid}/ai-assistants/improve-description. "
                      + "Required: description. Returns the improved text. "
                      + "This does NOT persist anything on the account — it's a pure AI utility call, so the write gate does not apply.")
    public String improveDescription(String description) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (description == null || description.isBlank()) return AiAssistJson.errorJson("invalid_argument", "description is required");
        AuthCredentials creds = AuthContext.current();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", description);
        return sendJson(creds, HttpMethod.POST, "/ai-assistants/improve-description", null, body);
    }

    // ---- Attachments (write, gated) ----

    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024; // 25 MB
    private static final int  MAX_DOWNLOAD_REDIRECTS = 5;

    @Tool(name = "exotel_aiassist_detach_attachment",
          description = "Detach (remove) one or more attachments from an assistant. "
                      + "PATCH /ai-assistants/{id}/attachments with delete_attachment_ids (multipart form field). "
                      + "Required: aiAssistantId, attachmentIds (comma-separated string OR JSON array), confirm=true. "
                      + "Discover attachmentIds via exotel_aiassist_list_attachments.")
    public String detachAttachment(String aiAssistantId, String attachmentIds, Boolean confirm) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (aiAssistantId == null || aiAssistantId.isBlank() || !AiAssistGuards.isValidId(aiAssistantId)) {
            return AiAssistJson.errorJson("invalid_argument", "aiAssistantId invalid");
        }
        List<String> ids = parseCsvOrJsonArray(attachmentIds);
        if (ids.isEmpty()) {
            return AiAssistJson.errorJson("invalid_argument", "attachmentIds is required (comma-separated or JSON array)");
        }
        for (String id : ids) {
            if (!AiAssistGuards.isValidId(id)) return AiAssistJson.errorJson("invalid_argument", "attachmentId '" + id + "' invalid");
        }

        AuthCredentials creds = AuthContext.current();
        String gate = requireWritePermission(creds, confirm, "detach_attachment");
        if (gate != null) return gate;

        return sendAttachmentsMultipart(creds, aiAssistantId, HttpMethod.PATCH, List.of(), ids);
    }

    @Tool(name = "exotel_aiassist_update_kb_file_descriptions",
          description = "Persist per-file 'what is this doc about' descriptions on an assistant's KB upload jobs. "
                      + "Use this AFTER exotel_aiassist_attach_attachment_from_file / _from_url to save the "
                      + "one-line descriptions the user typed for each file during the create-assistant wizard.\n\n"
                      + "PATCH /ai-assist/api/v1/accounts/{sid}/ai-assistants/{aiAssistantId}/upload-jobs "
                      + "with a single multipart field 'description' whose value is a JSON string mapping filename -> description.\n\n"
                      + "Required:\n"
                      + "  - aiAssistantId\n"
                      + "  - descriptionsJson: JSON object as a string, e.g. "
                      + "'{\"debug_guide.md\":\"On-call debugging playbook\",\"runbook.pdf\":\"AWS runbook\"}'. "
                      + "Filenames must match what the attach tools uploaded (case-sensitive).\n"
                      + "  - confirm=true.\n\n"
                      + "Idempotent: calling again with the same JSON is a no-op. Filenames not present in the KB are ignored by the backend.")
    public String updateKbFileDescriptions(String aiAssistantId, String descriptionsJson, Boolean confirm) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (aiAssistantId == null || aiAssistantId.isBlank() || !AiAssistGuards.isValidId(aiAssistantId)) {
            return AiAssistJson.errorJson("invalid_argument", "aiAssistantId invalid");
        }
        if (descriptionsJson == null || descriptionsJson.isBlank()) {
            return AiAssistJson.errorJson("invalid_argument", "descriptionsJson is required (JSON object mapping filename -> description)");
        }

        Map<String, String> descriptions;
        try {
            JsonNode node = objectMapper.readTree(descriptionsJson);
            if (!node.isObject()) {
                return AiAssistJson.errorJson("invalid_argument", "descriptionsJson must be a JSON object, not " + node.getNodeType());
            }
            descriptions = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getValue().isTextual()) {
                    return AiAssistJson.errorJson("invalid_argument",
                            "descriptionsJson value for '" + e.getKey() + "' must be a string");
                }
                String filename = e.getKey();
                String desc = e.getValue().asText();
                if (filename == null || filename.isBlank()) {
                    return AiAssistJson.errorJson("invalid_argument", "descriptionsJson has an empty filename key");
                }
                if (desc == null || desc.isBlank()) {
                    return AiAssistJson.errorJson("invalid_argument",
                            "descriptionsJson value for '" + filename + "' is blank \u2014 ask the user for the doc's purpose");
                }
                descriptions.put(filename, desc);
            }
        } catch (Exception e) {
            return AiAssistJson.errorJson("invalid_argument", "descriptionsJson is not valid JSON: " + e.getMessage());
        }
        if (descriptions.isEmpty()) {
            return AiAssistJson.errorJson("invalid_argument", "descriptionsJson must contain at least one filename -> description entry");
        }

        AuthCredentials creds = AuthContext.current();
        String gate = requireWritePermission(creds, confirm, "update_kb_file_descriptions");
        if (gate != null) return gate;

        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(descriptions);
        } catch (Exception e) {
            return AiAssistJson.errorJson("serialization_error", "Failed to serialize descriptions map: " + e.getMessage());
        }

        return sendUploadJobsPatch(creds, aiAssistantId, serialized);
    }

    private String sendUploadJobsPatch(AuthCredentials creds, String aiAssistantId, String descriptionJson) {
        String accountSid = effectiveAccountSid(creds);
        if (!AiAssistGuards.ACCOUNT_SID_PATTERN.matcher(accountSid).matches()) {
            return AiAssistJson.errorJson("invalid_credentials", "ai_assist_account_sid failed validation");
        }
        String baseUrl = creds.effectiveAiAssistBaseUrl(null);
        String contextPath = creds.effectiveAiAssistContextPath(CONTEXT_PATH);

        String url = UriComponentsBuilder
                .fromHttpUrl(baseUrl)
                .pathSegment(contextPath.split("/"))
                .path("/v1/accounts/" + accountSid + "/ai-assistants/" + aiAssistantId + "/upload-jobs")
                .build().toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        String authErr = applyAiAssistAuth(creds, headers);
        if (authErr != null) return authErr;

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("description", descriptionJson);

        HttpEntity<LinkedMultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            logger.info("AI Assist PATCH {} (descriptions payload {}B)", url, descriptionJson.length());
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.PATCH, entity, String.class);
            return resp.getBody() != null ? resp.getBody() : "{}";
        } catch (HttpClientErrorException e) {
            logger.warn("AI Assist upload-jobs client error {} on {}: {}", e.getStatusCode(), url, AiAssistJson.safeBodySnippet(e.getResponseBodyAsString()));
            return AiAssistJson.errorJson("http_" + e.getStatusCode().value(), "AI Assist responded " + e.getStatusCode(),
                    Map.of("body", AiAssistJson.safeBodySnippet(e.getResponseBodyAsString())));
        } catch (HttpServerErrorException e) {
            logger.warn("AI Assist upload-jobs server error {} on {}", e.getStatusCode(), url);
            return AiAssistJson.errorJson("http_" + e.getStatusCode().value(), "AI Assist responded " + e.getStatusCode(),
                    Map.of("body", AiAssistJson.safeBodySnippet(e.getResponseBodyAsString())));
        } catch (Exception e) {
            logger.error("AI Assist upload-jobs call failed", e);
            return AiAssistJson.errorJson("network_error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Tool(name = "exotel_aiassist_attach_attachment_from_url",
          description = "STEP 2 of the create-assistant wizard (variant: file lives at a public HTTPS URL). Attach ONE document to an assistant. "
                      + "Call this ONCE PER FILE the user supplied. If the user has 3 docs, call this 3 times.\n\n"
                      + "STRICT: description is REQUIRED (\u2265 10 chars, \u2264 500). The AI Assist UI blocks attachment API calls when any file lacks a description \u2014 do the same here. "
                      + "If the user hasn't given a per-file description yet, ASK THEM FIRST. Do NOT invent one; the description is what the LLM uses to decide when to consult this doc at runtime.\n\n"
                      + "Uploads go through the ASYNC upload-jobs API: the file is staged and a KB indexing job is created "
                      + "(QUEUED \u2192 UPLOADING \u2192 PROCESSING \u2192 COMPLETED). POST reuses the assistant's existing corpus, or creates "
                      + "one on the first upload \u2014 handled automatically. The tool then persists the description via /upload-jobs so "
                      + "the two never drift. Call exotel_aiassist_wait_for_kb_ready afterwards to block until indexing finishes.\n"
                      + "POST /ai-assistants/{id}/upload-jobs with files (multipart file field).\n"
                      + "Required: aiAssistantId, fileUrl (https), description (\u2265 10 chars), confirm=true. Optional: filename (defaults to URL last segment). "
                      + "Works on both local and hosted MCP. URL must be reachable from wherever MCP runs. "
                      + "Max file size 25 MB. Rejects http (non-TLS), localhost, and private/link-local IPs to prevent SSRF.")
    public String attachAttachmentFromUrl(String aiAssistantId, String fileUrl, String filename, String description, Boolean confirm) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (aiAssistantId == null || aiAssistantId.isBlank() || !AiAssistGuards.isValidId(aiAssistantId)) {
            return AiAssistJson.errorJson("invalid_argument", "aiAssistantId invalid");
        }
        if (fileUrl == null || fileUrl.isBlank()) {
            return AiAssistJson.errorJson("invalid_argument", "fileUrl is required");
        }
        String descCheck = validateAttachmentDescription(description);
        if (descCheck != null) return descCheck;

        String urlCheck = AiAssistGuards.validateFetchUrl(fileUrl);
        if (urlCheck != null) return AiAssistJson.errorJson("invalid_url", urlCheck);

        AuthCredentials creds = AuthContext.current();
        String gate = requireWritePermission(creds, confirm, "attach_attachment_from_url");
        if (gate != null) return gate;

        byte[] bytes;
        String effectiveName;
        try {
            // SSRF guard: auto-redirect is DISABLED so a 302 cannot silently jump to an internal
            // host after the initial validation. We follow redirects manually and re-run
            // AiAssistGuards.validateFetchUrl() on every hop (incl. the redirect target), so private/loopback/
            // link-local and non-https targets are rejected even mid-redirect-chain.
            RestTemplate downloader = createRestTemplate(true, false);
            String currentUrl = fileUrl;
            ResponseEntity<byte[]> dl;
            int hops = 0;
            while (true) {
                String hopCheck = AiAssistGuards.validateFetchUrl(currentUrl);
                if (hopCheck != null) return AiAssistJson.errorJson("invalid_url", hopCheck);
                dl = downloader.getForEntity(currentUrl, byte[].class);
                if (!dl.getStatusCode().is3xxRedirection()) break;
                if (++hops > MAX_DOWNLOAD_REDIRECTS) {
                    return AiAssistJson.errorJson("too_many_redirects",
                            "Source URL exceeded " + MAX_DOWNLOAD_REDIRECTS + " redirects");
                }
                URI location = dl.getHeaders().getLocation();
                if (location == null) {
                    return AiAssistJson.errorJson("download_failed", "Redirect (" + dl.getStatusCode() + ") without Location header");
                }
                currentUrl = URI.create(currentUrl).resolve(location).toString();
            }
            if (!dl.getStatusCode().is2xxSuccessful() || dl.getBody() == null) {
                return AiAssistJson.errorJson("download_failed", "Non-2xx from source URL: " + dl.getStatusCode());
            }
            bytes = dl.getBody();
            if (bytes.length > MAX_ATTACHMENT_BYTES) {
                return AiAssistJson.errorJson("file_too_large", "File > 25MB (" + bytes.length + " bytes)");
            }
            effectiveName = (filename != null && !filename.isBlank())
                    ? filename
                    : filenameFromUrl(fileUrl);
        } catch (Exception e) {
            return AiAssistJson.errorJson("download_error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        ByteArrayResource resource = namedByteResource(bytes, effectiveName);
        String uploadResult = addAttachmentsAsUploadJobs(creds, aiAssistantId, List.of(resource));
        return persistDescriptionAndMerge(creds, aiAssistantId, effectiveName, description.trim(), uploadResult);
    }

    @Tool(name = "exotel_aiassist_attach_attachment_from_file",
          description = "STEP 2 of the create-assistant wizard (variant: file is on the MCP server's local disk). Attach ONE document to an assistant. "
                      + "Call this ONCE PER FILE the user supplied. If the user has 3 docs, call this 3 times.\n\n"
                      + "STRICT: description is REQUIRED (\u2265 10 chars, \u2264 500). The AI Assist UI blocks attachment API calls when any file lacks a description \u2014 do the same here. "
                      + "If the user hasn't given a per-file description yet, ASK THEM FIRST. Do NOT invent one; the description is what the LLM uses to decide when to consult this doc at runtime.\n\n"
                      + "Uploads go through the ASYNC upload-jobs API: the file is staged and a KB indexing job is created "
                      + "(QUEUED \u2192 UPLOADING \u2192 PROCESSING \u2192 COMPLETED). POST reuses the assistant's existing corpus, or creates "
                      + "one on the first upload \u2014 handled automatically. The tool then persists the description via /upload-jobs so "
                      + "the two never drift. Call exotel_aiassist_wait_for_kb_ready afterwards to block until indexing finishes.\n"
                      + "POST /ai-assistants/{id}/upload-jobs with files (multipart file field).\n"
                      + "Required: aiAssistantId, filePath (absolute path), description (\u2265 10 chars), confirm=true.\n"
                      + "IMPORTANT: only works when the file exists on the MCP server's disk. "
                      + "For local MCP (localhost:8090) this includes chat-uploaded files in Cursor's local upload directory. "
                      + "For hosted MCP (mcp.exotel.com) use exotel_aiassist_attach_attachment_from_url instead. "
                      + "Max file size 25 MB.")
    public String attachAttachmentFromFile(String aiAssistantId, String filePath, String description, Boolean confirm) {
        String err = requireEffectiveAiAssist();
        if (err != null) return err;
        if (aiAssistantId == null || aiAssistantId.isBlank() || !AiAssistGuards.isValidId(aiAssistantId)) {
            return AiAssistJson.errorJson("invalid_argument", "aiAssistantId invalid");
        }
        if (filePath == null || filePath.isBlank()) {
            return AiAssistJson.errorJson("invalid_argument", "filePath is required");
        }
        String descCheck = validateAttachmentDescription(description);
        if (descCheck != null) return descCheck;

        Path path;
        try {
            path = Paths.get(filePath).toAbsolutePath().normalize();
        } catch (Exception e) {
            return AiAssistJson.errorJson("invalid_path", "filePath is not a valid path: " + e.getMessage());
        }
        if (!Files.exists(path)) {
            return AiAssistJson.errorJson("file_not_found", "No file at " + path);
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return AiAssistJson.errorJson("file_not_readable", "Not a readable regular file: " + path);
        }

        AuthCredentials creds = AuthContext.current();
        String gate = requireWritePermission(creds, confirm, "attach_attachment_from_file");
        if (gate != null) return gate;

        byte[] bytes;
        try {
            long size = Files.size(path);
            if (size > MAX_ATTACHMENT_BYTES) {
                return AiAssistJson.errorJson("file_too_large", "File > 25MB (" + size + " bytes)");
            }
            bytes = Files.readAllBytes(path);
        } catch (Exception e) {
            return AiAssistJson.errorJson("file_read_error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        String fileName = path.getFileName().toString();
        ByteArrayResource resource = namedByteResource(bytes, fileName);
        String uploadResult = addAttachmentsAsUploadJobs(creds, aiAssistantId, List.of(resource));
        return persistDescriptionAndMerge(creds, aiAssistantId, fileName, description.trim(), uploadResult);
    }

    private String validateAttachmentDescription(String description) {
        if (description == null || description.isBlank()) {
            return AiAssistJson.errorJson("description_required",
                    "description is required. The AI Assist UI blocks attachment uploads when any file lacks a description. "
                  + "Ask the user for a one-liner explaining what this document is about (e.g. 'On-call debugging playbook', "
                  + "'AWS runbook for Ameyo QA'). Min 10 chars, max 500.");
        }
        String trimmed = description.trim();
        if (trimmed.length() < 10) {
            return AiAssistJson.errorJson("description_too_short",
                    "description must be at least 10 characters. Ask the user for a fuller sentence.");
        }
        if (trimmed.length() > 500) {
            return AiAssistJson.errorJson("description_too_long",
                    "description must be at most 500 characters (got " + trimmed.length() + ").");
        }
        return null;
    }

    /**
     * After a successful attachment upload, persist the per-file description via /upload-jobs
     * so the description never drifts from the file. If the description POST fails, we surface
     * the error inline but do NOT roll back the upload \u2014 the file is on the corpus, the user
     * can retry the description via update_kb_file_descriptions.
     */
    private String persistDescriptionAndMerge(AuthCredentials creds, String aiAssistantId,
                                              String fileName, String description, String uploadResult) {
        Map<String, Object> uploadParsed = parseJsonObject(uploadResult);
        if (uploadParsed != null && uploadParsed.containsKey("error")) {
            return uploadResult;
        }
        String descPayload;
        try {
            descPayload = objectMapper.writeValueAsString(Map.of(fileName, description));
        } catch (Exception e) {
            uploadParsed = uploadParsed != null ? uploadParsed : new LinkedHashMap<>();
            uploadParsed.put("description_error",
                    "Upload succeeded but description could not be serialised: " + e.getMessage()
                  + ". Retry via exotel_aiassist_update_kb_file_descriptions.");
            return AiAssistJson.toJson(uploadParsed);
        }
        String descResult = sendUploadJobsPatch(creds, aiAssistantId, descPayload);
        Map<String, Object> descParsed = parseJsonObject(descResult);
        if (descParsed != null && descParsed.containsKey("error")) {
            uploadParsed = uploadParsed != null ? uploadParsed : new LinkedHashMap<>();
            uploadParsed.put("description_error",
                    "Upload succeeded but description PATCH failed: " + descParsed.get("message")
                  + ". Retry via exotel_aiassist_update_kb_file_descriptions.");
            return AiAssistJson.toJson(uploadParsed);
        }
        if (uploadParsed != null) {
            uploadParsed.put("description_saved", description);
            uploadParsed.put("description_file", fileName);
            return AiAssistJson.toJson(uploadParsed);
        }
        return uploadResult;
    }

    // ---- Shared multipart helpers ----

    /**
     * Add attachments via the async upload-jobs API (POST /ai-assistants/{id}/upload-jobs).
     * Jobs move QUEUED -> UPLOADING -> PROCESSING -> COMPLETED; poll with wait_for_kb_ready.
     * POST reuses the assistant's existing corpus or creates one on the first upload.
     */
    private String addAttachmentsAsUploadJobs(AuthCredentials creds, String aiAssistantId,
                                              List<ByteArrayResource> addFiles) {
        return sendAttachmentsMultipart(creds, aiAssistantId, HttpMethod.POST, addFiles, List.of());
    }

    private String sendAttachmentsMultipart(AuthCredentials creds, String aiAssistantId,
                                            HttpMethod method,
                                            List<ByteArrayResource> addFiles, List<String> deleteIds) {
        String sidErr = accountSidValidationError(creds);
        if (sidErr != null) return sidErr;
        String url = buildAccountApiUrl(creds, "/ai-assistants/" + aiAssistantId + "/upload-jobs", null);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        String authErr = applyAiAssistAuth(creds, headers);
        if (authErr != null) return authErr;
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        String addField = (method == HttpMethod.POST) ? "files" : "add_files";
        for (ByteArrayResource f : addFiles) body.add(addField, f);
        for (String id : deleteIds) body.add("delete_attachment_ids", id);
        logger.debug("AI Assist {} {} (add={}, delete={})", method, url, addFiles.size(), deleteIds.size());
        return exchangeApi(method, url, method + " upload-jobs", new HttpEntity<>(body, headers));
    }

    private static ByteArrayResource namedByteResource(byte[] bytes, String filename) {
        return new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
            @Override public long contentLength() { return bytes.length; }
        };
    }

    private static String filenameFromUrl(String fileUrl) {
        try {
            String path = URI.create(fileUrl).getPath();
            if (path == null || path.isEmpty()) return "attachment";
            String last = path.substring(path.lastIndexOf('/') + 1);
            return last.isBlank() ? "attachment" : last;
        } catch (Exception e) {
            return "attachment";
        }
    }

    // ======================== WRITE GATE ========================

    /**
     * Returns null when the write is allowed, otherwise a JSON error envelope.
     * The only gate is: confirm must be true (client acknowledged the write).
     * Host / env allow-lists were removed \u2014 credentials + confirm are sufficient.
     */
    private String requireWritePermission(AuthCredentials creds, Boolean confirm, String action) {
        String gate = AiAssistGuards.writeGateError(confirm, action);
        if (gate != null) return gate;
        logger.info("AI Assist write allowed: action={} baseUrl={}",
                action, creds.effectiveAiAssistBaseUrl(null));
        return null;
    }


    private String accountSidValidationError(AuthCredentials creds) {
        if (!AiAssistGuards.isValidAccountSid(effectiveAccountSid(creds))) {
            return AiAssistJson.errorJson("invalid_credentials", "ai_assist_account_sid failed validation");
        }
        return null;
    }

    private String buildAccountApiUrl(AuthCredentials creds, String path, Map<String, String> queryParams) {
        String accountSid = effectiveAccountSid(creds);
        String baseUrl = creds.effectiveAiAssistBaseUrl(null);
        String contextPath = creds.effectiveAiAssistContextPath(CONTEXT_PATH);
        UriComponentsBuilder ub = UriComponentsBuilder
                .fromHttpUrl(baseUrl)
                .pathSegment(contextPath.split("/"))
                .path("/v1/accounts/" + accountSid + path);
        if (queryParams != null) queryParams.forEach(ub::queryParam);
        return ub.build().toUriString();
    }

    private String exchangeApi(HttpMethod method, String url, String pathLabel, HttpEntity<?> entity) {
        try {
            if (method == HttpMethod.GET) logger.debug("AI Assist GET {}", url);
            else logger.info("AI Assist {} {} (body={}B)", method, url,
                    entity.getBody() == null ? 0 : String.valueOf(entity.getBody()).length());
            ResponseEntity<String> resp = restTemplate.exchange(url, method, entity, String.class);
            return resp.getBody() != null ? resp.getBody() : "{}";
        } catch (HttpClientErrorException e) {
            logger.warn("AI Assist client error {} on {} {}: {}", e.getStatusCode(), method, url,
                    AiAssistJson.safeBodySnippet(e.getResponseBodyAsString()));
            return AiAssistJson.errorJson("http_" + e.getStatusCode().value(),
                    "AI Assist responded " + e.getStatusCode() + " for " + pathLabel,
                    Map.of("body", AiAssistJson.safeBodySnippet(e.getResponseBodyAsString())));
        } catch (HttpServerErrorException e) {
            logger.warn("AI Assist server error {} on {} {}", e.getStatusCode(), method, url);
            return AiAssistJson.errorJson("http_" + e.getStatusCode().value(),
                    "AI Assist responded " + e.getStatusCode() + " for " + pathLabel,
                    Map.of("body", AiAssistJson.safeBodySnippet(e.getResponseBodyAsString())));
        } catch (Exception e) {
            logger.error("AI Assist {} call failed for {}", method, url, e);
            return AiAssistJson.errorJson("network_error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ======================== HTTP CORE ========================

    /**
     * GET {base}/{context}/v1/accounts/{sid}{path} with the AI Assist bearer.
     * Returns the raw response body (JSON) or a JSON error envelope on failure.
     */
    private String getJson(AuthCredentials creds, String path, Map<String, String> queryParams) {
        String sidErr = accountSidValidationError(creds);
        if (sidErr != null) return sidErr;
        String url = buildAccountApiUrl(creds, path, queryParams);
        HttpHeaders headers = new HttpHeaders();
        String authErr = applyAiAssistAuth(creds, headers);
        if (authErr != null) return authErr;
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return exchangeApi(HttpMethod.GET, url, path, new HttpEntity<>(headers));
    }

    /**
     * Auth resolution for AI Assist HTTP calls. Precedence:
     *  1. Twilix Basic (ai_assist_auth_key + ai_assist_auth_secret) — CPaaS API creds; per-tenant scope.
     *  2. Explicit bearer (ai_assist_auth_token) \u2014 manual paste.
     *  3. Auth0 M2M client_credentials (ai_assist_client_id + ai_assist_client_secret + account_sid)
     *     \u2014 exchanged via /oauth/token and cached until near expiry.
     *  4. Session cookie (ai_assist_session_cookie) \u2014 legacy paste for IAM tenants.
     *
     * Modifies {@code headers} in place. Returns a JSON error envelope if token exchange fails,
     * otherwise null (caller should proceed).
     */
    private String applyAiAssistAuth(AuthCredentials creds, HttpHeaders headers) {
        String basicHeader = creds.aiAssistBasicHeader();
        if (basicHeader != null) {
            headers.set(HttpHeaders.AUTHORIZATION, basicHeader);
        } else {
            String explicitBearer = creds.getAiAssistAuthToken();
            if (explicitBearer != null && !explicitBearer.isBlank()) {
                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + explicitBearer);
            } else if (hasEffectiveClientCredentials(creds)) {
                try {
                    String bearer = mintOrReuseBearer(
                            effectiveClientId(creds),
                            effectiveClientSecret(creds),
                            effectiveAccountSid(creds));
                    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
                } catch (Exception e) {
                    logger.warn("AI Assist token exchange failed: {}", e.getMessage());
                    return AiAssistJson.errorJson("token_exchange_error", e.getMessage());
                }
            }
        }
        String cookie = creds.getAiAssistSessionCookie();
        if (cookie != null && !cookie.isBlank()) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        return null;
    }

    /**
     * Send a request with an optional JSON body. Used for POST/PUT/PATCH/DELETE.
     * Returns the raw response body or a JSON error envelope on failure.
     */
    private String sendJson(AuthCredentials creds, HttpMethod method, String path,
                            Map<String, String> queryParams, Map<String, Object> jsonBody) {
        String sidErr = accountSidValidationError(creds);
        if (sidErr != null) return sidErr;
        String url = buildAccountApiUrl(creds, path, queryParams);
        HttpHeaders headers = new HttpHeaders();
        String authErr = applyAiAssistAuth(creds, headers);
        if (authErr != null) return authErr;
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String bodyString = null;
        if (jsonBody != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
            try {
                bodyString = objectMapper.writeValueAsString(jsonBody);
            } catch (Exception e) {
                return AiAssistJson.errorJson("serialization_error", "Failed to serialize request body: " + e.getMessage());
            }
        }
        String pathLabel = method + " " + path;
        return exchangeApi(method, url, pathLabel, new HttpEntity<>(bodyString, headers));
    }

    /**
     * Parse a JSON object    /**
     * Parse a JSON object string into a Map. Returns null on blank / malformed input or when the
     * top-level JSON is not an object. Callers must null-check.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The AI Assist backend wraps every payload as { http_code, status, method, request_id, response: { data, error_data, http_code } }.
     * This peels back two layers and returns the actual payload object.
     * Returns null if the shape doesn't match.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrapResponseData(Map<String, Object> body) {
        if (body == null) return null;
        Object response = body.get("response");
        if (!(response instanceof Map)) return null;
        Object data = ((Map<String, Object>) response).get("data");
        if (!(data instanceof Map)) return null;
        return (Map<String, Object>) data;
    }

    /**
     * Accepts either a JSON array (["a","b"]) or a comma-separated string ("a,b") and returns the list.
     */
    @SuppressWarnings("unchecked")
    private static List<String> parseCsvOrJsonArray(String value) {
        if (value == null || value.isBlank()) return List.of();
        String trimmed = value.trim();
        if (trimmed.startsWith("[")) {
            try {
                Object parsed = objectMapper.readValue(trimmed, Object.class);
                if (parsed instanceof List) {
                    List<Object> raw = (List<Object>) parsed;
                    return raw.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isEmpty()).toList();
                }
            } catch (Exception ignored) { /* fall through to CSV */ }
        }
        return java.util.Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static RestTemplate createRestTemplate(boolean verifySsl) {
        return createRestTemplate(verifySsl, true);
    }

    private static RestTemplate createRestTemplate(boolean verifySsl, boolean followRedirects) {
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
            var httpClientBuilder = HttpClients.custom().setConnectionManager(connMgr);
            if (!followRedirects) {
                // Do NOT auto-follow 3xx: the caller re-validates each redirect hop to block SSRF.
                httpClientBuilder.disableRedirectHandling();
            }
            var httpClient = httpClientBuilder.build();
            var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
            factory.setConnectTimeout(10_000);
            factory.setReadTimeout(30_000);
            return new RestTemplate(factory);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create RestTemplate for AI Assist", e);
        }
    }

}
