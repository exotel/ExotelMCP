package com.example.mcp_api.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Servlet filter that parses the Authorization header once per request
 * and stores the resulting AuthCredentials as a request attribute.
 *
 * Missing or unparseable Authorization is rejected immediately (401) —
 * no credential fallback.
 */
@Component
@Order(1)
public class AuthFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(AuthFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpRequest
                && response instanceof HttpServletResponse httpResponse) {
            String authHeader = httpRequest.getHeader("Authorization");

            // reject unauthenticated / unparseable MCP access at the edge.
            if (authHeader == null || authHeader.isBlank()) {
                logger.warn("Rejected {} {} — missing Authorization header",
                        httpRequest.getMethod(), httpRequest.getRequestURI());
                httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                httpResponse.setContentType("application/json");
                httpResponse.getWriter().write(
                        "{\"error\":\"unauthorized\",\"message\":\"Authorization header is required\"}");
                return;
            }

            AuthCredentials credentials = AuthCredentials.parse(authHeader);
            if (!credentials.isParsed()) {
                logger.warn("Rejected {} {} — Authorization header could not be parsed",
                        httpRequest.getMethod(), httpRequest.getRequestURI());
                httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                httpResponse.setContentType("application/json");
                httpResponse.getWriter().write(
                        "{\"error\":\"unauthorized\",\"message\":\"Authorization header is invalid\"}");
                return;
            }

            httpRequest.setAttribute(AuthCredentials.REQUEST_ATTRIBUTE, credentials);
            logger.debug("Auth parsed — products configured: {}", credentials.configuredProductsSummary());
        }

        chain.doFilter(request, response);
    }
}
