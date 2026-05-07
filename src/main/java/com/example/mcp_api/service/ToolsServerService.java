package com.example.mcp_api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;

import javax.net.ssl.SSLContext;
import java.util.List;

/**
 * Tools Server MCP Service — exposes the Exotel Tools Server API as MCP tools.
 *
 * Tools Server allows creating, testing, and managing tools (Python, OpenAPI, Knowledge Base)
 * that VoiceBots can invoke during conversations.
 *
 * Base URL: https://tools-server-uat.mum1.exotel.com/tools/api/v1  (UAT)
 *           https://tools-server.mum1.exotel.com/tools/api/v1       (Production)
 *
 * Auth: Bearer token via X-API-Key header, scoped to a tenant.
 */
@Service
public class ToolsServerService {

    private static final Logger logger = LoggerFactory.getLogger(ToolsServerService.class);

    private final RestTemplate restTemplate = createRestTemplate();

    @Value("${exotel.tools.server.base.url:https://tools-server-uat.mum1.exotel.com/tools/api/v1}")
    private String baseUrl;

    @Value("${exotel.tools.server.api.key:}")
    private String apiKey;

    @Value("${exotel.tools.server.tenant.id:}")
    private String defaultTenantId;

    // ======================== MCP TOOLS ========================

    @Tool(name = "createTool",
          description = "Create a new VoiceBot tool on the Exotel Tools Server. Supports three types: " +
                        "'python' (inline Python function), 'openapi' (external API via OpenAPI 3.0 spec), " +
                        "'knowledge_base' (RAG tool backed by a document corpus). " +
                        "Params: tenantId (leave blank to use default), name, description, type (python/openapi/knowledge_base), " +
                        "definitionJson (JSON string of the tool definition — for python: {body, parameters, return_value}).")
    public String createTool(String tenantId, String name, String description, String type, String definitionJson) {
        String tid = resolve(tenantId);
        logger.info("Creating tool — tenant={}, name={}, type={}", tid, name, type);
        try {
            String url = baseUrl + "/tenants/" + tid + "/tools";
            String body = buildCreateToolBody(name, description, type, definitionJson);
            HttpEntity<String> entity = new HttpEntity<>(body, jsonHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            return errorMsg("createTool", e);
        } catch (Exception e) {
            return "Error creating tool: " + e.getMessage();
        }
    }

    @Tool(name = "getTool",
          description = "Get full details of a specific VoiceBot tool by its ID. Returns name, description, type, " +
                        "status, latest version ID, and current active version. " +
                        "Params: tenantId (leave blank to use default), toolId (UUID).")
    public String getTool(String tenantId, String toolId) {
        String tid = resolve(tenantId);
        logger.info("Getting tool — tenant={}, toolId={}", tid, toolId);
        try {
            String url = baseUrl + "/tenants/" + tid + "/tools/" + toolId;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            return errorMsg("getTool", e);
        } catch (Exception e) {
            return "Error getting tool: " + e.getMessage();
        }
    }

    @Tool(name = "activateToolVersion",
          description = "Set the active version of a tool. Use this after creating or updating a tool to promote " +
                        "a version to 'active' status so the VoiceBot can use it. " +
                        "Params: tenantId (leave blank to use default), toolId (UUID), versionId (UUID of the version to activate).")
    public String activateToolVersion(String tenantId, String toolId, String versionId) {
        String tid = resolve(tenantId);
        logger.info("Activating tool version — tenant={}, toolId={}, versionId={}", tid, toolId, versionId);
        try {
            String url = baseUrl + "/tenants/" + tid + "/tools/" + toolId;
            String body = "{\"status\":\"active\",\"current_version_id\":\"" + versionId + "\"}";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, jsonHeaders()), String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            return errorMsg("activateToolVersion", e);
        } catch (Exception e) {
            return "Error activating tool version: " + e.getMessage();
        }
    }

    @Tool(name = "listToolVersions",
          description = "List all versions of a tool with pagination. Returns version IDs, version numbers, " +
                        "and creation timestamps. Use to find a version ID before testing or activating. " +
                        "Params: tenantId (leave blank to use default), toolId (UUID), limit (default 10), offset (default 0).")
    public String listToolVersions(String tenantId, String toolId, String limit, String offset) {
        String tid = resolve(tenantId);
        logger.info("Listing tool versions — tenant={}, toolId={}", tid, toolId);
        try {
            String lim = (limit != null && !limit.isBlank()) ? limit : "10";
            String off = (offset != null && !offset.isBlank()) ? offset : "0";
            String url = baseUrl + "/tenants/" + tid + "/tools/" + toolId
                    + "/versions?limit=" + lim + "&offset=" + off;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            return errorMsg("listToolVersions", e);
        } catch (Exception e) {
            return "Error listing tool versions: " + e.getMessage();
        }
    }

