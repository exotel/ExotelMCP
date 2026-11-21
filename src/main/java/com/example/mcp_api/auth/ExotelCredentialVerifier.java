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
import java.util.function.LongSupplier;

/**
 * Verifies caller-supplied credentials against Exotel itself, one cheap read-only authenticated GET per
 * credential set present in the Authorization header, on a host the guard allows for that product.
 *
 * A request is authenticated only if at least one set was VERIFIED by a real probe; shape alone never
 * logs anyone in. Every present set must be complete and pass its probe. Fails closed: only 2xx is VALID;
 * 401/403/404 is REJECTED; anything else (timeouts, 5xx, 429, unexpected statuses) is UNAVAILABLE and is
 * never treated as success. Results are cached in memory keyed by SHA-256 of set name, host and credential
 * values (never the raw values): VALID for a few minutes, REJECTED briefly, UNAVAILABLE not at all. The
 * cache is size-bounded.
 *
 * Probes (all GET, no redirects, 3 s connect / 5 s request timeout):
 *  - Core: api_domain {@code /v1/Accounts/{account_sid}/Calls.json?PageSize=1} (Basic token)
 *  - Calls: calls_base_url {@code /v1/Accounts/{calls_account_id}/Calls.json?PageSize=1} (Basic key:token)
 *  - VoiceBot: voicebot_base_url {@code /accounts/{id}/voicebots?limit=1} (Basic key:token)
 *  - Admin: voicebot_base_url (as /api/v1) {@code /accounts/{voicebot_account_id}/personas?limit=1} (Basic admin user:password)
 *  - Tools Server: tools_server_base_url {@code /tools/api/v1/tenants/{tenant}/tools?limit=1} (Basic key:token)
 *  - AI Assist: ai_assist_base_url {@code /{context}/v1/accounts/{sid}/ai-assistants?limit=1} (same auth the tools use)
 * Core and Calls are restricted to api.exotel.com / api.in.exotel.com; the other products use the host the
 * guard already allows (exotel.com or a subdomain) or the service's default. A 404 means the account or
 * tenant is unknown and is REJECTED for every product.
 *
 * Unverifiable sets (no safe probe): CQA (its API-key calls only read a known interaction/job/analysis id;
 * the list calls need a JWT from a username/password login) and AI Assist with only Auth0 client
 * credentials (the bearer is minted from an external token endpoint). They do not count towards login and do
 * not block a request that has another verified set.
 */
@Component
public class ExotelCredentialVerifier {

    public enum Outcome { VALID, REJECTED, UNAVAILABLE }

    /** Result of checking a whole header: the outcome and, unless VALID, a message naming the set(s) at fault. */
    public record Result(Outcome outcome, String message) {
        static final Result VALID = new Result(Outcome.VALID, null);
    }

    /** Performs the GET with the given headers and returns the status code. Replaceable in tests. */
    @FunctionalInterface
    interface StatusProbe {
        int get(String url, Map<String, String> headers) throws IOException, InterruptedException;
    }

    private static final Logger logger = LoggerFactory.getLogger(ExotelCredentialVerifier.class);

    /** The only hosts credentials are ever sent to for verification when the caller names one. */
    static final Set<String> VERIFICATION_HOSTS = Set.of("api.exotel.com", "api.in.exotel.com");

    static final String DEFAULT_VOICEBOT_BASE_URL = "https://voicebot.in.exotel.com/voicebot/api/v2";
    static final String DEFAULT_TOOLS_SERVER_BASE_URL = "https://tools-server-prod.mum1.exotel.com";
    /** Same default as AiAssistService.CONTEXT_PATH. */
    private static final String DEFAULT_AI_ASSIST_CONTEXT_PATH = "ai-assist/api";

    static final long POSITIVE_TTL_MS = 5 * 60_000L;
    static final long NEGATIVE_TTL_MS = 30_000L;
    static final int MAX_CACHE_ENTRIES = 5_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private record CacheEntry(Outcome outcome, long expiresAtMs) {}

    private final String defaultApiDomain;
    private final String defaultCallsBaseUrl;
    private final String defaultVoicebotBaseUrl;
    private final String defaultToolsServerBaseUrl;
    private final StatusProbe probe;
    private final LongSupplier clock;
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    @Autowired
    public ExotelCredentialVerifier(
            @Value("${exotel.auth.verify.api.domain:https://api.exotel.com}") String defaultApiDomain,
            @Value("${exotel.voicebot.calls.base.url:https://api.in.exotel.com}") String defaultCallsBaseUrl,
            @Value("${exotel.voicebot.base.url:" + DEFAULT_VOICEBOT_BASE_URL + "}") String defaultVoicebotBaseUrl,
            @Value("${exotel.tools.server.base.url:" + DEFAULT_TOOLS_SERVER_BASE_URL + "}") String defaultToolsServerBaseUrl) {
        this(defaultApiDomain, defaultCallsBaseUrl, defaultVoicebotBaseUrl, defaultToolsServerBaseUrl,
                httpProbe(), System::currentTimeMillis);
    }

