package com.example.mcp_api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record Message(
    @NotNull
    @NotEmpty
    String Body,
    
    @NotNull
    @NotEmpty
    String To
) {
}