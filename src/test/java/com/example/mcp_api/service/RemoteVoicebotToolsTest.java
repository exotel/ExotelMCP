package com.example.mcp_api.service;

import com.example.mcp_api.auth.AuthCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The remote voicebot tools against a stand-in for the voicebot application's MCP endpoint (CV-3871). */
class RemoteVoicebotToolsTest {

    private static final String HEADER =
            "{\"voicebot_api_key\":\"key1\",\"voicebot_api_token\":\"tok1\",\"voicebot_account_id\":\"acme\"}";
    private static final String TOOLS = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":["
            + "{\"name\":\"list_voicebots\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"},\"annotations\":{\"readOnlyHint\":true}},"
            + "{\"name\":\"create_persona\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"},\"annotations\":{\"readOnlyHint\":false}},"
            + "{\"name\":\"no_hint\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}},"
            + "{\"name\":\"bad name!\",\"description\":\"d\"}]}}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final List<JsonNode> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger listStatus = new AtomicInteger(200);
    private HttpServer server;
    private RemoteVoicebotTools remote;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode request = mapper.readTree(exchange.getRequestBody().readAllBytes());
            String answer;
            int status = 200;
            if ("tools/list".equals(request.path("method").asText())) {
                status = listStatus.get();
                answer = TOOLS;
            } else {
                calls.add(request.path("params"));
                answer = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}}";
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        remote = new RemoteVoicebotTools();
        remote.enabled = true;
        remote.prefix = "exotel_voicebot_";
        remote.readOnly = true;
        remote.allowTools = "";
        remote.denyTools = "";
        remote.refreshSeconds = 60;
        remote.sslVerify = true;
        remote.defaultBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/voicebot/api/v2";
        remote.init();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthCredentials.REQUEST_ATTRIBUTE, AuthCredentials.parse(HEADER));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        server.stop(0);
    }

    private List<Object> offeredNames() {
        return remote.listTools().stream().map(tool -> tool.get("name")).toList();
    }

    @Test
    void offersOnlyReadOnlyToolsByDefaultWithThePrefix() {
        assertEquals(List.of("exotel_voicebot_list_voicebots"), offeredNames());
        assertEquals("/voicebot/api/v1/accounts/acme/mcp", paths.get(0));
        assertEquals("Basic " + Base64.getEncoder().encodeToString("key1:tok1".getBytes(StandardCharsets.UTF_8)),
                authHeaders.get(0), "the caller's own key is sent");
    }

    @Test
    void allowListAddsAWriteToolAndDenyListRemovesATool() {
        remote.allowTools = "create_persona";
        remote.denyTools = "list_voicebots";

        assertEquals(List.of("exotel_voicebot_create_persona"), offeredNames());
    }

    @Test
    void listIsCachedBetweenRefreshes() {
        offeredNames();
        offeredNames();

        assertEquals(1, paths.size());
    }

    @Test
    void callForwardsTheUnprefixedNameAndArguments() {
        Map<String, Object> result = remote.callTool("exotel_voicebot_list_voicebots", Map.of("limit", 5));

        assertNotNull(result);
        assertEquals(false, result.get("isError"));
        assertEquals("list_voicebots", calls.get(0).path("name").asText());
        assertEquals(5, calls.get(0).path("arguments").path("limit").asInt());
    }

    @Test
    void aToolThatIsNotOfferedIsNotForwarded() {
        assertNull(remote.callTool("exotel_voicebot_create_persona", Map.of("name", "x")));
        assertNull(remote.callTool("exotel_voicebot_does_not_exist", Map.of()));
        assertNull(remote.callTool("exotel_send_sms", Map.of()));
        assertTrue(calls.isEmpty());
    }

    @Test
    void upstreamFailureGivesAnEmptyListNotAnError() {
        listStatus.set(503);

        assertTrue(remote.listTools().isEmpty());
        assertNull(remote.callTool("exotel_voicebot_list_voicebots", Map.of()));
    }

    @Test
    void offOrWithoutVoicebotCredentialsNothingIsCalled() {
        remote.enabled = false;
        assertTrue(remote.listTools().isEmpty());

        remote.enabled = true;
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthCredentials.REQUEST_ATTRIBUTE, AuthCredentials.parse("{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme\"}"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertTrue(remote.listTools().isEmpty());

        assertTrue(paths.isEmpty());
    }
}
