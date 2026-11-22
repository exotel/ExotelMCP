package com.example.mcp_api.auth;

import com.example.mcp_api.auth.ExotelCredentialVerifier.Outcome;
import com.example.mcp_api.auth.ExotelCredentialVerifier.ProbeResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ExotelCredentialVerifierTest {

    private static final String CORE = "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\"}";

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> urls = new ArrayList<>();

    private ExotelCredentialVerifier verifierReturning(int status) {
        return new ExotelCredentialVerifier("https://api.exotel.com", "https://api.in.exotel.com",
                (url, headers) -> { calls.incrementAndGet(); urls.add(url); headersSeen.add(headers); return ProbeResponse.json(status); }, now::get);
    }

    @Test
    void validCredentialsAreCachedForPositiveTtl() {
        ExotelCredentialVerifier v = verifierReturning(200);
        AuthCredentials creds = AuthCredentials.parse(CORE);

        assertEquals(Outcome.VALID, v.verify(creds).outcome());
        assertEquals(Outcome.VALID, v.verify(creds).outcome());
        assertEquals(1, calls.get());
        assertEquals("https://api.exotel.com/v1/Accounts/acme1/Calls.json?PageSize=1", urls.get(0));

        now.addAndGet(ExotelCredentialVerifier.POSITIVE_TTL_MS + 1);
        assertEquals(Outcome.VALID, v.verify(creds).outcome());
        assertEquals(2, calls.get());
    }

    @Test
    void usesCallerApiDomainWhenSupplied() {
        ExotelCredentialVerifier v = verifierReturning(200);
        v.verify(AuthCredentials.parse(
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"api_domain\":\"https://api.in.exotel.com\"}"));
        assertTrue(urls.get(0).startsWith("https://api.in.exotel.com/v1/Accounts/acme1/"));
    }

    @Test
    void rejectedCredentialsAreCachedBrieflyThenRechecked() {
        for (int status : new int[] {401, 403, 404}) {
            calls.set(0);
            ExotelCredentialVerifier v = verifierReturning(status);
            AuthCredentials creds = AuthCredentials.parse("{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\","
                    + "\"api_domain\":\"https://api.exotel.com\"}");
            assertEquals(Outcome.REJECTED, v.verify(creds).outcome(), "status " + status);
            assertEquals(Outcome.REJECTED, v.verify(creds).outcome());
            assertEquals(1, calls.get());
            now.addAndGet(ExotelCredentialVerifier.NEGATIVE_TTL_MS + 1);
            v.verify(creds);
            assertEquals(2, calls.get());
        }
    }

    @Test
    void unexpectedStatusesFailClosedAndAreNotCached() {
        for (int status : new int[] {400, 429, 500, 502, 503}) {
            calls.set(0);
            ExotelCredentialVerifier v = verifierReturning(status);
            AuthCredentials creds = AuthCredentials.parse(CORE);
            assertEquals(Outcome.UNAVAILABLE, v.verify(creds).outcome(), "status " + status);
            assertEquals(Outcome.UNAVAILABLE, v.verify(creds).outcome());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void timeoutsAndIoErrorsFailClosed() {
        ExotelCredentialVerifier timeout = new ExotelCredentialVerifier("https://api.exotel.com",
                "https://api.in.exotel.com", (url, token) -> { throw new HttpTimeoutException("slow"); }, now::get);
        assertEquals(Outcome.UNAVAILABLE, timeout.verify(AuthCredentials.parse(CORE)).outcome());

        ExotelCredentialVerifier io = new ExotelCredentialVerifier("https://api.exotel.com",
                "https://api.in.exotel.com", (url, token) -> { throw new IOException("reset"); }, now::get);
        assertEquals(Outcome.UNAVAILABLE, io.verify(AuthCredentials.parse(CORE)).outcome());
    }

    @Test
    void differentCredentialsDoNotShareCacheEntries() {
        ExotelCredentialVerifier v = verifierReturning(200);
        v.verify(AuthCredentials.parse(CORE));
        v.verify(AuthCredentials.parse("{\"token\":\"b3RoZXI6dG9r\",\"account_sid\":\"acme1\"}"));
        v.verify(AuthCredentials.parse("{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme2\"}"));
        assertEquals(3, calls.get());
    }

    @Test
    void callsCredentialsAreVerifiedAgainstCallsBaseUrl() {
        ExotelCredentialVerifier v = verifierReturning(401);
        Outcome outcome = v.verify(AuthCredentials.parse(
                "{\"calls_api_key\":\"k\",\"calls_api_token\":\"t\",\"calls_account_id\":\"acct9\"}")).outcome();
        assertEquals(Outcome.REJECTED, outcome);
        assertEquals("https://api.in.exotel.com/v1/Accounts/acct9/Calls.json?PageSize=1", urls.get(0));
    }

    private static final String VOICEBOT = "{\"voicebot_api_key\":\"vk\",\"voicebot_api_token\":\"vt\",\"voicebot_account_id\":\"vacct\"}";
    private static final String CQA = "{\"cqa_api_key\":\"k\",\"cqa_account_id\":\"a\"}";

    /** Probe that answers by URL substring, in order; first match wins, anything else is 500. */
    private ExotelCredentialVerifier verifierByUrl(Object... substringStatusPairs) {
        return new ExotelCredentialVerifier("https://api.exotel.com", "https://api.in.exotel.com",
                (url, headers) -> {
                    calls.incrementAndGet();
                    urls.add(url);
                    headersSeen.add(headers);
                    for (int i = 0; i < substringStatusPairs.length; i += 2) {
                        if (url.contains((String) substringStatusPairs[i])) return ProbeResponse.json((Integer) substringStatusPairs[i + 1]);
                    }
                    return ProbeResponse.json(500);
                }, now::get);
    }

    private final List<java.util.Map<String, String>> headersSeen = new ArrayList<>();

    @Test
    void cqaOnlyIsRejectedAsUnverifiable() {
        // MSG-2805: a made-up CQA set must never log anyone in on its own.
        for (int status : new int[] {200, 401}) {
            calls.set(0);
            ExotelCredentialVerifier v = verifierReturning(status);
            ExotelCredentialVerifier.Result r = v.verify(AuthCredentials.parse(CQA));
            assertEquals(Outcome.REJECTED, r.outcome(), "probe status " + status);
            assertTrue(r.detail().startsWith("CQA credentials (cqa_api_key, cqa_account_id) cannot be used alone"));
            assertEquals(0, calls.get(), "CQA has no probe; nothing is sent anywhere");
        }
    }

    @Test
    void cqaWithUnavailableCoreIsUnavailableNotValid() {
        ExotelCredentialVerifier v = verifierReturning(503);
        assertEquals(Outcome.UNAVAILABLE, v.verify(AuthCredentials.parse(
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"cqa_api_key\":\"k\",\"cqa_account_id\":\"a\"}")).outcome());
    }

    @Test
    void cqaWithAiAssistClientCredentialsOnlyIsRejected() {
        ExotelCredentialVerifier v = verifierReturning(200);
        ExotelCredentialVerifier.Result r = v.verify(AuthCredentials.parse(
                "{\"cqa_api_key\":\"k\",\"cqa_account_id\":\"a\",\"ai_assist_base_url\":\"https://ai-assist.in.exotel.com\",\"ai_assist_account_sid\":\"sid1\","
                        + "\"ai_assist_client_id\":\"cid\",\"ai_assist_client_secret\":\"cs\"}"));
        assertEquals(Outcome.REJECTED, r.outcome());
        assertTrue(r.detail().contains("CQA credentials"));
        assertTrue(r.detail().contains("AI Assist credentials"));
        assertTrue(r.detail().contains("cannot be used alone"), r.detail());
        assertEquals(0, calls.get());
    }

    @Test
    void cqaWithRejectedCoreIsRejectedNamingCore() {
        ExotelCredentialVerifier v = verifierReturning(401);
        ExotelCredentialVerifier.Result r = v.verify(AuthCredentials.parse(
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"cqa_api_key\":\"k\",\"cqa_account_id\":\"a\"}"));
        assertEquals(Outcome.REJECTED, r.outcome());
        assertTrue(r.detail().startsWith("Core credentials (token, account_sid) were rejected by Exotel"));
    }

    @Test
    void cqaDoesNotBlockAVerifiedSet() {
        ExotelCredentialVerifier v = verifierReturning(200);
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"cqa_api_key\":\"k\",\"cqa_account_id\":\"a\"}")).outcome());
        assertEquals(1, calls.get());
    }

    @Test
    void madeUpVoicebotOnlyIsRejectedNamingVoicebot() {
        ExotelCredentialVerifier v = verifierReturning(401);
        ExotelCredentialVerifier.Result r = v.verify(AuthCredentials.parse(VOICEBOT));
        assertEquals(Outcome.REJECTED, r.outcome());
        assertTrue(r.detail().startsWith("VoiceBot credentials (voicebot_api_key, voicebot_api_token, voicebot_account_id) were rejected by Exotel"));
        assertFalse(r.detail().contains("vk") || r.detail().contains("vacct"));
        assertEquals("https://voicebot.in.exotel.com/voicebot/api/v2/accounts/vacct/voicebots?limit=1", urls.get(0));
    }

    @Test
    void validVoicebotOnlyIsAccepted() {
        ExotelCredentialVerifier v = verifierReturning(200);
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(VOICEBOT)).outcome());
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("vk:vt".getBytes()),
                headersSeen.get(0).get("Authorization"));
    }

    @Test
    void validCoreWithRejectedVoicebotIsRejectedNamingVoicebot() {
        ExotelCredentialVerifier v = verifierByUrl("/voicebots", 401, "/Calls.json", 200);
        ExotelCredentialVerifier.Result r = v.verify(AuthCredentials.parse(
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"voicebot_api_key\":\"vk\","
                        + "\"voicebot_api_token\":\"vt\",\"voicebot_account_id\":\"vacct\"}"));
        assertEquals(Outcome.REJECTED, r.outcome());
        assertTrue(r.detail().contains("VoiceBot credentials"));
        assertFalse(r.detail().contains("Core"));
    }

    @Test
    void tokenWithoutAccountSidIsRejectedNamingCoreEvenWithAValidOtherSet() {
        ExotelCredentialVerifier v = verifierReturning(200);
        ExotelCredentialVerifier.Result r = v.verify(AuthCredentials.parse(
                "{\"token\":\"a2V5OnRvaw==\",\"voicebot_api_key\":\"vk\",\"voicebot_api_token\":\"vt\","
                        + "\"voicebot_account_id\":\"vacct\"}"));
        assertEquals(Outcome.REJECTED, r.outcome());
        assertTrue(r.detail().contains("Core credentials (token, account_sid) are incomplete"));
        assertEquals(0, calls.get(), "incomplete headers are refused before any probe");
    }

    @Test
    void probeUnavailableForOneSetIsUnavailable() {
        ExotelCredentialVerifier v = verifierByUrl("/Calls.json", 200, "/voicebots", 503);
        ExotelCredentialVerifier.Result r = v.verify(AuthCredentials.parse(
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"voicebot_api_key\":\"vk\","
                        + "\"voicebot_api_token\":\"vt\",\"voicebot_account_id\":\"vacct\"}"));
        assertEquals(Outcome.UNAVAILABLE, r.outcome());
        assertTrue(r.detail().contains("VoiceBot credentials"));
    }

    @Test
    void emptyHeaderHasNoVerifiedSet() {
        ExotelCredentialVerifier v = verifierReturning(200);
        assertEquals(Outcome.REJECTED, v.verify(AuthCredentials.parse("{\"from_number\":\"+911234567890\"}")).outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void toolsServerProbeUsesTenantListAndFallsBackToVoicebotToken() {
        ExotelCredentialVerifier v = verifierReturning(200);
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(
                "{\"voicebot_api_key\":\"vk\",\"voicebot_api_token\":\"vt\",\"voicebot_account_id\":\"vacct\","
                        + "\"tools_server_api_key\":\"tk\",\"tools_server_tenant_id\":\"ten1\","
                        + "\"tools_server_base_url\":\"https://Tools-Server-Prod.mum1.exotel.com/\"}")).outcome());
        assertEquals("https://tools-server-prod.mum1.exotel.com/tools/api/v1/tenants/ten1/tools?limit=1", urls.get(1));
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("tk:vt".getBytes()),
                headersSeen.get(1).get("Authorization"));
    }

    @Test
    void toolsServerDefaultHostIsUsedWhenNotNamed() {
        ExotelCredentialVerifier v = verifierReturning(404);
        ExotelCredentialVerifier.Result r = v.verify(AuthCredentials.parse(
                "{\"tools_server_api_key\":\"tk\",\"tools_server_tenant_id\":\"ten1\"}"));
        assertEquals(Outcome.REJECTED, r.outcome(), "404 means unknown tenant");
        assertTrue(r.detail().contains("Tools Server credentials"));
        assertTrue(urls.get(0).startsWith("https://tools-server-prod.mum1.exotel.com/tools/api/v1/tenants/ten1/tools"));
    }

    @Test
    void adminProbeListsPersonasOnTheV1VoicebotApi() {
        ExotelCredentialVerifier v = verifierReturning(200);
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(
                "{\"admin_username\":\"adm\",\"admin_password\":\"pw\",\"voicebot_account_id\":\"vacct\"}")).outcome());
        assertEquals("https://voicebot.in.exotel.com/voicebot/api/v1/accounts/vacct/personas?limit=1", urls.get(0));
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("adm:pw".getBytes()),
                headersSeen.get(0).get("Authorization"));
    }

    @Test
    void adminWithoutAccountIdIsIncomplete() {
        ExotelCredentialVerifier v = verifierReturning(200);
        ExotelCredentialVerifier.Result r = v.verify(AuthCredentials.parse("{\"admin_username\":\"adm\",\"admin_password\":\"pw\"}"));
        assertEquals(Outcome.REJECTED, r.outcome());
        assertTrue(r.detail().contains("Admin credentials"));
        assertEquals(0, calls.get());
    }

    @Test
    void aiAssistBasicBearerAndCookieModesAreProbed() {
        String base = "\"ai_assist_base_url\":\"https://ai-assist.in.exotel.com\",\"ai_assist_account_sid\":\"sid1\"";
        ExotelCredentialVerifier v = verifierReturning(200);
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(
                "{" + base + ",\"ai_assist_auth_key\":\"k\",\"ai_assist_auth_secret\":\"s\"}")).outcome());
        assertEquals("https://ai-assist.in.exotel.com/ai-assist/api/v1/accounts/sid1/ai-assistants?limit=1", urls.get(0));
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("k:s".getBytes()),
                headersSeen.get(0).get("Authorization"));

        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(
                "{" + base + ",\"ai_assist_auth_token\":\"jwt1\",\"ai_assist_context_path\":\"/ai-assist/api/\"}")).outcome());
        assertEquals("https://ai-assist.in.exotel.com/ai-assist/api/v1/accounts/sid1/ai-assistants?limit=1", urls.get(1));
        assertEquals("Bearer jwt1", headersSeen.get(1).get("Authorization"));

        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(
                "{" + base + ",\"ai_assist_session_cookie\":\"sess=1\"}")).outcome());
        assertEquals("sess=1", headersSeen.get(2).get("Cookie"));
        assertFalse(headersSeen.get(2).containsKey("Authorization"));
    }

    @Test
    void aiAssistClientCredentialsOnlyIsUnverifiable() {
        ExotelCredentialVerifier v = verifierReturning(200);
        ExotelCredentialVerifier.Result r = v.verify(AuthCredentials.parse(
                "{\"ai_assist_base_url\":\"https://ai-assist.in.exotel.com\",\"ai_assist_account_sid\":\"sid1\","
                        + "\"ai_assist_client_id\":\"cid\",\"ai_assist_client_secret\":\"cs\"}"));
        assertEquals(Outcome.REJECTED, r.outcome());
        assertTrue(r.detail().contains("AI Assist credentials"));
        assertTrue(r.detail().contains("cannot be used alone"));
        assertEquals(0, calls.get());
    }

    @Test
    void aiAssistClientCredentialsWithVerifiedCorePasses() {
        ExotelCredentialVerifier v = verifierReturning(200);
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"ai_assist_base_url\":\"https://ai-assist.in.exotel.com\",\"ai_assist_account_sid\":\"sid1\","
                        + "\"ai_assist_client_id\":\"cid\",\"ai_assist_client_secret\":\"cs\"}")).outcome());
        assertEquals(1, calls.get(), "only Core is probed");
        assertTrue(urls.get(0).contains("/Calls.json"));
    }

    @Test
    void productHostsMustPassTheGuard() {
        ExotelCredentialVerifier v = verifierReturning(200);
        ExotelHostGuard.InvalidCredentialException e = assertThrows(ExotelHostGuard.InvalidCredentialException.class,
                () -> v.verify(AuthCredentials.parse(
                        "{\"voicebot_api_key\":\"vk\",\"voicebot_api_token\":\"vt\",\"voicebot_account_id\":\"vacct\","
                                + "\"voicebot_base_url\":\"https://evil.io\"}")));
        assertTrue(e.getMessage().contains("voicebot_base_url"));
        assertEquals(0, calls.get());
    }

    @Test
    void sameValuesInDifferentSetsDoNotShareCacheEntries() {
        ExotelCredentialVerifier v = verifierReturning(200);
        v.verify(AuthCredentials.parse("{\"calls_api_key\":\"k\",\"calls_api_token\":\"t\",\"calls_account_id\":\"acct9\","
                + "\"calls_base_url\":\"https://api.in.exotel.com\"}"));
        v.verify(AuthCredentials.parse("{\"token\":\"" + java.util.Base64.getEncoder().encodeToString("k:t".getBytes())
                + "\",\"account_sid\":\"acct9\",\"api_domain\":\"https://api.in.exotel.com\"}"));
        assertEquals(2, calls.get());
    }

    @Test
    void cacheIsBounded() {
        int many = ExotelCredentialVerifier.MAX_CACHE_ENTRIES * 2;
        ExotelCredentialVerifier v = new ExotelCredentialVerifier("https://api.exotel.com", "https://api.in.exotel.com",
                (url, headers) -> { calls.incrementAndGet(); return ProbeResponse.json(200); }, now::get,
                new ExotelCredentialVerifier.Limits(many, many, many, many, 6));
        for (int i = 0; i < ExotelCredentialVerifier.MAX_CACHE_ENTRIES + 10; i++) {
            v.verify(AuthCredentials.CredentialSet.CORE, "https://api.exotel.com", "acct" + i, "tok");
        }
        calls.set(0);
        v.verify(AuthCredentials.CredentialSet.CORE, "https://api.exotel.com", "acct0", "tok");
        assertEquals(1, calls.get(), "oldest entry should have been evicted");
    }

    @Test
    void onlyFixedApiHostsAreVerifiedAgainst() {
        assertEquals(java.util.Set.of("api.exotel.com", "api.in.exotel.com"), ExotelCredentialVerifier.VERIFICATION_HOSTS);
        for (String domain : new String[] {"https://evil.exotel.com", "https://my.exotel.com", "https://exotel.com",
                "https://api.exotel.com.evil.com", "https://api.exotel.com:8443"}) {
            ExotelCredentialVerifier v = verifierReturning(200);
            ExotelHostGuard.InvalidCredentialException e = assertThrows(ExotelHostGuard.InvalidCredentialException.class,
                    () -> v.verify(AuthCredentials.parse("{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"api_domain\":\""
                            + domain + "\"}")), domain);
            assertTrue(e.getMessage().contains("api_domain"));
            assertFalse(e.getMessage().contains("evil"));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void unlistedCallsBaseUrlIsRejectedNamingField() {
        ExotelCredentialVerifier v = verifierReturning(200);
        ExotelHostGuard.InvalidCredentialException e = assertThrows(ExotelHostGuard.InvalidCredentialException.class,
                () -> v.verify(AuthCredentials.parse("{\"calls_api_key\":\"k\",\"calls_api_token\":\"t\","
                        + "\"calls_account_id\":\"acct9\",\"calls_base_url\":\"https://evil.exotel.com\"}")));
        assertTrue(e.getMessage().contains("calls_base_url"));
        assertEquals(0, calls.get());
    }

    @Test
    void callerPathIsRefusedAndHostIsPartOfCacheKey() {
        ExotelCredentialVerifier v = verifierReturning(200);
        v.verify(AuthCredentials.parse(CORE));
        v.verify(AuthCredentials.parse(
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"api_domain\":\"https://api.in.exotel.com\"}"));
        assertEquals(2, calls.get());
        assertEquals("https://api.in.exotel.com/v1/Accounts/acme1/Calls.json?PageSize=1", urls.get(1));

        ExotelHostGuard.InvalidCredentialException e = assertThrows(ExotelHostGuard.InvalidCredentialException.class,
                () -> v.verify(AuthCredentials.parse("{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\","
                        + "\"api_domain\":\"https://api.in.exotel.com/some/path\"}")));
        assertTrue(e.getMessage().contains("api_domain"));
        assertEquals(2, calls.get());
    }

    private ExotelCredentialVerifier verifierByHost(java.util.function.Function<String, Object> byHost) {
        return new ExotelCredentialVerifier("https://api.exotel.com", "https://api.in.exotel.com",
                (url, token) -> {
                    calls.incrementAndGet();
                    urls.add(url);
                    Object r = byHost.apply(java.net.URI.create(url).getHost());
                    if (r instanceof IOException io) throw io;
                    return ProbeResponse.json((Integer) r);
                }, now::get);
    }

    @Test
    void unspecifiedHostValidOnDefaultOnlyProbesOnce() {
        ExotelCredentialVerifier v = verifierByHost(h -> 200);
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(CORE)).outcome());
        assertEquals(1, calls.get());
    }

    @Test
    void unspecifiedHostValidOnOtherRegionOnly() {
        ExotelCredentialVerifier v = verifierByHost(h -> h.equals("api.in.exotel.com") ? 200 : 401);
        AuthCredentials creds = AuthCredentials.parse(CORE);
        assertEquals(Outcome.VALID, v.verify(creds).outcome());
        assertEquals(2, calls.get());
        assertTrue(urls.get(0).startsWith("https://api.exotel.com/"));
        assertTrue(urls.get(1).startsWith("https://api.in.exotel.com/"));
        assertEquals(Outcome.VALID, v.verify(creds).outcome());
        assertEquals(2, calls.get(), "valid result is cached");
    }

    @Test
    void unspecifiedCallsHostValidOnOtherRegionOnly() {
        ExotelCredentialVerifier v = verifierByHost(h -> h.equals("api.exotel.com") ? 200 : 404);
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(
                "{\"calls_api_key\":\"k\",\"calls_api_token\":\"t\",\"calls_account_id\":\"acct9\"}")).outcome());
        assertTrue(urls.get(0).startsWith("https://api.in.exotel.com/"));
        assertTrue(urls.get(1).startsWith("https://api.exotel.com/"));
    }

    @Test
    void unspecifiedHostRejectedOnBothIsRejectedAndCachedBriefly() {
        ExotelCredentialVerifier v = verifierByHost(h -> 401);
        AuthCredentials creds = AuthCredentials.parse(CORE);
        assertEquals(Outcome.REJECTED, v.verify(creds).outcome());
        assertEquals(2, calls.get());
        assertEquals(Outcome.REJECTED, v.verify(creds).outcome());
        assertEquals(2, calls.get());
        now.addAndGet(ExotelCredentialVerifier.NEGATIVE_TTL_MS + 1);
        v.verify(creds);
        assertEquals(4, calls.get());
    }

    @Test
    void unspecifiedHostDefaultRejectsOtherUnreachableIsUnavailableAndNotCached() {
        ExotelCredentialVerifier v = verifierByHost(h -> h.equals("api.exotel.com") ? (Object) 401 : new IOException("down"));
        AuthCredentials creds = AuthCredentials.parse(CORE);
        assertEquals(Outcome.UNAVAILABLE, v.verify(creds).outcome());
        int first = calls.get();
        assertEquals(Outcome.UNAVAILABLE, v.verify(creds).outcome());
        assertTrue(calls.get() > first, "unavailable must not be cached");
    }

    @Test
    void callerSpecifiedHostRejectsWithoutProbingOther() {
        ExotelCredentialVerifier v = verifierByHost(h -> h.equals("api.in.exotel.com") ? 200 : 401);
        assertEquals(Outcome.REJECTED, v.verify(AuthCredentials.parse(
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"api_domain\":\"https://api.exotel.com\"}")).outcome());
        assertEquals(1, calls.get());
    }

    @Test
    void specifiedAndUnspecifiedHostResultsDoNotShareCache() {
        ExotelCredentialVerifier v = verifierByHost(h -> h.equals("api.in.exotel.com") ? 200 : 401);
        assertEquals(Outcome.REJECTED, v.verify(AuthCredentials.parse(
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"api_domain\":\"https://api.exotel.com\"}")).outcome());
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(CORE)).outcome());
    }

    // ---- known API bases (SL-3868 review finding) ----

    private static final String HOSTILE_VOICEBOT_HOST =
            "{\"voicebot_api_key\":\"made-up\",\"voicebot_api_token\":\"made-up\",\"voicebot_account_id\":\"x1\","
                    + "\"voicebot_base_url\":\"https://voicebot.in.exotel.com\"}";

    private void assertRefusedBeforeAnyProbe(ExotelCredentialVerifier v, String header, String field) {
        ExotelHostGuard.InvalidCredentialException e = assertThrows(ExotelHostGuard.InvalidCredentialException.class,
                () -> v.verify(AuthCredentials.parse(header)), header);
        assertEquals(field + " must be one of the Exotel", e.getMessage().substring(0, field.length() + 26));
        assertFalse(e.getMessage().contains("evil") || e.getMessage().contains("made-up"));
        assertEquals(0, calls.get(), "no probe may be sent for an unknown base");
    }

    @Test
    void voicebotBaseOutsideTheKnownBaseIsRefusedWithoutProbing() {
        ExotelCredentialVerifier v = verifierReturning(200);
        assertRefusedBeforeAnyProbe(v, HOSTILE_VOICEBOT_HOST, "voicebot_base_url");
        assertRefusedBeforeAnyProbe(v, HOSTILE_VOICEBOT_HOST.replace("https://voicebot.in.exotel.com",
                "https://other.exotel.com/voicebot/api/v2"), "voicebot_base_url");
        assertRefusedBeforeAnyProbe(v, HOSTILE_VOICEBOT_HOST.replace("https://voicebot.in.exotel.com",
                "https://voicebot.in.exotel.com/voicebot/api/v2/extra"), "voicebot_base_url");
        assertRefusedBeforeAnyProbe(v, HOSTILE_VOICEBOT_HOST.replace("https://voicebot.in.exotel.com",
                "https://voicebot.in.exotel.com/voicebot/api/v1"), "voicebot_base_url");
    }

    @Test
    void adminBaseOutsideTheKnownBaseIsRefusedWithoutProbing() {
        ExotelCredentialVerifier v = verifierReturning(200);
        assertRefusedBeforeAnyProbe(v, "{\"admin_username\":\"a\",\"admin_password\":\"p\",\"voicebot_account_id\":\"x1\","
                + "\"voicebot_base_url\":\"https://voicebot.in.exotel.com\"}", "voicebot_base_url");
        assertRefusedBeforeAnyProbe(v, "{\"admin_username\":\"a\",\"admin_password\":\"p\",\"voicebot_account_id\":\"x1\","
                + "\"voicebot_base_url\":\"https://x.exotel.com/voicebot/api/v2\"}", "voicebot_base_url");
    }

    @Test
    void toolsServerBaseOutsideTheKnownBaseIsRefusedWithoutProbing() {
        ExotelCredentialVerifier v = verifierReturning(200);
        String header = "{\"tools_server_api_key\":\"made-up\",\"tools_server_tenant_id\":\"t1\","
                + "\"tools_server_base_url\":\"%s\"}";
        assertRefusedBeforeAnyProbe(v, header.formatted("https://tools.exotel.com"), "tools_server_base_url");
        assertRefusedBeforeAnyProbe(v, header.formatted("https://tools-server-prod.mum1.exotel.com/ui"),
                "tools_server_base_url");
    }

    @Test
    void aiAssistBaseOrContextOutsideTheKnownBaseIsRefusedWithoutProbing() {
        ExotelCredentialVerifier v = verifierReturning(200);
        String header = "{\"ai_assist_base_url\":\"%s\",\"ai_assist_account_sid\":\"sid1\",\"ai_assist_auth_token\":\"made-up\"%s}";
        assertRefusedBeforeAnyProbe(v, header.formatted("https://ai-assist.other.exotel.com", ""), "ai_assist_base_url");
        assertRefusedBeforeAnyProbe(v, header.formatted("https://ai-assist.in.exotel.com/ui", ""), "ai_assist_base_url");
        assertRefusedBeforeAnyProbe(v, header.formatted("https://ai-assist.in.exotel.com",
                ",\"ai_assist_context_path\":\"web/ui\""), "ai_assist_context_path");
        assertRefusedBeforeAnyProbe(v, header.formatted("https://ai-assist.in.exotel.com",
                ",\"ai_assist_context_path\":\"ai-assist\""), "ai_assist_context_path");
    }

    @Test
    void callerBaseEqualToKnownBaseIsProbedOnThatBaseOnly() {
        ExotelCredentialVerifier v = verifierReturning(200);
        for (String base : new String[] {"https://voicebot.in.exotel.com/voicebot/api/v2",
                "https://VoiceBot.IN.exotel.com/voicebot/api/v2/", "https://voicebot.in.exotel.com:443/voicebot/api/v2"}) {
            calls.set(0);
            urls.clear();
            assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(VOICEBOT.replace("}",
                    ",\"voicebot_base_url\":\"" + base + "\"}").replace("vacct", "va" + base.length()))).outcome(), base);
            assertEquals(1, calls.get());
            assertEquals("https://voicebot.in.exotel.com/voicebot/api/v2/accounts/va" + base.length() + "/voicebots?limit=1",
                    urls.get(0));
        }
        urls.clear();
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(
                "{\"admin_username\":\"a\",\"admin_password\":\"p\",\"voicebot_account_id\":\"adm1\","
                        + "\"voicebot_base_url\":\"https://voicebot.in.exotel.com/voicebot/api/v2\"}")).outcome());
        assertEquals("https://voicebot.in.exotel.com/voicebot/api/v1/accounts/adm1/personas?limit=1", urls.get(0));
    }

    @Test
    void aiAssistCallerBaseWithKnownContextIsProbedOnTheKnownBase() {
        ExotelCredentialVerifier v = verifierReturning(200);
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(
                "{\"ai_assist_base_url\":\"https://AI-Assist.in.exotel.com/\",\"ai_assist_account_sid\":\"sid1\","
                        + "\"ai_assist_auth_token\":\"jwt\",\"ai_assist_context_path\":\"/ai-assist/api/\"}")).outcome());
        assertEquals("https://ai-assist.in.exotel.com/ai-assist/api/v1/accounts/sid1/ai-assistants?limit=1", urls.get(0));
    }

    @Test
    void noCallerBaseProbesTheDefaultKnownBase() {
        ExotelCredentialVerifier v = verifierReturning(200);
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(VOICEBOT)).outcome());
        assertEquals("https://voicebot.in.exotel.com/voicebot/api/v2/accounts/vacct/voicebots?limit=1", urls.get(0));
    }

    @Test
    void htmlAnswerIsUnavailableNotCachedAndNotAccepted() {
        for (String contentType : new String[] {"text/html", "text/html; charset=utf-8", "text/plain", null}) {
            calls.set(0);
            ExotelCredentialVerifier v = new ExotelCredentialVerifier("https://api.exotel.com", "https://api.in.exotel.com",
                    (url, headers) -> { calls.incrementAndGet(); return new ProbeResponse(200, contentType); }, now::get);
            AuthCredentials creds = AuthCredentials.parse(VOICEBOT);
            assertEquals(Outcome.UNAVAILABLE, v.verify(creds).outcome(), String.valueOf(contentType));
            assertEquals(Outcome.UNAVAILABLE, v.verify(creds).outcome());
            assertEquals(2, calls.get(), "non-JSON answers are not cached");
        }
    }

    @Test
    void jsonContentTypesAreAccepted() {
        for (String contentType : new String[] {"application/json", "Application/JSON; charset=UTF-8",
                "application/problem+json", "application/vnd.api+json;version=2"}) {
            ExotelCredentialVerifier v = new ExotelCredentialVerifier("https://api.exotel.com", "https://api.in.exotel.com",
                    (url, headers) -> new ProbeResponse(200, contentType), now::get);
            assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(VOICEBOT)).outcome(), contentType);
        }
    }

    @Test
    void coreAndCallsAnswerWithHtmlIsUnavailable() {
        ExotelCredentialVerifier v = new ExotelCredentialVerifier("https://api.exotel.com", "https://api.in.exotel.com",
                (url, headers) -> new ProbeResponse(200, "text/html"), now::get);
        assertEquals(Outcome.UNAVAILABLE, v.verify(AuthCredentials.parse(CORE)).outcome());
        assertEquals(Outcome.UNAVAILABLE, v.verify(AuthCredentials.parse(
                "{\"calls_api_key\":\"k\",\"calls_api_token\":\"t\",\"calls_account_id\":\"acct9\"}")).outcome());
    }

    @Test
    void configuredBasesReplaceTheDefaultsAndFallBackInOrder() {
        List<String> seen = new ArrayList<>();
        ExotelCredentialVerifier v = new ExotelCredentialVerifier("https://api.exotel.com", "https://api.in.exotel.com",
                "https://voicebot.uat.exotel.com/voicebot/api/v2, https://voicebot.eu.exotel.com/voicebot/api/v2/",
                "", "https://tools.uat.exotel.com", "https://ai.uat.exotel.com/assist",
                (url, headers) -> {
                    seen.add(url);
                    return ProbeResponse.json(url.contains("voicebot.uat") ? 401 : 200);
                }, now::get);

        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(VOICEBOT)).outcome());
        assertEquals(List.of("https://voicebot.uat.exotel.com/voicebot/api/v2/accounts/vacct/voicebots?limit=1",
                "https://voicebot.eu.exotel.com/voicebot/api/v2/accounts/vacct/voicebots?limit=1"), seen);

        seen.clear();
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(VOICEBOT.replace("vacct", "other").replace("}",
                ",\"voicebot_base_url\":\"https://voicebot.eu.exotel.com/voicebot/api/v2\"}"))).outcome());
        assertEquals(1, seen.size());

        assertThrows(ExotelHostGuard.InvalidCredentialException.class, () -> v.verify(AuthCredentials.parse(
                VOICEBOT.replace("}", ",\"voicebot_base_url\":\"https://voicebot.in.exotel.com/voicebot/api/v2\"}"))));

        seen.clear();
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(
                "{\"admin_username\":\"a\",\"admin_password\":\"p\",\"voicebot_account_id\":\"adm1\"}")).outcome());
        assertEquals("https://voicebot.uat.exotel.com/voicebot/api/v1/accounts/adm1/personas?limit=1", seen.get(0));

        seen.clear();
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse(
                "{\"ai_assist_base_url\":\"https://ai.uat.exotel.com\",\"ai_assist_account_sid\":\"sid1\","
                        + "\"ai_assist_auth_token\":\"jwt\",\"ai_assist_context_path\":\"assist\"}")).outcome());
        assertEquals("https://ai.uat.exotel.com/assist/v1/accounts/sid1/ai-assistants?limit=1", seen.get(0));
    }

    @Test
    void allKnownBasesRejectingIsRejected() {
        ExotelCredentialVerifier v = verifierReturning(401);
        assertEquals(Outcome.REJECTED, v.verify(AuthCredentials.parse(VOICEBOT)).outcome());
    }
}
