package com.example.mcp_api.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import com.example.mcp_api.service.ExotelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class McpAuthInterceptor implements HandlerInterceptor {
    
    private static final Logger logger = LoggerFactory.getLogger(McpAuthInterceptor.class);
    
    @Autowired
    private ExotelService exotelService;
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String requestURI = request.getRequestURI();
        String authHeader = request.getHeader("Authorization");
        
        logger.info("MCP Auth Interceptor - URI: {}, Method: {}", requestURI, request.getMethod());
        
        // Capture Authorization header for SSE and MCP endpoints
        if (authHeader != null && !authHeader.trim().isEmpty()) {
            logger.info("Capturing Authorization header for MCP session: {}", maskAuthHeader(authHeader));
            
            // Store the auth header for this session
            exotelService.setAuthHeaderForSession(authHeader);
            
            // Store with multiple keys to ensure MCP calls can find it
            String threadSessionKey = "MCP-" + Thread.currentThread().getId();
            String genericMcpKey = "MCP-GLOBAL";
            
            exotelService.setAuthHeaderForSessionKey(threadSessionKey, authHeader);
            exotelService.setAuthHeaderForSessionKey(genericMcpKey, authHeader);
            
            logger.info("Stored auth header for multiple session keys: {}, {}", threadSessionKey, genericMcpKey);
            
        } else {
            logger.warn("No Authorization header found in request to: {}", requestURI);
        }
        
        return true;
    }
    
    private String maskAuthHeader(String authHeader) {
        if (authHeader == null || authHeader.length() < 10) {
            return "***";
        }
        return authHeader.substring(0, 6) + "***" + authHeader.substring(authHeader.length() - 4);
    }
}