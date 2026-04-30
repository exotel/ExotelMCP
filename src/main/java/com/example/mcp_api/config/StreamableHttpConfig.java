package com.example.mcp_api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunctions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

/**
 * Configuration class for Streamable HTTP transport
 * This enables the /mcp endpoint for unified request/response handling
 */
@Configuration
public class StreamableHttpConfig {


    
    @Autowired
    private com.example.mcp_api.service.ExotelService exotelService;
    
    @Autowired
    private com.example.mcp_api.service.QuickAudioService quickAudioService;
    
    @Autowired
    private com.example.mcp_api.service.CqaService cqaService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Router function for Streamable HTTP MCP endpoint
     * Handles both GET (SSE establishment) and POST (request/response) requests
     */
    @Bean
    public RouterFunction<ServerResponse> mcpStreamableRoutes() {
        return RouterFunctions
            .route(RequestPredicates.POST("/mcp")
                .and(RequestPredicates.accept(MediaType.APPLICATION_JSON)), 
                this::handleMcpPost)
            .andRoute(RequestPredicates.GET("/mcp"), 
                this::handleMcpGet);
    }

    /**
     * Handle POST requests to /mcp endpoint
     * Processes MCP JSON-RPC messages and returns appropriate responses
     */
    private ServerResponse handleMcpPost(org.springframework.web.servlet.function.ServerRequest request) {
        try {
            // Parse the JSON-RPC request as generic Map
            @SuppressWarnings("unchecked")
            Map<String, Object> mcpRequest = request.body(Map.class);
            
            // Process the request using the MCP server
            Map<String, Object> response = processMcpRequest(mcpRequest);
            
            // Check if client accepts SSE for streaming responses
            String acceptHeader = request.headers().firstHeader(HttpHeaders.ACCEPT);
            boolean acceptsSSE = acceptHeader != null && acceptHeader.contains("text/event-stream");
            
            // For now, return JSON response (can be enhanced for SSE streaming)
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
                
        } catch (Exception e) {
            // Return JSON-RPC error response
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(createErrorResponse("Internal server error: " + e.getMessage()));
        }
    }

    /**
     * Handle GET requests to /mcp endpoint
     * Establishes SSE connection for bi-directional communication
     */
    private ServerResponse handleMcpGet(org.springframework.web.servlet.function.ServerRequest request) {
        try {
            // For Phase 1, we'll return a simple SSE stream
            // This can be enhanced to support session management
            return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body("data: {\"type\": \"connection_established\"}\n\n");
                
        } catch (Exception e) {
            return ServerResponse.status(500)
                .body("SSE connection failed: " + e.getMessage());
        }
    }

    /**
     * Process MCP JSON-RPC requests
     * This is a simplified implementation for Phase 1
     */
    private Map<String, Object> processMcpRequest(Map<String, Object> request) {
        try {
            String method = (String) request.get("method");
            Object id = request.get("id");
            
            switch (method) {
                case "initialize":
                    return createInitializeResponse(id);
                case "tools/list":
                    return createToolsListResponse(id);
                case "tools/call":
                    return executeToolCall(id, request.get("params"));
                case "prompts/list":
                    return createPromptsListResponse(id);
                case "prompts/get":
                    return getPrompt(id, request.get("params"));
                case "resources/list":
                    return createResourcesListResponse(id, request.get("params"));
                case "resources/read":
                    return readResource(id, request.get("params"));
                case "resources/templates/list":
                    return createResourceTemplatesListResponse(id);
                case "resources/subscribe":
                    return subscribeToResource(id, request.get("params"));
                case "resources/unsubscribe":
                    return unsubscribeFromResource(id, request.get("params"));
                default:
                    return createErrorResponse(id, "Method not found: " + method);
            }
        } catch (Exception e) {
            return createErrorResponse(request.get("id"), "Processing error: " + e.getMessage());
        }
    }

