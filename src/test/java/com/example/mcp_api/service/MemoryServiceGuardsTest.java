package com.example.mcp_api.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryServiceGuardsTest {

    @Test
    void indianNumbersNormalizeToOneKeySoInboundAndOutboundMatch() {
        String expected = "+919876543210";
        assertEquals(expected, MemoryService.normalizePhone("+919876543210"));
        assertEquals(expected, MemoryService.normalizePhone("09876543210"));   // Exotel inbound From
        assertEquals(expected, MemoryService.normalizePhone("9876543210"));
        assertEquals(expected, MemoryService.normalizePhone("919876543210"));  // WhatsApp style
        assertEquals(expected, MemoryService.normalizePhone("+91 98765-43210"));
        assertEquals("+14155550100", MemoryService.normalizePhone("+14155550100"));
    }

    @Test
    void badPhonesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> MemoryService.normalizePhone(null));
        assertThrows(IllegalArgumentException.class, () -> MemoryService.normalizePhone("12345"));
        assertThrows(IllegalArgumentException.class, () -> MemoryService.normalizePhone("+91abc"));
        assertThrows(IllegalArgumentException.class, () -> MemoryService.normalizePhone("+9198765432101234567"));
    }

    @Test
    void rolesMapToMemoryRoles() {
        assertEquals("customer", MemoryService.normalizeRole("user"));
        assertEquals("customer", MemoryService.normalizeRole("contact"));
        assertEquals("customer", MemoryService.normalizeRole("Customer"));
        assertEquals("bot", MemoryService.normalizeRole("assistant"));
        assertEquals("bot", MemoryService.normalizeRole("bot"));
        assertEquals("agent", MemoryService.normalizeRole("agent"));
        assertEquals("system", MemoryService.normalizeRole("system"));
    }

    @Test
    void memoryKeyIsOnlySentToExotelMemoryHostsOverHttps() {
        assertDoesNotThrow(() -> MemoryService.validateHost("https://memory.in.exotel.com"));
        assertDoesNotThrow(() -> MemoryService.validateHost("https://memory.us3.qaexotel.com/memory-client/x"));
        assertThrows(IllegalArgumentException.class, () -> MemoryService.validateHost("http://memory.in.exotel.com"));
        assertThrows(IllegalArgumentException.class, () -> MemoryService.validateHost("https://evil.example.com"));
        assertThrows(IllegalArgumentException.class, () -> MemoryService.validateHost("https://memory.in.exotel.com.evil.io"));
        assertThrows(IllegalArgumentException.class, () -> MemoryService.validateHost("https://api.in.exotel.com"));
        assertThrows(IllegalArgumentException.class, () -> MemoryService.validateHost("not a url"));
    }

    @Test
    void openingLineThatAsksForTheNameIsShortened() {
        String g = "Hello, thank you for calling Exotel Bank support. I can help you with a lost debit card. May I have your name please?";
        assertTrue(MemoryService.asksQuestion(g));
        assertEquals("Hello, thank you for calling Exotel Bank support. I can help you with a lost debit card.", MemoryService.shortenGreeting(g));
        assertFalse(MemoryService.asksQuestion("Hi, this is City Dental."));
        assertTrue(MemoryService.asksQuestion("Hi, this is Exotel Bank support. How can I assist you today?"));
        assertEquals("Hi, this is Exotel Bank support.", MemoryService.shortenGreeting("Hi, this is Exotel Bank support. How can I assist you today?"));
        assertEquals("Hello!", MemoryService.shortenGreeting("What is your name?"));
    }

    @Test
    void memorySectionIsAddedOnceAndRemovedCleanly() {
        String p = "You are a receptionist.";
        String on = MemoryService.withMemoryBlock(p);
        assertTrue(on.startsWith(p) && on.contains("{{entities.person_name}}"));
        assertEquals(on, MemoryService.withMemoryBlock(on), "idempotent");
        assertEquals(p, MemoryService.withoutMemoryBlock(on));
        assertFalse(MemoryService.MEMORY_BLOCK.contains("<"), "the VoiceBot API strips angle brackets");
    }

    @Test
    void newestNoteIsTheFirstNoteLine() {
        String brief = "## About\nRavi\n## Notes taken in recent calls\n- note: Outbound call: Your card was dispatched.\n- note: Older note.\n";
        assertEquals("Outbound call: Your card was dispatched.", MemoryService.newestNote(brief));
        assertNull(MemoryService.newestNote("## About\nno notes here"));
        assertTrue(MemoryService.MEMORY_BLOCK.contains("Outbound call:"));
    }

    @Test
    void anEditedMemorySectionNeverTakesTheCustomersPromptWithIt() {
        String edited = "Be helpful.\n\n" + MemoryService.MEMORY_BLOCK_START + "\nmy own rules\nMore of my prompt.";
        String out = MemoryService.withoutMemoryBlock(edited);
        assertTrue(out.contains("my own rules") && out.contains("More of my prompt."), "text after a broken section is kept");
        assertFalse(out.contains(MemoryService.MEMORY_BLOCK_START));
    }

    @Test
    void anOlderMemorySectionIsReplacedNotDuplicated() {
        String old = "Be helpful.\n\n### EXOTEL MEMORY (an older wording) ###\nold rules\n" + MemoryService.MEMORY_BLOCK_END;
        String on = MemoryService.withMemoryBlock(old);
        assertEquals(1, on.split(java.util.regex.Pattern.quote(MemoryService.MEMORY_BLOCK_PREFIX), -1).length - 1);
        assertFalse(on.contains("old rules"));
        assertTrue(on.startsWith("Be helpful."));
    }

    @Test
    void shortenedGreetingKeepsTheBotsIntroduction() {
        assertEquals("Hi, I'm Asha from Exotel Bank.", MemoryService.shortenGreeting("Hi, I'm Asha from Exotel Bank. What's your name?"));
        assertEquals("Hi, my name is Asha.", MemoryService.shortenGreeting("Hi, my name is Asha. May I have your name, please?"));
    }

    @Test
    void finishedOutboundNoteDoesNotLookLikeALiveOne() {
        assertFalse(MemoryService.OUTBOUND_DONE_PREFIX.startsWith("Outbound call"));
        assertNotEquals("Outbound call: x", MemoryService.newestNote("- note: " + MemoryService.OUTBOUND_DONE_PREFIX + "x"));
        assertTrue(MemoryService.failed("Error: nope") && MemoryService.failed(null) && !MemoryService.failed("{\"ok\":true}"));
    }

    @Test
    void searchTreatsNumbersAsPhones() {
        assertTrue(MemoryService.looksLikePhone("09876543210"));
        assertTrue(MemoryService.looksLikePhone("+91 98765 43210"));
        assertFalse(MemoryService.looksLikePhone("riya@example.com"));
        assertFalse(MemoryService.looksLikePhone("C-881"));
    }
}
