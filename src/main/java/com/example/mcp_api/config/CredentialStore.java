package com.example.mcp_api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared credential store for all Exotel services.
 *
 * Priority order when resolving a credential:
 *   1. Session-level override (set via setupExotelCredentials tool)
 *   2. Global override (set via setupExotelCredentials with no session)
 *   3. application.properties defaults (handled by each service's @Value)
 *
 * Keys follow the pattern:  "exotel.{service}.{field}"
 *   e.g. "exotel.voicebot.api.key", "exotel.cpaas.account.sid"
 *
 * A special "default" session stores values shared across all sessions.
 */
@Component
public class CredentialStore {

    private static final Logger logger = LoggerFactory.getLogger(CredentialStore.class);

    public static final String DEFAULT_SESSION = "__default__";

    // sessionId -> (credKey -> value)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> store = new ConcurrentHashMap<>();

    public void put(String key, String value) {
        put(DEFAULT_SESSION, key, value);
    }

    public void put(String sessionId, String key, String value) {
        store.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(key, value);
        logger.debug("CredentialStore: set [{}] for session={}", key, sessionId);
    }

    public String get(String sessionId, String key) {
        if (sessionId != null) {
            ConcurrentHashMap<String, String> sessionCreds = store.get(sessionId);
            if (sessionCreds != null && sessionCreds.containsKey(key)) {
                return sessionCreds.get(key);
            }
        }
        ConcurrentHashMap<String, String> defaultCreds = store.get(DEFAULT_SESSION);
        if (defaultCreds != null && defaultCreds.containsKey(key)) {
            return defaultCreds.get(key);
        }
        return null;
    }

    public String get(String key) {
        return get(DEFAULT_SESSION, key);
    }

    public boolean hasCredentials(String sessionId) {
        ConcurrentHashMap<String, String> sessionCreds = store.get(sessionId);
        if (sessionCreds != null && !sessionCreds.isEmpty()) return true;
        ConcurrentHashMap<String, String> defaultCreds = store.get(DEFAULT_SESSION);
        return defaultCreds != null && !defaultCreds.isEmpty();
    }

    public boolean hasCredentials() {
        return hasCredentials(DEFAULT_SESSION);
    }

    public void clearSession(String sessionId) {
        store.remove(sessionId);
        logger.info("CredentialStore: cleared session={}", sessionId);
    }

    public void clearAll() {
        store.clear();
        logger.info("CredentialStore: cleared all");
    }

    public String summary(String sessionId) {
        StringBuilder sb = new StringBuilder();
        ConcurrentHashMap<String, String> creds = store.get(sessionId != null ? sessionId : DEFAULT_SESSION);
        if (creds == null || creds.isEmpty()) {
            sb.append("No dynamic credentials configured. Using application.properties defaults.");
        } else {
            sb.append("Dynamic credentials configured: ");
            creds.forEach((k, v) -> sb.append(k).append("=").append(mask(v)).append(", "));
        }
        return sb.toString();
    }

    private String mask(String value) {
        if (value == null || value.length() < 8) return "***";
        return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
    }
}
