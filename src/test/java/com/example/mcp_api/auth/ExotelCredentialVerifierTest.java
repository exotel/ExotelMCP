package com.example.mcp_api.auth;

import com.example.mcp_api.auth.ExotelCredentialVerifier.Outcome;
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
                (url, token) -> { calls.incrementAndGet(); urls.add(url); return status; }, now::get);
    }

    @Test
    void validCredentialsAreCachedForPositiveTtl() {
        ExotelCredentialVerifier v = verifierReturning(200);
        AuthCredentials creds = AuthCredentials.parse(CORE);

        assertEquals(Outcome.VALID, v.verify(creds));
        assertEquals(Outcome.VALID, v.verify(creds));
        assertEquals(1, calls.get());
        assertEquals("https://api.exotel.com/v1/Accounts/acme1/Calls.json?PageSize=1", urls.get(0));

        now.addAndGet(ExotelCredentialVerifier.POSITIVE_TTL_MS + 1);
        assertEquals(Outcome.VALID, v.verify(creds));
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
            AuthCredentials creds = AuthCredentials.parse(CORE);
            assertEquals(Outcome.REJECTED, v.verify(creds), "status " + status);
            assertEquals(Outcome.REJECTED, v.verify(creds));
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
            assertEquals(Outcome.UNAVAILABLE, v.verify(creds), "status " + status);
            assertEquals(Outcome.UNAVAILABLE, v.verify(creds));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void timeoutsAndIoErrorsFailClosed() {
        ExotelCredentialVerifier timeout = new ExotelCredentialVerifier("https://api.exotel.com",
                "https://api.in.exotel.com", (url, token) -> { throw new HttpTimeoutException("slow"); }, now::get);
        assertEquals(Outcome.UNAVAILABLE, timeout.verify(AuthCredentials.parse(CORE)));

        ExotelCredentialVerifier io = new ExotelCredentialVerifier("https://api.exotel.com",
                "https://api.in.exotel.com", (url, token) -> { throw new IOException("reset"); }, now::get);
        assertEquals(Outcome.UNAVAILABLE, io.verify(AuthCredentials.parse(CORE)));
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
                "{\"calls_api_key\":\"k\",\"calls_api_token\":\"t\",\"calls_account_id\":\"acct9\"}"));
        assertEquals(Outcome.REJECTED, outcome);
        assertEquals("https://api.in.exotel.com/v1/Accounts/acct9/Calls.json?PageSize=1", urls.get(0));
    }

    /** KNOWN LIMITATION (open owner decision, SL-3868): sets Exotel cannot verify are only structurally checked. */
    @Test
    void knownLimitationNonVerifiableSetsAreNotVerified() {
        ExotelCredentialVerifier v = verifierReturning(500);
        assertEquals(Outcome.VALID, v.verify(AuthCredentials.parse("{\"cqa_api_key\":\"k\",\"cqa_account_id\":\"a\"}")));
        assertEquals(0, calls.get());
    }

    @Test
    void cacheIsBounded() {
        ExotelCredentialVerifier v = verifierReturning(200);
        for (int i = 0; i < ExotelCredentialVerifier.MAX_CACHE_ENTRIES + 10; i++) {
            v.verify("https://api.exotel.com", "acct" + i, "tok");
        }
        calls.set(0);
        v.verify("https://api.exotel.com", "acct0", "tok");
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
    void callerPathIsDroppedAndHostIsPartOfCacheKey() {
        ExotelCredentialVerifier v = verifierReturning(200);
        v.verify(AuthCredentials.parse(CORE));
        v.verify(AuthCredentials.parse(
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"api_domain\":\"https://api.in.exotel.com/some/path\"}"));
        assertEquals(2, calls.get());
        assertEquals("https://api.in.exotel.com/v1/Accounts/acme1/Calls.json?PageSize=1", urls.get(1));
    }
}
