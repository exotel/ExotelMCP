package com.example.mcp_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VoiceBotTeToolsTest {

    private static final ObjectMapper M = new ObjectMapper();
    @Test
    void teBaseUrlFollowsTheSharedHostRule() {
        assertNull(VoiceBotBuilderTools.validateTeBaseUrl("https://te.example.exotel.com/voicebot"));
        assertNotNull(VoiceBotBuilderTools.validateTeBaseUrl("http://te.example.exotel.com/voicebot"));
        assertNotNull(VoiceBotBuilderTools.validateTeBaseUrl("https://evil.example.org/voicebot"));
        assertNotNull(VoiceBotBuilderTools.validateTeBaseUrl("https://user@te.example.exotel.com/voicebot"));
        assertNotNull(VoiceBotBuilderTools.validateTeBaseUrl("https://te.example.exotel.com/voicebot?x=1"));
        // the builder-only allowlist (which also allowed .exotel.in) is gone: one rule for every tool
        assertNotNull(VoiceBotBuilderTools.validateTeBaseUrl("https://te.example.exotel.in/voicebot"));
        assertNotNull(VoiceBotBuilderTools.validateTeBaseUrl("https://te.example.exotel.com:8443/voicebot"));
    }

    /**
     * te_base_url is pinned at login: a caller value that is not a configured base gets 400 on every request.
     * No guide page, setup text or error may tell callers to add it (PR #27 review, blocker 2).
     */
    @Test
    void nothingTellsCallersToSendAnUnconfiguredBaseUrl() throws Exception {
        List<java.nio.file.Path> files = new java.util.ArrayList<>();
        try (var docs = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/resources/mcp-docs"))) {
            docs.filter(p -> p.toString().endsWith(".md")).forEach(files::add);
        }
        files.add(java.nio.file.Path.of("src/main/java/com/example/mcp_api/service/VoiceBotBuilderTools.java"));
        files.add(java.nio.file.Path.of("src/main/java/com/example/mcp_api/service/setup/SetupTools.java"));
        files.add(java.nio.file.Path.of("src/main/resources/application.properties"));
        // Instructions to the caller, not negated ("Do not add te_base_url" is the fix), plus envelope examples.
        java.util.regex.Pattern bad = java.util.regex.Pattern.compile(
                "(?<!not )\\b[Aa]dd (te|voicebot|tools_server)_base_url|callers must send te_base_url"
              + "|\"(te|voicebot|tools_server)_base_url\"\\s*:|Set it only when Exotel gives you one"
              + "|is the environment the key was issued for|[Nn]eeds te_base_url|te_base_url in the envelope unless");
        List<String> hits = new java.util.ArrayList<>();
        for (java.nio.file.Path f : files) {
            String text = java.nio.file.Files.readString(f);
            var m = bad.matcher(text);
            while (m.find()) hits.add(f.getFileName() + ": " + m.group());
        }
        assertTrue(hits.isEmpty(), "callers are told to add te_base_url: " + hits);
        assertTrue(files.size() > 20, "guide pages not found from the working directory");
    }

    @Test
    void teRootDropsTrailingApiVersion() {
        assertEquals("https://te.example.exotel.com/voicebot", VoiceBotBuilderTools.teRoot("https://te.example.exotel.com/voicebot/"));
        assertEquals("https://te.example.exotel.com/voicebot", VoiceBotBuilderTools.teRoot("https://te.example.exotel.com/voicebot/api/v1"));
    }

    @Test
    void pathSuffixRules() {
        assertEquals("orders/status", VoiceBotBuilderTools.requireTePathSuffix("/orders/status"));
        assertThrows(RuntimeException.class, () -> VoiceBotBuilderTools.requireTePathSuffix("../admin"));
        assertThrows(RuntimeException.class, () -> VoiceBotBuilderTools.requireTePathSuffix("orders//x"));
        assertThrows(RuntimeException.class, () -> VoiceBotBuilderTools.requireTePathSuffix("orders?x=1"));
    }

    @Test
    void openApiForPostUsesJsonBody() throws Exception {
        JsonNode schema = M.readTree("{\"type\":\"object\",\"properties\":{\"order_id\":{\"type\":\"string\"}},\"required\":[\"order_id\"]}");
        ObjectNode spec = VoiceBotBuilderTools.openApiForRoute("https://te.example.exotel.com/voicebot/api/v1/accounts/acme/published-routes",
                "orders/status", "POST", "get_order_status", "Order status", schema);
        assertEquals("3.0.0", spec.get("openapi").asText());
        assertEquals("https://te.example.exotel.com/voicebot/api/v1/accounts/acme/published-routes", spec.at("/servers/0/url").asText());
        JsonNode op = spec.at("/paths/~1orders~1status/post");
        assertEquals("get_order_status", op.get("operationId").asText());
        assertEquals(schema, op.at("/requestBody/content/application~1json/schema"));
    }

    @Test
    void openApiForGetUsesQueryParameters() throws Exception {
        JsonNode schema = M.readTree("{\"type\":\"object\",\"properties\":{\"order_id\":{\"type\":\"string\",\"description\":\"Order id\"},"
                + "\"limit\":{\"type\":\"integer\"}},\"required\":[\"order_id\"]}");
        ObjectNode spec = VoiceBotBuilderTools.openApiForRoute("https://x.exotel.com/p", "orders", "GET", "find_order", "Find", schema);
        JsonNode params = spec.at("/paths/~1orders/get/parameters");
        assertEquals(2, params.size());
        assertEquals("order_id", params.get(0).get("name").asText());
        assertTrue(params.get(0).get("required").asBoolean());
        assertEquals("Order id", params.get(0).get("description").asText());
        assertFalse(params.get(0).get("schema").has("description"));
        assertFalse(params.get(1).get("required").asBoolean());
    }
}
