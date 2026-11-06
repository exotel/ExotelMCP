package com.example.mcp_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpWiringGuardTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void rejectsTheManagementServerAsABotMcpServer() throws Exception {
        // The payload an agent sent on 2026-11-06 when asked to attach a tool.
        Map<String, Object> err = VoiceBotBuilderTools.mcpWiringProblem(M.readTree(
                "{\"mcp_servers\":[{\"name\":\"exotel_mcp_server\",\"url\":\"https://exotel-mcp-uat.example.com/mcp\"}]}"));
        assertNotNull(err);
        assertEquals("MCP_WIRING_REJECTED", err.get("error_code"));
        assertNotNull(VoiceBotBuilderTools.mcpWiringProblem(M.readTree(
                "{\"assistant_config\":{\"mcp_servers\":[{\"name\":\"x\",\"url\":\"https://mcp.exotel.com/mcp\"}]}}")));
    }

    @Test
    void rejectsAToolServerEntryAndMalformedToolNames() throws Exception {
        assertNotNull(VoiceBotBuilderTools.mcpWiringProblem(M.readTree(
                "{\"mcp_servers\":[{\"name\":\"tool_server\",\"url\":\"https://tools.example.com/mcp\"}]}")));
        assertNotNull(VoiceBotBuilderTools.mcpWiringProblem(M.readTree(
                "{\"agents\":[{\"name\":\"main\",\"mcp_tools\":[{\"tool_name\":\"get_order_status\"}]}]}")));
    }

    @Test
    void acceptsTheExpectedWiring() throws Exception {
        assertNull(VoiceBotBuilderTools.mcpWiringProblem(M.readTree(
                "{\"agents\":[{\"name\":\"main\",\"mcp_tools\":[{\"tool_name\":\"tool_server.get_order_status\",\"name\":\"get_order_status\"}]}]}")));
        assertNull(VoiceBotBuilderTools.mcpWiringProblem(M.readTree(
                "{\"mcp_servers\":[{\"name\":\"crm\",\"url\":\"https://crm.example.com/mcp\"}],"
                        + "\"agents\":[{\"name\":\"main\",\"mcp_tools\":[{\"tool_name\":\"crm.lookup\",\"name\":\"lookup\"}]}]}")));
        assertNull(VoiceBotBuilderTools.mcpWiringProblem(M.readTree("{\"greeting_message\":{\"text\":\"Hi\"}}")));
    }
}
