package com.example.mcp_api.auth;

import com.example.mcp_api.auth.AuthCredentials.CredentialSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * Verifies caller-supplied credentials against Exotel itself, one cheap read-only authenticated GET per
 * credential set present in the Authorization header, always on a KNOWN API base for that product.
 *
 * A request is authenticated only if at least one set was VERIFIED by a real probe; shape alone never
 * logs anyone in. Every present set must be complete and pass its probe. Fails closed: only a 2xx with a
 * JSON Content-Type is VALID (a 2xx of any other type means the probe did not reach the API); 401/403/404 is
 * REJECTED; anything else (timeouts, 5xx, 429, non-JSON 2xx, unexpected statuses) is UNAVAILABLE and is
 * never treated as success. Results are cached in memory keyed by SHA-256 of set name, host and credential
 * values (never the raw values): VALID for a few minutes, REJECTED briefly, UNAVAILABLE not at all. The
 * cache is size-bounded.
 *
 * Probes (all GET, no redirects, 3 s connect / 5 s request timeout):
 *  - Core: api_domain {@code /v1/Accounts/{account_sid}/Calls.json?PageSize=1} (Basic token)
 *  - Calls: calls_base_url {@code /v1/Accounts/{calls_account_id}/Calls.json?PageSize=1} (Basic key:token)
 *  - VoiceBot: known VoiceBot base {@code /accounts/{id}/voicebots?limit=1} (Basic key:token)
 *  - Admin: known Admin base (VoiceBot base at /api/v1) {@code /accounts/{voicebot_account_id}/personas?limit=1} (Basic admin user:password)
 *  - Tools Server: known Tools Server base {@code /tools/api/v1/tenants/{tenant}/tools?limit=1} (Basic key:token)
 *  - AI Assist: known AI Assist base (host + context path) {@code /v1/accounts/{sid}/ai-assistants?limit=1} (same auth the tools use)
 * Core and Calls are restricted to api.exotel.com / api.in.exotel.com. The other products are probed only on
 * the known API bases configured by the operator (scheme + host + base path, see application.properties). A
 * caller-supplied base URL (or ai_assist_context_path) must equal a known base after normalisation (lower-case
 * host, no trailing slash), otherwise the request is refused with a 400 naming the field; when the caller
 * supplies none, the known bases are tried in order. The verifier builds the full probe URL itself, so no
 * caller-supplied path ever reaches it. A 404 means the account or tenant is unknown and is REJECTED for
 * every product.
 *
 * Unverifiable sets (no safe probe): CQA (the CQA API has no read-only call that works with the API key,
 * CEC-1442) and AI Assist with only Auth0 client credentials (the bearer is minted from an external token
 * endpoint). They never count towards login and do not block a request that has another verified set
 * (MSG-2805).
 *
 * Throttling (MSG-2807): every live probe (a cache miss) costs one token from the caller's bucket and one
 * from a global bucket, and one request may make at most {@code maxProbesPerRequest} probes. Over either
 * limit the result is THROTTLED with a Retry-After and no probe is made; a retry continues from the cache.
 * Cached results cost nothing, so a caller whose credentials are cached is never throttled. Concurrent
 * checks of the same cache key share one probe (single-flight). The REJECTED and UNAVAILABLE messages
 * returned to the caller are generic, so the server cannot be used to learn which of several credential
 * sets Exotel accepts; the set is logged and kept in {@link Result#detail()}.
 */
@Component
public class ExotelCredentialVerifier {

    public enum Outcome { VALID, REJECTED, UNAVAILABLE, THROTTLED }

    /** Shown to the caller when Exotel rejected a set; deliberately does not say which set (MSG-2807). */
    static final String REJECTED_MESSAGE = "The credentials in the Authorization header were not accepted. "
            + "Check each credential set you supplied.";
    static final String UNAVAILABLE_MESSAGE = "The credentials could not be verified right now; retry shortly";
    static final String THROTTLED_MESSAGE = "Too many credential checks; retry after the number of seconds in Retry-After";

    /**
     * Result of checking a whole header. {@code message} is safe to return to the caller; {@code detail}
     * names the set at fault and is for logs and tests only. {@code retryAfterSeconds} is set for THROTTLED.
     */
    public record Result(Outcome outcome, String message, String detail, long retryAfterSeconds) {
        static final Result VALID = new Result(Outcome.VALID, null, null, 0);
    }

    /** Verification limits (MSG-2807). Refill rates are tokens per minute. */
    public record Limits(int clientCapacity, int clientRefillPerMinute, int globalCapacity, int globalRefillPerMinute,
                         int maxProbesPerRequest) {
        public static final Limits DEFAULTS = new Limits(20, 10, 100, 300, 6);

        public Limits {
            if (maxProbesPerRequest < 1) throw new IllegalArgumentException("maxProbesPerRequest must be at least 1");
        }
    }

    /** Thrown inside one verification when it must stop: probe cap reached or a bucket is empty. */
    private static final class Throttled extends RuntimeException {
        final long retryAfterSeconds;

        Throttled(long retryAfterSeconds, String reason) {
            super(reason, null, false, false);
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    /** One header's verification: who is asking and how many live probes it has made. */
    private final class Attempt {
        final String client;
        int probes;

        Attempt(String client) {
            this.client = client;
        }

        /** Charges one live probe against the per-request cap and the buckets, or throws Throttled. */
        void charge() {
            if (++probes > limits.maxProbesPerRequest()) {
                throw new Throttled(1, "more than " + limits.maxProbesPerRequest() + " probes in one request");
            }
            long waitMs = buckets.tryAcquire(client);
            if (waitMs > 0) throw new Throttled(Math.max(1, (waitMs + 999) / 1000), "rate limit");
        }
    }

    /** Status code and Content-Type (null if absent) of a probe response. */
    record ProbeResponse(int status, String contentType) {
        static ProbeResponse json(int status) {
            return new ProbeResponse(status, "application/json");
        }
    }

    /** Performs the GET with the given headers and returns the response. Replaceable in tests. */
    @FunctionalInterface
    interface StatusProbe {
        ProbeResponse get(String url, Map<String, String> headers) throws IOException, InterruptedException;
    }

    private static final Logger logger = LoggerFactory.getLogger(ExotelCredentialVerifier.class);

    /** The only hosts credentials are ever sent to for verification when the caller names one. */
    static final Set<String> VERIFICATION_HOSTS = ExotelHostGuard.API_HOSTS;

    static final String DEFAULT_VOICEBOT_BASE_URL = "https://voicebot.in.exotel.com/voicebot/api/v2";
    static final String DEFAULT_TOOLS_SERVER_BASE_URL = "https://tools-server-prod.mum1.exotel.com";
    /** Same default as AiAssistService.CONTEXT_PATH. */
    private static final String DEFAULT_AI_ASSIST_CONTEXT_PATH = "ai-assist/api";
    static final String DEFAULT_AI_ASSIST_BASE_URL = "https://ai-assist.in.exotel.com/" + DEFAULT_AI_ASSIST_CONTEXT_PATH;

    static final long POSITIVE_TTL_MS = 5 * 60_000L;
    static final long NEGATIVE_TTL_MS = 30_000L;
    static final int MAX_CACHE_ENTRIES = 5_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private record CacheEntry(Outcome outcome, long expiresAtMs) {}

    private final String defaultApiDomain;
    private final String defaultCallsBaseUrl;
    private final List<String> voicebotBases;
    private final List<String> adminBases;
    private final List<String> toolsServerBases;
    private final List<String> aiAssistBases;
    private final StatusProbe probe;
    private final LongSupplier clock;
    private final Limits limits;
    private final TokenBuckets buckets;
    private final Map<String, CompletableFuture<Outcome>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    /**
     * The four lists are comma-separated API bases (scheme + host + base path) from operator configuration.
     * A blank list falls back to the service default; a blank Admin list is derived from the VoiceBot list.
     */
    @Autowired
    public ExotelCredentialVerifier(
            @Value("${exotel.auth.verify.api.domain:https://api.exotel.com}") String defaultApiDomain,
            @Value("${exotel.voicebot.calls.base.url:https://api.in.exotel.com}") String defaultCallsBaseUrl,
            @Value("${exotel.auth.verify.voicebot.api.bases:${exotel.voicebot.base.url:" + DEFAULT_VOICEBOT_BASE_URL + "}}")
            String voicebotBases,
            @Value("${exotel.auth.verify.admin.api.bases:}") String adminBases,
            @Value("${exotel.auth.verify.tools.server.api.bases:${exotel.tools.server.base.url:" + DEFAULT_TOOLS_SERVER_BASE_URL + "}}")
            String toolsServerBases,
            @Value("${exotel.auth.verify.ai.assist.api.bases:" + DEFAULT_AI_ASSIST_BASE_URL + "}") String aiAssistBases,
            @Value("${exotel.auth.ratelimit.client.capacity:20}") int clientCapacity,
            @Value("${exotel.auth.ratelimit.client.refill-per-minute:10}") int clientRefillPerMinute,
            @Value("${exotel.auth.ratelimit.global.capacity:100}") int globalCapacity,
            @Value("${exotel.auth.ratelimit.global.refill-per-minute:300}") int globalRefillPerMinute,
            @Value("${exotel.auth.verify.max-probes-per-request:6}") int maxProbesPerRequest) {
        this(defaultApiDomain, defaultCallsBaseUrl, voicebotBases, adminBases, toolsServerBases, aiAssistBases,
                httpProbe(), System::currentTimeMillis,
                new Limits(clientCapacity, clientRefillPerMinute, globalCapacity, globalRefillPerMinute, maxProbesPerRequest));
    }

    ExotelCredentialVerifier(String defaultApiDomain, String defaultCallsBaseUrl,
                             StatusProbe probe, LongSupplier clock) {
        this(defaultApiDomain, defaultCallsBaseUrl, probe, clock, Limits.DEFAULTS);
    }

    ExotelCredentialVerifier(String defaultApiDomain, String defaultCallsBaseUrl,
                             StatusProbe probe, LongSupplier clock, Limits limits) {
        this(defaultApiDomain, defaultCallsBaseUrl, DEFAULT_VOICEBOT_BASE_URL, "", DEFAULT_TOOLS_SERVER_BASE_URL,
                DEFAULT_AI_ASSIST_BASE_URL, probe, clock, limits);
    }

    ExotelCredentialVerifier(String defaultApiDomain, String defaultCallsBaseUrl, String voicebotBases,
                             String adminBases, String toolsServerBases, String aiAssistBases,
                             StatusProbe probe, LongSupplier clock) {
        this(defaultApiDomain, defaultCallsBaseUrl, voicebotBases, adminBases, toolsServerBases, aiAssistBases,
                probe, clock, Limits.DEFAULTS);
    }

    ExotelCredentialVerifier(String defaultApiDomain, String defaultCallsBaseUrl, String voicebotBases,
                             String adminBases, String toolsServerBases, String aiAssistBases,
                             StatusProbe probe, LongSupplier clock, Limits limits) {
        this.defaultApiDomain = defaultApiDomain;
        this.defaultCallsBaseUrl = defaultCallsBaseUrl;
        this.voicebotBases = parseBases(voicebotBases, DEFAULT_VOICEBOT_BASE_URL);
        List<String> admin = parseBases(adminBases, null);
        this.adminBases = admin.isEmpty()
                ? this.voicebotBases.stream().map(ExotelCredentialVerifier::toAdminBase).distinct().toList()
                : admin.stream().map(ExotelCredentialVerifier::toAdminBase).distinct().toList();
        this.toolsServerBases = parseBases(toolsServerBases, DEFAULT_TOOLS_SERVER_BASE_URL);
        this.aiAssistBases = parseBases(aiAssistBases, DEFAULT_AI_ASSIST_BASE_URL);
        this.probe = probe;
        this.clock = clock;
        this.limits = limits;
        this.buckets = new TokenBuckets(limits.clientCapacity(), limits.clientRefillPerMinute(),
                limits.globalCapacity(), limits.globalRefillPerMinute(), clock);
    }

    /** Splits a comma-separated list of operator-configured bases into normalised https bases. */
    private static List<String> parseBases(String csv, String fallback) {
        String source = csv == null || csv.isBlank() ? fallback : csv;
        if (source == null) return List.of();
        List<String> bases = new ArrayList<>();
        for (String part : source.split(",")) {
            if (part.isBlank()) continue;
            String base = normalizeBase(part);
            if (!base.startsWith("https://")) {
                throw new IllegalStateException("Configured Exotel API base must be an https URL: " + base);
            }
            if (!bases.contains(base)) bases.add(base);
        }
        return List.copyOf(bases);
    }

    /** Lower-case scheme and host, default port dropped, trailing slashes stripped. Query/fragment are dropped. */
    static String normalizeBase(String url) {
        URI uri = URI.create(url.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        String portPart = port == -1 || ("https".equals(scheme) && port == 443) ? "" : ":" + port;
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
        return scheme + "://" + host + portPart + path;
    }

    private static String toAdminBase(String voicebotBase) {
        return voicebotBase.replaceFirst("/api/v\\d+$", "/api/v1");
    }

    /**
     * Pins every caller-supplied base URL that is present, whichever credential sets the header carries:
     * api_domain and calls_base_url must be one of {@link #VERIFICATION_HOSTS}; voicebot_base_url,
     * tools_server_base_url and ai_assist_base_url (+ ai_assist_context_path) must equal a known base of
     * the operator. Runs on every request before any tool, so a field can never reach a request URL
     * unchecked. Throws InvalidCredentialException naming the field (never the value).
     */
    void requireKnownBaseUrls(AuthCredentials c) {
        if (c.getApiDomain() != null) ExotelHostGuard.requireApiHost("api_domain", c.getApiDomain());
        if (c.getCallsBaseUrl() != null) ExotelHostGuard.requireApiHost("calls_base_url", c.getCallsBaseUrl());
        if (c.getVoicebotBaseUrl() != null) {
            String normalized = normalizeBase(ExotelHostGuard.requireAllowedBaseUrl("voicebot_base_url", c.getVoicebotBaseUrl()));
            if (!voicebotBases.contains(normalized) && !adminBases.contains(normalized)) {
                throw new ExotelHostGuard.InvalidCredentialException(
                        "voicebot_base_url must be one of the Exotel VoiceBot API bases");
            }
        }
        if (c.getToolsServerBaseUrl() != null) {
            requireKnownBase("tools_server_base_url", "Tools Server", c.getToolsServerBaseUrl(), toolsServerBases,
                    UnaryOperator.identity());
        }
        if (c.getAiAssistBaseUrl() != null) aiAssistCandidateBase(c);
        else if (c.getAiAssistContextPath() != null) {
            ExotelHostGuard.requireSafePath("ai_assist_context_path", c.getAiAssistContextPath());
        }
    }

    /** Normalises a caller base and requires it to equal one of {@code known}; returns the normalised base. */
    private String requireKnownBase(String field, String product, String callerBase, List<String> known,
                                    UnaryOperator<String> canonical) {
        String normalized = canonical.apply(normalizeBase(ExotelHostGuard.requireAllowedBaseUrl(field, callerBase)));
        if (!known.contains(normalized)) {
            throw new ExotelHostGuard.InvalidCredentialException(
                    field + " must be one of the Exotel " + product + " API bases");
        }
        return normalized;
    }

    /** Caller AI Assist base + context path (default ai-assist/api) as a normalised known base, or InvalidCredentialException. */
    private String aiAssistCandidateBase(AuthCredentials c) {
        String callerBase = normalizeBase(ExotelHostGuard.requireAllowedBaseUrl("ai_assist_base_url", c.getAiAssistBaseUrl()));
        String context = c.getAiAssistContextPath() != null
                ? ExotelHostGuard.requireSafePath("ai_assist_context_path", c.getAiAssistContextPath())
                : DEFAULT_AI_ASSIST_CONTEXT_PATH;
        context = context.replaceAll("^/+|/+$", "");
        String candidate = callerBase + (context.isEmpty() ? "" : "/" + context);
        if (!aiAssistBases.contains(candidate)) {
            boolean contextIsTheProblem = c.getAiAssistContextPath() != null
                    && aiAssistBases.contains(callerBase + "/" + DEFAULT_AI_ASSIST_CONTEXT_PATH);
            throw new ExotelHostGuard.InvalidCredentialException(contextIsTheProblem
                    ? "ai_assist_context_path must be one of the Exotel AI Assist API paths"
                    : "ai_assist_base_url must be one of the Exotel AI Assist API bases");
        }
        return candidate;
    }

    /**
     * Checks every credential set present in the header. REJECTED if a present set is incomplete, is
     * rejected by Exotel, or if no set could be verified (none present, or only unverifiable ones such as CQA).
     * UNAVAILABLE if a probe could not be completed. VALID only if every present set passed or is
     * unverifiable and at least one set was verified. THROTTLED if the probe cap or a bucket ran out (MSG-2807).
     * Shape errors (no set, incomplete set, unverifiable-only) name the set, never a value; the REJECTED and
     * UNAVAILABLE messages from live probes are generic and the set is in {@link Result#detail()}.
     *
     * @param client the caller's key for the per-client bucket (the client IP, see AuthFilter.clientKey)
     */
    public Result verify(AuthCredentials creds, String client) {
        requireKnownBaseUrls(creds);
        List<CredentialSet> present = creds.presentSets();
        if (present.isEmpty()) {
            return rejected("Authorization header must carry at least one complete credential set (token + account_sid, "
                    + "voicebot_*, calls_*, tools_server_*, ai_assist_*, admin_*)");
        }
        List<String> incomplete = new ArrayList<>();
        for (CredentialSet set : present) {
            if (!creds.isComplete(set)) {
                incomplete.add(set.describe() + " are incomplete. Provide every field of the set or remove the fields you supplied.");
            }
        }
        if (!incomplete.isEmpty()) return rejected(String.join(" ", incomplete));

        Attempt attempt = new Attempt(client);
        Result unavailable = null;
        boolean anyVerified = false;
        List<String> unverifiable = new ArrayList<>();
        try {
            for (CredentialSet set : present) {
                Optional<Outcome> outcome = probeSet(set, creds, attempt);
                if (outcome.isEmpty()) {
                    unverifiable.add(set.describe());
                    continue;
                }
                switch (outcome.get()) {
                    case REJECTED -> {
                        logger.info("Credential check from {}: {} rejected by Exotel", client, set.label());
                        return new Result(Outcome.REJECTED, REJECTED_MESSAGE,
                                set.describe() + " were rejected by Exotel", 0);
                    }
                    case UNAVAILABLE -> {
                        if (unavailable == null) {
                            unavailable = new Result(Outcome.UNAVAILABLE, UNAVAILABLE_MESSAGE,
                                    set.describe() + " could not be verified", 0);
                        }
                    }
                    case VALID -> anyVerified = true;
                    case THROTTLED -> throw new IllegalStateException("a probe never returns THROTTLED");
                }
            }
        } catch (Throttled t) {
            // DEBUG, not WARN: a flood of throttled requests must not control the log volume.
            logger.debug("Credential check from {} throttled: {}", client, t.getMessage());
            return new Result(Outcome.THROTTLED, THROTTLED_MESSAGE, t.getMessage(), t.retryAfterSeconds);
        }
        if (unavailable != null) {
            logger.warn("Credential check from {}: {}", client, unavailable.detail());
            return unavailable;
        }
        if (!anyVerified) {
            return rejected(String.join("; ", unverifiable) + " cannot be used alone because Exotel offers no safe way to "
                    + "verify them. Add a verifiable set such as token + account_sid.");
        }
        return Result.VALID;
    }

    /** Test entry point with a fixed client key; production calls {@link #verify(AuthCredentials, String)}. */
    Result verify(AuthCredentials creds) {
        return verify(creds, "test");
    }

    /** Shape errors: the message names the set (never a value) and needs no probe, so it reveals nothing. */
    private static Result rejected(String message) {
        return new Result(Outcome.REJECTED, message, message, 0);
    }

    /** Probes one set. Empty means the set is unverifiable. May throw InvalidCredentialException naming a field. */
    private Optional<Outcome> probeSet(CredentialSet set, AuthCredentials c, Attempt attempt) {
        return switch (set) {
            case CORE -> Optional.of(verifyAccount(set, "api_domain", c.getApiDomain(), defaultApiDomain,
                    c.getAccountSid(), c.getToken(), attempt));
            case CALLS -> Optional.of(verifyAccount(set, "calls_base_url", c.getCallsBaseUrl(), defaultCallsBaseUrl,
                    c.getCallsAccountId(), c.callsBasicToken(), attempt));
            case VOICEBOT -> {
                String id = safeId("voicebot_account_id", c.getVoicebotAccountId());
                yield Optional.of(probeKnownBases(set, "voicebot_base_url", "VoiceBot", c.getVoicebotBaseUrl(),
                        voicebotBases, UnaryOperator.identity(),
                        base -> base + "/accounts/" + id + "/voicebots?limit=1", basic(c.voicebotBasicToken()), attempt));
            }
            case ADMIN -> {
                String id = safeId("voicebot_account_id", c.getVoicebotAccountId());
                yield Optional.of(probeKnownBases(set, "voicebot_base_url", "VoiceBot Admin", c.getVoicebotBaseUrl(),
                        adminBases, ExotelCredentialVerifier::toAdminBase,
                        base -> base + "/accounts/" + id + "/personas?limit=1", basic(c.adminBasicToken()), attempt));
            }
            case TOOLS_SERVER -> {
                String tenant = safeId("tools_server_tenant_id", c.getToolsServerTenantId());
                yield Optional.of(probeKnownBases(set, "tools_server_base_url", "Tools Server",
                        c.getToolsServerBaseUrl(), toolsServerBases, UnaryOperator.identity(),
                        base -> base + "/tools/api/v1/tenants/" + tenant + "/tools?limit=1",
                        basic(toolsServerBasicToken(c)), attempt));
            }
            case AI_ASSIST -> probeAiAssist(c, attempt);
            case CQA -> Optional.empty();
        };
    }

    private static Map<String, String> basic(String basicToken) {
        return Map.of("Authorization", "Basic " + basicToken);
    }

    /** Same credential resolution as VoiceBotToolsService: tools_server_* first, then voicebot_*. */
    private static String toolsServerBasicToken(AuthCredentials c) {
        String token = c.getToolsServerApiToken() != null ? c.getToolsServerApiToken()
                : c.getVoicebotApiToken() != null ? c.getVoicebotApiToken() : "";
        return Base64.getEncoder().encodeToString(
                (c.getToolsServerApiKey() + ":" + token).getBytes(StandardCharsets.UTF_8));
    }

    /** Uses the same auth precedence as AiAssistService: Basic, then bearer, (client credentials), plus any cookie. */
    private Optional<Outcome> probeAiAssist(AuthCredentials c, Attempt attempt) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (c.aiAssistBasicHeader() != null) {
            headers.put("Authorization", c.aiAssistBasicHeader());
        } else if (c.aiAssistBearerHeader() != null) {
            headers.put("Authorization", c.aiAssistBearerHeader());
        } else if (c.hasAiAssistClientCredentials()) {
            return Optional.empty();
        }
        if (c.getAiAssistSessionCookie() != null) {
            headers.put("Cookie", c.getAiAssistSessionCookie());
        }
        String sid = safeId("ai_assist_account_sid", c.getAiAssistAccountSid());
        Function<String, String> url = base -> base + "/v1/accounts/" + sid + "/ai-assistants?limit=1";
        if (c.getAiAssistBaseUrl() == null) {
            return Optional.of(probeFirstAccepted(CredentialSet.AI_ASSIST, aiAssistBases, url, headers, attempt));
        }
        String candidate = aiAssistCandidateBase(c);
        return Optional.of(probeProduct(CredentialSet.AI_ASSIST, url.apply(candidate), headers, attempt));
    }

    /**
     * Probes a product on a KNOWN base. A caller-supplied base must (after the guard, normalisation and
     * {@code canonical}) equal one of {@code known}; only that base is probed, otherwise
     * InvalidCredentialException names the field. With no caller base the known bases are tried in order.
     */
    private Outcome probeKnownBases(CredentialSet set, String field, String product, String callerBase,
                                    List<String> known, UnaryOperator<String> canonical,
                                    Function<String, String> probeUrl, Map<String, String> headers, Attempt attempt) {
        if (callerBase == null) {
            return probeFirstAccepted(set, known, probeUrl, headers, attempt);
        }
        String normalized = requireKnownBase(field, product, callerBase, known, canonical);
        return probeProduct(set, probeUrl.apply(normalized), headers, attempt);
    }

    /**
     * Tries each known base in order: VALID as soon as one accepts, REJECTED only if every base rejects,
     * UNAVAILABLE if none accepted and at least one could not be verified.
     */
    private Outcome probeFirstAccepted(CredentialSet set, List<String> known,
                                       Function<String, String> probeUrl, Map<String, String> headers,
                                       Attempt attempt) {
        Outcome result = Outcome.REJECTED;
        for (String base : known) {
            Outcome outcome = probeProduct(set, probeUrl.apply(base), headers, attempt);
            if (outcome == Outcome.VALID) return Outcome.VALID;
            if (outcome == Outcome.UNAVAILABLE) result = Outcome.UNAVAILABLE;
        }
        return result;
    }

    /**
     * One probe, through the cache. A cache miss is charged to the attempt (probe cap and buckets, which may
     * throw Throttled) and then joins any in-flight probe of the same key, so concurrent identical checks make
     * one call to Exotel (single-flight). The leader caches the result before leaving the in-flight map.
     */
    private Outcome probeProduct(CredentialSet set, String url, Map<String, String> headers, Attempt attempt) {
        String key = cacheKey(set.name(), url, new TreeMap<>(headers).toString());
        Outcome cached = cacheGet(key);
        if (cached != null) return cached;
        attempt.charge();
        CompletableFuture<Outcome> mine = new CompletableFuture<>();
        CompletableFuture<Outcome> running = inFlight.putIfAbsent(key, mine);
        if (running != null) return running.join();
        Outcome outcome = Outcome.UNAVAILABLE;
        try {
            Outcome finished = cacheGet(key); // a leader may have finished between our cache check and putIfAbsent
            if (finished != null) {
                outcome = finished;
            } else {
                outcome = callExotel(set, url, headers);
                cachePut(key, outcome);
            }
            return outcome;
        } finally {
            mine.complete(outcome);
            inFlight.remove(key, mine);
        }
    }

    /**
     * Verifies one credential set. When the caller named the host, only that host is probed. When the
     * caller did not, the default host is probed first and, if it rejects, the other allowed host too
     * (an account may live in the other region): VALID if either accepts, REJECTED only if every probed
     * host rejects, UNAVAILABLE if none accepted and at least one could not be reached.
     */
    private Outcome verifyAccount(CredentialSet set, String field, String callerValue, String defaultUrl,
                                  String accountSid, String basicToken, Attempt attempt) {
        if (callerValue != null) {
            return verify(set, verificationBaseUrl(field, callerValue, defaultUrl), accountSid, basicToken, attempt);
        }
        String key = cacheKey(set.name(), "any-host:" + defaultUrl, accountSid, basicToken);
        Outcome cached = cacheGet(key);
        if (cached != null) return cached;

        Outcome result = verify(set, defaultUrl, accountSid, basicToken, attempt);
        if (result == Outcome.REJECTED) {
            String defaultHost = URI.create(defaultUrl).getHost();
            for (String host : new java.util.TreeSet<>(VERIFICATION_HOSTS)) {
                if (host.equalsIgnoreCase(defaultHost)) continue;
                Outcome other = verify(set, "https://" + host, accountSid, basicToken, attempt);
                if (other == Outcome.VALID) {
                    result = Outcome.VALID;
                } else if (other == Outcome.UNAVAILABLE) {
                    result = Outcome.UNAVAILABLE;
                }
            }
        }
        cachePut(key, result);
        return result;
    }

    private Outcome cacheGet(String key) {
        long now = clock.getAsLong();
        synchronized (cache) {
            CacheEntry hit = cache.get(key);
            if (hit != null) {
                if (hit.expiresAtMs() > now) return hit.outcome();
                cache.remove(key);
            }
        }
        return null;
    }

    private void cachePut(String key, Outcome outcome) {
        if (outcome == Outcome.UNAVAILABLE) return;
        long ttl = outcome == Outcome.VALID ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS;
        long expires = clock.getAsLong() + ttl;
        synchronized (cache) {
            cache.put(key, new CacheEntry(outcome, expires));
        }
    }

    /**
     * Picks the host the credential check is sent to. The caller never chooses it freely: an absent
     * field uses the operator default; a present field must (after ExotelHostGuard) be exactly one of
     * {@link #VERIFICATION_HOSTS} with no path (ExotelHostGuard.requireApiHost), otherwise
     * InvalidCredentialException naming the field (not the value).
     */
    private static String verificationBaseUrl(String field, String callerValue, String defaultUrl) {
        return callerValue == null ? defaultUrl : ExotelHostGuard.requireApiHost(field, callerValue);
    }

    /** Test entry point: one account probe, charged to a fresh attempt of client "test". */
    Outcome verify(CredentialSet set, String baseUrl, String accountSid, String basicToken) {
        return verify(set, baseUrl, accountSid, basicToken, new Attempt("test"));
    }

    /** Verifies one account. {@code baseUrl} must already be a fixed verification host or operator-set. */
    private Outcome verify(CredentialSet set, String baseUrl, String accountSid, String basicToken, Attempt attempt) {
        String url = baseUrl.replaceAll("/+$", "") + "/v1/Accounts/" + safeId("account id", accountSid)
                + "/Calls.json?PageSize=1";
        return probeProduct(set, url, basic(basicToken), attempt);
    }

    private static String safeId(String field, String value) {
        return ExotelHostGuard.requireSafeId(field, value);
    }

    private Outcome callExotel(CredentialSet set, String url, Map<String, String> headers) {
        try {
            ProbeResponse response = probe.get(url, headers);
            int status = response.status();
            if (status >= 200 && status < 300) {
                if (isJson(response.contentType())) return Outcome.VALID;
                logger.warn("Exotel credential verification inconclusive for {}: HTTP {} with a non-JSON response",
                        set.label(), status);
                return Outcome.UNAVAILABLE;
            }
            if (status == 401 || status == 403 || status == 404) return Outcome.REJECTED;
            logger.warn("Exotel credential verification inconclusive for {}: HTTP {}", set.label(), status);
            return Outcome.UNAVAILABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Exotel credential verification interrupted for {}", set.label());
            return Outcome.UNAVAILABLE;
        } catch (Exception e) {
            logger.warn("Exotel credential verification failed for {}: {}", set.label(), e.getClass().getSimpleName());
            return Outcome.UNAVAILABLE;
        }
    }

    /** True for application/json and any structured-syntax +json media type; parameters are ignored. */
    private static boolean isJson(String contentType) {
        if (contentType == null) return false;
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mediaType.equals("application/json") || mediaType.endsWith("+json");
    }

    private static String cacheKey(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = String.join("\0", parts);
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static StatusProbe httpProbe() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (url, headers) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET();
            headers.forEach(request::header);
            HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
            return new ProbeResponse(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null));
        };
    }
}
