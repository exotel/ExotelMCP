package com.example.mcp_api.dto;

public record CqaAuthData(
    String apiKey,
    String accountId,
    String cqaHost
) {
    public String baseUrl() {
        String host = cqaHost.endsWith("/") ? cqaHost.substring(0, cqaHost.length() - 1) : cqaHost;
        return host + "/cqa/api/v1/accounts/" + accountId;
    }
}
