package com.example.mcp_api.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** SL-3868: every caller-supplied base URL is pinned whenever present, whichever credential sets the header carries. */
class BaseUrlPinTest {

    private static final String CQA = "\"cqa_api_key\":\"made-up\",\"cqa_account_id\":\"made-up\"";
    private static final String VOICEBOT =
            "\"voicebot_api_key\":\"k\",\"voicebot_api_token\":\"t\",\"voicebot_account_id\":\"acct1\"";
    private static final String CALLS = "\"calls_api_key\":\"k\",\"calls_api_token\":\"t\",\"calls_account_id\":\"acct1\"";
    private static final String CORE = "\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\"";
    private static final String AI_CLIENT = "\"ai_assist_account_sid\":\"tenant1\",\"ai_assist_client_id\":\"cid\","
            + "\"ai_assist_client_secret\":\"sec\"";
    private static final String OTHER = "https://something.exotel.com";

    private final AtomicInteger probeCalls = new AtomicInteger();
    private AuthFilter filter;

    @BeforeEach
    void setUp() {
        probeCalls.set(0);
        filter = new AuthFilter(new ExotelCredentialVerifier("https://api.exotel.com", "https://api.in.exotel.com",
                (url, headers) -> {
                    probeCalls.incrementAndGet();
                    return ExotelCredentialVerifier.ProbeResponse.json(200);
                }, System::currentTimeMillis));
    }

    private MockHttpServletResponse post(String json, MockFilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setServletPath("/mcp");
        request.addHeader("Authorization", "{" + json + "}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private void assertRefused(String json, String field) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = post(json, chain);
        assertEquals(400, response.getStatus(), json);
        assertNull(chain.getRequest(), json);
        assertEquals(0, probeCalls.get(), "no outbound call for " + json);
        String body = response.getContentAsString();
        assertTrue(body.contains(field), body);
        assertFalse(body.contains("something.exotel.com"), "value must not be echoed: " + body);
    }

    private void assertAccepted(String json) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = post(json, chain);
        assertEquals(200, response.getStatus(), json + " -> " + response.getContentAsString());
        assertNotNull(chain.getRequest(), json);
    }

    @Test
    void apiDomainOnAnotherHostIsRefusedWhateverSetsArePresent() throws Exception {
        for (String sets : new String[] {CQA, VOICEBOT, CALLS, CORE, CQA + "," + VOICEBOT}) {
            assertRefused(sets + ",\"api_domain\":\"" + OTHER + "\"", "api_domain");
        }
    }

    @Test
    void apiDomainWithPathQueryOrOtherSchemeIsRefused() throws Exception {
        for (String value : new String[] {OTHER + "/internal/admin", "https://api.exotel.com/internal/admin",
                "https://api.exotel.com//", "https://api.exotel.com/?x=1", "http://api.exotel.com",
                "https://api.exotel.com:8443", "https://api.sg.exotel.com"}) {
            assertRefused(CQA + ",\"api_domain\":\"" + value + "\"", "api_domain");
        }
    }

    @Test
    void callsBaseUrlOnAnotherHostIsRefusedWithOnlyAVoicebotSet() throws Exception {
        assertRefused(VOICEBOT + ",\"calls_base_url\":\"" + OTHER + "\"", "calls_base_url");
        assertRefused(CQA + ",\"calls_base_url\":\"" + OTHER + "/x\"", "calls_base_url");
    }

    @Test
    void aiAssistBaseUrlOnAnotherHostIsRefusedWithMadeUpCqaAndClientCredentials() throws Exception {
        assertRefused(CQA + "," + AI_CLIENT + ",\"ai_assist_base_url\":\"" + OTHER + "\"", "ai_assist_base_url");
        assertRefused(CQA + "," + AI_CLIENT + ",\"ai_assist_base_url\":\"https://ai-assist.in.exotel.com\","
                + "\"ai_assist_context_path\":\"internal/admin\"", "ai_assist_context_path");
    }

    @Test
    void voicebotAndToolsServerBasesMustBeKnownEvenWithoutTheirSets() throws Exception {
        assertRefused(CORE + ",\"voicebot_base_url\":\"" + OTHER + "/voicebot/api/v2\"", "voicebot_base_url");
        assertRefused(CQA + ",\"voicebot_base_url\":\"https://voicebot.in.exotel.com/voicebot/api/v9\"", "voicebot_base_url");
        assertRefused(CQA + ",\"tools_server_base_url\":\"" + OTHER + "\"", "tools_server_base_url");
    }

    @Test
    void allowedHostsAndKnownBasesStillPassWithAndWithoutTheMatchingSet() throws Exception {
        assertAccepted(CQA + ",\"api_domain\":\"https://api.exotel.com\"");
        assertAccepted(CQA + ",\"api_domain\":\"https://API.in.exotel.com/\"");
        assertAccepted(CQA + ",\"calls_base_url\":\"https://api.in.exotel.com\"");
        assertAccepted(CORE + ",\"api_domain\":\"https://api.in.exotel.com\"");
        assertAccepted(CALLS + ",\"calls_base_url\":\"https://api.exotel.com\"");
        assertAccepted(CQA + ",\"voicebot_base_url\":\"https://voicebot.in.exotel.com/voicebot/api/v2/\"");
        assertAccepted(VOICEBOT + ",\"voicebot_base_url\":\"https://voicebot.in.exotel.com/voicebot/api/v2\"");
        assertAccepted(CQA + ",\"tools_server_base_url\":\"https://tools-server-prod.mum1.exotel.com\"");
        assertAccepted(CQA + "," + AI_CLIENT + ",\"ai_assist_base_url\":\"https://ai-assist.in.exotel.com\"");
        assertAccepted(CQA + "," + AI_CLIENT + ",\"ai_assist_base_url\":\"https://ai-assist.in.exotel.com\",\"ai_assist_context_path\":\"ai-assist/api\"");
    }

    @Test
    void secondParserCannotYieldADifferentHostThanTheOneChecked() {
        for (String value : new String[] {OTHER, "https://api.exotel.com/internal"}) {
            assertThrows(ExotelHostGuard.InvalidCredentialException.class,
                    () -> ExotelHostGuard.requireApiHost("api_domain", value), value);
        }
        assertEquals("https://api.in.exotel.com", ExotelHostGuard.requireApiHost("api_domain", " https://API.IN.exotel.com/ "));
    }
}
