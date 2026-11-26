package com.example.mcp_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolsGuardTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void rejectsNamesIdsAndObjectsUnderAgentTools() throws Exception {
        for (String tools : List.of("[\"billing\"]", "[\"3f2b6c1e-0000-4000-8000-000000000001\"]",
                "[{\"tool_name\":\"tool_server.lookup\",\"name\":\"lookup\"}]", "\"billing\"")) {
            Map<String, Object> err = VoiceBotBuilderTools.agentToolsProblem(M.readTree(
                    "{\"assistant_config\":{\"agents\":[{\"name\":\"main\",\"tools\":" + tools + "}]}}"));
            assertNotNull(err, tools);
            assertEquals("AGENT_TOOLS_REJECTED", err.get("error_code"));
            assertEquals(List.of("assistant_config.agents[0] (main)"), err.get("agents_with_tools"));
        }
    }

    @Test
    void acceptsMcpToolsAndEmptyOrMissingTools() throws Exception {
        assertNull(VoiceBotBuilderTools.agentToolsProblem(M.readTree(
                "{\"agents\":[{\"name\":\"main\",\"mcp_tools\":[{\"tool_name\":\"tool_server.lookup\",\"name\":\"lookup\"}]},"
                        + "{\"name\":\"b\",\"tools\":[]},{\"name\":\"c\",\"tools\":null}]}")));
        assertNull(VoiceBotBuilderTools.agentToolsProblem(M.readTree("{\"greeting_message\":{\"text\":\"Hi\"}}")));
    }

    @Test
    void readDropsToolsFromAgentsOnly() throws Exception {
        JsonNode read = M.readTree("{\"tools\":[\"keep\"],\"assistant_config\":{\"agents\":[{\"name\":\"main\","
                + "\"tools\":[{\"tool_name\":\"tool_server.lookup\"}],\"mcp_tools\":[{\"tool_name\":\"tool_server.lookup\"}]}]}}");
        assertEquals(1, VoiceBotBuilderTools.dropAgentTools(read));
        JsonNode agent = read.path("assistant_config").path("agents").get(0);
        assertFalse(agent.has("tools"));
        assertEquals(1, agent.path("mcp_tools").size());
        assertTrue(read.has("tools"));
    }
}
