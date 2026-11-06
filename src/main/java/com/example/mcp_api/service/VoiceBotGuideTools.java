package com.example.mcp_api.service;

import com.example.mcp_api.docs.McpDocsRegistry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/** Serves the guide pages as a tool, for MCP clients that never read resources. Needs no credentials. */
@Service
public class VoiceBotGuideTools {

    private final McpDocsRegistry registry;

    public VoiceBotGuideTools(McpDocsRegistry registry) {
        this.registry = registry;
    }

    @Tool(name = "exotel_voicebot_guide",
          description = "Read-only guide for building and running Exotel voicebots with these tools: how objects connect, "
              + "every bot/assistant/voice/webhook field, tools-server and Tools Extension, step-by-step recipes, use-case "
              + "playbooks (FAQ, status lookup, number capture, outbound, booking, multilingual, agent transfer, low latency), "
              + "known problems and errors. Call it with topic \"start\" before your first change. An empty topic returns the index.")
    public String guide(
            @ToolParam(required = false, description = "Page topic from the index, e.g. start, workflows, objects/bot, "
                + "playbooks/index, known-problems. Empty returns the index.") String topic) {
        if (topic == null || topic.isBlank()) return registry.index();
        return registry.find(topic)
                .map(McpDocsRegistry.Doc::markdown)
                .orElseGet(() -> "No guide page '" + topic.trim() + "'.\n\n" + registry.index());
    }
}
