package com.example.mcp_api.auth;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
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
    /** The only hosts api_domain / calls_base_url may name, and the only hosts credentials are verified on. */
    public static final Set<String> API_HOSTS = Set.of("api.exotel.com", "api.in.exotel.com");
    private static final Pattern PHONE_NUMBER = Pattern.compile("^\\+?[0-9]{5,20}$");
    private static final Pattern SAFE_QUERY_VALUE = Pattern.compile("^[A-Za-z0-9:.+_-]{1,64}$");
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

    /**
     * Pins a caller-supplied API base (api_domain, calls_base_url) to exactly one of {@link #API_HOSTS}:
     * https, no path beyond "/", no query. Returns the canonical {@code https://host}.
     */
    public static String requireApiHost(String field, String value) {
        requireAllowedBaseUrl(field, value);
        URI uri = URI.create(value.trim());
        String path = uri.getRawPath();
        if (!API_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                || (path != null && !path.isEmpty() && !path.equals("/"))) {
            throw new InvalidCredentialException(field + " must be one of the Exotel API hosts " + new java.util.TreeSet<>(API_HOSTS)
                    + " (https, no path)");
        }
        return "https://" + uri.getHost().toLowerCase(Locale.ROOT);
    }

    /** Validates a phone number that is interpolated into a URL: optional +, then 5 to 20 digits (after trimming). Returns the trimmed value. */
    public static String requirePhoneNumber(String field, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!PHONE_NUMBER.matcher(trimmed).matches()) {
            throw new InvalidCredentialException(field + " must be a phone number (digits, optional leading +)");
        }
        return trimmed;
    }

    /** Validates an identifier that is interpolated into a URL path (account sid, tenant id, ...). */
    public static String requireSafeId(String field, String value) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new InvalidCredentialException(field + " contains invalid characters");
        }
        return value;
    }

    /** Validates a value that is appended to a URL query string unencoded (version, status, ISO dates, ...). */
    public static boolean isSafeQueryValue(String value) {
        return value != null && SAFE_QUERY_VALUE.matcher(value).matches();
    }

    /** Like {@link #isSafeQueryValue} but throws, naming the field. */
    public static String requireSafeQueryValue(String field, String value) {
        if (!isSafeQueryValue(value)) {
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
