package com.example.mcp_api.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolsetsTest {

    @Test
    void noToolsetsMeansEveryTool() {
        assertTrue(StreamableHttpConfig.inToolsets("exotel_cqa_list_profiles", Set.of()));
    }

    @Test
    void voicebotAndMemoryGetOnlyTheirTools() {
        Set<String> sets = StreamableHttpConfig.parseToolsets("voicebot, memory");
        assertEquals(Set.of("memory", "voicebot"), sets);
        assertTrue(StreamableHttpConfig.inToolsets("exotel_voicebot_place_call", sets));
        assertTrue(StreamableHttpConfig.inToolsets("exotel_memory_get", sets));
        assertTrue(StreamableHttpConfig.inToolsets("exotel_voicebot_call_result", sets));
        assertTrue(StreamableHttpConfig.inToolsets("exotel_setup_guide", sets));
        assertFalse(StreamableHttpConfig.inToolsets("exotel_cqa_list_profiles", sets));
        assertFalse(StreamableHttpConfig.inToolsets("exotel_aiassist_list_assistants", sets));
        assertFalse(StreamableHttpConfig.inToolsets("exotel_tools_server_table_list", sets));
        assertTrue(StreamableHttpConfig.inToolsets("exotel_tools_server_table_list", StreamableHttpConfig.parseToolsets("toolserver")));
    }

    @Test
    void legsToolsetHasTheExomlTools() {
        Set<String> sets = StreamableHttpConfig.parseToolsets("voicebot,legs");
        assertTrue(StreamableHttpConfig.inToolsets("exotel_legs_call_bot", sets));
        assertTrue(StreamableHttpConfig.inToolsets("exotel_legs_action", sets));
        assertTrue(StreamableHttpConfig.inToolsets("exotel_events_endpoint_guide", sets));
        assertFalse(StreamableHttpConfig.inToolsets("exotel_legs_call_bot", StreamableHttpConfig.parseToolsets("voicebot,memory")));
    }

    @Test
    void unknownToolsetsAreIgnored() {
        assertEquals(Set.of("calls"), StreamableHttpConfig.parseToolsets("calls,nonsense"));
        assertTrue(StreamableHttpConfig.parseToolsets(null).isEmpty());
    }
}