    @Tool(name = "getToolVersion",
          description = "Get a specific version of a tool, including its full definition (code, parameters, return value). " +
                        "Params: tenantId (leave blank to use default), toolId (UUID), versionId (UUID).")
    public String getToolVersion(String tenantId, String toolId, String versionId) {
        String tid = resolve(tenantId);
        logger.info("Getting tool version — tenant={}, toolId={}, versionId={}", tid, toolId, versionId);
        try {
            String url = baseUrl + "/tenants/" + tid + "/tools/" + toolId + "/versions/" + versionId;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            return errorMsg("getToolVersion", e);
        } catch (Exception e) {
            return "Error getting tool version: " + e.getMessage();
        }
    }

    @Tool(name = "testToolVersion",
          description = "Execute a test run of a specific tool version with provided inputs. Returns test result, " +
                        "execution time (ms), output, and any error messages. Run this before activating a tool. " +
                        "Params: tenantId (leave blank to use default), toolId (UUID), versionId (UUID), " +
                        "testInputsJson (JSON object of input values, e.g. {\"x\":4,\"y\":7}).")
    public String testToolVersion(String tenantId, String toolId, String versionId, String testInputsJson) {
        String tid = resolve(tenantId);
        logger.info("Testing tool version — tenant={}, toolId={}, versionId={}", tid, toolId, versionId);
        try {
            String url = baseUrl + "/tenants/" + tid + "/tools/" + toolId + "/versions/" + versionId + "/tests";
            String body = "{\"test_inputs\":" + testInputsJson + "}";
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, jsonHeaders()), String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            return errorMsg("testToolVersion", e);
        } catch (Exception e) {
            return "Error testing tool version: " + e.getMessage();
        }
    }

    @Tool(name = "createKnowledgeCorpus",
          description = "Create a Knowledge Base corpus for RAG-powered VoiceBot tools. Upload documents " +
                        "that the bot can query during conversations. Corpus creation is async — poll getKnowledgeCorpus " +
                        "until status is 'ready'. " +
                        "Params: tenantId (leave blank to use default), corpusName, corpusDescription, " +
                        "fileUrl (publicly accessible URL of the document to ingest).")
    public String createKnowledgeCorpus(String tenantId, String corpusName, String corpusDescription, String fileUrl) {
        String tid = resolve(tenantId);
        logger.info("Creating knowledge corpus — tenant={}, name={}", tid, corpusName);
        try {
            String url = baseUrl + "/tenants/" + tid + "/knowledgecorpora";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("X-API-Key", apiKey);

            String metadata = "{\"name\":\"" + corpusName + "\","
                    + "\"description\":\"" + corpusDescription + "\","
                    + "\"rag_engine\":\"vertex_ai\"}";

            org.springframework.util.LinkedMultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
            form.add("metadata", metadata);
            if (fileUrl != null && !fileUrl.isBlank()) {
                form.add("file_url", fileUrl);
            }

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            return errorMsg("createKnowledgeCorpus", e);
        } catch (Exception e) {
            return "Error creating knowledge corpus: " + e.getMessage();
        }
    }

    @Tool(name = "getKnowledgeCorpus",
          description = "Get the status and details of a Knowledge Base corpus. Poll this after createKnowledgeCorpus " +
                        "until status is 'ready' before using it in a knowledge_base tool. " +
                        "Params: tenantId (leave blank to use default), corpusId (UUID).")
    public String getKnowledgeCorpus(String tenantId, String corpusId) {
        String tid = resolve(tenantId);
        logger.info("Getting knowledge corpus — tenant={}, corpusId={}", tid, corpusId);
        try {
            String url = baseUrl + "/tenants/" + tid + "/knowledgecorpora/" + corpusId;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            return errorMsg("getKnowledgeCorpus", e);
        } catch (Exception e) {
            return "Error getting knowledge corpus: " + e.getMessage();
        }
    }

    // ======================== HELPERS ========================

    private String resolve(String tenantId) {
        return (tenantId != null && !tenantId.isBlank()) ? tenantId : defaultTenantId;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-API-Key", apiKey);
        return headers;
    }

    private String buildCreateToolBody(String name, String description, String type, String definitionJson) {
        return "{" +
                "\"name\":\"" + name + "\"," +
                "\"description\":\"" + description + "\"," +
                "\"type\":\"" + type + "\"," +
                "\"definition\":" + definitionJson +
                "}";
    }

    private String errorMsg(String method, HttpClientErrorException e) {
        logger.error("{} API error: {} - {}", method, e.getStatusCode(), e.getResponseBodyAsString());
        return "Error " + e.getStatusCode().value() + ": " + method + " failed";
    }

    private static RestTemplate createRestTemplate() {
        try {
            SSLContext sslCtx = SSLContextBuilder.create()
                    .loadTrustMaterial(null, (chain, authType) -> true)
                    .build();
            var connMgr = PoolingHttpClientConnectionManagerBuilder.create()
                    .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                            .setSslContext(sslCtx)
                            .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                            .build())
                    .build();
            var httpClient = HttpClients.custom().setConnectionManager(connMgr).build();
            var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
            factory.setConnectTimeout(10_000);
            return new RestTemplate(factory);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create RestTemplate", e);
        }
    }
}
