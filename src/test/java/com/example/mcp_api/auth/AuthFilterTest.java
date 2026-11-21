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
    private AuthFilter filter;

    @BeforeEach
    void setUp() {
        probeCalls.set(0);
        probeStatus = 200;
        ExotelCredentialVerifier verifier = new ExotelCredentialVerifier(
                "https://api.exotel.com", "https://api.in.exotel.com",
                (url, token) -> {
                    probeCalls.incrementAndGet();
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

    /** KNOWN LIMITATION (open owner decision, SL-3868): sets Exotel cannot verify are only structurally checked. */
    @Test
    void knownLimitationStructureOnlySetPassesWithoutBeingVerified() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertEquals(200, post("{\"cqa_api_key\":\"k\",\"cqa_account_id\":\"a1\"}", chain).getStatus());
        assertTrue(passed(chain));
        assertEquals(0, probeCalls.get());
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
}
