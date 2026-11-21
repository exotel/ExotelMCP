package com.example.mcp_api.auth;

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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Verifies caller-supplied Exotel API credentials against Exotel itself using a cheap
 * authenticated GET (list calls, PageSize=1) on an allow-listed host.
 *
 * Fails closed: only 2xx is VALID; 401/403/404 is REJECTED; anything else (timeouts, 5xx,
 * 429, unexpected statuses) is UNAVAILABLE and is never treated as success.
 * Results are cached in memory keyed by SHA-256 of the credential values (never the raw values):
 * VALID for a few minutes, REJECTED briefly, UNAVAILABLE not at all. The cache is size-bounded.
 *
 * Verified sets: core (token + account_sid on api_domain) and Calls (calls_api_key/token +
 * calls_account_id on calls_base_url), each only on api.exotel.com or api.in.exotel.com. Other sets are only structurally checked.
 */
@Component
public class ExotelCredentialVerifier {

    public enum Outcome { VALID, REJECTED, UNAVAILABLE }

    /** Performs the HTTP call and returns the status code. Replaceable in tests. */
    @FunctionalInterface
    interface StatusProbe {
        int get(String url, String basicToken) throws IOException, InterruptedException;
    }

    private static final Logger logger = LoggerFactory.getLogger(ExotelCredentialVerifier.class);

    /** The only hosts credentials are ever sent to for verification when the caller names one. */
    static final Set<String> VERIFICATION_HOSTS = Set.of("api.exotel.com", "api.in.exotel.com");

    static final long POSITIVE_TTL_MS = 5 * 60_000L;
    static final long NEGATIVE_TTL_MS = 30_000L;
    static final int MAX_CACHE_ENTRIES = 5_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private record CacheEntry(Outcome outcome, long expiresAtMs) {}

    private final String defaultApiDomain;
    private final String defaultCallsBaseUrl;
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
            @Value("${exotel.voicebot.calls.base.url:https://api.in.exotel.com}") String defaultCallsBaseUrl) {
        this(defaultApiDomain, defaultCallsBaseUrl, httpProbe(), System::currentTimeMillis);
    }

    ExotelCredentialVerifier(String defaultApiDomain, String defaultCallsBaseUrl,
                             StatusProbe probe, LongSupplier clock) {
        this.defaultApiDomain = defaultApiDomain;
        this.defaultCallsBaseUrl = defaultCallsBaseUrl;
        this.probe = probe;
        this.clock = clock;
    }

    /**
     * Verifies every verifiable credential set present in the header. Returns REJECTED if any
     * is rejected, else UNAVAILABLE if any could not be checked, else VALID (also when only
     * non-verifiable sets are present).
     */
    public Outcome verify(AuthCredentials creds) {
        Outcome result = Outcome.VALID;
        if (creds.hasCoreExotelCredentials()) {
            result = worse(result, verify(verificationBaseUrl("api_domain", creds.getApiDomain(), defaultApiDomain),
                    creds.getAccountSid(), creds.getToken()));
        }
        if (creds.hasCallsCredentials()) {
            result = worse(result, verify(verificationBaseUrl("calls_base_url", creds.getCallsBaseUrl(), defaultCallsBaseUrl),
                    creds.getCallsAccountId(), creds.callsBasicToken()));
        }
        return result;
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
    Outcome verify(String baseUrl, String accountSid, String basicToken) {
        String key = cacheKey(baseUrl, accountSid, basicToken);
        long now = clock.getAsLong();
        synchronized (cache) {
            CacheEntry hit = cache.get(key);
            if (hit != null) {
                if (hit.expiresAtMs() > now) return hit.outcome();
                cache.remove(key);
            }
        }

        Outcome outcome = callExotel(baseUrl, accountSid, basicToken);
        if (outcome != Outcome.UNAVAILABLE) {
            long ttl = outcome == Outcome.VALID ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS;
            synchronized (cache) {
                cache.put(key, new CacheEntry(outcome, now + ttl));
            }
        }
        return outcome;
    }

    private Outcome callExotel(String baseUrl, String accountSid, String basicToken) {
        String url = baseUrl.replaceAll("/+$", "") + "/v1/Accounts/" + accountSid + "/Calls.json?PageSize=1";
        try {
            int status = probe.get(url, basicToken);
            if (status >= 200 && status < 300) return Outcome.VALID;
            if (status == 401 || status == 403 || status == 404) return Outcome.REJECTED;
            logger.warn("Exotel credential verification inconclusive: HTTP {}", status);
            return Outcome.UNAVAILABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Exotel credential verification interrupted");
            return Outcome.UNAVAILABLE;
        } catch (Exception e) {
            logger.warn("Exotel credential verification failed: {}", e.getClass().getSimpleName());
            return Outcome.UNAVAILABLE;
        }
    }

    private static Outcome worse(Outcome a, Outcome b) {
        if (a == Outcome.REJECTED || b == Outcome.REJECTED) return Outcome.REJECTED;
        if (a == Outcome.UNAVAILABLE || b == Outcome.UNAVAILABLE) return Outcome.UNAVAILABLE;
        return Outcome.VALID;
    }

    private static String cacheKey(String baseUrl, String accountSid, String basicToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = baseUrl + '\0' + accountSid + '\0' + basicToken;
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
        return (url, basicToken) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Basic " + basicToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        };
    }
}
