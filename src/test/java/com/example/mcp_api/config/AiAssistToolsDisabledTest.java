package com.example.mcp_api.config;

import com.example.mcp_api.service.AiAssistService;
import com.example.mcp_api.service.CqaService;
import com.example.mcp_api.service.EngageService;
import com.example.mcp_api.service.ExotelService;
import com.example.mcp_api.service.QuickAudioService;
import com.example.mcp_api.service.VoiceBotAdminTools;
import com.example.mcp_api.service.VoiceBotService;
import com.example.mcp_api.service.VoiceBotToolsService;
import com.example.mcp_api.service.WhatsAppService;
import com.example.mcp_api.service.setup.SetupTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * MSG-2809: the AI Assist tools are switched off for go-live until AIA-629 (the bearer-token cache ignores the
 * client secret) is fixed. They must be neither listed nor callable; every other product's tools stay as they were.
 */
class AiAssistToolsDisabledTest {

    private static final List<Class<?>> ENABLED_SERVICES = List.of(ExotelService.class, QuickAudioService.class,
            CqaService.class, VoiceBotService.class, VoiceBotAdminTools.class, SetupTools.class,
            VoiceBotToolsService.class, EngageService.class, WhatsAppService.class);

    private StreamableHttpConfig config;
    private AiAssistService aiAssistService;

    @BeforeEach
    void setUp() {
        config = new StreamableHttpConfig();
        ReflectionTestUtils.setField(config, "exotelService", mock(ExotelService.class));
        ReflectionTestUtils.setField(config, "quickAudioService", mock(QuickAudioService.class));
        ReflectionTestUtils.setField(config, "cqaService", mock(CqaService.class));
        ReflectionTestUtils.setField(config, "voiceBotService", mock(VoiceBotService.class));
        ReflectionTestUtils.setField(config, "voiceBotAdminTools", mock(VoiceBotAdminTools.class));
        ReflectionTestUtils.setField(config, "setupTools", new SetupTools());
        ReflectionTestUtils.setField(config, "voiceBotToolsService", mock(VoiceBotToolsService.class));
        ReflectionTestUtils.setField(config, "engageService", mock(EngageService.class));
        ReflectionTestUtils.setField(config, "whatsAppService", mock(WhatsAppService.class));
        aiAssistService = mock(AiAssistService.class);
        ReflectionTestUtils.setField(config, "aiAssistService", aiAssistService);
    }

    @Test
    void aiAssistServiceStillDeclaresTools() {
        // Guards the tests below: if AiAssistService lost its @Tool methods they would pass vacuously.
        assertTrue(toolNames(AiAssistService.class).contains("exotel_aiassist_whoami"));
    }

    @Test
    void toolsListHasNoAiAssistTool() {
        Set<String> listed = listedToolNames();
        assertFalse(listed.isEmpty());
        listed.forEach(name -> assertFalse(name.startsWith("exotel_aiassist_"), "AI Assist tool is listed: " + name));
        toolNames(AiAssistService.class).forEach(name ->
                assertFalse(listed.contains(name), "AI Assist tool is listed: " + name));
    }

    @Test
    void toolsListIsExactlyTheOtherProductsTools() {
        Set<String> expected = ENABLED_SERVICES.stream()
                .flatMap(c -> toolNames(c).stream())
                .collect(Collectors.toSet());
        assertEquals(expected, listedToolNames());
    }

    @Test
    void callingAnAiAssistToolReturnsUnknownTool() {
        for (String tool : List.of("exotel_aiassist_whoami", "exotel_aiassist_list_assistants")) {
            Map<String, Object> result = callTool(tool);
            assertEquals(true, result.get("isError"), tool);
            assertEquals("Unknown tool: " + tool, text(result), tool);
        }
        verifyNoInteractions(aiAssistService);
    }

    @Test
    void otherToolsStillRun() {
        Map<String, Object> result = callTool("exotel_setup_guide");
        assertEquals(false, result.get("isError"));
        assertFalse(text(result).startsWith("Unknown tool"));
    }

    // ---------------------------------------------------------------- helpers

    private static Set<String> toolNames(Class<?> service) {
        return Stream.of(service.getDeclaredMethods())
                .map(m -> m.getAnnotation(Tool.class))
                .filter(Objects::nonNull)
                .map(Tool::name)
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> process(Map<String, Object> request) {
        Map<String, Object> response = (Map<String, Object>) ReflectionTestUtils.invokeMethod(config, "processMcpRequest", request);
        return (Map<String, Object>) response.get("result");
    }

    @SuppressWarnings("unchecked")
    private Set<String> listedToolNames() {
        Map<String, Object> result = process(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"));
        return ((List<Map<String, Object>>) result.get("tools")).stream()
                .map(t -> (String) t.get("name"))
                .collect(Collectors.toSet());
    }

    private Map<String, Object> callTool(String name) {
        return process(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                "params", Map.of("name", name, "arguments", Map.of())));
    }

    @SuppressWarnings("unchecked")
    private static String text(Map<String, Object> result) {
        return (String) ((List<Map<String, Object>>) result.get("content")).get(0).get("text");
    }
}
