package com.example.mcp_api.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoiceBotAdminToolsTest {

    @Test
    void updatePromptKeepsTheFirstAgentBehaviourOnMultiAgentBots() {
        var p = VoiceBotAdminTools.promptPatch("NEW");
        assertEquals("NEW", p.apply("main", "main", -1), "top-level instruction");
        assertEquals("NEW", p.apply("billing agent", "main", 0), "first agent always changes, as before");
        assertEquals("transfer agent", p.apply("transfer agent", "main", 1), "other agents keep their own prompt");
        assertEquals("NEW", p.apply("main", "main", 2), "an agent that shares the main prompt changes too");
    }
}
