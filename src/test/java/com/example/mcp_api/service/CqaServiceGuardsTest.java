package com.example.mcp_api.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CqaServiceGuardsTest {

    @Test
    void pathSafeResourceIdAcceptsUuidRejectsTraversal() {
        assertTrue(CqaService.isPathSafeResourceId("a1b2c3d4-e5f6-7890-abcd-ef1234567890"));
        assertTrue(CqaService.isPathSafeResourceId("ABC123"));
        assertFalse(CqaService.isPathSafeResourceId("../etc/passwd"));
        assertFalse(CqaService.isPathSafeResourceId("id/with/slash"));
        assertFalse(CqaService.isPathSafeResourceId("id?x=1"));
        assertFalse(CqaService.isPathSafeResourceId(""));
        assertFalse(CqaService.isPathSafeResourceId(null));
    }
}
