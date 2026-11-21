package com.example.mcp_api.auth;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Central guard for caller-supplied hosts, base URLs and identifiers taken from the
 * Authorization header. Callers may only point the server at https://exotel.com
 * (or a subdomain) and may only supply identifiers that are safe to put in a URL path.
 *
 * Rejected values are never echoed back or logged — only the field name is reported.
 */
public final class ExotelHostGuard {

    /** Host must be exotel.com or a DNS-label subdomain of it. No IP literals, no trailing dot. */
    private static final Pattern ALLOWED_HOST =
            Pattern.compile("^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)*exotel\\.com$");
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Pattern SAFE_PATH = Pattern.compile("^[A-Za-z0-9/_.-]*$");

    private ExotelHostGuard() {}

    /** Thrown when a caller-supplied value fails validation. Maps to a 4xx at the edge. */
    public static class InvalidCredentialException extends IllegalArgumentException {
        public InvalidCredentialException(String message) {
            super(message);
        }
    }

    /**
     * Validates a caller-supplied base URL / host field. Returns the value unchanged.
     * Rules: https only, no userinfo, port absent or 443, host exotel.com or a subdomain,
     * no query/fragment, path limited to safe characters without ".." segments.
     */
    public static String requireAllowedBaseUrl(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidCredentialException(field + " must not be blank");
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (Exception e) {
            throw reject(field);
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.isOpaque()
                || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || host == null
                || !ALLOWED_HOST.matcher(host.toLowerCase(Locale.ROOT)).matches()
                || !isSafePath(uri.getRawPath())) {
            throw reject(field);
        }
        return value;
    }

    /** Validates an identifier that is interpolated into a URL path (account sid, tenant id, ...). */
    public static String requireSafeId(String field, String value) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new InvalidCredentialException(field + " contains invalid characters");
        }
        return value;
    }

    /** Validates a caller-supplied URL path prefix such as ai_assist_context_path. */
    public static String requireSafePath(String field, String value) {
        if (value == null || !isSafePath(value)) {
            throw new InvalidCredentialException(field + " contains invalid characters");
        }
        return value;
    }

    private static boolean isSafePath(String path) {
        if (path == null || path.isEmpty()) return true;
        if (!SAFE_PATH.matcher(path).matches()) return false;
        for (String segment : path.split("/")) {
            if (segment.equals("..") || segment.equals(".")) return false;
        }
        return true;
    }

    private static InvalidCredentialException reject(String field) {
        return new InvalidCredentialException(
                field + " must be an https URL on exotel.com (no credentials, IP addresses or custom ports)");
    }
}
