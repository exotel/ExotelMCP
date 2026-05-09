package com.example.mcp_api.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Servlet filter that parses the Authorization header once per request
 * and stores the resulting AuthCredentials as a request attribute.
 *
 * All product tools read credentials from the request attribute — no
 * ThreadLocal, no static maps, no race conditions.
 */
@Component
@Order(1)
public class AuthFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(AuthFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpRequest) {
            String authHeader = httpRequest.getHeader("Authorization");
            AuthCredentials credentials = AuthCredentials.parse(authHeader);
            httpRequest.setAttribute(AuthCredentials.REQUEST_ATTRIBUTE, credentials);

            if (credentials.isParsed()) {
                logger.debug("Auth parsed — products configured: {}", credentials.configuredProductsSummary());
            } else if (authHeader != null && !authHeader.isBlank()) {
                logger.warn("Authorization header present but could not be parsed as JSON");
            }
        }

        chain.doFilter(request, response);
    }
}
