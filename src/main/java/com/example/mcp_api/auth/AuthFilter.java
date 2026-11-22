package com.example.mcp_api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Map;

/**
 * Servlet filter that authenticates every request carrying caller credentials, parses the
 * Authorization header once and stores the resulting AuthCredentials as a request attribute.
 *
 * Rejected at the edge, with no credential fallback:
 *  - missing / unparseable header (401)
 *  - empty or sample-placeholder values, and values that fail ExotelHostGuard (hosts not on
 *    exotel.com, unsafe ids) (400)
 *  - incomplete credential sets and headers where no set could be verified by a real probe (401; the
 *    message names the set, never a value), and sets that Exotel rejects (401 with a generic message, so
 *    the server is no oracle for which set is valid; the set is logged)
 *  - if Exotel cannot be reached to verify a set (503, generic message)
 *  - too many live credential checks from one client or in total, or in one request (429 with
 *    Retry-After); see ExotelCredentialVerifier and {@link #clientKey} (MSG-2807)
 *
 * Requests whose raw path contains traversal or ambiguity markers (.., ;, //, \\, %2e, %2f, %5c) are
 * rejected with 400 before anything else. There are no public callback paths (MSG-2804, VO-6235:
 * call and SMS status are read live from Exotel); only CORS preflights are skipped.
 */
@Component
@Order(1)
public class AuthFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(AuthFilter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExotelCredentialVerifier verifier;

    public AuthFilter(ExotelCredentialVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpRequest
                && response instanceof HttpServletResponse httpResponse) {
            if (hasAmbiguousPath(httpRequest.getRequestURI())) {
                reject(httpRequest, httpResponse, HttpServletResponse.SC_BAD_REQUEST,
                        "bad_request", "Request path is not allowed");
                return;
            }

            if (isPublic(httpRequest) || isWellKnownProbe(httpRequest)) {
                chain.doFilter(request, response);
                return;
            }

            String authHeader = httpRequest.getHeader("Authorization");

            if (authHeader == null || authHeader.isBlank()) {
                reject(httpRequest, httpResponse, HttpServletResponse.SC_UNAUTHORIZED,
                        "unauthorized", "Authorization header is required");
                return;
            }

            AuthCredentials credentials = AuthCredentials.parse(authHeader);
            if (!credentials.isParsed()) {
                reject(httpRequest, httpResponse, HttpServletResponse.SC_UNAUTHORIZED,
                        "unauthorized", "Authorization header is invalid");
                return;
            }

            try {
                credentials.validateFields();
            } catch (ExotelHostGuard.InvalidCredentialException e) {
                reject(httpRequest, httpResponse, HttpServletResponse.SC_BAD_REQUEST,
                        "invalid_credentials", e.getMessage());
                return;
            }

            ExotelCredentialVerifier.Result result;
            try {
                result = verifier.verify(credentials, clientKey(httpRequest));
            } catch (ExotelHostGuard.InvalidCredentialException e) {
                reject(httpRequest, httpResponse, HttpServletResponse.SC_BAD_REQUEST,
                        "invalid_credentials", e.getMessage());
                return;
            }
            if (result.outcome() == ExotelCredentialVerifier.Outcome.REJECTED) {
                reject(httpRequest, httpResponse, HttpServletResponse.SC_UNAUTHORIZED,
                        "unauthorized", result.message());
                return;
            }
            if (result.outcome() == ExotelCredentialVerifier.Outcome.THROTTLED) {
                httpResponse.setHeader("Retry-After", Long.toString(Math.max(1, result.retryAfterSeconds())));
                reject(httpRequest, httpResponse, 429, "too_many_requests", result.message());
                return;
            }
            if (result.outcome() == ExotelCredentialVerifier.Outcome.UNAVAILABLE) {
                httpResponse.setHeader("Retry-After", "5");
                reject(httpRequest, httpResponse, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "verification_unavailable", result.message());
                return;
            }

            httpRequest.setAttribute(AuthCredentials.REQUEST_ATTRIBUTE, credentials);
            logger.debug("Auth verified — products configured: {}", credentials.configuredProductsSummary());
        }

        chain.doFilter(request, response);
    }

    /**
     * The key for the per-client verification bucket (MSG-2807). The server sits behind mcp-alb, which appends
     * the address it saw to X-Forwarded-For, so only the LAST entry is trusted, and only when the TCP peer is
     * a private or loopback address (the ALB inside the VPC). Earlier entries are whatever the caller sent.
     * A request from a public peer (no ALB in front) is keyed on the peer itself and its X-Forwarded-For is
     * ignored. Spring's forward-headers-strategy is not used, because it would also rewrite scheme and host
     * for every controller.
     */
    static String clientKey(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || !isPrivatePeer(peer)) return peer;
        String[] hops = forwarded.split(",");
        String last = hops.length == 0 ? "" : hops[hops.length - 1].trim();
        return last.isEmpty() ? peer : last;
    }

    /** Site-local or loopback IP literal. Never resolves a name: the peer address is always a literal. */
    private static boolean isPrivatePeer(String peer) {
        if (peer == null || peer.isBlank() || !peer.matches("[0-9a-fA-F:.]+")) return false;
        try {
            InetAddress address = InetAddress.getByName(peer);
            return address.isSiteLocalAddress() || address.isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /** True if the raw request path could be resolved differently by us and by the dispatcher. */
    static boolean hasAmbiguousPath(String rawUri) {
        if (rawUri == null) return false;
        String lower = rawUri.toLowerCase(Locale.ROOT);
        return lower.contains("..") || lower.contains(";") || lower.contains("//") || lower.contains("\\")
                || lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c");
    }

    /** Unauthenticated discovery probes (OAuth metadata etc.) fall through to a plain 404. */
    private static boolean isWellKnownProbe(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) return false;
        String servletPath = request.getServletPath() == null ? "" : request.getServletPath();
        String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
        return (servletPath + pathInfo).startsWith("/.well-known/");
    }

    // MSG-2804 removed the last public callback (SMS status is read live from Exotel); only CORS preflights skip auth.
    private static boolean isPublic(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response,
                               int status, String error, String message) throws IOException {
        logger.warn("Rejected {} {} — {} ({})", request.getMethod(), request.getRequestURI(), error, status);
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(MAPPER.writeValueAsString(Map.of("error", error, "message", message)));
    }
}
