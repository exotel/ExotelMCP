package com.example.mcp_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VoiceBotTeToolsTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final List<String> ALLOWED = List.of(".exotel.com", ".exotel.in");

    @Test
    void teBaseUrlMustBeHttpsOnAnExotelHost() {
        assertNull(VoiceBotBuilderTools.validateTeBaseUrl("https://te.example.exotel.com/voicebot", ALLOWED));
        assertNotNull(VoiceBotBuilderTools.validateTeBaseUrl("http://te.example.exotel.com/voicebot", ALLOWED));
        assertNotNull(VoiceBotBuilderTools.validateTeBaseUrl("https://evil.example.org/voicebot", ALLOWED));
        assertNotNull(VoiceBotBuilderTools.validateTeBaseUrl("https://user@te.example.exotel.com/voicebot", ALLOWED));
        assertNotNull(VoiceBotBuilderTools.validateTeBaseUrl("https://te.example.exotel.com/voicebot?x=1", ALLOWED));
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