    /**
     * Create initialize response for MCP protocol
     * Updated to MCP 2025-06-18 specification with full prompt and resources support
     */
    private Map<String, Object> createInitializeResponse(Object id) {
        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("tools", Map.of("listChanged", true));
        capabilities.put("prompts", Map.of("listChanged", true));
        capabilities.put("resources", Map.of(
            "subscribe", true,
            "listChanged", true
        ));
        
        Map<String, Object> result = Map.of(
            "protocolVersion", "2025-06-18",
            "capabilities", capabilities,
            "serverInfo", Map.of(
                "name", "exotelmcp-server",
                "version", "1.0.0",
                "description", "Exotel MCP Server with Communication Tools, Audio Tools, and API Resources"
            )
        );
        
        return Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "result", result
        );
    }

    /**
     * Create tools list response using reflection to extract @Tool annotations from both ShoppingCart and ExotelService
     */
    private Map<String, Object> createToolsListResponse(Object id) {
        List<Map<String, Object>> tools = new ArrayList<>();
        
        // Scan services for @Tool annotated methods
        Object[] services = {exotelService, quickAudioService, cqaService};
        
        for (Object service : services) {
            Method[] methods = service.getClass().getDeclaredMethods();
            for (Method method : methods) {
                Tool toolAnnotation = method.getAnnotation(Tool.class);
                if (toolAnnotation != null) {
                    Map<String, Object> toolInfo = new HashMap<>();
                    toolInfo.put("name", toolAnnotation.name());
                    toolInfo.put("description", toolAnnotation.description());
                    
                    // Generate input schema based on method parameters
                    Map<String, Object> inputSchema = new HashMap<>();
                    inputSchema.put("type", "object");
                    Map<String, Object> properties = new HashMap<>();
                    List<String> required = new ArrayList<>();
                    
                    Parameter[] parameters = method.getParameters();
                    for (Parameter param : parameters) {
                        String paramName = param.getName();
                        Map<String, Object> property = new HashMap<>();
                        
                        // Determine parameter type
                        Class<?> paramType = param.getType();
                        if (paramType == int.class || paramType == Integer.class) {
                            property.put("type", "integer");
                            property.put("description", "Integer parameter");
                        } else if (paramType == String.class) {
                            property.put("type", "string");
                            property.put("description", "String parameter");
                        } else if (paramType == java.util.List.class) {
                            property.put("type", "array");
                            property.put("description", "List parameter");
                            property.put("items", Map.of("type", "string"));
                        } else if (paramType == java.util.Map.class) {
                            property.put("type", "object");
                            property.put("description", "Map parameter");
                        } else if (paramType == boolean.class || paramType == Boolean.class) {
                            property.put("type", "boolean");
                            property.put("description", "Boolean parameter");
                        } else {
                            property.put("type", "string");
                            property.put("description", "Parameter");
                        }
                        
                        properties.put(paramName, property);
                        // For this example, we'll assume all parameters are required
                        // You could add custom annotations later to make some optional
                        required.add(paramName);
                    }
                    
                    inputSchema.put("properties", properties);
                    inputSchema.put("required", required);
                    toolInfo.put("inputSchema", inputSchema);
                    tools.add(toolInfo);
                }
            }
        }
            
        Map<String, Object> result = Map.of("tools", tools);
        return Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "result", result
        );
    }

    /**
     * Execute actual tool calls using reflection to call @Tool annotated methods
     */
    private Map<String, Object> executeToolCall(Object id, Object params) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) params;
            String toolName = (String) parameters.get("name");
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = parameters.get("arguments") != null
                ? (Map<String, Object>) parameters.get("arguments")
                : Collections.emptyMap();
            
            Object toolResult = null;
            boolean isError = false;
            
            // Search for the tool method in active services
            Object[] services = {exotelService, quickAudioService, cqaService};
            
            for (Object service : services) {
                Method[] methods = service.getClass().getDeclaredMethods();
                for (Method method : methods) {
                    Tool toolAnnotation = method.getAnnotation(Tool.class);
                    if (toolAnnotation != null && toolAnnotation.name().equals(toolName)) {
                        // Prepare method arguments
                        Parameter[] methodParams = method.getParameters();
                        Object[] methodArgs = new Object[methodParams.length];
                        
                        for (int i = 0; i < methodParams.length; i++) {
                            Parameter param = methodParams[i];
                            String paramName = param.getName();
                            Object argValue = arguments.get(paramName);
                            
                            // Enhanced type conversion
                            if (param.getType() == int.class || param.getType() == Integer.class) {
                                if (argValue instanceof Number) {
                                    methodArgs[i] = ((Number) argValue).intValue();
                                } else {
                                    methodArgs[i] = Integer.parseInt(argValue.toString());
                                }
                            } else if (param.getType() == boolean.class || param.getType() == Boolean.class) {
                                if (argValue instanceof Boolean) {
                                    methodArgs[i] = argValue;
                                } else {
                                    methodArgs[i] = Boolean.parseBoolean(argValue.toString());
                                }
                            } else if (param.getType() == java.util.List.class) {
                                // Handle List parameters
                                if (argValue instanceof java.util.List) {
                                    methodArgs[i] = argValue;
                                } else {
                                    methodArgs[i] = java.util.Arrays.asList(argValue.toString().split(","));
                                }
                            } else if (param.getType() == java.util.Map.class) {
                                // Handle Map parameters
                                if (argValue instanceof java.util.Map) {
                                    methodArgs[i] = argValue;
                                } else {
                                    methodArgs[i] = new java.util.HashMap<>();
                                }
                            } else {
                                methodArgs[i] = argValue;
                            }
                        }
                        
                        // Invoke the method
                        toolResult = method.invoke(service, methodArgs);
                        break;
                    }
                }
                if (toolResult != null) break; // Exit if tool was found and executed
            }
            
            if (toolResult == null) {
                toolResult = "Unknown tool: " + toolName;
                isError = true;
            }
            
            // Format the result for MCP response
            Map<String, Object> content = Map.of(
                "type", "text",
                "text", toolResult != null ? toolResult.toString() : "No result"
            );
            
            Map<String, Object> result = Map.of(
                "content", List.of(content),
                "isError", isError
            );
            
            return Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "result", result
            );
            
        } catch (Exception e) {
            // Return error response for tool execution failure
            Map<String, Object> content = Map.of(
                "type", "text",
                "text", "Tool execution failed: " + e.getMessage()
            );
            
            Map<String, Object> result = Map.of(
                "content", List.of(content),
                "isError", true
            );
            
            return Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "result", result
            );
        }
    }

    /**
     * Create prompts list response for MCP protocol following 2025-06-18 specification
     * Returns available prompts for Exotel Communication Tools and Quick Audio Tools
     */
    private Map<String, Object> createPromptsListResponse(Object id) {
        List<Map<String, Object>> prompts = new ArrayList<>();
        
        // ========== EXOTEL COMMUNICATION PROMPTS ==========
        
        // 1. Send SMS Prompt
        prompts.add(createPrompt(
            "send_sms",
            "Send SMS Message",
            "Send an SMS message to a phone number with DLT compliance",
            List.of(
                createArgument("phone_number", "The recipient's phone number (e.g., +919876543210)", true),
                createArgument("message", "The SMS message content", true),
                createArgument("dlt_template_id", "DLT Template ID for compliance", true),
                createArgument("dlt_entity_id", "DLT Entity ID for compliance", true)
            )
        ));
        
        // 2. Voice Call Prompt
        prompts.add(createPrompt(
            "make_voice_call",
            "Make Voice Call",
            "Initiate a voice call to a phone number",
            List.of(
                createArgument("phone_number", "The number to call (e.g., +919876543210)", true)
            )
        ));
        
        // 3. Connect Two Numbers Prompt
        prompts.add(createPrompt(
            "connect_calls",
            "Connect Two Numbers",
            "Connect two phone numbers in a voice call",
            List.of(
                createArgument("from_number", "The calling number", true),
                createArgument("to_number", "The number to connect to", true)
            )
        ));
        
        // 4. Bulk SMS Prompt
        prompts.add(createPrompt(
            "send_bulk_sms",
            "Send Bulk SMS",
            "Send the same SMS message to multiple phone numbers",
            List.of(
                createArgument("phone_numbers", "Comma-separated list of phone numbers", true),
                createArgument("message", "The SMS message content for all recipients", true)
            )
        ));
        
        // 5. Dynamic Bulk SMS Prompt
        prompts.add(createPrompt(
            "send_dynamic_bulk_sms",
            "Send Dynamic Bulk SMS",
            "Send personalized SMS messages to multiple recipients",
            List.of(
                createArgument("messages_json", "JSON array of messages with 'To' and 'Body' fields", true)
            )
        ));
        
        // 6. Call Flow Prompt
        prompts.add(createPrompt(
            "connect_call_flow",
            "Connect to Call Flow",
            "Connect a phone number to a predefined Exotel call flow",
            List.of(
                createArgument("app_id", "The Exotel app/call flow ID", true),
                createArgument("from_number", "The phone number to connect", true)
            )
        ));
        
        // 7. SMS Status Check Prompt
        prompts.add(createPrompt(
            "check_sms_status",
            "Check SMS Delivery Status",
            "Check the delivery status of SMS messages for a phone number",
            List.of(
                createArgument("phone_number", "The phone number to check SMS status for", true)
            )
        ));
        
        // 8. Voice Call Status Prompt
        prompts.add(createPrompt(
            "check_call_status",
            "Check Voice Call Status",
            "Check the status and details of voice calls for a phone number",
            List.of(
                createArgument("phone_number", "The phone number to check call status for", true)
            )
        ));
        
        // 9. Call Details Prompt
        prompts.add(createPrompt(
            "get_call_details",
            "Get Call Details",
            "Get detailed information about a specific call using Call SID",
            List.of(
                createArgument("call_sid", "The Exotel Call SID to get details for", true)
            )
        ));
        
        // 10. Number Metadata Prompt
        prompts.add(createPrompt(
            "lookup_number_info",
            "Lookup Number Information",
            "Get metadata and information about a phone number",
            List.of(
                createArgument("phone_number", "The phone number to lookup information for", true)
            )
        ));
        
        // ========== QUICK AUDIO PROMPTS ==========
        
        // 11. Quick Play Audio Prompt
        prompts.add(createPrompt(
            "quick_play_audio",
            "Quick Play Audio",
            "🎵 Play any audio URL instantly in your browser with one click",
            List.of(
                createArgument("audio_url", "The URL of the audio file to play (e.g., https://example.com/audio.mp3)", true)
            )
        ));
        
        // 12. Open Audio Player Prompt
        prompts.add(createPrompt(
            "open_audio_player",
            "Open Audio Player",
            "🎛️ Open the web-based audio player interface for manual audio control",
            List.of()
        ));
        
        // 13. Quick Download Audio Prompt
        prompts.add(createPrompt(
            "download_audio",
            "Download Audio File",
            "💾 Get a direct download link for any audio URL",
            List.of(
                createArgument("audio_url", "The URL of the audio file to download", true)
            )
        ));
        
        // 14. Communication Workflow Prompt
        prompts.add(createPrompt(
            "communication_workflow",
            "Complete Communication Workflow",
            "Execute a complete communication workflow with SMS and voice calls",
            List.of(
                createArgument("workflow_type", "Type of workflow: 'notify', 'confirm', 'survey', or 'support'", true),
                createArgument("phone_number", "The target phone number", true),
                createArgument("message", "The message content (for SMS workflows)", false),
                createArgument("follow_up", "Whether to follow up with a voice call (true/false)", false)
            )
        ));
        
        // 15. Bulk Communication Campaign Prompt
        prompts.add(createPrompt(
            "bulk_campaign",
            "Bulk Communication Campaign",
            "Execute a bulk communication campaign with SMS and optional voice follow-up",
            List.of(
                createArgument("campaign_type", "Campaign type: 'promotional', 'notification', 'alert', or 'survey'", true),
                createArgument("recipient_list", "Comma-separated list of phone numbers", true),
                createArgument("sms_message", "The SMS message for the campaign", true),
                createArgument("include_voice", "Whether to include voice calls (true/false)", false)
            )
        ));
        
        // ========== CONVERSATIONAL INTELLIGENCE PROMPTS ==========
        
        prompts.add(createPrompt(
            "cqa_ingest_interaction",
            "Ingest Interaction for Quality Analysis",
            "Submit a call recording or transcript to Exotel Conversational Intelligence for AI-powered quality scoring",
            List.of(
                createArgument("external_interaction_id", "Your unique identifier for this interaction", true),
                createArgument("channel_type", "Interaction channel: voice, chat, email, sms, whatsapp", true),
                createArgument("audio_url", "URL to the audio recording", false),
                createArgument("transcript_url", "URL to the transcript file", false),
                createArgument("language", "Language code (e.g. en, hi)", false)
            )
        ));
        
        prompts.add(createPrompt(
            "cqa_ingest_file",
            "Bulk Ingest from File",
            "Submit a CSV file containing multiple interactions for Conversational Intelligence analysis",
            List.of(
                createArgument("file_url", "URL to the CSV file (https, http, or s3)", true),
                createArgument("format", "File format: csv", true),
                createArgument("source", "Originating system identifier", false)
            )
        ));
        
        prompts.add(createPrompt(
            "cqa_check_interaction",
            "Check Interaction Status",
            "Look up the processing status and details of an ingested Conversational Intelligence interaction",
            List.of(
                createArgument("interaction_id", "Platform-assigned UUID or your external_interaction_id", true)
            )
        ));
        
        prompts.add(createPrompt(
            "cqa_get_analysis",
            "Get Quality Analysis Results",
            "Retrieve the full quality scoring breakdown for a completed Conversational Intelligence analysis",
            List.of(
                createArgument("analysis_id", "The analysis UUID from the interaction detail", true)
            )
        ));
        
        prompts.add(createPrompt(
            "cqa_track_job",
            "Track Batch/File Job",
            "Check the progress of a Conversational Intelligence batch or file ingestion job",
            List.of(
                createArgument("job_id", "The job ID returned by batch or file ingest", true)
            )
        ));
        
        Map<String, Object> result = Map.of("prompts", prompts);
        return Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "result", result
        );
    }

    /**
     * Helper method to create a prompt object following MCP 2025-06-18 specification
     */
    private Map<String, Object> createPrompt(String name, String title, String description, List<Map<String, Object>> arguments) {
        Map<String, Object> prompt = new HashMap<>();
        prompt.put("name", name);
        prompt.put("title", title);
        prompt.put("description", description);
        if (arguments != null && !arguments.isEmpty()) {
            prompt.put("arguments", arguments);
        }
        return prompt;
    }
    
    /**
     * Helper method to create argument objects for prompts
     */
    private Map<String, Object> createArgument(String name, String description, boolean required) {
        Map<String, Object> argument = new HashMap<>();
        argument.put("name", name);
        argument.put("description", description);
        argument.put("required", required);
        return argument;
    }

    /**
     * Get a specific prompt by name with argument substitution
     * Supports MCP 2025-06-18 specification with template variable replacement
     */
    private Map<String, Object> getPrompt(Object id, Object params) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) params;
            String promptName = (String) parameters.get("name");
            
            // Get arguments if provided
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = (Map<String, Object>) parameters.get("arguments");
            
            Map<String, Object> prompt = getPromptByName(promptName);
            if (prompt == null) {
                return createErrorResponse(id, "Prompt not found: " + promptName);
            }
            
            // If arguments are provided, substitute them in the prompt messages
            if (arguments != null && !arguments.isEmpty()) {
                prompt = substituteArgumentsInPrompt(prompt, arguments);
            }
            
            return Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "result", prompt
            );
            
        } catch (Exception e) {
            return createErrorResponse(id, "Error getting prompt: " + e.getMessage());
        }
    }

    /**
     * Substitute template variables in prompt messages with provided arguments
     * Follows MCP 2025-06-18 specification for argument substitution
     */
    private Map<String, Object> substituteArgumentsInPrompt(Map<String, Object> prompt, Map<String, Object> arguments) {
        // Create a mutable copy of the prompt
        Map<String, Object> result = new HashMap<>(prompt);
        
        // Get the messages array and substitute variables
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) prompt.get("messages");
        if (messages != null) {
            List<Map<String, Object>> substitutedMessages = new ArrayList<>();
            
            for (Map<String, Object> message : messages) {
                Map<String, Object> substitutedMessage = new HashMap<>(message);
                
                // Get the content object
                @SuppressWarnings("unchecked")
                Map<String, Object> content = (Map<String, Object>) message.get("content");
                if (content != null) {
                    Map<String, Object> substitutedContent = new HashMap<>(content);
                    
                    // Substitute variables in text content
                    String text = (String) content.get("text");
                    if (text != null) {
                        String substitutedText = substituteTemplateVariables(text, arguments);
                        substitutedContent.put("text", substitutedText);
                    }
                    
                    substitutedMessage.put("content", substitutedContent);
                }
                
                substitutedMessages.add(substitutedMessage);
            }
            
            result.put("messages", substitutedMessages);
        }
        
        return result;
    }
    
    /**
     * Replace template variables in text with actual argument values
     * Supports {{variable}} syntax for template substitution
     */
    private String substituteTemplateVariables(String text, Map<String, Object> arguments) {
        if (text == null || arguments == null) {
            return text;
        }
        
        String result = text;
        
        // Replace simple template variables like {{phone_number}}
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            
            // Replace {{key}} with value
            String template = "{{" + key + "}}";
            result = result.replace(template, value);
        }
        
        // Handle conditional templates like {{#if variable}}...{{/if}}
        // For now, we'll implement basic conditional logic
        result = handleConditionalTemplates(result, arguments);
        
        return result;
    }
    
    /**
     * Handle basic conditional template logic for {{#if}} statements
     * Simplified implementation for common use cases
     */
    private String handleConditionalTemplates(String text, Map<String, Object> arguments) {
        // Handle {{#if variable}} content {{/if}} patterns
        String result = text;
        
        // Simple regex to find {{#if variable}} content {{/if}} blocks
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "\\{\\{#if ([^}]+)\\}\\}([^{]*?)\\{\\{/if\\}\\}"
        );
        java.util.regex.Matcher matcher = pattern.matcher(text);
        
        while (matcher.find()) {
            String variable = matcher.group(1).trim();
            String content = matcher.group(2);
            String fullMatch = matcher.group(0);
            
            // Check if the variable exists and is truthy
            Object value = arguments.get(variable);
            boolean condition = false;
            
            if (value != null) {
                if (value instanceof Boolean) {
                    condition = (Boolean) value;
                } else if (value instanceof String) {
                    condition = !((String) value).isEmpty() && 
                               !((String) value).equalsIgnoreCase("false");
                } else {
                    condition = true; // Non-null, non-boolean, non-empty string values are truthy
                }
            }
            
            // Replace the conditional block with content or empty string
            String replacement = condition ? content : "";
            result = result.replace(fullMatch, replacement);
        }
        
        return result;
    }

    /**
     * Helper method to get prompt definition by name following MCP 2025-06-18 specification
     * Includes message templates for all Exotel Communication and Audio prompts
     */
    private Map<String, Object> getPromptByName(String name) {
        switch (name) {
            // ========== EXOTEL COMMUNICATION PROMPTS ==========
            
            case "send_sms":
                return createPromptResponse(
                    "send_sms",
                    "Send an SMS message to a phone number with DLT compliance",
                    "Please send an SMS to {{phone_number}} with the message: '{{message}}' using DLT Template ID: {{dlt_template_id}} and Entity ID: {{dlt_entity_id}}",
                    "I'll send the SMS to {{phone_number}} using the sendSmsToUser tool with DLT compliance. Let me process this request now."
                );
                
            case "make_voice_call":
                return createPromptResponse(
                    "make_voice_call",
                    "Initiate a voice call to a phone number",
                    "Please make a voice call to {{phone_number}}",
                    "I'll initiate a voice call to {{phone_number}} using the sendVoiceCallToUser tool. The call will be placed now."
                );
                
            case "connect_calls":
                return createPromptResponse(
                    "connect_calls",
                    "Connect two phone numbers in a voice call",
                    "Please connect {{from_number}} to {{to_number}} in a voice call",
                    "I'll connect {{from_number}} to {{to_number}} using the outgoingCallToConnectNumber tool. Setting up the connection now."
                );
                
            case "send_bulk_sms":
                return createPromptResponse(
                    "send_bulk_sms",
                    "Send the same SMS message to multiple phone numbers",
                    "Please send this message: '{{message}}' to these phone numbers: {{phone_numbers}}",
                    "I'll send the bulk SMS to all the specified numbers using the sendMessageToBulkNumbers tool. Processing the bulk message now."
                );
                
            case "send_dynamic_bulk_sms":
                return createPromptResponse(
                    "send_dynamic_bulk_sms",
                    "Send personalized SMS messages to multiple recipients",
                    "Please send these personalized messages: {{messages_json}}",
                    "I'll send the personalized SMS messages to each recipient using the sendDynamicBulkSms tool. Processing the dynamic bulk messages now."
                );
                
            case "connect_call_flow":
                return createPromptResponse(
                    "connect_call_flow",
                    "Connect a phone number to a predefined Exotel call flow",
                    "Please connect {{from_number}} to the call flow with app ID: {{app_id}}",
                    "I'll connect {{from_number}} to the call flow {{app_id}} using the connectNumberToCallFlow tool. Initiating the call flow connection now."
                );
                
            case "check_sms_status":
                return createPromptResponse(
                    "check_sms_status",
                    "Check the delivery status of SMS messages for a phone number",
                    "Please check the SMS delivery status for {{phone_number}}",
                    "I'll check the SMS delivery status for {{phone_number}} using the getSmsCallbacks tool. Retrieving the status information now."
                );
                
            case "check_call_status":
                return createPromptResponse(
                    "check_call_status",
                    "Check the status and details of voice calls for a phone number",
                    "Please check the voice call status for {{phone_number}}",
                    "I'll check the voice call status for {{phone_number}} using the getVoiceCallCallbacks tool. Retrieving the call history and status now."
                );
                
            case "get_call_details":
                return createPromptResponse(
                    "get_call_details",
                    "Get detailed information about a specific call using Call SID",
                    "Please get the details for call with SID: {{call_sid}}",
                    "I'll retrieve the detailed information for call SID {{call_sid}} using the getCallDetails tool. Fetching the call details now."
                );
                
            case "lookup_number_info":
                return createPromptResponse(
                    "lookup_number_info",
                    "Get metadata and information about a phone number",
                    "Please lookup information for phone number: {{phone_number}}",
                    "I'll lookup the metadata and information for {{phone_number}} using the getNumberMetadata tool. Retrieving the number information now."
                );
                
            // ========== QUICK AUDIO PROMPTS ==========
            
            case "quick_play_audio":
                return createPromptResponse(
                    "quick_play_audio",
                    "🎵 Play any audio URL instantly in your browser with one click",
                    "Please play this audio: {{audio_url}}",
                    "🎵 I'll create a direct playback link for {{audio_url}} using the quickPlayAudio tool. You'll get a clickable link to play the audio instantly in your browser!"
                );
                
            case "open_audio_player":
                return createPromptResponse(
                    "open_audio_player",
                    "🎛️ Open the web-based audio player interface for manual audio control",
                    "Please open the audio player interface",
                    "🎛️ I'll provide you with a link to the web-based audio player using the openAudioPlayer tool. You can use it to play any audio file or URL with full controls!"
                );
                
            case "download_audio":
                return createPromptResponse(
                    "download_audio",
                    "💾 Get a direct download link for any audio URL",
                    "Please provide a download link for this audio: {{audio_url}}",
                    "💾 I'll create a direct download link for {{audio_url}} using the downloadAudioQuick tool. You'll get a clickable link to download the audio file to your computer!"
                );
                
            // ========== WORKFLOW PROMPTS ==========
            
            case "communication_workflow":
                return createPromptResponse(
                    "communication_workflow",
                    "Execute a complete communication workflow with SMS and voice calls",
                    "Please execute a {{workflow_type}} workflow for {{phone_number}}" + 
                    "{{#if message}} with message: '{{message}}'{{/if}}" +
                    "{{#if follow_up}} and follow up with a voice call{{/if}}",
                    "I'll execute the {{workflow_type}} communication workflow for {{phone_number}}. " +
                    "This will include the appropriate SMS and voice call tools based on your requirements."
                );
                
            case "bulk_campaign":
                return createPromptResponse(
                    "bulk_campaign",
                    "Execute a bulk communication campaign with SMS and optional voice follow-up",
                    "Please run a {{campaign_type}} campaign for these recipients: {{recipient_list}} " +
                    "with message: '{{sms_message}}'{{#if include_voice}} and include voice calls{{/if}}",
                    "I'll execute the {{campaign_type}} bulk communication campaign for all recipients. " +
                    "This will include SMS messaging{{#if include_voice}} followed by voice calls{{/if}} using the appropriate Exotel tools."
                );
                
            // ========== CQA PROMPTS ==========
            
            case "cqa_ingest_interaction":
                return createPromptResponse(
                    "cqa_ingest_interaction",
                    "Submit a call recording or transcript to Exotel Conversational Intelligence for AI-powered quality scoring",
                    "Please ingest interaction {{external_interaction_id}} ({{channel_type}}) " +
                    "{{#if audio_url}}with audio at {{audio_url}}{{/if}} " +
                    "{{#if transcript_url}}with transcript at {{transcript_url}}{{/if}} " +
                    "{{#if language}}in {{language}}{{/if}} into Conversational Intelligence for quality analysis",
                    "I'll submit this interaction to Conversational Intelligence using the cqaIngestInteraction tool. " +
                    "Once ingested, the platform will run it through the configured quality profiles and produce scores."
                );
                
            case "cqa_ingest_file":
                return createPromptResponse(
                    "cqa_ingest_file",
                    "Submit a CSV file for bulk Conversational Intelligence analysis",
                    "Please ingest the {{format}} file at {{file_url}} " +
                    "{{#if source}}from source '{{source}}'{{/if}} into Conversational Intelligence",
                    "I'll submit the file for bulk ingestion using the cqaIngestFile tool. " +
                    "The platform will process up to 100,000 rows asynchronously and return a job ID for tracking."
                );
                
            case "cqa_check_interaction":
                return createPromptResponse(
                    "cqa_check_interaction",
                    "Look up the processing status of a Conversational Intelligence interaction",
                    "Please check the status of interaction {{interaction_id}} in Conversational Intelligence",
                    "I'll retrieve the interaction details using the cqaGetInteraction tool. " +
                    "This will show the current status (queued/processing/completed/failed) and any analyses."
                );
                
            case "cqa_get_analysis":
                return createPromptResponse(
                    "cqa_get_analysis",
                    "Retrieve the full quality analysis scoring breakdown from Conversational Intelligence",
                    "Please get the quality analysis results for analysis ID {{analysis_id}}",
                    "I'll retrieve the full scoring breakdown using the cqaGetAnalysis tool. " +
                    "This includes categories, subcategories, individual KPI scores, AI justifications, and suggestions."
                );
                
            case "cqa_track_job":
                return createPromptResponse(
                    "cqa_track_job",
                    "Check the progress of a Conversational Intelligence batch or file ingestion job",
                    "Please check the status of job {{job_id}} in Conversational Intelligence",
                    "I'll track the job using the cqaTrackJob tool. " +
                    "This will show the overall job status, accepted/rejected counts, and paginated interactions."
                );
                
            default:
                return null;
        }
    }
    
    /**
     * Helper method to create consistent prompt response structure following MCP 2025-06-18 specification
     */
    private Map<String, Object> createPromptResponse(String name, String description, String userMessage, String assistantMessage) {
                return Map.of(
            "name", name,
            "description", description,
                    "messages", List.of(
                        Map.of(
                            "role", "user",
                            "content", Map.of(
                                "type", "text",
                        "text", userMessage
                            )
                        ),
                        Map.of(
                            "role", "assistant", 
                            "content", Map.of(
                                "type", "text",
                        "text", assistantMessage
                            )
                        )
                    )
                );
    }

    /**
     * Create error response - fixed to handle null values
     */
    private Map<String, Object> createErrorResponse(String message) {
        return createErrorResponse(null, message);
    }

    private Map<String, Object> createErrorResponse(Object id, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("code", -32603);
        error.put("message", message != null ? message : "Unknown error");
        
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", error);
        
        return response;
    }
    
    // ========== MCP RESOURCES IMPLEMENTATION ==========
    
    /**
     * Create resources list response following MCP 2025-06-18 specification
     * Exposes Exotel API documentation and schemas as resources
     */
    private Map<String, Object> createResourcesListResponse(Object id, Object params) {
        List<Map<String, Object>> resources = new ArrayList<>();
        
        // ========== EXOTEL API DOCUMENTATION RESOURCES ==========
        
        // 1. Voice API Documentation
        resources.add(createResource(
            "exotel://api/voice/overview",
            "voice-api-overview",
            "📞 Exotel Voice API Overview",
            "Complete overview of Exotel Voice API including authentication, endpoints, and examples",
            "text/markdown",
            null,
                        Map.of(
                "audience", List.of("assistant", "user"),
                "priority", 0.9
            )
        ));
        
        // 2. Voice API - Connect Two Numbers
        resources.add(createResource(
            "exotel://api/voice/connect-numbers",
            "voice-connect-numbers",
            "📞 Connect Two Numbers API",
            "API documentation for connecting two phone numbers via Exotel Voice API",
            "application/json",
            null,
                        Map.of(
                "audience", List.of("assistant"),
                "priority", 0.8
            )
        ));
        
        // 3. Voice API - Connect to Call Flow
        resources.add(createResource(
            "exotel://api/voice/call-flow",
            "voice-call-flow",
            "📞 Call Flow API",
            "API documentation for connecting numbers to Exotel call flows",
            "application/json",
            null,
            Map.of(
                "audience", List.of("assistant"),
                "priority", 0.8
            )
        ));
        
        // 4. SMS API Documentation
        resources.add(createResource(
            "exotel://api/sms/overview",
            "sms-api-overview",
            "💬 Exotel SMS API Overview",
            "Complete overview of Exotel SMS API including DLT compliance, bulk messaging, and callbacks",
            "text/markdown",
            null,
            Map.of(
                "audience", List.of("assistant", "user"),
                "priority", 0.9
            )
        ));
        
        // 5. SMS API - Send SMS
        resources.add(createResource(
            "exotel://api/sms/send",
            "sms-send-api",
            "💬 Send SMS API",
            "API documentation for sending SMS messages with DLT compliance",
            "application/json",
            null,
                        Map.of(
                "audience", List.of("assistant"),
                "priority", 0.8
            )
        ));
        
        // 6. SMS API - Bulk SMS
        resources.add(createResource(
            "exotel://api/sms/bulk",
            "sms-bulk-api",
            "💬 Bulk SMS API",
            "API documentation for sending bulk SMS messages",
            "application/json",
            null,
                        Map.of(
                "audience", List.of("assistant"),
                "priority", 0.7
            )
        ));
        
        // ========== EXOTEL API SCHEMAS ==========
        
        // 7. Voice API Request Schema
        resources.add(createResource(
            "exotel://schema/voice/request",
            "voice-request-schema",
            "📋 Voice API Request Schema",
            "JSON schema for Exotel Voice API requests including all parameters and validation rules",
            "application/schema+json",
            null,
            Map.of(
                "audience", List.of("assistant"),
                "priority", 0.6
            )
        ));
        
        // 8. Voice API Response Schema
        resources.add(createResource(
            "exotel://schema/voice/response",
            "voice-response-schema",
            "📋 Voice API Response Schema",
            "JSON schema for Exotel Voice API responses including status codes and callback formats",
            "application/schema+json",
            null,
            Map.of(
                "audience", List.of("assistant"),
                "priority", 0.6
            )
        ));
        
        // 9. SMS API Request Schema
        resources.add(createResource(
            "exotel://schema/sms/request",
            "sms-request-schema",
            "📋 SMS API Request Schema",
            "JSON schema for Exotel SMS API requests including DLT parameters and validation",
            "application/schema+json",
            null,
            Map.of(
                "audience", List.of("assistant"),
                "priority", 0.6
            )
        ));
        
        // 10. SMS API Response Schema
        resources.add(createResource(
            "exotel://schema/sms/response",
            "sms-response-schema",
            "📋 SMS API Response Schema",
            "JSON schema for Exotel SMS API responses including delivery status and callback formats",
            "application/schema+json",
            null,
            Map.of(
                "audience", List.of("assistant"),
                "priority", 0.6
            )
        ));
        
        // ========== CALLBACK DOCUMENTATION ==========
        
        // 11. Voice Callbacks
        resources.add(createResource(
            "exotel://callbacks/voice",
            "voice-callbacks-doc",
            "🔄 Voice Callbacks Documentation",
            "Complete documentation for Exotel Voice API callbacks including status events and payload formats",
            "text/markdown",
            null,
            Map.of(
                "audience", List.of("assistant"),
                "priority", 0.7
            )
        ));
        
        // 12. SMS Callbacks
        resources.add(createResource(
            "exotel://callbacks/sms",
            "sms-callbacks-doc",
            "🔄 SMS Callbacks Documentation",
            "Complete documentation for Exotel SMS API callbacks including delivery reports and status updates",
            "text/markdown",
            null,
            Map.of(
                "audience", List.of("assistant"),
                "priority", 0.7
            )
        ));
        
        // ========== ERROR HANDLING ==========
        
        // 13. Error Codes Documentation
        resources.add(createResource(
            "exotel://errors/codes",
            "error-codes-doc",
            "❌ Error Codes Documentation",
            "Complete list of Exotel API error codes, their meanings, and troubleshooting guides",
            "text/markdown",
            null,
            Map.of(
                "audience", List.of("assistant", "user"),
                "priority", 0.5
            )
        ));
        
        // ========== USAGE EXAMPLES ==========
        
        // 14. Voice API Examples
        resources.add(createResource(
            "exotel://examples/voice",
            "voice-examples",
            "💡 Voice API Usage Examples",
            "Real-world examples and code samples for Exotel Voice API integration",
            "text/markdown",
            null,
            Map.of(
                "audience", List.of("assistant", "user"),
                "priority", 0.8
            )
        ));
        
        // 15. SMS API Examples
        resources.add(createResource(
            "exotel://examples/sms",
            "sms-examples",
            "💡 SMS API Usage Examples",
            "Real-world examples and code samples for Exotel SMS API integration with DLT compliance",
            "text/markdown",
            null,
            Map.of(
                "audience", List.of("assistant", "user"),
                "priority", 0.8
            )
        ));
        
        // ========== CONVERSATIONAL INTELLIGENCE RESOURCES ==========
        
        resources.add(createResource(
            "exotel://cqa/api/overview",
            "conversational-intelligence-api-overview",
            "Conversational Intelligence API Overview",
            "Complete overview of Exotel Conversational Intelligence API: data import, analysis retrieval, authentication, and rate limits",
            "text/markdown",
            null,
            Map.of(
                "audience", List.of("assistant", "user"),
                "priority", 0.9
            )
        ));
        
        resources.add(createResource(
            "exotel://cqa/api/ingest",
            "conversational-intelligence-ingest-api",
            "Conversational Intelligence Data Import API",
            "API documentation for ingesting interactions into Conversational Intelligence: single, batch, and file-based ingestion",
            "text/markdown",
            null,
            Map.of(
                "audience", List.of("assistant"),
                "priority", 0.8
            )
        ));
        
        resources.add(createResource(
            "exotel://cqa/api/analysis",
            "conversational-intelligence-analysis-api",
            "Conversational Intelligence Analysis API",
            "API documentation for retrieving quality analysis results with category and KPI scoring breakdowns",
            "text/markdown",
            null,
            Map.of(
                "audience", List.of("assistant"),
                "priority", 0.8
            )
        ));
        
        resources.add(createResource(
            "exotel://cqa/schema/csv",
            "conversational-intelligence-csv-schema",
            "Conversational Intelligence CSV File Schema",
            "CSV column specifications for bulk file ingestion including canonical column names and column mapping",
            "text/markdown",
            null,
            Map.of(
                "audience", List.of("assistant"),
                "priority", 0.7
            )
        ));
        
        Map<String, Object> result = Map.of("resources", resources);
        return Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "result", result
        );
    }
    
    /**
     * Helper method to create resource objects following MCP 2025-06-18 specification
     */
    private Map<String, Object> createResource(String uri, String name, String title, 
                                             String description, String mimeType, Integer size,
                                             Map<String, Object> annotations) {
        Map<String, Object> resource = new HashMap<>();
        resource.put("uri", uri);
        resource.put("name", name);
        resource.put("title", title);
        resource.put("description", description);
        resource.put("mimeType", mimeType);
        if (size != null) {
            resource.put("size", size);
        }
        if (annotations != null && !annotations.isEmpty()) {
            resource.put("annotations", annotations);
        }
        return resource;
    }
    
    /**
     * Read a specific resource by URI following MCP 2025-06-18 specification
     * Returns the actual content of Exotel API documentation
     */
    private Map<String, Object> readResource(Object id, Object params) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) params;
            String uri = (String) parameters.get("uri");
            
            if (uri == null || uri.isEmpty()) {
                return createErrorResponse(id, "Resource URI is required");
            }
            
            Map<String, Object> resourceContent = getResourceContent(uri);
            if (resourceContent == null) {
        Map<String, Object> error = new HashMap<>();
                error.put("code", -32002);
                error.put("message", "Resource not found");
                error.put("data", Map.of("uri", uri));
        
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", error);
        return response;
            }
            
            Map<String, Object> result = Map.of("contents", List.of(resourceContent));
            return Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "result", result
            );
            
        } catch (Exception e) {
            return createErrorResponse(id, "Error reading resource: " + e.getMessage());
        }
    }
    
    /**
     * Get the actual content for a resource URI
     * This method returns the detailed Exotel API documentation content
     */
    private Map<String, Object> getResourceContent(String uri) {
        switch (uri) {
            case "exotel://api/voice/overview":
                return createTextResourceContent(
                    uri,
                    "voice-api-overview",
                    "📞 Exotel Voice API Overview",
                    "text/markdown",
                    getVoiceApiOverviewContent()
                );
                
            case "exotel://api/voice/connect-numbers":
                return createTextResourceContent(
                    uri,
                    "voice-connect-numbers",
                    "📞 Connect Two Numbers API",
                    "application/json",
                    getVoiceConnectNumbersApiContent()
                );
                
            case "exotel://api/sms/overview":
                return createTextResourceContent(
                    uri,
                    "sms-api-overview",
                    "💬 Exotel SMS API Overview",
                    "text/markdown",
                    getSmsApiOverviewContent()
                );
                
            case "exotel://api/sms/send":
                return createTextResourceContent(
                    uri,
                    "sms-send-api",
                    "💬 Send SMS API",
                    "application/json",
                    getSmsSendApiContent()
                );
                
            case "exotel://examples/voice":
                return createTextResourceContent(
                    uri,
                    "voice-examples",
                    "💡 Voice API Usage Examples",
                    "text/markdown",
                    getVoiceExamplesContent()
                );
                
            case "exotel://examples/sms":
                return createTextResourceContent(
                    uri,
                    "sms-examples",
                    "💡 SMS API Usage Examples",
                    "text/markdown",
                    getSmsExamplesContent()
                );
                
            case "exotel://cqa/api/overview":
                return createTextResourceContent(
                    uri,
                    "conversational-intelligence-api-overview",
                    "Conversational Intelligence API Overview",
                    "text/markdown",
                    getCqaApiOverviewContent()
                );
                
            case "exotel://cqa/api/ingest":
                return createTextResourceContent(
                    uri,
                    "conversational-intelligence-ingest-api",
                    "Conversational Intelligence Data Import API",
                    "text/markdown",
                    getCqaIngestApiContent()
                );
                
            case "exotel://cqa/api/analysis":
                return createTextResourceContent(
                    uri,
                    "conversational-intelligence-analysis-api",
                    "Conversational Intelligence Analysis API",
                    "text/markdown",
                    getCqaAnalysisApiContent()
                );
                
            case "exotel://cqa/schema/csv":
                return createTextResourceContent(
                    uri,
                    "conversational-intelligence-csv-schema",
                    "Conversational Intelligence CSV File Schema",
                    "text/markdown",
                    getCqaCsvSchemaContent()
                );
                
            default:
                return null;
        }
    }
    
    /**
     * Helper method to create text resource content
     */
    private Map<String, Object> createTextResourceContent(String uri, String name, String title, 
                                                        String mimeType, String text) {
        Map<String, Object> content = new HashMap<>();
        content.put("uri", uri);
        content.put("name", name);
        content.put("title", title);
        content.put("mimeType", mimeType);
        content.put("text", text);
        return content;
    }
    
    /**
     * Create resource templates list response following MCP 2025-06-18 specification
     */
    private Map<String, Object> createResourceTemplatesListResponse(Object id) {
        List<Map<String, Object>> resourceTemplates = new ArrayList<>();
        
        // Template for dynamic API endpoint documentation
        Map<String, Object> apiTemplate = new HashMap<>();
        apiTemplate.put("uriTemplate", "exotel://api/{endpoint}/{method}");
        apiTemplate.put("name", "Exotel API Endpoints");
        apiTemplate.put("title", "📚 Dynamic Exotel API Documentation");
        apiTemplate.put("description", "Access documentation for any Exotel API endpoint dynamically");
        apiTemplate.put("mimeType", "application/json");
        resourceTemplates.add(apiTemplate);
        
        // Template for schema access
        Map<String, Object> schemaTemplate = new HashMap<>();
        schemaTemplate.put("uriTemplate", "exotel://schema/{api}/{type}");
        schemaTemplate.put("name", "Exotel API Schemas");
        schemaTemplate.put("title", "📋 Dynamic API Schema Access");
        schemaTemplate.put("description", "Access JSON schemas for any Exotel API request or response");
        schemaTemplate.put("mimeType", "application/schema+json");
        resourceTemplates.add(schemaTemplate);
        
        Map<String, Object> result = Map.of("resourceTemplates", resourceTemplates);
        return Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "result", result
        );
    }
    
    /**
     * Subscribe to resource changes (basic implementation)
     */
    private Map<String, Object> subscribeToResource(Object id, Object params) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) params;
            String uri = (String) parameters.get("uri");
            
            Map<String, Object> result = Map.of(
                "subscribed", true,
                "uri", uri != null ? uri : "unknown"
            );
            
            return Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "result", result
            );
            
        } catch (Exception e) {
            return createErrorResponse(id, "Error subscribing to resource: " + e.getMessage());
        }
    }
    
    /**
     * Unsubscribe from resource changes following MCP 2025-06-18 specification
     */
    private Map<String, Object> unsubscribeFromResource(Object id, Object params) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) params;
            String uri = (String) parameters.get("uri");
            
            if (uri == null || uri.isEmpty()) {
                return createErrorResponse(id, "Resource URI is required for unsubscribe");
            }
            
            // In a real implementation, you would remove the subscription from your tracking
            // For this basic implementation, we'll just acknowledge the unsubscribe request
            
            Map<String, Object> result = Map.of(
                "unsubscribed", true,
                "uri", uri
            );
            
            return Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "result", result
            );
            
        } catch (Exception e) {
            return createErrorResponse(id, "Error unsubscribing from resource: " + e.getMessage());
        }
    }
    
    // ========== EXOTEL API CONTENT METHODS ==========
    
    /**
     * Get Voice API Overview content based on https://developer.exotel.com/api/make-a-call-api
     */
    private String getVoiceApiOverviewContent() {
        return """
# Exotel Voice API Overview

## Base URL
```
https://<your_api_key>:<your_api_token>@<subdomain>/v1/Accounts/<your_sid>/
```

## Authentication
- Uses HTTP Basic Authentication
- API Key and API Token in the format: `api_key:api_token`
- Account SID is required in the URL path

## Available Endpoints

### 1. Connect Two Numbers
**POST** `/Calls/connect`
- Connects two phone numbers in sequence
- First connects to `From` number, then connects to `To` number
- Supports recording and status callbacks

### 2. Connect to Call Flow
**POST** `/Calls/connect`
- Connects a number to a predefined call flow
- Uses Exotel's call flow engine for IVR
- Supports custom applications and routing

## Common Parameters
- `From`: Source phone number
- `To`: Destination phone number (for direct calls)
- `CallerId`: Caller ID to display
- `StatusCallback`: URL for status notifications
- `Record`: Enable/disable call recording

## Response Format
All responses are in JSON format with call details, status, and tracking information.
""";
    }
    
    /**
     * Get Voice Connect Numbers API content from https://developer.exotel.com/api/make-a-call-api
     */
    private String getVoiceConnectNumbersApiContent() {
        return """
{
  "endpoint": "POST /v1/Accounts/{AccountSid}/Calls/connect",
  "description": "Connect two phone numbers via Exotel Voice API",
  "parameters": {
    "From": {
      "type": "string",
      "required": true,
      "description": "The phone number to call first",
      "example": "+919876543210"
    },
    "To": {
      "type": "string", 
      "required": true,
      "description": "The phone number to connect to after From answers",
      "example": "+919876543211"
    },
    "CallerId": {
      "type": "string",
      "required": true,
      "description": "The caller ID to display",
      "example": "08033755555"
    },
    "StatusCallback": {
      "type": "string",
      "required": false,
      "description": "URL to receive status callbacks",
      "example": "https://your-app.com/callback"
    },
    "StatusCallbackEvents": {
      "type": "array",
      "required": false,
      "description": "Events to receive callbacks for",
      "example": ["terminal", "in-progress"]
    },
    "Record": {
      "type": "boolean",
      "required": false,
      "description": "Whether to record the call",
      "default": false
    }
  },
  "response": {
    "Call": {
      "Sid": "string",
      "Status": "string",
      "From": "string",
      "To": "string",
      "Direction": "outbound-api",
      "DateCreated": "string",
      "DateUpdated": "string"
    }
  },
  "example_request": {
    "curl": "curl -X POST https://api_key:api_token@subdomain/v1/Accounts/sid/Calls/connect -d 'From=+919876543210' -d 'To=+919876543211' -d 'CallerId=08033755555'"
  }
}
""";
    }
    
    /**
     * Get SMS API Overview content
     */
    private String getSmsApiOverviewContent() {
        return """
# Exotel SMS API Overview

## Base URL
```
https://<your_api_key>:<your_api_token>@<subdomain>/v1/Accounts/<your_sid>/
```

## DLT Compliance
All SMS messages in India must comply with DLT (Distributed Ledger Technology) regulations:
- **DLT Template ID**: Required for each message type
- **DLT Entity ID**: Required for sender identification
- **Message Content**: Must match registered DLT template

## Available Endpoints

### 1. Send Single SMS
**POST** `/Sms/send.json`
- Send SMS to a single recipient
- Requires DLT compliance parameters

### 2. Send Bulk SMS
**POST** `/Sms/send.json`
- Send same message to multiple recipients
- Uses `To[0]`, `To[1]` format for multiple numbers

### 3. Send Dynamic Bulk SMS
**POST** `/Sms/bulksend.json`
- Send different messages to different recipients
- Uses `Message[0][To]`, `Message[0][Body]` format

## Common Parameters
- `From`: Registered sender number
- `To`: Recipient phone number(s)
- `Body`: Message content
- `DltTemplateId`: DLT registered template ID
- `DltEntityId`: DLT registered entity ID
- `StatusCallback`: URL for delivery reports

## Response Format
Returns SMS SID, status, and delivery information in JSON format.
""";
    }
    
    /**
     * Get SMS Send API content
     */
    private String getSmsSendApiContent() {
        return """
{
  "endpoint": "POST /v1/Accounts/{AccountSid}/Sms/send.json",
  "description": "Send SMS message to a single recipient with DLT compliance",
  "parameters": {
    "From": {
      "type": "string",
      "required": true,
      "description": "Registered sender number",
      "example": "08033755555"
    },
    "To": {
      "type": "string",
      "required": true,
      "description": "Recipient phone number",
      "example": "+919876543210"
    },
    "Body": {
      "type": "string",
      "required": true,
      "description": "SMS message content",
      "example": "Hello from Exotel!"
    },
    "DltTemplateId": {
      "type": "string",
      "required": true,
      "description": "DLT registered template ID",
      "example": "1234567890123456789"
    },
    "DltEntityId": {
      "type": "string",
      "required": true,
      "description": "DLT registered entity ID",
      "example": "9876543210987654321"
    },
    "StatusCallback": {
      "type": "string",
      "required": false,
      "description": "URL to receive delivery reports",
      "example": "https://your-app.com/sms-callback"
    },
    "SmsType": {
      "type": "string",
      "required": false,
      "description": "Type of SMS",
      "enum": ["transactional", "promotional"],
      "default": "transactional"
    }
  },
  "response": {
    "SMSMessage": {
      "Sid": "string",
      "Status": "string",
      "From": "string",
      "To": "string",
      "Body": "string",
      "DateCreated": "string",
      "DateSent": "string"
    }
  },
  "example_request": {
    "curl": "curl -X POST https://api_key:api_token@subdomain/v1/Accounts/sid/Sms/send.json -d 'From=08033755555' -d 'To=+919876543210' -d 'Body=Hello' -d 'DltTemplateId=1234567890123456789' -d 'DltEntityId=9876543210987654321'"
  }
}
""";
    }
    
    /**
     * Get Voice Examples content
     */
    private String getVoiceExamplesContent() {
        return """
# Exotel Voice API Usage Examples

## Example 1: Simple Call Connection
Connect two numbers with basic parameters:

```bash
curl -X POST \\
  https://api_key:api_token@subdomain/v1/Accounts/account_sid/Calls/connect \\
  -d "From=+919876543210" \\
  -d "To=+919876543211" \\
  -d "CallerId=08033755555"
```

## Example 2: Call with Recording and Callbacks
```bash
curl -X POST \\
  https://api_key:api_token@subdomain/v1/Accounts/account_sid/Calls/connect \\
  -d "From=+919876543210" \\
  -d "To=+919876543211" \\
  -d "CallerId=08033755555" \\
  -d "Record=true" \\
  -d "StatusCallback=https://your-app.com/voice-callback" \\
  -d "StatusCallbackEvents[0]=terminal"
```

## Example 3: Connect to Call Flow
```bash
curl -X POST \\
  https://api_key:api_token@subdomain/v1/Accounts/account_sid/Calls/connect \\
  -d "From=+919876543210" \\
  -d "CallerId=08033755555" \\
  -d "Url=https://my.exotel.com/account_sid/exoml/start_voice/app_id"
```

## Response Example
```json
{
  "Call": {
    "Sid": "abc123def456",
    "Status": "queued",
    "From": "+919876543210",
    "To": "+919876543211",
    "Direction": "outbound-api",
    "DateCreated": "2024-01-15 10:30:00",
    "DateUpdated": "2024-01-15 10:30:00"
  }
}
```
""";
    }
    
    /**
     * Get SMS Examples content
     */
    private String getSmsExamplesContent() {
        return """
# Exotel SMS API Usage Examples

## Example 1: Single SMS with DLT Compliance
```bash
curl -X POST \\
  https://api_key:api_token@subdomain/v1/Accounts/account_sid/Sms/send.json \\
  -d "From=08033755555" \\
  -d "To=+919876543210" \\
  -d "Body=Your OTP is 123456" \\
  -d "DltTemplateId=1234567890123456789" \\
  -d "DltEntityId=9876543210987654321" \\
  -d "SmsType=transactional"
```

## Example 2: Bulk SMS to Multiple Recipients
```bash
curl -X POST \\
  https://api_key:api_token@subdomain/v1/Accounts/account_sid/Sms/send.json \\
  -d "From=08033755555" \\
  -d "To[0]=+919876543210" \\
  -d "To[1]=+919876543211" \\
  -d "To[2]=+919876543212" \\
  -d "Body=Important announcement for all users" \\
  -d "StatusCallback=https://your-app.com/sms-callback"
```

## Example 3: Dynamic Bulk SMS
```bash
curl -X POST \\
  https://api_key:api_token@subdomain/v1/Accounts/account_sid/Sms/bulksend.json \\
  -d "From=08033755555" \\
  -d "Message[0][To]=+919876543210" \\
  -d "Message[0][Body]=Hello John" \\
  -d "Message[1][To]=+919876543211" \\
  -d "Message[1][Body]=Hello Jane" \\
  -d "StatusCallback=https://your-app.com/sms-callback"
```

## Response Example
```json
{
  "SMSMessage": {
    "Sid": "xyz789abc123",
    "Status": "queued",
    "From": "08033755555",
    "To": "+919876543210",
    "Body": "Your OTP is 123456",
    "DateCreated": "2024-01-15 10:30:00",
    "DateSent": null
  }
}
```
""";
    }
    
    // ========== CQA API CONTENT METHODS ==========
    
    private String getCqaApiOverviewContent() {
        return """
# Exotel Conversational Intelligence API Overview

## What is Conversational Intelligence?
Exotel Conversational Intelligence (formerly CQA) is an AI-powered platform that
analyzes 100% of customer conversations across voice and digital channels to deliver
real-time quality intelligence. Unlike traditional manual QA that relies on sampling,
Conversational Intelligence reviews thousands of conversations in minutes — maintaining
over 90% accuracy in objective evaluations.

### Key Capabilities
- **Continuous QA at Scale**: Reviews every conversation without sampling or added headcount
- **Self-Service Configuration**: Create and update KPIs instantly as SOPs evolve; attach SOPs
  directly to Quality Profiles for enhanced AI accuracy
- **Multi-Channel & Multilingual**: Works across voice, chat, email, SMS, and WhatsApp with
  consistent quality standards across regions and languages
- **Proactive Compliance Monitoring**: Flags deviations and risk patterns early to reduce
  regulatory exposure
- **Platform Agnostic**: Integrates with existing contact center stacks without ecosystem lock-in
- **Automated SOP Evaluation**: Evaluates conversations against defined KPIs and scripts
- **Upsell Opportunity Detection**: Identifies sales and cross-sell opportunities from
  conversation patterns

Learn more: https://exotel.com/products/conversation-quality-analysis/

## Authentication
All API endpoints authenticate via an API key passed in the `X-API-Key` header.
**Generate your API key from the Conversational Intelligence console** and include it
in the MCP configuration as `cqa_api_key`. Keys are scoped to a single account.

## Base URL
```
https://{host}/cqa/api/v1/accounts/{account_id}
```

## Rate Limits
| Endpoint Pattern       | Method | Default Limit          |
|------------------------|--------|------------------------|
| /ingress/interactions  | POST   | 100 requests/minute    |
| /ingress/*             | GET    | 300 requests/minute    |

## API Surface

### Data Import API (Ingress)
- **Single Ingest**: POST /ingress/interactions
- **Batch Ingest**: POST /ingress/interactions/batch (up to 100)
- **File Ingest**: POST /ingress/interactions/files (CSV, up to 100k rows)
- **Get Interaction**: GET /ingress/interactions/{id}
- **Track Job**: GET /ingress/interactions/batch/{id}

### Analysis API
- **Get Analysis**: GET /analyses/{analysis_id}

## Interaction Lifecycle
| Status     | Meaning                                    |
|------------|--------------------------------------------|
| queued     | Accepted, waiting to be processed          |
| processing | Analysis is underway                       |
| completed  | All analyses finished successfully         |
| failed     | Processing failed (check failure_reason)   |

## Response Envelope
All responses follow a common envelope with status, request_id, data, and error fields.

## API Reference
Full documentation: https://docs.exotel.com/conversation-intelligence/api-reference-guide
""";
    }
    
    private String getCqaIngestApiContent() {
        return """
# Conversational Intelligence Data Import API

## Ingest a Single Interaction
**POST** `/api/v1/accounts/{account_id}/ingress/interactions`

### Required Fields
- `external_interaction_id` (string): Your unique identifier for deduplication
- `channel_type` (string): voice, chat, email, sms, whatsapp

### Content Requirement
At least one of `audio_url` or `transcript_url` must be provided.

### Optional Fields
- `source` (string): Originating system identifier
- `language` (string): Language code (e.g. en, hi)
- `interaction_start_time` (ISO 8601): When the interaction started
- `duration_seconds` (integer): Duration in seconds
- `audio_format` (string): Format hint (wav, mp3, ogg)
- `callback_url` (string): Webhook URL for status notifications
- `pii_redacted` (boolean): Whether PII has been redacted (default false)
- `metadata` (object): Up to 50 key-value pairs

### Response (201 Created)
```json
{
  "status": 201,
  "data": {
    "interaction_id": "550e8400-...",
    "external_interaction_id": "call-001",
    "status": "queued"
  }
}
```

---

## Ingest a Batch
**POST** `/api/v1/accounts/{account_id}/ingress/interactions/batch`

- Body: `{ "interactions": [...], "skip_duplication_check": false }`
- Max 100 interactions per batch
- Returns a job ID for tracking

---

## Submit a File
**POST** `/api/v1/accounts/{account_id}/ingress/interactions/files`

- `file_url` (required): URL to CSV file (https, http, s3)
- `format` (required): "csv"
- `column_mapping` (optional): Map your headers to canonical names
- `metadata` (optional): Default metadata merged into every row
- Max 100,000 rows, 100 MB file size

---

## Get Interaction
**GET** `/api/v1/accounts/{account_id}/ingress/interactions/{identifier}`

Accepts the platform-assigned UUID or external_interaction_id. Returns status, metadata, and analyses array.

---

## Track Batch/File Job
**GET** `/api/v1/accounts/{account_id}/ingress/interactions/batch/{id}?page=0&size=20`

Returns job_status (pending/processing/completed/failed), total_rows, accepted, rejected,
errors array, and paginated interaction list.
""";
    }
    
    private String getCqaAnalysisApiContent() {
        return """
# Conversational Intelligence Analysis API

## Get Analysis Detail
**GET** `/api/v1/accounts/{account_id}/analyses/{analysis_id}`

Returns the full scoring breakdown for a completed analysis.

### Response Fields
| Field                      | Type   | Description                                      |
|----------------------------|--------|--------------------------------------------------|
| analysis_id                | UUID   | Unique analysis identifier                       |
| interaction_id             | UUID   | The interaction this analysis belongs to          |
| external_interaction_id    | string | Your interaction identifier                      |
| profile_id                 | string | Quality profile used for scoring                 |
| profile_name               | string | Human-readable quality profile name              |
| status                     | string | queued, processing, completed, or failed         |
| ai_score                   | float  | AI-generated quality score                       |
| qa_score                   | float  | Manual QA score (if reviewer overrode)           |
| final_score                | float  | Effective score (qa_score if present, else ai)   |
| criticality_adjusted_score | float  | Score after criticality weights                  |
| max_score                  | float  | Maximum possible score for the profile           |
| categories                 | array  | Scored categories with subcategories and KPIs    |

### Category Object
Each category contains `name`, `ai_score`, `qa_score`, `final_score`,
`criticality_adjusted_score`, `max_score`, and `sub_categories` array.

### KPI Object
Each KPI contains:
- `kpi_name`: Name of the KPI (e.g. "Proper Greeting")
- `ai_response`: The AI's answer (e.g. "yes", "no", "partially")
- `ai_justification`: The AI's reasoning for its score
- `ai_suggestion`: Optional improvement suggestion
- `ai_score`, `qa_score`, `final_score`, `max_score`

### Example Response
```json
{
  "status": 200,
  "data": {
    "analysis_id": "a1b2c3d4-...",
    "profile_name": "Voice Quality Standard",
    "ai_score": 85.0,
    "final_score": 85.0,
    "max_score": 100.0,
    "categories": [
      {
        "name": "Communication Skills",
        "ai_score": 90.0,
        "sub_categories": [
          {
            "name": "Greeting",
            "kpis": [
              {
                "kpi_name": "Proper Greeting",
                "ai_response": "yes",
                "ai_justification": "Agent greeted the customer promptly...",
                "ai_score": 10.0,
                "max_score": 10.0
              }
            ]
          }
        ]
      }
    ]
  }
}
```
""";
    }
    
    private String getCqaCsvSchemaContent() {
        return """
# Conversational Intelligence CSV File Schema

## Canonical Column Names
The first row of a CSV must contain headers. Headers are trimmed and lowercased.

| Column                  | Required | Type        | Description                              |
|-------------------------|----------|-------------|------------------------------------------|
| external_interaction_id | Yes      | string      | Your unique interaction identifier       |
| channel_type            | Yes      | string      | voice, chat, email, sms, whatsapp        |
| source                  | No       | string      | Originating system identifier            |
| language                | No       | string      | Language code (e.g. en, hi)              |
| interaction_start_time  | No       | ISO 8601    | When the interaction started             |
| duration_seconds        | No       | integer     | Interaction duration in seconds          |
| audio_format            | No       | string      | Format hint (wav, mp3, ogg)              |
| callback_url            | No       | string      | Per-row callback URL                     |
| pii_redacted            | No       | boolean     | true or false                            |
| audio_url               | No       | string      | Audio file URL(s), semicolon-separated   |
| transcript_url          | No       | string      | Transcript file URL(s), semicolon-sep    |

### Content Requirement
Each row must have at least one of: audio_url, transcript_url.

### Extra Columns Become Metadata
Any column header not in the canonical set is automatically added to the
row's metadata map. E.g. columns named "agent", "campaign", or "disposition"
become metadata key-value pairs.

## Column Mapping
If your CSV uses non-standard headers, supply a `column_mapping` object:
```json
{
  "column_mapping": {
    "call id": "external_interaction_id",
    "type": "channel_type",
    "recording": "audio_url"
  }
}
```

## Example CSV
```csv
external_interaction_id,channel_type,audio_url,transcript_url,language,agent,campaign
call-001,voice,https://s3.example.com/rec-001.wav,https://s3.example.com/tr-001.txt,en,agent-42,retention
call-002,voice,https://s3.example.com/rec-002.wav,,hi,agent-15,support
```

## Limits
| Constraint                    | Value   |
|-------------------------------|---------|
| Max rows per file job         | 100,000 |
| Max file size                 | 100 MB  |
| Max concurrent file jobs      | 5       |
| Max metadata keys per row     | 50      |
| Supported formats             | csv         |
| Supported URL schemes         | https, http, s3 |
""";
    }
}