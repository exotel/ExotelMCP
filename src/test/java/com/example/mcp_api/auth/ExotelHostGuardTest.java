package com.example.mcp_api.auth;

import com.example.mcp_api.auth.ExotelHostGuard.InvalidCredentialException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExotelHostGuardTest {

    private static void assertRejected(String url) {
        assertThrows(InvalidCredentialException.class,
                () -> ExotelHostGuard.requireAllowedBaseUrl("api_domain", url), url);
    }

    @Test
    void acceptsExotelComAndSubdomains() {
        assertDoesNotThrow(() -> ExotelHostGuard.requireAllowedBaseUrl("f", "https://exotel.com"));
        assertDoesNotThrow(() -> ExotelHostGuard.requireAllowedBaseUrl("f", "https://api.exotel.com"));
        assertDoesNotThrow(() -> ExotelHostGuard.requireAllowedBaseUrl("f", "https://api.in.exotel.com/"));
        assertDoesNotThrow(() -> ExotelHostGuard.requireAllowedBaseUrl("f", "https://voicebot.in.exotel.com/voicebot/api/v2"));
        assertDoesNotThrow(() -> ExotelHostGuard.requireAllowedBaseUrl("f", "https://tools-server-prod.mum1.exotel.com"));
        assertDoesNotThrow(() -> ExotelHostGuard.requireAllowedBaseUrl("f", "https://cqa-console.in.exotel.com:443"));
    }

    @Test
    void acceptsMixedCaseHost() {
        assertDoesNotThrow(() -> ExotelHostGuard.requireAllowedBaseUrl("f", "https://API.Exotel.COM"));
    }

    @Test
    void rejectsLookAlikeDomains() {
        assertRejected("https://evilexotel.com");
        assertRejected("https://api.evilexotel.com");
        assertRejected("https://exotel.com.evil.io");
        assertRejected("https://api.exotel.com.evil.io");
        assertRejected("https://exotel.in");
        assertRejected("https://api.exotel.org");
    }

    @Test
    void rejectsIpLiteralsLocalhostAndMetadata() {
        assertRejected("https://169.254.169.254");
        assertRejected("https://127.0.0.1");
        assertRejected("https://10.1.2.3");
        assertRejected("https://[::1]");
        assertRejected("https://localhost");
        assertRejected("https://169.254.169.254.nip.io");
        assertRejected("https://exotel.com.169.254.169.254.nip.io");
    }

    @Test
    void rejectsNonHttpsAndMalformed() {
        assertRejected("http://api.exotel.com");
        assertRejected("ftp://api.exotel.com");
        assertRejected("api.exotel.com");
        assertRejected("//api.exotel.com");
        assertRejected("");
        assertRejected("   ");
        assertRejected(null);
        assertRejected("https://");
        assertRejected("https://api.exotel.com\\@127.0.0.1");
        assertRejected("https://api.exotel.com 127.0.0.1");
    }

    @Test
    void rejectsUserinfoPortsTrailingDotQueryAndTraversal() {
        assertRejected("https://user@api.exotel.com");
        assertRejected("https://api.exotel.com@169.254.169.254");
        assertRejected("https://api.exotel.com:evil@127.0.0.1");
        assertRejected("https://api.exotel.com:8443");
        assertRejected("https://api.exotel.com:80");
        assertRejected("https://api.exotel.com.");
        assertRejected("https://.exotel.com");
        assertRejected("https://api.exotel.com?x=1");
        assertRejected("https://api.exotel.com#frag");
        assertRejected("https://api.exotel.com/../latest");
        assertRejected("https://api.exotel.com/%2e%2e/x");
    }

    @Test
    void rejectionMessageDoesNotEchoValue() {
        InvalidCredentialException e = assertThrows(InvalidCredentialException.class,
                () -> ExotelHostGuard.requireAllowedBaseUrl("api_domain", "https://169.254.169.254"));
        assertTrue(e.getMessage().contains("api_domain"));
        assertFalse(e.getMessage().contains("169.254"));
    }

    @Test
    void safeIdAcceptsSimpleIdsAndRejectsTraversal() {
        assertEquals("exotel1", ExotelHostGuard.requireSafeId("account_sid", "exotel1"));
        assertDoesNotThrow(() -> ExotelHostGuard.requireSafeId("account_sid", "a1b2-c3_D4"));
        for (String bad : new String[] {"../../latest/user-data", "a/b", "a.b", "a b", "a?x=1", "a#b", "%2e%2e", "..", "", null}) {
            assertThrows(InvalidCredentialException.class,
                    () -> ExotelHostGuard.requireSafeId("account_sid", bad), String.valueOf(bad));
        }
    }

    @Test
    void safePathAllowsContextPathButNotTraversal() {
        assertDoesNotThrow(() -> ExotelHostGuard.requireSafePath("ai_assist_context_path", "ai-assist/api"));
        assertThrows(InvalidCredentialException.class,
                () -> ExotelHostGuard.requireSafePath("ai_assist_context_path", "../x"));
        assertThrows(InvalidCredentialException.class,
                () -> ExotelHostGuard.requireSafePath("ai_assist_context_path", "a?b"));
    }

    @Test
    void credentialsRejectCallerSuppliedHostsButKeepOperatorDefaults() {
        AuthCredentials ok = AuthCredentials.parse("{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\","
                + "\"api_domain\":\"https://api.in.exotel.com\"}");
        assertDoesNotThrow(ok::validateFields);
        assertEquals("https://api.in.exotel.com", ok.effectiveApiDomain("https://fallback"));

        AuthCredentials none = AuthCredentials.parse("{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\"}");
        assertEquals("http://localhost:9999", none.effectiveVoicebotBaseUrl("http://localhost:9999"));

        for (String field : new String[] {"api_domain", "voicebot_base_url", "calls_base_url",
                "tools_server_base_url", "ai_assist_base_url", "cqa_host", "exotel_portal_url"}) {
            AuthCredentials bad = AuthCredentials.parse("{\"" + field + "\":\"https://169.254.169.254\"}");
            assertThrows(InvalidCredentialException.class, bad::validateFields, field);
        }
        AuthCredentials badVoicebot = AuthCredentials.parse("{\"voicebot_base_url\":\"http://127.0.0.1\"}");
        assertThrows(InvalidCredentialException.class, () -> badVoicebot.effectiveVoicebotBaseUrl("https://x.exotel.com"));
    }

    @Test
    void credentialsRejectUnsafeIdsAndToken() {
        for (String field : new String[] {"account_sid", "voicebot_account_id", "calls_account_id",
                "cqa_account_id", "tools_server_tenant_id", "ai_assist_account_sid"}) {
            AuthCredentials bad = AuthCredentials.parse("{\"" + field + "\":\"../../latest/user-data\"}");
            assertThrows(InvalidCredentialException.class, bad::validateFields, field);
        }
        AuthCredentials badToken = AuthCredentials.parse("{\"token\":\"abc def\\r\\nX: y\"}");
        assertThrows(InvalidCredentialException.class, badToken::validateFields);
    }

    @Test
    void phoneNumbersAcceptNormalValuesAndRejectUrlInjection() {
        for (String ok : new String[] {"+919999999999", "09999999999", " 9999999999 ", "12345"}) {
            assertDoesNotThrow(() -> ExotelHostGuard.requirePhoneNumber("number", ok), ok);
        }
        assertEquals("+919999999999", ExotelHostGuard.requirePhoneNumber("number", " +919999999999 "));
        for (String bad : new String[] {"../../../Calls/connect.json?x=", "9999999999?x=1", "9999999999#", "99999 99999",
                "%2e%2e%2f", "9999999999%3Fx", "..", "", "   ", "1234", "123456789012345678901", "+", "++919999999999",
                "9999999999/../x"}) {
            InvalidCredentialException e = assertThrows(InvalidCredentialException.class,
                    () -> ExotelHostGuard.requirePhoneNumber("number", bad), bad);
            assertTrue(e.getMessage().startsWith("number "));
        }
        assertThrows(InvalidCredentialException.class, () -> ExotelHostGuard.requirePhoneNumber("number", null));
    }

    @Test
    void tenantIdsUseTheSafeIdPattern() {
        assertDoesNotThrow(() -> ExotelHostGuard.requireSafeId("tenantId", "acme_corp-1"));
        for (String bad : new String[] {"../x", "a/b", "a?b=1", "a#b", "a b", "a%2fb", "x/spawn?y"}) {
            assertThrows(InvalidCredentialException.class, () -> ExotelHostGuard.requireSafeId("tenantId", bad), bad);
        }
    }

    @Test
    void queryValuesRejectSeparatorsAndEncodings() {
        for (String ok : new String[] {"stable", "v2", "true", "2024-01-01", "2024-01-01T10:00:00Z", "2024-01-01T10:00:00+05:30", "ready"}) {
            assertTrue(ExotelHostGuard.isSafeQueryValue(ok), ok);
        }
        for (String bad : new String[] {"a&b=c", "a=b", "x#y", "a b", "a%26b", "", "../x", "a?b"}) {
            assertFalse(ExotelHostGuard.isSafeQueryValue(bad), bad);
        }
        assertThrows(InvalidCredentialException.class, () -> ExotelHostGuard.requireSafeQueryValue("version", "a&b"));
    }
}