    ExotelCredentialVerifier(String defaultApiDomain, String defaultCallsBaseUrl,
                             StatusProbe probe, LongSupplier clock) {
        this(defaultApiDomain, defaultCallsBaseUrl, DEFAULT_VOICEBOT_BASE_URL, DEFAULT_TOOLS_SERVER_BASE_URL,
                probe, clock);
    }

    ExotelCredentialVerifier(String defaultApiDomain, String defaultCallsBaseUrl, String defaultVoicebotBaseUrl,
                             String defaultToolsServerBaseUrl, StatusProbe probe, LongSupplier clock) {
        this.defaultApiDomain = defaultApiDomain;
        this.defaultCallsBaseUrl = defaultCallsBaseUrl;
        this.defaultVoicebotBaseUrl = defaultVoicebotBaseUrl;
        this.defaultToolsServerBaseUrl = defaultToolsServerBaseUrl;
        this.probe = probe;
        this.clock = clock;
    }

    /**
     * Checks every credential set present in the header. REJECTED if a present set is incomplete, is
     * rejected by Exotel, or if no set could be verified (none present, or only unverifiable ones).
     * UNAVAILABLE if a probe could not be completed. VALID only if every present set passed or is
     * unverifiable and at least one set was verified. The message names the set(s), never a value.
     */
    public Result verify(AuthCredentials creds) {
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

        Result unavailable = null;
        boolean anyVerified = false;
        List<String> unverifiable = new ArrayList<>();
        for (CredentialSet set : present) {
            Optional<Outcome> outcome = probeSet(set, creds);
            if (outcome.isEmpty()) {
                unverifiable.add(set.describe());
                continue;
            }
            switch (outcome.get()) {
                case REJECTED -> {
                    return rejected(set.describe() + " were rejected by Exotel. Remove these fields or correct them.");
                }
                case UNAVAILABLE -> {
                    if (unavailable == null) {
                        unavailable = new Result(Outcome.UNAVAILABLE,
                                set.describe() + " could not be verified right now; retry shortly");
                    }
                }
                case VALID -> anyVerified = true;
            }
        }
        if (unavailable != null) return unavailable;
        if (!anyVerified) {
            return rejected(String.join("; ", unverifiable) + " cannot be used alone because Exotel offers no safe way to "
                    + "verify them. Add a verifiable set such as token + account_sid.");
        }
        return Result.VALID;
    }

    private static Result rejected(String message) {
        return new Result(Outcome.REJECTED, message);
    }

