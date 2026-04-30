package com.example.mcp_api.service;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.mcp_api.dto.CqaAuthData;

import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

@Service
public class CqaService {

    private static final Logger logger = LoggerFactory.getLogger(CqaService.class);
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_BATCH_PAYLOAD_CHARS = 2_000_000;
    private static final Set<String> ALLOWED_HOSTS = Set.of(
        "cqa-console.in.exotel.com",
        "cqa.exotel.com"
    );

    @Autowired
    private ExotelService exotelService;

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CqaService() {
        this.httpClient = createHttpClient();
    }

    @jakarta.annotation.PreDestroy
    public void destroy() throws Exception {
        httpClient.close();
    }

    private CloseableHttpClient createHttpClient() {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(30);
        cm.setDefaultMaxPerRoute(10);

        RequestConfig rc = RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofSeconds(5))
            .setResponseTimeout(Timeout.ofSeconds(60))
            .build();

        return HttpClients.custom()
            .setConnectionManager(cm)
            .setDefaultRequestConfig(rc)
            .setKeepAliveStrategy((response, context) -> Timeout.ofSeconds(30))
            .build();
    }

    // ===================== MCP TOOLS =====================

    @Tool(name = "cqaIngestInteraction",
          description = "Ingest a single interaction into Exotel Conversational Intelligence for quality analysis. "
              + "Requires at least one of audioUrl or transcriptUrl. "
              + "Returns the interaction ID and processing status. "
              + "Authentication uses cqa_api_key, cqa_account_id, and cqa_host from the session.")
    public Map<String, Object> cqaIngestInteraction(
            String externalInteractionId,
            String channelType,
            String audioUrl,
            String transcriptUrl,
            String language,
            String source,
            String metadataJson) {
        logger.info("CQA ingest single interaction: externalId={}, channel={}", externalInteractionId, channelType);
        try {
            CqaAuthData auth = getCqaAuth();
            String url = auth.baseUrl() + "/ingress/interactions";

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("external_interaction_id", externalInteractionId);
            body.put("channel_type", channelType);

            if (audioUrl != null && !audioUrl.isBlank()) body.put("audio_url", audioUrl);
            if (transcriptUrl != null && !transcriptUrl.isBlank()) body.put("transcript_url", transcriptUrl);
            if (language != null && !language.isBlank()) body.put("language", language);
            if (source != null && !source.isBlank()) body.put("source", source);

            if (metadataJson != null && !metadataJson.isBlank()) {
                try {
                    body.put("metadata", objectMapper.readValue(metadataJson, Map.class));
                } catch (Exception e) {
                    logger.warn("Could not parse metadata JSON, skipping: {}", e.getMessage());
                }
            }

            String response = postJson(url, body, auth.apiKey());
            return parseJsonResponse(response, "Interaction ingested successfully");
        } catch (Exception e) {
            logger.error("CQA ingest error", e);
            return errorResult(e);
        }
    }

    @Tool(name = "cqaIngestBatch",
          description = "Ingest a batch of interactions (up to 100) into Exotel Conversational Intelligence as a single asynchronous job. "
              + "Accepts a JSON array string of interaction objects. "
              + "Returns a job ID for tracking. "
              + "Authentication uses cqa_api_key, cqa_account_id, and cqa_host from the session.")
    public Map<String, Object> cqaIngestBatch(String interactionsJson, boolean skipDuplicationCheck) {
        logger.info("CQA ingest batch, skipDuplication={}", skipDuplicationCheck);
        try {
            CqaAuthData auth = getCqaAuth();
            String url = auth.baseUrl() + "/ingress/interactions/batch";

            if (interactionsJson.length() > MAX_BATCH_PAYLOAD_CHARS) {
                throw new IllegalArgumentException("Batch payload too large (max " + MAX_BATCH_PAYLOAD_CHARS + " chars)");
            }
            List<?> interactions = objectMapper.readValue(interactionsJson, List.class);
            if (interactions.isEmpty() || interactions.size() > 100) {
                throw new IllegalArgumentException("Batch must contain 1-100 interactions, got " + interactions.size());
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("interactions", interactions);
            if (skipDuplicationCheck) {
                body.put("skip_duplication_check", true);
            }

            String response = postJson(url, body, auth.apiKey());
            return parseJsonResponse(response, "Batch submitted successfully");
        } catch (Exception e) {
            logger.error("CQA batch ingest error", e);
            return errorResult(e);
        }
    }

    @Tool(name = "cqaIngestFile",
          description = "Submit a remote CSV file URL for asynchronous bulk ingestion into Exotel Conversational Intelligence. "
              + "The platform downloads and processes the file in the background (up to 100k rows, 100MB). "
              + "Returns a job ID for tracking. "
              + "Authentication uses cqa_api_key, cqa_account_id, and cqa_host from the session.")
    public Map<String, Object> cqaIngestFile(
            String fileUrl,
            String format,
            String source,
            String columnMappingJson,
            String metadataJson,
            boolean skipDuplicationCheck) {
        logger.info("CQA ingest file: url={}, format={}", fileUrl, format);
        try {
            CqaAuthData auth = getCqaAuth();
            String url = auth.baseUrl() + "/ingress/interactions/files";

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("file_url", fileUrl);
            body.put("format", format);
            if (source != null && !source.isBlank()) body.put("source", source);
            if (skipDuplicationCheck) body.put("skip_duplication_check", true);

            if (columnMappingJson != null && !columnMappingJson.isBlank()) {
                try {
                    body.put("column_mapping", objectMapper.readValue(columnMappingJson, Map.class));
                } catch (Exception e) {
                    logger.warn("Could not parse column_mapping JSON: {}", e.getMessage());
                }
            }
            if (metadataJson != null && !metadataJson.isBlank()) {
                try {
                    body.put("metadata", objectMapper.readValue(metadataJson, Map.class));
                } catch (Exception e) {
                    logger.warn("Could not parse metadata JSON: {}", e.getMessage());
                }
            }

            String response = postJson(url, body, auth.apiKey());
            return parseJsonResponse(response, "File submitted for ingestion");
        } catch (Exception e) {
            logger.error("CQA file ingest error", e);
            return errorResult(e);
        }
    }

    @Tool(name = "cqaGetInteraction",
          description = "Retrieve the current status and details of an ingested interaction from Exotel Conversational Intelligence. "
              + "Accepts either the platform-assigned UUID or your external_interaction_id. "
              + "Authentication uses cqa_api_key, cqa_account_id, and cqa_host from the session.")
    public Map<String, Object> cqaGetInteraction(String interactionIdentifier) {
        logger.info("CQA get interaction: {}", interactionIdentifier);
        try {
            CqaAuthData auth = getCqaAuth();
            String url = auth.baseUrl() + "/ingress/interactions/"
                + java.net.URLEncoder.encode(interactionIdentifier, StandardCharsets.UTF_8);

            String response = getJson(url, auth.apiKey());
            return parseJsonResponse(response, "Interaction retrieved");
        } catch (Exception e) {
            logger.error("CQA get interaction error", e);
            return errorResult(e);
        }
    }

    @Tool(name = "cqaTrackJob",
          description = "Track the status of a batch or file ingestion job in Exotel Conversational Intelligence. "
              + "Returns paginated interaction list and overall job status (pending/processing/completed/failed). "
              + "Use the job ID returned by cqaIngestBatch or cqaIngestFile. "
              + "Authentication uses cqa_api_key, cqa_account_id, and cqa_host from the session.")
    public Map<String, Object> cqaTrackJob(String jobId, int page, int size) {
        logger.info("CQA track job: id={}, page={}, size={}", jobId, page, size);
        try {
            CqaAuthData auth = getCqaAuth();
            if (size < 1) size = 20;
            if (size > 100) size = 100;
            if (page < 0) page = 0;

            String url = auth.baseUrl() + "/ingress/interactions/batch/"
                + java.net.URLEncoder.encode(jobId, StandardCharsets.UTF_8)
                + "?page=" + page + "&size=" + size;

            String response = getJson(url, auth.apiKey());
            return parseJsonResponse(response, "Job status retrieved");
        } catch (Exception e) {
            logger.error("CQA track job error", e);
            return errorResult(e);
        }
    }

    @Tool(name = "cqaGetAnalysis",
          description = "Retrieve the full quality analysis for a completed interaction from Exotel Conversational Intelligence. "
              + "Returns the scoring breakdown including categories, subcategories, and individual KPI scores with AI justifications. "
              + "Use the analysis_id from the interaction detail's analyses array. "
              + "Authentication uses cqa_api_key, cqa_account_id, and cqa_host from the session.")
    public Map<String, Object> cqaGetAnalysis(String analysisId) {
        logger.info("CQA get analysis: {}", analysisId);
        try {
            CqaAuthData auth = getCqaAuth();
            String url = auth.baseUrl() + "/analyses/"
                + java.net.URLEncoder.encode(analysisId, StandardCharsets.UTF_8);

            String response = getJson(url, auth.apiKey());
            return parseJsonResponse(response, "Analysis retrieved");
        } catch (Exception e) {
            logger.error("CQA get analysis error", e);
            return errorResult(e);
        }
    }

    // ===================== AUTH =====================

    private CqaAuthData getCqaAuth() {
        String authHeader = getAuthHeader();
        return parseCqaAuth(authHeader);
    }

    private CqaAuthData parseCqaAuth(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            throw new IllegalStateException("Authorization header is missing. Provide cqa_api_key, cqa_account_id, and cqa_host in the auth config.");
        }
        try {
            String json = authHeader.trim();
            if (json.startsWith("Bearer ")) json = json.substring(7);
            else if (json.startsWith("Basic ")) json = json.substring(6);
            json = json.replace('\'', '"');
            if (!json.startsWith("{")) json = "{" + json + "}";

            JsonNode node = objectMapper.readTree(json);

            String apiKey = getField(node, "cqa_api_key");
            String accountId = getField(node, "cqa_account_id");
            String host = getField(node, "cqa_host");

            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("cqa_api_key is required in the auth config");
            }
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("cqa_account_id is required in the auth config");
            }
            if (host == null || host.isBlank()) {
                host = "https://cqa-console.in.exotel.com";
            }
            validateHost(host);
            validateAccountId(accountId);

            return new CqaAuthData(apiKey, accountId, host);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse CQA auth from Authorization header: " + e.getMessage(), e);
        }
    }

    private String getField(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    private void validateHost(String host) {
        try {
            URI uri = URI.create(host);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("cqa_host must use HTTPS");
            }
            String hostname = uri.getHost();
            if (hostname == null || !ALLOWED_HOSTS.contains(hostname.toLowerCase())) {
                throw new IllegalArgumentException(
                    "cqa_host must be a recognized Exotel endpoint. Allowed: " + ALLOWED_HOSTS);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("cqa_host is not a valid URL: " + host);
        }
    }

    private void validateAccountId(String accountId) {
        if (!accountId.matches("[a-zA-Z0-9\\-]+")) {
            throw new IllegalArgumentException("cqa_account_id contains invalid characters");
        }
    }

    private String getAuthHeader() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpServletRequest request = attrs.getRequest();
            String h = request.getHeader("Authorization");
            if (h != null && !h.isBlank()) return h;
        } catch (Exception ignored) {
        }

        try {
            java.lang.reflect.Method m = ExotelService.class.getDeclaredMethod("getCurrentAuthHeader");
            m.setAccessible(true);
            return (String) m.invoke(exotelService);
        } catch (Exception ignored) {
        }

        return null;
    }

    // ===================== HTTP =====================

    private String postJson(String url, Map<String, Object> body, String apiKey) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(body);
        logger.debug("CQA POST {} body={}", url, jsonBody);

        ClassicHttpRequest request = ClassicRequestBuilder.post(url)
            .setHeader("X-API-Key", apiKey)
            .setHeader("Content-Type", "application/json")
            .setHeader("Accept", "application/json")
            .setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8))
            .build();

        return executeRequest(request);
    }

    private String getJson(String url, String apiKey) throws Exception {
        logger.debug("CQA GET {}", url);

        ClassicHttpRequest request = ClassicRequestBuilder.get(url)
            .setHeader("X-API-Key", apiKey)
            .setHeader("Accept", "application/json")
            .build();

        return executeRequest(request);
    }

    private String executeRequest(ClassicHttpRequest request) throws Exception {
        long start = System.currentTimeMillis();
        return httpClient.execute(request, response -> {
            long ms = System.currentTimeMillis() - start;
            int code = response.getCode();

            HttpEntity entity = response.getEntity();
            String responseBody;
            if (entity != null) {
                InputStream is = entity.getContent();
                byte[] buf = is.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (buf.length > MAX_RESPONSE_BYTES) {
                    throw new RuntimeException("CQA API response exceeded " + MAX_RESPONSE_BYTES + " bytes");
                }
                responseBody = new String(buf, StandardCharsets.UTF_8);
            } else {
                responseBody = "{}";
            }

            logger.debug("CQA response: status={} ({}ms)", code, ms);

            if (code >= 400) {
                logger.warn("CQA API error: status={} body={}", code, responseBody);
                throw new RuntimeException("CQA API returned HTTP " + code);
            }
            return responseBody;
        });
    }

    // ===================== HELPERS =====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonResponse(String json, String successHint) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            parsed.put("_hint", successHint);
            return parsed;
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("raw_response", json);
            result.put("_hint", successHint);
            return result;
        }
    }

    private Map<String, Object> errorResult(Exception e) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", true);
        err.put("message", e.getMessage());
        err.put("type", e.getClass().getSimpleName());
        return err;
    }
}
