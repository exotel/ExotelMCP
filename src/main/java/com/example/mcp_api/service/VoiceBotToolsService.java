package com.example.mcp_api.service;

import com.example.mcp_api.auth.AuthContext;
import com.example.mcp_api.auth.AuthCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * MCP tools for the Exotel Tools Server — manage callable tools, tables, and
 * knowledge corpora that VoiceBots can invoke during conversations.
 *
 * Credentials fall back in order:
 *   1. tools_server_* fields in the Authorization header (if provided)
 *   2. voicebot_* fields in the Authorization header (same key/token/account)
 *   3. @Value defaults from application.properties / env vars
 */
@Service
public class VoiceBotToolsService {

    private static final Logger logger = LoggerFactory.getLogger(VoiceBotToolsService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F\\-]{1,64}$");
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^[0-9]{1,5}$");

    private RestTemplate restTemplate;

    @Value("${exotel.voicebot.ssl.verify:true}")
    private boolean sslVerify;

    @Value("${exotel.tools.server.base.url:https://tools-server-prod.mum1.exotel.com}")
    private String defaultBaseUrl;

    @Value("${exotel.tools.server.api.key:${exotel.voicebot.api.key:}}")
    private String defaultApiKey;

    @Value("${exotel.tools.server.api.token:${exotel.voicebot.api.token:}}")
    private String defaultApiToken;

    @Value("${exotel.tools.server.tenant.id:${exotel.voicebot.account.id:}}")
    private String defaultTenantId;

