package com.example.mcp_api.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiAssistGuardsTest {

    @Test
    void validIdAcceptsAlphanumericRejectsTraversal() {
        assertTrue(AiAssistGuards.isValidId("abc-123_xyz"));
        assertFalse(AiAssistGuards.isValidId("../etc/passwd"));
        assertFalse(AiAssistGuards.isValidId("id/with/slash"));
        assertFalse(AiAssistGuards.isValidId(""));
        assertFalse(AiAssistGuards.isValidId(null));
    }

    @Test
    void validAccountSidMatchesExpectedPattern() {
        assertTrue(AiAssistGuards.isValidAccountSid("your_tenant_sid"));
        assertFalse(AiAssistGuards.isValidAccountSid("sid with spaces"));
        assertFalse(AiAssistGuards.isValidAccountSid(null));
    }

    @Test
    void validateFetchUrlRejectsHttpAndPrivateHosts() {
        assertEquals("fileUrl must be https (got http)", AiAssistGuards.validateFetchUrl("http://example.com/file.pdf"));
        assertNotNull(AiAssistGuards.validateFetchUrl("https://127.0.0.1/file.pdf"));
        assertNull(AiAssistGuards.validateFetchUrl("https://example.com/file.pdf"));
    }

    @Test
    void writeGateRequiresConfirmTrue() {
        assertNotNull(AiAssistGuards.writeGateError(false, "create_assistant"));
        assertNotNull(AiAssistGuards.writeGateError(null, "create_assistant"));
        assertNull(AiAssistGuards.writeGateError(true, "create_assistant"));
        assertTrue(AiAssistGuards.writeGateError(false, "x").contains("confirm_required"));
    }

    @Test
    void validateGeneralConfigStripsDispositionForExolite() {
        Map<String, Object> cfg = minimalValidConfig();
        assertNull(AiAssistGuards.validateGeneralConfig(cfg, "exolite"));
        assertFalse(cfg.containsKey("disposition_config"));
    }

    @Test
    void validateGeneralConfigRequiresDispositionForAmeyo() {
        Map<String, Object> cfg = minimalValidConfig();
        cfg.remove("disposition_config");
        assertNotNull(AiAssistGuards.validateGeneralConfig(cfg, "ameyo_6x"));
    }

    @Test
    void isValidAzureLocaleAndSource() {
        assertTrue(AiAssistGuards.isValidAzureLocale("en-IN"));
        assertFalse(AiAssistGuards.isValidAzureLocale("english"));
        assertTrue(AiAssistGuards.isValidSource("exolite"));
        assertFalse(AiAssistGuards.isValidSource("unknown"));
    }

    private static Map<String, Object> minimalValidConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("mask_sensitive_data", false);
        cfg.put("live_transcript", true);
        cfg.put("user_sentiment", true);
        Map<String, Object> suggestions = new LinkedHashMap<>();
        suggestions.put("enabled", false);
        cfg.put("suggestions", suggestions);
        Map<String, Object> dc = new LinkedHashMap<>();
        dc.put("disposition", false);
        dc.put("notes", false);
        dc.put("allow_agent_edit_notes", false);
        cfg.put("disposition_config", dc);
        return cfg;
    }
}
