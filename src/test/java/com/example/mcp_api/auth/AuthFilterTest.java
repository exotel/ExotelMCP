package com.example.mcp_api.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AuthFilterTest {

    private static final String CORE = "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\"}";

    private final AtomicInteger probeCalls = new AtomicInteger();
    private int probeStatus;
    private final java.util.Map<String, Integer> statusBySubstring = new java.util.LinkedHashMap<>();
    private AuthFilter filter;

    @BeforeEach
    void setUp() {
        probeCalls.set(0);
        probeStatus = 200;
        statusBySubstring.clear();
        ExotelCredentialVerifier verifier = new ExotelCredentialVerifier(
                "https://api.exotel.com", "https://api.in.exotel.com",
                (url, headers) -> {
                    probeCalls.incrementAndGet();
                    for (var e : statusBySubstring.entrySet()) {
                        if (url.contains(e.getKey())) return e.getValue();
                    }
                    if (probeStatus < 0) throw new java.net.SocketTimeoutException("timeout");
                    return probeStatus;
                },
                System::currentTimeMillis);
        filter = new AuthFilter(verifier);
    }

    private MockHttpServletResponse call(String method, String uri, String authHeader, MockFilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setServletPath(uri);
        if (authHeader != null) request.addHeader("Authorization", authHeader);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private MockHttpServletResponse post(String authHeader, MockFilterChain chain) throws Exception {
        return call("POST", "/mcp", authHeader, chain);
    }

    private static boolean passed(MockFilterChain chain) {
        return chain.getRequest() != null;
    }

    @Test
    void missingHeaderIs401() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertEquals(401, post(null, chain).getStatus());
        assertFalse(passed(chain));
    }

    @Test
    void emptyJsonIs401() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertEquals(401, post("{}", chain).getStatus());
        assertFalse(passed(chain));
        assertEquals(0, probeCalls.get());
    }

    @Test
    void bearerEmptyJsonAndIncompleteSetsAre401() throws Exception {
        for (String header : new String[] {"Bearer {}", "{\"token\":\"a2V5OnRvaw==\"}", "{\"account_sid\":\"acme1\"}",
                "{\"voicebot_api_key\":\"k\"}", "{\"from_number\":\"+911234567890\"}", "not json at all {"}) {
            MockFilterChain chain = new MockFilterChain();
            assertEquals(401, post(header, chain).getStatus(), header);
            assertFalse(passed(chain), header);
        }
        assertEquals(0, probeCalls.get());
    }

    @Test
    void ssrfTargetsAre400AndNeverCallOut() throws Exception {
        for (String header : new String[] {
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"api_domain\":\"https://169.254.169.254\"}",
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"api_domain\":\"https://webhook.site\"}",
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"voicebot_base_url\":\"http://127.0.0.1\"}",
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"../../latest/user-data\"}"}) {
            MockFilterChain chain = new MockFilterChain();
            assertEquals(400, post(header, chain).getStatus(), header);
            assertFalse(passed(chain), header);
        }
        assertEquals(0, probeCalls.get());
    }

    @Test
    void exotelRejectionIs401() throws Exception {
        probeStatus = 401;
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = post(CORE, chain);
        assertEquals(401, response.getStatus());
        assertFalse(passed(chain));
        assertFalse(response.getContentAsString().contains("a2V5OnRvaw=="));
    }

    @Test
    void exotelTimeoutIs503() throws Exception {
        probeStatus = -1;
        MockFilterChain chain = new MockFilterChain();
        assertEquals(503, post(CORE, chain).getStatus());
        assertFalse(passed(chain));
    }

    @Test
    void exotelServerErrorIs503() throws Exception {
        probeStatus = 500;
        MockFilterChain chain = new MockFilterChain();
        assertEquals(503, post(CORE, chain).getStatus());
        assertFalse(passed(chain));
    }

    @Test
    void validCredentialsPassAndSecondRequestUsesCache() throws Exception {
        MockFilterChain first = new MockFilterChain();
        assertEquals(200, post(CORE, first).getStatus());
        assertTrue(passed(first));
        assertInstanceOf(AuthCredentials.class, first.getRequest().getAttribute(AuthCredentials.REQUEST_ATTRIBUTE));

        MockFilterChain second = new MockFilterChain();
        assertEquals(200, post(CORE, second).getStatus());
        assertTrue(passed(second));
        assertEquals(1, probeCalls.get());
    }

    private static final String CALLS_WRONG = "\"calls_api_key\":\"k\",\"calls_api_token\":\"t\",\"calls_account_id\":\"acct9\"";

    private String body(MockHttpServletResponse response) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString())
                .path("message").asText();
    }

    @Test
    void knownLimitationMadeUpCqaOnlyHeaderPassesWithoutAnyOutboundCall() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = post("{\"cqa_api_key\":\"made-up\",\"cqa_account_id\":\"made-up\"}", chain);
        assertEquals(200, response.getStatus());
        assertTrue(passed(chain));
        assertEquals(0, probeCalls.get());
    }

    @Test
    void madeUpVoicebotOnlyHeaderIs401() throws Exception {
        probeStatus = 401;
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = post(
                "{\"voicebot_api_key\":\"made-up\",\"voicebot_api_token\":\"x1\",\"voicebot_account_id\":\"acct1\"}", chain);
        assertEquals(401, response.getStatus());
        assertFalse(passed(chain));
        assertTrue(body(response).contains("VoiceBot credentials"));
        assertFalse(response.getContentAsString().contains("made-up"));
    }

    @Test
    void validVoicebotOnlyHeaderPasses() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertEquals(200, post(
                "{\"voicebot_api_key\":\"k\",\"voicebot_api_token\":\"t\",\"voicebot_account_id\":\"acct1\"}", chain).getStatus());
        assertTrue(passed(chain));
        assertEquals(1, probeCalls.get());
    }

    @Test
    void realCorePlusWrongCallsIs401NamingCallsSet() throws Exception {
        statusBySubstring.put("api.in.exotel.com", 401);
        statusBySubstring.put("/Accounts/acct9/", 401);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = post("{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\"," + CALLS_WRONG + "}", chain);
        assertEquals(401, response.getStatus());
        assertFalse(passed(chain));
        assertEquals("Calls credentials (calls_api_key, calls_api_token, calls_account_id) were rejected by Exotel. "
                + "Remove these fields or correct them.", body(response));
    }

    @Test
    void realCorePlusPlaceholderCallsIs400NamingTheField() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = post("{'token':'a2V5OnRvaw==','account_sid':'acme1',"
                + "'calls_api_key':'YOUR_CALLS_API_KEY','calls_api_token':'YOUR_CALLS_API_TOKEN','calls_account_id':'YOUR_CALLS_ACCOUNT_ID'}", chain);
        assertEquals(400, response.getStatus());
        assertFalse(passed(chain));
        assertEquals("Calls credentials: calls_api_key still holds the sample placeholder. "
                + "Remove this field or fill in your real value.", body(response));
        assertFalse(response.getContentAsString().contains("YOUR_CALLS"));
        assertEquals(0, probeCalls.get());
    }

    @Test
    void everyShippedPlaceholderStyleAndEmptyValuesAre400() throws Exception {
        for (String field : new String[] {"\"from_number\":\"YOUR_FROM_NUMBER\"", "\"api_domain\":\"https://YOUR_SUB_DOMAIN\"",
                "\"caller_id\":\"your_caller_id\"", "\"dlt_temp\":\"<template>\"", "\"dlt_entity\":\"XXXXXXX\"",
                "\"cqa_host\":\"\"", "\"whatsapp_from\":\"  \""}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = post("{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\"," + field + "}", chain);
            assertEquals(400, response.getStatus(), field);
            assertFalse(passed(chain), field);
            String name = field.substring(1, field.indexOf('"', 1));
            assertTrue(body(response).contains(name), field);
        }
        assertEquals(0, probeCalls.get());
    }

    @Test
    void realLookingValuesAreNotTreatedAsPlaceholders() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertEquals(200, post("{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"from_number\":\"+911234567890\","
                + "\"api_domain\":\"https://api.exotel.com\",\"dlt_entity\":\"1101234567890\"}", chain).getStatus());
        assertTrue(passed(chain));
    }

    @Test
    void tokenWithoutAccountSidPlusValidOtherSetIs401NamingCoreSet() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = post("{\"token\":\"a2V5OnRvaw==\",\"voicebot_api_key\":\"k\","
                + "\"voicebot_api_token\":\"t\",\"voicebot_account_id\":\"acct1\"}", chain);
        assertEquals(401, response.getStatus());
        assertFalse(passed(chain));
        assertTrue(body(response).contains("Core credentials (token, account_sid) are incomplete"));
        assertEquals(0, probeCalls.get());
    }

    @Test
    void cqaWithPlaceholderOrEmptyFieldIs400() throws Exception {
        for (String field : new String[] {"\"cqa_api_key\":\"YOUR_CQA_API_KEY\"", "\"cqa_api_key\":\"\""}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = post("{" + field + ",\"cqa_account_id\":\"a1\"}", chain);
            assertEquals(400, response.getStatus(), field);
            assertFalse(passed(chain), field);
        }
        assertEquals(0, probeCalls.get());
    }

    @Test
    void cqaWithRejectedCoreIs401NamingCore() throws Exception {
        statusBySubstring.put("/Calls.json", 401);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = post("{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\","
                + "\"cqa_api_key\":\"k\",\"cqa_account_id\":\"a1\"}", chain);
        assertEquals(401, response.getStatus());
        assertTrue(body(response).contains("Core credentials"));
        assertFalse(passed(chain));
    }

    @Test
    void cqaWithHostOutsideExotelIs400() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = post("{\"cqa_api_key\":\"k\",\"cqa_account_id\":\"a1\","
                + "\"cqa_host\":\"https://evil.example.com\"}", chain);
        assertEquals(400, response.getStatus());
        assertTrue(body(response).contains("cqa_host"));
        assertFalse(passed(chain));
    }

    @Test
    void aiAssistClientCredentialsAloneAre401() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = post("{\"ai_assist_base_url\":\"https://ai-assist.in.exotel.com\",\"ai_assist_account_sid\":\"a1\","
                + "\"ai_assist_client_id\":\"k\",\"ai_assist_client_secret\":\"s\"}", chain);
        assertEquals(401, response.getStatus());
        assertTrue(body(response).contains("cannot be used alone"));
        assertFalse(passed(chain));
    }

    @Test
    void unavailableProbeForOneSetIs503() throws Exception {
        statusBySubstring.put("/voicebots", 503);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = post("{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\","
                + "\"voicebot_api_key\":\"k\",\"voicebot_api_token\":\"t\",\"voicebot_account_id\":\"acct1\"}", chain);
        assertEquals(503, response.getStatus());
        assertEquals("5", response.getHeader("Retry-After"));
        assertTrue(body(response).contains("VoiceBot credentials"));
        assertFalse(passed(chain));
    }

    @Test
    void verifiedCoreWithCqaPasses() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertEquals(200, post("{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\","
                + "\"cqa_api_key\":\"k\",\"cqa_account_id\":\"a1\"}", chain).getStatus());
        assertTrue(passed(chain));
    }

    @Test
    void restControllerPathsAreProtected() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertEquals(401, call("GET", "/send-sms-to-user", "{}", chain).getStatus());
        assertFalse(passed(chain));
        chain = new MockFilterChain();
        assertEquals(401, call("GET", "/get-call-flow-callbacks", null, chain).getStatus());
        assertFalse(passed(chain));
    }

    @Test
    void exotelCallbacksAndPreflightSkipAuth() throws Exception {
        for (String uri : new String[] {"/sms-status-callback/id1/abc", "/call-status/id1/abc"}) {
            MockFilterChain chain = new MockFilterChain();
            assertEquals(200, call("POST", uri, null, chain).getStatus(), uri);
            assertTrue(passed(chain), uri);
        }
        MockFilterChain chain = new MockFilterChain();
        assertEquals(200, call("OPTIONS", "/mcp", null, chain).getStatus());
        assertTrue(passed(chain));
    }

    @Test
    void ambiguousPathsAre400EvenWithCallbackPrefix() throws Exception {
        for (String uri : new String[] {"/call-status/../mcp", "/call-status/%2e%2e/mcp", "/call-status/%2E%2E/mcp",
                "/call-status/..;/mcp", "/sms-status-callback/x/../../mcp", "//mcp", "/call-status/a%2fb/c",
                "/call-status/a%5Cb/c", "/call-status\\a/b", "/call-status/a/b;x=1"}) {
            MockFilterChain chain = new MockFilterChain();
            assertEquals(400, call("POST", uri, null, chain).getStatus(), uri);
            assertFalse(passed(chain), uri);
        }
    }

    @Test
    void callbackShapeAndMethodAreExact() throws Exception {
        for (String[] c : new String[][] {{"GET", "/call-status/id1/abc"}, {"POST", "/call-status/id1"},
                {"POST", "/call-status/id1/abc/extra"}, {"POST", "/call-status/"}, {"POST", "/mcp"},
                {"GET", "/sms-status-callback/id1/abc"}}) {
            MockFilterChain chain = new MockFilterChain();
            assertEquals(401, call(c[0], c[1], null, chain).getStatus(), c[0] + " " + c[1]);
            assertFalse(passed(chain), c[0] + " " + c[1]);
        }
    }

    @Test
    void publicDecisionUsesContainerResolvedPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/call-status/id1/abc");
        request.setServletPath("/mcp");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus());
        assertFalse(passed(chain));
    }

    @Test
    void errorBodyIsValidJson() throws Exception {
        MockHttpServletResponse response = call("POST", "/mcp", "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"x\\\"y\"}",
                new MockFilterChain());
        assertDoesNotThrow(() -> new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString()));
    }

    @Test
    void unlistedApiDomainIs400AndNeverCallsOut() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = post(
                "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\",\"api_domain\":\"https://evil.exotel.com\"}", chain);
        assertEquals(400, response.getStatus());
        assertFalse(passed(chain));
        assertFalse(response.getContentAsString().contains("evil"));
        assertEquals(0, probeCalls.get());
    }

    @Test
    void wellKnownGetWithoutHeaderPassesThrough() throws Exception {
        for (String method : new String[] {"GET", "HEAD"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = call(method, "/.well-known/oauth-authorization-server", null, chain);
            assertEquals(200, response.getStatus());
            assertTrue(passed(chain));
        }
        assertEquals(0, probeCalls.get());
    }

    @Test
    void wellKnownTraversalIs400() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertEquals(400, call("GET", "/.well-known/../mcp", null, chain).getStatus());
        assertFalse(passed(chain));
    }

    @Test
    void wellKnownPostWithoutHeaderIs401() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertEquals(401, call("POST", "/.well-known/x", null, chain).getStatus());
        assertFalse(passed(chain));
    }
}
