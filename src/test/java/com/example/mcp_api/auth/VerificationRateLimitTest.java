package com.example.mcp_api.auth;

import com.example.mcp_api.auth.ExotelCredentialVerifier.Limits;
import com.example.mcp_api.auth.ExotelCredentialVerifier.Outcome;
import com.example.mcp_api.auth.ExotelCredentialVerifier.ProbeResponse;
import com.example.mcp_api.auth.ExotelCredentialVerifier.Result;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** MSG-2807: throttled credential checks, probe cap per request, single-flight, client key behind the ALB. */
class VerificationRateLimitTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AtomicInteger probes = new AtomicInteger();

    private ExotelCredentialVerifier verifier(Limits limits, Function<String, Integer> statusByUrl) {
        return new ExotelCredentialVerifier("https://api.exotel.com", "https://api.in.exotel.com",
                (url, headers) -> { probes.incrementAndGet(); return ProbeResponse.json(statusByUrl.apply(url)); },
                now::get, limits);
    }

    /** A distinct Core set pinned to one host, so it costs exactly one probe. */
    private static AuthCredentials core(int i) {
        return AuthCredentials.parse("{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acct" + i
                + "\",\"api_domain\":\"https://api.exotel.com\"}");
    }

    @Test
    void burstOfBadCredentialsFromOneClientIsThrottled() {
        ExotelCredentialVerifier v = verifier(new Limits(5, 1, 1000, 1000, 6), url -> 401);
        for (int i = 0; i < 5; i++) {
            assertEquals(Outcome.REJECTED, v.verify(core(i), "198.51.100.7").outcome(), "attempt " + i);
        }
        Result throttled = v.verify(core(99), "198.51.100.7");
        assertEquals(Outcome.THROTTLED, throttled.outcome());
        assertTrue(throttled.retryAfterSeconds() >= 1, "Retry-After must be at least 1 s");
        assertEquals(5, probes.get(), "a throttled attempt makes no probe");

        // Another client is unaffected.
        assertEquals(Outcome.REJECTED, v.verify(core(100), "203.0.113.9").outcome());
    }

    @Test
    void throttledClientIsLetBackInAfterRefill() {
        ExotelCredentialVerifier v = verifier(new Limits(2, 1, 1000, 1000, 6), url -> 401);
        v.verify(core(1), "c");
        v.verify(core(2), "c");
        Result throttled = v.verify(core(3), "c");
        assertEquals(Outcome.THROTTLED, throttled.outcome());
        now.addAndGet(throttled.retryAfterSeconds() * 1000L);
        assertEquals(Outcome.REJECTED, v.verify(core(3), "c").outcome());
    }

    @Test
    void globalBucketThrottlesManyClients() {
        ExotelCredentialVerifier v = verifier(new Limits(100, 100, 3, 1, 6), url -> 401);
        for (int i = 0; i < 3; i++) {
            assertEquals(Outcome.REJECTED, v.verify(core(i), "client-" + i).outcome());
        }
        assertEquals(Outcome.THROTTLED, v.verify(core(3), "client-new").outcome());
        assertEquals(3, probes.get());
    }

    @Test
    void goodCredentialsPassUnderTheLimitAndCachedChecksCostNothing() {
        ExotelCredentialVerifier v = verifier(new Limits(1, 1, 1, 1, 6), url -> 200);
        for (int i = 0; i < 100; i++) {
            assertEquals(Outcome.VALID, v.verify(core(1), "c").outcome(), "call " + i);
        }
        assertEquals(1, probes.get());
    }

    @Test
    void repeatingTheSameBadCredentialsIsServedFromTheCache() {
        ExotelCredentialVerifier v = verifier(new Limits(1, 1, 1000, 1000, 6), url -> 401);
        for (int i = 0; i < 20; i++) {
            assertEquals(Outcome.REJECTED, v.verify(core(1), "c").outcome());
        }
        assertEquals(1, probes.get());
    }

    @Test
    void probesPerRequestAreCappedAndARetryContinuesFromTheCache() {
        // Core with no api_domain: api.exotel.com rejects, api.in.exotel.com accepts (2 probes).
        // Calls on api.in.exotel.com accepts (1 probe). Cap 2 stops the request at the third probe.
        ExotelCredentialVerifier v = verifier(new Limits(100, 100, 100, 100, 2),
                url -> url.startsWith("https://api.exotel.com/v1/Accounts/acme1/") ? 401 : 200);
        AuthCredentials both = AuthCredentials.parse("{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\","
                + "\"calls_api_key\":\"k\",\"calls_api_token\":\"t\",\"calls_account_id\":\"acct9\","
                + "\"calls_base_url\":\"https://api.in.exotel.com\"}");
        Result first = v.verify(both, "c");
        assertEquals(Outcome.THROTTLED, first.outcome());
        assertEquals(2, probes.get());

        assertEquals(Outcome.VALID, v.verify(both, "c").outcome());
        assertEquals(3, probes.get());
    }

    @Test
    void concurrentIdenticalChecksCauseOneProbe() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger slowProbes = new AtomicInteger();
        ExotelCredentialVerifier v = new ExotelCredentialVerifier("https://api.exotel.com", "https://api.in.exotel.com",
                (url, headers) -> {
                    slowProbes.incrementAndGet();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return ProbeResponse.json(200);
                }, now::get, new Limits(100, 100, 100, 100, 6));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch started = new CountDownLatch(threads);
        List<Future<Outcome>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    started.countDown();
                    return v.verify(core(1), "c").outcome();
                }));
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(300); // let every thread reach the probe or the in-flight wait
            release.countDown();
            for (Future<Outcome> r : results) assertEquals(Outcome.VALID, r.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, slowProbes.get());
    }

    // ---- AuthFilter: 429, Retry-After, generic texts, client key ----

    private static MockHttpServletResponse post(AuthFilter filter, String header, String remoteAddr, String xff)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setServletPath("/mcp");
        request.setRemoteAddr(remoteAddr);
        if (xff != null) request.addHeader("X-Forwarded-For", xff);
        request.addHeader("Authorization", header);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static String coreHeader(int i) {
        return "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acct" + i + "\",\"api_domain\":\"https://api.exotel.com\"}";
    }

    @Test
    void filterAnswers429WithRetryAfterAfterTheLimit() throws Exception {
        AuthFilter filter = new AuthFilter(verifier(new Limits(3, 1, 1000, 1000, 6), url -> 401));
        for (int i = 0; i < 3; i++) {
            assertEquals(401, post(filter, coreHeader(i), "10.22.1.5", "198.51.100.7").getStatus());
        }
        MockHttpServletResponse throttled = post(filter, coreHeader(50), "10.22.1.5", "198.51.100.7");
        assertEquals(429, throttled.getStatus());
        String retryAfter = throttled.getHeader("Retry-After");
        assertNotNull(retryAfter);
        assertTrue(Integer.parseInt(retryAfter) >= 1);
        assertTrue(throttled.getContentAsString().contains("too_many_requests"));
        assertEquals(3, probes.get());

        // A spoofed first XFF entry does not buy a fresh bucket: only the ALB-appended last entry counts.
        assertEquals(429, post(filter, coreHeader(51), "10.22.1.5", "192.0.2.1, 198.51.100.7").getStatus());
        // A different real client is fine.
        assertEquals(401, post(filter, coreHeader(52), "10.22.1.5", "203.0.113.9").getStatus());
    }

    @Test
    void rejected401AndUnavailable503DoNotNameTheSet() throws Exception {
        AuthFilter rejecting = new AuthFilter(verifier(new Limits(100, 100, 100, 100, 6), url -> 401));
        MockHttpServletResponse r401 = post(rejecting, "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\","
                + "\"calls_api_key\":\"k\",\"calls_api_token\":\"t\",\"calls_account_id\":\"acct9\"}", "10.0.0.1", null);
        assertEquals(401, r401.getStatus());
        assertGeneric(r401.getContentAsString());

        AuthFilter failing = new AuthFilter(verifier(new Limits(100, 100, 100, 100, 6), url -> 500));
        MockHttpServletResponse r503 = post(failing, "{\"voicebot_api_key\":\"vk\",\"voicebot_api_token\":\"vt\","
                + "\"voicebot_account_id\":\"vacct\"}", "10.0.0.1", null);
        assertEquals(503, r503.getStatus());
        assertGeneric(r503.getContentAsString());
    }

    private static void assertGeneric(String body) {
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        for (String leak : new String[] {"core", "calls", "voicebot", "token", "account_sid", "admin", "tools server",
                "ai assist"}) {
            assertFalse(lower.contains(leak), "body names a credential set (" + leak + "): " + body);
        }
    }

    @Test
    void clientKeyTrustsOnlyTheLastForwardedHopFromAPrivatePeer() {
        assertEquals("198.51.100.7", AuthFilter.clientKey(req("10.22.1.5", "192.0.2.1, 198.51.100.7")));
        assertEquals("198.51.100.7", AuthFilter.clientKey(req("127.0.0.1", "198.51.100.7")));
        assertEquals("203.0.113.9", AuthFilter.clientKey(req("203.0.113.9", "198.51.100.7")),
                "XFF from a public peer is caller-controlled and ignored");
        assertEquals("10.22.1.5", AuthFilter.clientKey(req("10.22.1.5", null)));
        assertEquals("10.22.1.5", AuthFilter.clientKey(req("10.22.1.5", " , ")));
    }

    private static MockHttpServletRequest req(String remoteAddr, String xff) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setRemoteAddr(remoteAddr);
        if (xff != null) request.addHeader("X-Forwarded-For", xff);
        return request;
    }
}
