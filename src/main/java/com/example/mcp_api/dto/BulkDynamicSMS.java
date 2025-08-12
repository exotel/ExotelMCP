package com.example.mcp_api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record BulkDynamicSMS(
    @NotNull
    @NotEmpty
    List<Message> message
) {
}