package com.example.mcp_api.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VoiceBotToolsServiceCorpusTest {

    @Test
    void parsesTextAndMarkdownDocuments() {
        List<VoiceBotToolsService.TextDocument> docs = VoiceBotToolsService.parseTextDocuments(
                "[{\"filename\":\"faq.md\",\"content\":\"# Hours\\n9 to 9\"},{\"filename\":\"policy.txt\",\"content\":\"Refunds in 7 days\"}]",
                "documentsJson");
        assertEquals(2, docs.size());
        assertEquals("faq.md", docs.get(0).filename());
    }

    @Test
    void removingDocumentsNeedsConfirmAndReturnsAPreview() throws Exception {
        List<VoiceBotToolsService.TextDocument> adds = VoiceBotToolsService.parseTextDocuments(
                "[{\"filename\":\"hours.txt\",\"content\":\"9 to 6\"}]", "addDocumentsJson");
        List<String> ids = List.of("11111111-1111-4111-8111-111111111111");

        String gated = VoiceBotToolsService.deleteGate("c1", ids, adds, null);
        com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(gated);
        assertEquals("CONFIRM_REQUIRED", n.path("error_code").asText());
        assertFalse(n.path("ok").asBoolean(true));
        assertEquals(ids.get(0), n.at("/preview/delete_document_ids/0").asText());
        assertEquals("hours.txt", n.at("/preview/add_filenames/0").asText());
        assertNotNull(VoiceBotToolsService.deleteGate("c1", ids, List.of(), false), "confirm=false is not consent");

        assertNull(VoiceBotToolsService.deleteGate("c1", ids, adds, true), "confirm=true lets the delete through");
        assertNull(VoiceBotToolsService.deleteGate("c1", List.of(), adds, null), "adding documents alone needs no confirm");
    }

    @Test
    void rejectsBadDocuments() {
        assertThrows(IllegalArgumentException.class, () -> VoiceBotToolsService.parseTextDocuments("[]", "d"));
        assertThrows(IllegalArgumentException.class, () -> VoiceBotToolsService.parseTextDocuments(
                "[{\"filename\":\"doc.pdf\",\"content\":\"x\"}]", "d"));
        assertThrows(IllegalArgumentException.class, () -> VoiceBotToolsService.parseTextDocuments(
                "[{\"filename\":\"../etc.txt\",\"content\":\"x\"}]", "d"));
        assertThrows(IllegalArgumentException.class, () -> VoiceBotToolsService.parseTextDocuments(
                "[{\"filename\":\"a.txt\",\"content\":\"x\"},{\"filename\":\"a.txt\",\"content\":\"y\"}]", "d"));
        assertThrows(IllegalArgumentException.class, () -> VoiceBotToolsService.parseTextDocuments(
                "[{\"filename\":\"a.txt\",\"content\":\"   \"}]", "d"));
        String big = "x".repeat(VoiceBotToolsService.MAX_DOC_BYTES_PER_CALL + 1);
        assertThrows(IllegalArgumentException.class, () -> VoiceBotToolsService.parseTextDocuments(
                "[{\"filename\":\"big.txt\",\"content\":\"" + big + "\"}]", "d"));
    }
}