    /** Probes one set. Empty means the set is unverifiable. May throw InvalidCredentialException naming a field. */
    private Optional<Outcome> probeSet(CredentialSet set, AuthCredentials c) {
        return switch (set) {
            case CORE -> Optional.of(verifyAccount(set, "api_domain", c.getApiDomain(), defaultApiDomain,
                    c.getAccountSid(), c.getToken()));
            case CALLS -> Optional.of(verifyAccount(set, "calls_base_url", c.getCallsBaseUrl(), defaultCallsBaseUrl,
                    c.getCallsAccountId(), c.callsBasicToken()));
            case VOICEBOT -> Optional.of(probeProduct(set,
                    productBaseUrl("voicebot_base_url", c.getVoicebotBaseUrl(), defaultVoicebotBaseUrl)
                            + "/accounts/" + safeId("voicebot_account_id", c.getVoicebotAccountId()) + "/voicebots?limit=1",
                    basic(c.voicebotBasicToken())));
            case ADMIN -> Optional.of(probeProduct(set,
                    productBaseUrl("voicebot_base_url", c.getVoicebotBaseUrl(), defaultVoicebotBaseUrl)
                            .replaceFirst("/api/v\\d+$", "/api/v1")
                            + "/accounts/" + safeId("voicebot_account_id", c.getVoicebotAccountId()) + "/personas?limit=1",
                    basic(c.adminBasicToken())));
            case TOOLS_SERVER -> Optional.of(probeProduct(set,
                    productBaseUrl("tools_server_base_url", c.getToolsServerBaseUrl(), defaultToolsServerBaseUrl)
                            + "/tools/api/v1/tenants/" + safeId("tools_server_tenant_id", c.getToolsServerTenantId())
                            + "/tools?limit=1",
                    basic(toolsServerBasicToken(c))));
            case AI_ASSIST -> probeAiAssist(c);
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
    private Optional<Outcome> probeAiAssist(AuthCredentials c) {
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
        String context = c.getAiAssistContextPath() != null
                ? ExotelHostGuard.requireSafePath("ai_assist_context_path", c.getAiAssistContextPath())
                : DEFAULT_AI_ASSIST_CONTEXT_PATH;
        context = context.replaceAll("^/+|/+$", "");
        String url = productBaseUrl("ai_assist_base_url", c.getAiAssistBaseUrl(), null)
                + (context.isEmpty() ? "" : "/" + context)
                + "/v1/accounts/" + safeId("ai_assist_account_sid", c.getAiAssistAccountSid()) + "/ai-assistants?limit=1";
        return Optional.of(probeProduct(CredentialSet.AI_ASSIST, url, headers));
    }

    private Outcome probeProduct(CredentialSet set, String url, Map<String, String> headers) {
        String key = cacheKey(set.name(), url, new TreeMap<>(headers).toString());
        Outcome cached = cacheGet(key);
        if (cached != null) return cached;
        Outcome outcome = callExotel(set, url, headers);
        cachePut(key, outcome);
        return outcome;
    }

    /**
     * Verifies one credential set. When the caller named the host, only that host is probed. When the
     * caller did not, the default host is probed first and, if it rejects, the other allowed host too
     * (an account may live in the other region): VALID if either accepts, REJECTED only if every probed
     * host rejects, UNAVAILABLE if none accepted and at least one could not be reached.
     */
    private Outcome verifyAccount(CredentialSet set, String field, String callerValue, String defaultUrl,
                                  String accountSid, String basicToken) {
        if (callerValue != null) {
            return verify(set, verificationBaseUrl(field, callerValue, defaultUrl), accountSid, basicToken);
        }
        String key = cacheKey(set.name(), "any-host:" + defaultUrl, accountSid, basicToken);
        Outcome cached = cacheGet(key);
        if (cached != null) return cached;

        Outcome result = verify(set, defaultUrl, accountSid, basicToken);
        if (result == Outcome.REJECTED) {
            String defaultHost = URI.create(defaultUrl).getHost();
            for (String host : new java.util.TreeSet<>(VERIFICATION_HOSTS)) {
                if (host.equalsIgnoreCase(defaultHost)) continue;
                Outcome other = verify(set, "https://" + host, accountSid, basicToken);
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
     * {@link #VERIFICATION_HOSTS}, otherwise InvalidCredentialException naming the field (not the value).
     * The URL is rebuilt as https://host so a caller-supplied path is never used.
     */
    private static String verificationBaseUrl(String field, String callerValue, String defaultUrl) {
        if (callerValue == null) return defaultUrl;
        ExotelHostGuard.requireAllowedBaseUrl(field, callerValue);
        String host = URI.create(callerValue.trim()).getHost().toLowerCase(Locale.ROOT);
        if (!VERIFICATION_HOSTS.contains(host)) {
            throw new ExotelHostGuard.InvalidCredentialException(
                    field + " must be one of the Exotel API hosts " + VERIFICATION_HOSTS);
        }
        return "https://" + host;
    }

    /** Verifies one account. {@code baseUrl} must already be a fixed verification host or operator-set. */
    Outcome verify(CredentialSet set, String baseUrl, String accountSid, String basicToken) {
        String url = baseUrl.replaceAll("/+$", "") + "/v1/Accounts/" + safeId("account id", accountSid)
                + "/Calls.json?PageSize=1";
        return probeProduct(set, url, basic(basicToken));
    }

    /**
     * Picks the base URL a product probe is sent to: the operator default when the field is absent, else
     * the caller's value, which must pass ExotelHostGuard (https, exotel.com, port 443). Any base path
     * (for example /voicebot/api/v2) is kept because the product services use it too.
     */
    private static String productBaseUrl(String field, String callerValue, String defaultUrl) {
        String base = callerValue == null ? defaultUrl : ExotelHostGuard.requireAllowedBaseUrl(field, callerValue).trim();
        return base.replaceAll("/+$", "");
    }

    private static String safeId(String field, String value) {
        return ExotelHostGuard.requireSafeId(field, value);
    }

    private Outcome callExotel(CredentialSet set, String url, Map<String, String> headers) {
        try {
            int status = probe.get(url, headers);
            if (status >= 200 && status < 300) return Outcome.VALID;
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
            return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        };
    }
}
