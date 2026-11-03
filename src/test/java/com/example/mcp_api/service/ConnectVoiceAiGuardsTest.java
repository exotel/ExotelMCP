package com.example.mcp_api.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Input checks for exotel_connect_voice_ai and the form encoder used by every CPaaS call.
 * The validation cases return before any credential lookup or HTTP call, so no Spring context or network is needed.
 */
class ConnectVoiceAiGuardsTest {

    private final ExotelService service = new ExotelService();

    private String call(String to, String url) {
        return service.connectVoiceAi(to, url, "", "false", "", "", "", "", "", "");
    }

    @Test
    void requiresToNumberAndStreamUrl() {
        assertTrue(call("", "wss://bot.example.com/").contains("toNumber is required"));
        assertTrue(call(null, "wss://bot.example.com/").contains("toNumber is required"));
        assertTrue(call("+919999999999", "").contains("streamUrl is required"));
    }

    @Test
    void rejectsNonWebSocketOrUnsafeUrls() {
        assertTrue(call("+919999999999", "https://bot.example.com/").contains("ws:// or wss://"));
        assertTrue(call("+919999999999", "wss://bot.example.com/a b").contains("ws:// or wss://"));
        assertTrue(call("+919999999999", "wss://bot.example.com/\"x").contains("ws:// or wss://"));
        assertTrue(call("+919999999999", "wss://bot.example.com/" + "a".repeat(600)).contains("under 600"));
    }

    @Test
    void rejectsUnsupportedSampleRateAndOptions() {
        assertTrue(call("+919999999999", "wss://b.example.com/?sample-rate=44100").contains("sample-rate must be"));
        String u = "wss://b.example.com/";
        assertTrue(service.connectVoiceAi("+91999", u, "", "", "stereo", "", "", "", "", "").contains("recordingChannels"));
        assertTrue(service.connectVoiceAi("+91999", u, "", "", "", "20000", "", "", "", "").contains("timeLimit"));
        assertTrue(service.connectVoiceAi("+91999", u, "", "", "", "", "", "https://cb", "completed", "").contains("statusCallbackEvents"));
        assertTrue(service.connectVoiceAi("+91999", u, "", "", "", "", "", "http://cb", "", "").contains("https URL"));
    }

    @Test
    void formDataIsPercentEncodedWithRepeatedListKeys() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("From", "+919876543210");
        data.put("Body", "Tom & Jerry = 100%");
        data.put("StreamUrl", "wss://bot.example.com/ws?sample-rate=8000&x=1");
        data.put("StatusCallbackEvents[]", List.of("answered", "terminal"));
        data.put("Skipped", null);
        assertEquals("From=%2B919876543210"
                + "&Body=Tom+%26+Jerry+%3D+100%25"
                + "&StreamUrl=wss%3A%2F%2Fbot.example.com%2Fws%3Fsample-rate%3D8000%26x%3D1"
                + "&StatusCallbackEvents%5B%5D=answered&StatusCallbackEvents%5B%5D=terminal",
            ExotelService.buildFormData(data));
    }
}