    @jakarta.annotation.PostConstruct
    void init() {
        try {
            var connMgrBuilder = PoolingHttpClientConnectionManagerBuilder.create();
            if (!sslVerify) {
                SSLContext sslCtx = SSLContextBuilder.create()
                        .loadTrustMaterial(null, (chain, authType) -> true).build();
                connMgrBuilder.setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                        .setSslContext(sslCtx).setHostnameVerifier(NoopHostnameVerifier.INSTANCE).build());
            }
            var httpClient = HttpClients.custom().setConnectionManager(connMgrBuilder.build()).build();
            var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
            factory.setConnectTimeout(10_000);
            factory.setReadTimeout(30_000);
            this.restTemplate = new RestTemplate(factory);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create RestTemplate", e);
        }
    }

    // ======================== CREDENTIAL RESOLUTION ========================
    // Priority: tools_server_* header fields → voicebot_* header fields → @Value defaults

    private String getApiKey() {
        AuthCredentials creds = AuthContext.current();
        if (creds.getToolsServerApiKey() != null) return creds.getToolsServerApiKey();
        if (creds.getVoicebotApiKey() != null) return creds.getVoicebotApiKey();
        return defaultApiKey;
    }

    private String getApiToken() {
        AuthCredentials creds = AuthContext.current();
        if (creds.getToolsServerApiToken() != null) return creds.getToolsServerApiToken();
        if (creds.getVoicebotApiToken() != null) return creds.getVoicebotApiToken();
        return defaultApiToken;
    }

    private String getTenantId() {
        AuthCredentials creds = AuthContext.current();
        if (creds.getToolsServerTenantId() != null) return creds.getToolsServerTenantId();
        if (creds.getVoicebotAccountId() != null) return creds.getVoicebotAccountId();
        return defaultTenantId;
    }

    private String getBaseUrl() {
        AuthCredentials creds = AuthContext.current();
        return creds.effectiveToolsServerBaseUrl(defaultBaseUrl);
    }

    private String requireAuth() {
        String key = getApiKey();
        String tenantId = getTenantId();
        if (key != null && !key.isBlank() && tenantId != null && !tenantId.isBlank()) return null;
        return "Missing Tools Server credentials.\n\n"
            + "Add voicebot credentials (they are shared with the Tools Server):\n"
            + "  - voicebot_api_key\n"
            + "  - voicebot_api_token\n"
            + "  - voicebot_account_id\n\n"
            + "Or provide tools_server_* overrides if using a different account.\n"
            + "For setup help, use the tool: exotel_setup_guide";
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String key = getApiKey();
        String token = getApiToken();
        if (key != null && !key.isBlank()) {
            String basic = Base64.getEncoder().encodeToString(
                    (key + ":" + (token != null ? token : "")).getBytes(StandardCharsets.UTF_8));
            headers.set("Authorization", "Basic " + basic);
        }
        return headers;
    }

    private void validateId(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (!UUID_PATTERN.matcher(value).matches())
            throw new IllegalArgumentException(name + " contains invalid characters");
    }

    private void validateNumericParam(String value, String name) {
        if (value != null && !value.isBlank() && !NUMERIC_PATTERN.matcher(value).matches())
            throw new IllegalArgumentException(name + " must be a number (1-99999)");
    }

    private String sanitize(String input) {
        if (input == null) return "null";
        return input.replaceAll("[\\r\\n\\t]", " ").substring(0, Math.min(input.length(), 200));
    }

    private String safeBody(ResponseEntity<String> response) {
        String body = response.getBody();
        if (body == null) return "{}";
        if (body.length() > MAX_RESPONSE_BYTES) return body.substring(0, MAX_RESPONSE_BYTES);
        return body;
    }

    private String errorMsg(String method, HttpClientErrorException e) {
        logger.error("{} API error: {} - {}", method, e.getStatusCode(), e.getResponseBodyAsString());
        return "Error " + e.getStatusCode().value() + ": " + method + " failed — " + e.getResponseBodyAsString();
    }

    private String serverError(String method, HttpServerErrorException e) {
        logger.error("{} server error: {} - {}", method, e.getStatusCode(), e.getResponseBodyAsString());
        return "Error " + e.getStatusCode().value() + ": " + method + " — upstream service unavailable";
    }

    // ======================== TENANT TOOLS ========================

    @Tool(name = "exotel_tools_server_tenant_create",
          description = "Creates a new tenant on the Exotel Tools Server. "
              + "Params: tenantId (unique string ID), name (human-readable name). "
              + "After creating, call exotel_tools_server_tenant_spawn to activate it.")
    public String createTenant(String tenantId, String name) {
        logger.info("Creating Tools Server tenant: {}", sanitize(tenantId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            if (tenantId == null || tenantId.isBlank()) return "Error: tenantId is required";
            if (name == null || name.isBlank()) return "Error: name is required";

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("tenant_id", tenantId);
            payload.put("name", name);

            String url = getBaseUrl() + "/tools/api/v1/tenants";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_tenant_create", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_tenant_create", e);
        } catch (Exception e) { return "Error creating tenant: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_tenant_spawn",
          description = "Spawns (activates) a tenant so it can start serving tool calls to VoiceBots. "
              + "Call this after creating a tenant or after exotel_tools_server_tenant_terminate to bring it back online.")
    public String spawnTenant(String tenantId) {
        logger.info("Spawning Tools Server tenant: {}", sanitize(tenantId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            if (tenantId == null || tenantId.isBlank()) return "Error: tenantId is required";
            String url = getBaseUrl() + "/tools/api/v1/tenants/" + tenantId + "/spawn";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(jsonHeaders()), String.class);
            String body = response.getBody();
            return (body != null && !body.isBlank()) ? body : "Tenant '" + tenantId + "' spawned successfully.";
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_tenant_spawn", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_tenant_spawn", e);
        } catch (Exception e) { return "Error spawning tenant: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_tenant_terminate",
          description = "Terminates (deactivates) a tenant on the Tools Server. The tenant stops serving tool calls. Use exotel_tools_server_tenant_spawn to reactivate.")
    public String terminateTenant(String tenantId) {
        logger.info("Terminating Tools Server tenant: {}", sanitize(tenantId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            if (tenantId == null || tenantId.isBlank()) return "Error: tenantId is required";
            String url = getBaseUrl() + "/tools/api/v1/tenants/" + tenantId + "/terminate";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(jsonHeaders()), String.class);
            String body = response.getBody();
            return (body != null && !body.isBlank()) ? body : "Tenant '" + tenantId + "' terminated successfully.";
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_tenant_terminate", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_tenant_terminate", e);
        } catch (Exception e) { return "Error terminating tenant: " + e.getMessage(); }
    }

    // ======================== TOOLS CRUD ========================

    @Tool(name = "exotel_tools_server_tool_list",
          description = "Lists all callable tools registered for the tenant. These are tools that VoiceBots invoke during conversations "
              + "(e.g. database lookups, API calls, knowledge searches). "
              + "Params: limit (default 20, max 100), offset, active ('true'/'false' to filter), type (tool type filter).")
    public String listTools(String limit, String offset, String active, String type) {
        logger.info("Listing Tools Server tools — tenant={}", sanitize(getTenantId()));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateNumericParam(limit, "limit");
            validateNumericParam(offset, "offset");

            StringBuilder url = new StringBuilder(getBaseUrl())
                    .append("/tools/api/v1/tenants/").append(getTenantId()).append("/tools?");
            url.append("limit=").append(limit != null && !limit.isBlank() ? limit : "20").append("&");
            if (offset != null && !offset.isBlank()) url.append("offset=").append(offset).append("&");
            if (active != null && !active.isBlank()) url.append("active=").append(active).append("&");
            if (type != null && !type.isBlank()) url.append("type=").append(type).append("&");

            ResponseEntity<String> response = restTemplate.exchange(url.toString(), HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_tool_list", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_tool_list", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error listing tools: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_tool_get",
          description = "Gets full details of a specific callable tool by its ID, including its current version definition (OpenAPI spec) and status.")
    public String getTool(String toolId) {
        logger.info("Getting tool: {}", sanitize(toolId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(toolId, "toolId");
            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId() + "/tools/" + toolId;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_tool_get", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_tool_get", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error getting tool: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_tool_create",
          description = "Creates a new callable tool in the Tools Server that VoiceBots can invoke during calls. "
              + "Params:\n"
              + "  - name: unique tool name (e.g. 'get_product_price')\n"
              + "  - type: always use 'openapi' — tools are defined as OpenAPI 3.0 specs\n"
              + "  - description: what the tool does (shown to the LLM to decide when to call it)\n"
              + "  - definitionJson: a full OpenAPI 3.0 JSON spec with 'openapi', 'info', 'servers', and 'paths'.\n"
              + "Example definitionJson: {\"openapi\":\"3.0.0\",\"info\":{\"title\":\"get_price\",\"version\":\"1.0.0\"},"
              + "\"servers\":[{\"url\":\"https://api.example.com\"}],\"paths\":{\"/price\":{\"get\":"
              + "{\"parameters\":[{\"name\":\"product_id\",\"in\":\"query\",\"schema\":{\"type\":\"string\"}}],"
              + "\"responses\":{\"200\":{\"description\":\"OK\"}}}}}}\n"
              + "After creation, attach this tool to a VoiceBot assistant using exotel_voicebot_assistant_push_version.")
    public String createTool(String name, String type, String description, String definitionJson) {
        logger.info("Creating tool: {} type={}", sanitize(name), sanitize(type));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            if (name == null || name.isBlank()) return "Error: name is required";
            if (type == null || type.isBlank()) return "Error: type is required";
            if (definitionJson == null || definitionJson.isBlank()) return "Error: definitionJson is required";

            JsonNode definitionNode;
            try { definitionNode = objectMapper.readTree(definitionJson); }
            catch (Exception e) { return "Error: definitionJson is not valid JSON — " + e.getMessage(); }

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("name", name);
            payload.put("type", type);
            if (description != null && !description.isBlank()) payload.put("description", description);
            payload.set("definition", definitionNode);

            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId() + "/tools";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_tool_create", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_tool_create", e);
        } catch (Exception e) { return "Error creating tool: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_tool_update",
          description = "Updates a tool's status or switches its active version. "
              + "Params: toolId (UUID), status ('active' or 'inactive' — optional), currentVersionId (UUID of version to activate — optional). "
              + "At least one of status or currentVersionId must be provided.")
    public String updateTool(String toolId, String status, String currentVersionId) {
        logger.info("Updating tool: {}", sanitize(toolId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(toolId, "toolId");

            ObjectNode payload = objectMapper.createObjectNode();
            if (status != null && !status.isBlank()) {
                if (!Set.of("active", "inactive").contains(status.toLowerCase()))
                    return "Error: status must be 'active' or 'inactive'";
                payload.put("status", status.toLowerCase());
            }
            if (currentVersionId != null && !currentVersionId.isBlank()) {
                validateId(currentVersionId, "currentVersionId");
                payload.put("current_version_id", currentVersionId);
            }
            if (payload.isEmpty()) return "Error: provide at least status or currentVersionId to update";

            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId() + "/tools/" + toolId;
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_tool_update", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_tool_update", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error updating tool: " + e.getMessage(); }
    }

    // ======================== TOOL VERSIONS ========================

    @Tool(name = "exotel_tools_server_tool_version_list",
          description = "Lists all versions of a callable tool. Use to review history or find the version ID to activate via exotel_tools_server_tool_update. "
              + "Params: toolId (UUID), limit, offset.")
    public String listToolVersions(String toolId, String limit, String offset) {
        logger.info("Listing versions for tool: {}", sanitize(toolId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(toolId, "toolId");
            validateNumericParam(limit, "limit");
            validateNumericParam(offset, "offset");

            StringBuilder url = new StringBuilder(getBaseUrl())
                    .append("/tools/api/v1/tenants/").append(getTenantId())
                    .append("/tools/").append(toolId).append("/versions?");
            if (limit != null && !limit.isBlank()) url.append("limit=").append(limit).append("&");
            if (offset != null && !offset.isBlank()) url.append("offset=").append(offset).append("&");

            ResponseEntity<String> response = restTemplate.exchange(url.toString(), HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_tool_version_list", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_tool_version_list", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error listing tool versions: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_tool_version_get",
          description = "Gets details of a specific tool version including its full OpenAPI 3.0 definition.")
    public String getToolVersion(String toolId, String versionId) {
        logger.info("Getting version {} for tool {}", sanitize(versionId), sanitize(toolId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(toolId, "toolId");
            validateId(versionId, "versionId");
            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId()
                    + "/tools/" + toolId + "/versions/" + versionId;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_tool_version_get", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_tool_version_get", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error getting tool version: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_tool_version_create",
          description = "Creates a new version of an existing callable tool with an updated OpenAPI definition. "
              + "Params: toolId (UUID), definitionJson (updated OpenAPI 3.0 JSON spec), changelog (description of what changed). "
              + "After creating, activate it using exotel_tools_server_tool_update with the returned version ID.")
    public String createToolVersion(String toolId, String definitionJson, String changelog) {
        logger.info("Creating version for tool: {}", sanitize(toolId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(toolId, "toolId");
            if (definitionJson == null || definitionJson.isBlank()) return "Error: definitionJson is required";

            JsonNode definitionNode;
            try { definitionNode = objectMapper.readTree(definitionJson); }
            catch (Exception e) { return "Error: definitionJson is not valid JSON — " + e.getMessage(); }

            ObjectNode payload = objectMapper.createObjectNode();
            payload.set("definition", definitionNode);
            if (changelog != null && !changelog.isBlank()) payload.put("changelog", changelog);

            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId()
                    + "/tools/" + toolId + "/versions";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_tool_version_create", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_tool_version_create", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error creating tool version: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_tool_version_test",
          description = "Tests a specific tool version by running it with sample inputs and returning the result. "
              + "Use this to validate a tool before activating it in production. "
              + "Params: toolId (UUID), versionId (UUID), testInputsJson (JSON object of input field names to values).")
    public String testToolVersion(String toolId, String versionId, String testInputsJson) {
        logger.info("Testing version {} for tool {}", sanitize(versionId), sanitize(toolId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(toolId, "toolId");
            validateId(versionId, "versionId");
            if (testInputsJson == null || testInputsJson.isBlank()) return "Error: testInputsJson is required";

            JsonNode testInputs;
            try { testInputs = objectMapper.readTree(testInputsJson); }
            catch (Exception e) { return "Error: testInputsJson is not valid JSON — " + e.getMessage(); }

            ObjectNode payload = objectMapper.createObjectNode();
            payload.set("test_inputs", testInputs);

            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId()
                    + "/tools/" + toolId + "/versions/" + versionId + "/tests";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_tool_version_test", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_tool_version_test", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error testing tool version: " + e.getMessage(); }
    }

    // ======================== TABLES ========================

    @Tool(name = "exotel_tools_server_table_list",
          description = "Lists all data tables in the Tools Server. Tables store structured lookup data (e.g. product catalogs, pricing, FAQs) "
              + "that VoiceBot tools can query during calls. Params: limit, offset.")
    public String listTables(String limit, String offset) {
        logger.info("Listing tables — tenant={}", sanitize(getTenantId()));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateNumericParam(limit, "limit");
            validateNumericParam(offset, "offset");

            StringBuilder url = new StringBuilder(getBaseUrl())
                    .append("/tools/api/v1/tenants/").append(getTenantId()).append("/tables?");
            if (limit != null && !limit.isBlank()) url.append("limit=").append(limit).append("&");
            if (offset != null && !offset.isBlank()) url.append("offset=").append(offset).append("&");

            ResponseEntity<String> response = restTemplate.exchange(url.toString(), HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_table_list", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_table_list", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error listing tables: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_table_get",
          description = "Gets full details of a data table including its column schema and metadata.")
    public String getTable(String tableId) {
        logger.info("Getting table: {}", sanitize(tableId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(tableId, "tableId");
            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId() + "/tables/" + tableId;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_table_get", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_table_get", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error getting table: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_table_create",
          description = "Creates a new data table for VoiceBot tool lookups. "
              + "Params: name, description (optional), "
              + "columnsJson (JSON array of column defs, each with 'name', 'type' (string/number/boolean), optional 'description'). "
              + "Example columnsJson: [{\"name\":\"product_id\",\"type\":\"string\"},{\"name\":\"price\",\"type\":\"number\"}]")
    public String createTable(String name, String description, String columnsJson) {
        logger.info("Creating table: {}", sanitize(name));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            if (name == null || name.isBlank()) return "Error: name is required";
            if (columnsJson == null || columnsJson.isBlank()) return "Error: columnsJson is required";

            JsonNode columnsNode;
            try { columnsNode = objectMapper.readTree(columnsJson); }
            catch (Exception e) { return "Error: columnsJson is not valid JSON — " + e.getMessage(); }
            if (!columnsNode.isArray()) return "Error: columnsJson must be a JSON array";

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("name", name);
            if (description != null && !description.isBlank()) payload.put("description", description);
            payload.set("columns", columnsNode);

            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId() + "/tables";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_table_create", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_table_create", e);
        } catch (Exception e) { return "Error creating table: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_table_update",
          description = "Updates a table's name, description, or columns. At least one field must be provided. "
              + "Params: tableId (UUID), name (optional), description (optional), columnsJson (optional JSON array).")
    public String updateTable(String tableId, String name, String description, String columnsJson) {
        logger.info("Updating table: {}", sanitize(tableId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(tableId, "tableId");

            ObjectNode payload = objectMapper.createObjectNode();
            if (name != null && !name.isBlank()) payload.put("name", name);
            if (description != null && !description.isBlank()) payload.put("description", description);
            if (columnsJson != null && !columnsJson.isBlank()) {
                JsonNode columnsNode;
                try { columnsNode = objectMapper.readTree(columnsJson); }
                catch (Exception e) { return "Error: columnsJson is not valid JSON — " + e.getMessage(); }
                if (!columnsNode.isArray()) return "Error: columnsJson must be a JSON array";
                payload.set("columns", columnsNode);
            }
            if (payload.isEmpty()) return "Error: provide at least one of name, description, or columnsJson";

            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId() + "/tables/" + tableId;
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_table_update", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_table_update", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error updating table: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_table_delete",
          description = "Permanently deletes a data table and all its rows. This action cannot be undone. Always confirm with the user before calling.")
    public String deleteTable(String tableId) {
        logger.info("Deleting table: {}", sanitize(tableId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(tableId, "tableId");
            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId() + "/tables/" + tableId;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE,
                    new HttpEntity<>(jsonHeaders()), String.class);
            String body = response.getBody();
            return (body != null && !body.isBlank()) ? body : "Table " + tableId + " deleted successfully.";
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_table_delete", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_table_delete", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error deleting table: " + e.getMessage(); }
    }

    // ======================== TABLE ROWS ========================

    @Tool(name = "exotel_tools_server_table_row_list",
          description = "Lists rows in a data table. Params: tableId (UUID), limit (default 50, max 500), offset.")
    public String listTableRows(String tableId, String limit, String offset) {
        logger.info("Listing rows for table: {}", sanitize(tableId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(tableId, "tableId");
            validateNumericParam(limit, "limit");
            validateNumericParam(offset, "offset");

            StringBuilder url = new StringBuilder(getBaseUrl())
                    .append("/tools/api/v1/tenants/").append(getTenantId())
                    .append("/tables/").append(tableId).append("/rows?");
            url.append("limit=").append(limit != null && !limit.isBlank() ? limit : "50").append("&");
            if (offset != null && !offset.isBlank()) url.append("offset=").append(offset).append("&");

            ResponseEntity<String> response = restTemplate.exchange(url.toString(), HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_table_row_list", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_table_row_list", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error listing table rows: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_table_row_create",
          description = "Inserts one or more rows into a data table. "
              + "Params: tableId (UUID), rowsJson (JSON array of row data objects matching the table's column schema). "
              + "Example rowsJson: [{\"product_id\":\"P001\",\"price\":99.99},{\"product_id\":\"P002\",\"price\":49.99}]")
    public String createTableRows(String tableId, String rowsJson) {
        logger.info("Creating rows for table: {}", sanitize(tableId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(tableId, "tableId");
            if (rowsJson == null || rowsJson.isBlank()) return "Error: rowsJson is required";

            JsonNode rowsNode;
            try { rowsNode = objectMapper.readTree(rowsJson); }
            catch (Exception e) { return "Error: rowsJson is not valid JSON — " + e.getMessage(); }
            if (!rowsNode.isArray()) return "Error: rowsJson must be a JSON array";

            ObjectNode payload = objectMapper.createObjectNode();
            payload.set("rows", rowsNode);

            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId()
                    + "/tables/" + tableId + "/rows";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_table_row_create", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_table_row_create", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error creating table rows: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_table_row_get",
          description = "Gets a single row from a table by its row ID.")
    public String getTableRow(String tableId, String rowId) {
        logger.info("Getting row {} from table {}", sanitize(rowId), sanitize(tableId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(tableId, "tableId");
            validateId(rowId, "rowId");
            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId()
                    + "/tables/" + tableId + "/rows/" + rowId;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_table_row_get", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_table_row_get", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error getting table row: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_table_row_update",
          description = "Updates a single table row by its ID. "
              + "Params: tableId (UUID), rowId (UUID), dataJson (JSON object with the updated field values).")
    public String updateTableRow(String tableId, String rowId, String dataJson) {
        logger.info("Updating row {} in table {}", sanitize(rowId), sanitize(tableId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(tableId, "tableId");
            validateId(rowId, "rowId");
            if (dataJson == null || dataJson.isBlank()) return "Error: dataJson is required";

            JsonNode dataNode;
            try { dataNode = objectMapper.readTree(dataJson); }
            catch (Exception e) { return "Error: dataJson is not valid JSON — " + e.getMessage(); }

            ObjectNode payload = objectMapper.createObjectNode();
            payload.set("data", dataNode);

            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId()
                    + "/tables/" + tableId + "/rows/" + rowId;
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(payload), jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_table_row_update", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_table_row_update", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error updating table row: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_table_row_delete",
          description = "Deletes a single row from a table by its row ID. This action cannot be undone.")
    public String deleteTableRow(String tableId, String rowId) {
        logger.info("Deleting row {} from table {}", sanitize(rowId), sanitize(tableId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(tableId, "tableId");
            validateId(rowId, "rowId");
            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId()
                    + "/tables/" + tableId + "/rows/" + rowId;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE,
                    new HttpEntity<>(jsonHeaders()), String.class);
            String body = response.getBody();
            return (body != null && !body.isBlank()) ? body : "Row " + rowId + " deleted from table " + tableId + ".";
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_table_row_delete", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_table_row_delete", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error deleting table row: " + e.getMessage(); }
    }

    // ======================== KNOWLEDGE CORPORA ========================

    @Tool(name = "exotel_tools_server_corpus_list",
          description = "Lists all knowledge corpora (document collections) for the tenant. "
              + "Corpora enable RAG-based knowledge retrieval during VoiceBot calls. "
              + "Params: limit, offset, status ('processing'/'ready'/'failed'), fromDate, toDate (ISO-8601).")
    public String listCorpora(String limit, String offset, String status, String fromDate, String toDate) {
        logger.info("Listing corpora — tenant={}", sanitize(getTenantId()));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateNumericParam(limit, "limit");
            validateNumericParam(offset, "offset");

            StringBuilder url = new StringBuilder(getBaseUrl())
                    .append("/tools/api/v1/tenants/").append(getTenantId()).append("/knowledge-corpora?");
            if (limit != null && !limit.isBlank()) url.append("limit=").append(limit).append("&");
            if (offset != null && !offset.isBlank()) url.append("offset=").append(offset).append("&");
            if (status != null && !status.isBlank()) url.append("status=").append(status).append("&");
            if (fromDate != null && !fromDate.isBlank()) url.append("from_date=").append(fromDate).append("&");
            if (toDate != null && !toDate.isBlank()) url.append("to_date=").append(toDate).append("&");

            ResponseEntity<String> response = restTemplate.exchange(url.toString(), HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_corpus_list", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_corpus_list", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error listing corpora: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_corpus_get",
          description = "Gets details of a specific knowledge corpus including its document list, processing status, and metadata.")
    public String getCorpus(String corpusId) {
        logger.info("Getting corpus: {}", sanitize(corpusId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(corpusId, "corpusId");
            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId()
                    + "/knowledge-corpora/" + corpusId;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders()), String.class);
            return safeBody(response);
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_corpus_get", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_corpus_get", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error getting corpus: " + e.getMessage(); }
    }

    @Tool(name = "exotel_tools_server_corpus_delete",
          description = "Permanently deletes a knowledge corpus and all its documents. This action cannot be undone. Always confirm with the user before calling.")
    public String deleteCorpus(String corpusId) {
        logger.info("Deleting corpus: {}", sanitize(corpusId));
        try {
            String authErr = requireAuth(); if (authErr != null) return authErr;
            validateId(corpusId, "corpusId");
            String url = getBaseUrl() + "/tools/api/v1/tenants/" + getTenantId()
                    + "/knowledge-corpora/" + corpusId;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE,
                    new HttpEntity<>(jsonHeaders()), String.class);
            String body = response.getBody();
            return (body != null && !body.isBlank()) ? body : "Corpus " + corpusId + " deleted successfully.";
        } catch (HttpClientErrorException e) { return errorMsg("exotel_tools_server_corpus_delete", e);
        } catch (HttpServerErrorException e) { return serverError("exotel_tools_server_corpus_delete", e);
        } catch (IllegalArgumentException e) { return "Error: " + e.getMessage();
        } catch (Exception e) { return "Error deleting corpus: " + e.getMessage(); }
    }
}
