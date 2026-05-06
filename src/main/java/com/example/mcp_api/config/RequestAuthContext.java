package com.example.mcp_api.config;

/**
 * ThreadLocal holder for the current request's Authorization header.
 *
 * Populated by AuthHeaderFilter at the start of every HTTP request
 * and cleared in the finally block. Services read it to get the per-request
 * auth header safely, without relying on thread-reused ambient fields.
 */
public final class RequestAuthContext {

    private static final ThreadLocal<String> AUTH = new ThreadLocal<>();

    private RequestAuthContext() {}

    public static void set(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            AUTH.remove();
        } else {
            AUTH.set(authHeader);
        }
    }

    public static String get() {
        return AUTH.get();
    }

    public static void clear() {
        AUTH.remove();
    }
}
