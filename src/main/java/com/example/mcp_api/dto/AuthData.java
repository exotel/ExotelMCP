package com.example.mcp_api.dto;

public record AuthData(
    String token,
    String tokenMd5,
    String fromNumber,
    String callerId,
    String apiDomain,
    String accountSid,
    String exotelPortalUrl,
    String authType  // "Basic" or "Bearer"
) {
    // Constructor for backward compatibility (defaults to Basic)
    public AuthData(String token, String tokenMd5, String fromNumber, String callerId, 
                   String apiDomain, String accountSid, String exotelPortalUrl) {
        this(token, tokenMd5, fromNumber, callerId, apiDomain, accountSid, exotelPortalUrl, "Basic");
    }
}