package com.example.mcp_api.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** te_base_url is pinned like voicebot_base_url: only an operator-configured Tools Extension base passes. */
class TeBaseUrlPinTest {

    private static final String VOICEBOT =
            "{\"voicebot_api_key\":\"k\",\"voicebot_api_token\":\"t\",\"voicebot_account_id\":\"acct1\",";
    private static final String KNOWN = "https://te.in.exotel.com/voicebot";

    private static ExotelCredentialVerifier verifier(String teBases) {
        ExotelCredentialVerifier v = new ExotelCredentialVerifier("https://api.exotel.com", "https://api.in.exotel.com",
                (url, headers) -> ExotelCredentialVerifier.ProbeResponse.json(200), System::currentTimeMillis);
        v.setTeBases(teBases);
        return v;
    }

    private static AuthCredentials creds(String teBaseUrl) {
        AuthCredentials c = AuthCredentials.parse(VOICEBOT + "\"te_base_url\":\"" + teBaseUrl + "\"}");
        assertTrue(c.isParsed());
        return c;
    }

    @Test
    void metadataAddressAndForeignHostsAreRefusedByTheHostGuard() {
        for (String bad : new String[] {"http://169.254.169.254/latest/meta-data", "https://169.254.169.254",
                "https://httpbin.org/anything", "https://exotel.com.evil.example", "https://te.in.exotel.com:8443/voicebot"}) {
            AuthCredentials c = creds(bad);
            assertThrows(ExotelHostGuard.InvalidCredentialException.class, c::validateFields, bad);
            assertThrows(ExotelHostGuard.InvalidCredentialException.class, () -> c.effectiveTeBaseUrl(null), bad);
        }
    }

    @Test
    void anUnlistedExotelBaseIsRefused() {
        ExotelHostGuard.InvalidCredentialException e = assertThrows(ExotelHostGuard.InvalidCredentialException.class,
                () -> verifier(KNOWN).requireKnownBaseUrls(creds("https://something.exotel.com/voicebot")));
        assertTrue(e.getMessage().contains("te_base_url"));
        assertFalse(e.getMessage().contains("something"), "value must not be echoed");
    }

    @Test
    void withNoConfiguredBaseEveryCallerValueIsRefused() {
        assertThrows(ExotelHostGuard.InvalidCredentialException.class,
                () -> verifier("").requireKnownBaseUrls(creds(KNOWN)));
    }

    @Test
    void theConfiguredBasePasses() {
        assertDoesNotThrow(() -> verifier(KNOWN).requireKnownBaseUrls(creds(KNOWN + "/")));
        assertDoesNotThrow(() -> verifier("").requireKnownBaseUrls(
                AuthCredentials.parse(VOICEBOT.replaceAll(",$", "}"))));
    }
}
