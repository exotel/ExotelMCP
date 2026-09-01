package com.example.mcp_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared JSON helpers for AI Assist MCP tool responses. */
final class AiAssistJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiAssistJson() {}

    static String toJson(Object o) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    static String errorJson(String code, String message) {
        return errorJson(code, message, Map.of());
    }

    static String errorJson(String code, String message, Map<String, Object> extras) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        if (extras != null && !extras.isEmpty()) body.putAll(extras);
        return toJson(body);
    }

    static String safeBodySnippet(String body) {
        if (body == null) return null;
        return body.length() > 500 ? body.substring(0, 500) + "\u2026" : body;
    }

    static String maskToken(String token) {
        if (token == null || token.isBlank()) return null;
        if (token.length() <= 12) return "***";
        return token.substring(0, 10) + "...";
    }
}
