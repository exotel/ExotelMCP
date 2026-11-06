package com.example.mcp_api.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamableHttpConfigFailureTest {

    @Test
    void flagsVoicebotAndToolsServerFailures() {
        assertTrue(StreamableHttpConfig.isFailureResult("exotel_voicebot_list_all", "Error 401: exotel_voicebot_list_all request failed"));
        assertTrue(StreamableHttpConfig.isFailureResult("exotel_tools_server_tool_list", "Missing Tools Server credentials.\n\n..."));
        assertTrue(StreamableHttpConfig.isFailureResult("exotel_voicebot_update_config", "{\"ok\":false,\"error_code\":\"UNAUTHORIZED\"}"));
    }

    @Test
    void leavesSuccessesAndOtherProductsAlone() {
        assertFalse(StreamableHttpConfig.isFailureResult("exotel_voicebot_update_config", "{\"ok\":true,\"version\":\"v3\"}"));
        assertFalse(StreamableHttpConfig.isFailureResult("exotel_voicebot_list_all", "{\"response\":[]}"));
        assertFalse(StreamableHttpConfig.isFailureResult("exotel_send_sms", "Error 401: request failed"));
        assertFalse(StreamableHttpConfig.isFailureResult("exotel_voicebot_list_all", "{not json"));
    }
}
