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
