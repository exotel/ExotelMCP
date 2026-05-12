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
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.mcp_api.auth.AuthContext;
import com.example.mcp_api.auth.AuthCredentials;

import com.example.mcp_api.dto.CqaAuthData;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class CqaService {

    private static final Logger logger = LoggerFactory.getLogger(CqaService.class);
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_BATCH_PAYLOAD_CHARS = 2_000_000;
    private static final Set<String> ALLOWED_HOSTS = Set.of(
        "cqa-console.in.exotel.com",
        "cqa.exotel.com"
    );

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

    @Tool(name = "exotel_cqa_ingest_interaction",
          description = "Ingest a single interaction into Exotel Conversational Intelligence for quality analysis. "
              + "Requires at least one of audioUrl or transcriptUrl. "
              + "Returns the interaction ID and processing status.")
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

    @Tool(name = "exotel_cqa_ingest_batch",
          description = "Ingest a batch of interactions (up to 100) into Exotel Conversational Intelligence as a single asynchronous job. "
              + "Accepts a JSON array string of interaction objects. Returns a job ID for tracking.")
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

    @Tool(name = "exotel_cqa_ingest_file",
          description = "Submit a remote CSV file URL for asynchronous bulk ingestion into Exotel Conversational Intelligence. "
              + "The platform downloads and processes the file in the background (up to 100k rows, 100MB). Returns a job ID for tracking.")
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

    @Tool(name = "exotel_cqa_get_interaction",
          description = "Retrieve the current status and details of an ingested interaction from Exotel Conversational Intelligence. "
              + "Accepts either the platform-assigned UUID or your external_interaction_id.")
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

    @Tool(name = "exotel_cqa_track_job",
          description = "Track the status of a batch or file ingestion job in Exotel Conversational Intelligence. "
              + "Returns paginated interaction list and overall job status (pending/processing/completed/failed). "
              + "Use the job ID returned by exotel_cqa_ingest_batch or exotel_cqa_ingest_file.")
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

    @Tool(name = "exotel_cqa_get_analysis",
          description = "Retrieve the full quality analysis for a completed interaction from Exotel Conversational Intelligence. "
              + "Returns the scoring breakdown including categories, subcategories, and individual KPI scores with AI justifications. "
              + "Use the analysis_id from the interaction detail's analyses array.")
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

    // ===================== SETUP TOOLS (JWT Auth) =====================

    @Tool(name = "exotel_cqa_login",
          description = "Authenticate with the CQA platform to obtain a JWT bearer token. "
              + "This token is required for setup operations: creating quality profiles, generating API keys, and managing assignment rules. "
              + "Returns the bearer_token and account_id from response.data needed for subsequent setup tools.")
    public Map<String, Object> cqaLogin(String username, String password, String tenantName) {
        logger.info("CQA login: tenant={}, user={}", tenantName, username);
        try {
            String host = getCqaHostUrl();
            validateHost(host);
            String url = host + "/cqa/api/v1/native/login";

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("username", username);
            body.put("password", password);
            body.put("tenant_name", tenantName);

            String response = postJsonNoAuth(url, body);
            return parseJsonResponse(response,
                "Login successful. Use response.data.token as jwtToken and response.data.account_id as accountId for setup tools.");
        } catch (Exception e) {
            logger.error("CQA login error", e);
            return errorResult(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Tool(name = "exotel_cqa_create_quality_profile",
          description = "Create a complete quality profile with categories, sub-categories, and KPIs in one call. "
              + "The profile is automatically created with is_ai_analysis_enabled=true for AI-powered analysis. "
              + "Requires a JWT token from exotel_cqa_login. "
              + "categoriesJson must be a JSON array of category objects. Each category has: name (string) "
              + "and sub_categories (array). Each sub_category has: name and kpis (array). "
              + "Each KPI has: kpi_name, kpi_question, input_type (Yes/No, Selection, Rating, or Text), and help_text. "
              + "Options are REQUIRED for Yes/No, Selection, and Rating types -- each option needs 'label' (string) "
              + "and 'weightage' (integer). Rating options also need 'type':'STAR'. Text KPIs do not support options. "
              + "Yes/No example options: [{\"label\":\"Yes\",\"weightage\":1},{\"label\":\"No\",\"weightage\":0}]. "
              + "Additional optional KPI fields: is_scoring_allowed (boolean), is_critical (boolean), "
              + "is_mandatory (boolean), is_comment_mandatory (boolean), is_dispute_allowed (boolean), "
              + "criticality_level (string). All KPI fields provided in categoriesJson are passed through to the API.")
    public Map<String, Object> cqaCreateQualityProfile(
            String jwtToken,
            String accountId,
            String profileName,
            String description,
            String categoriesJson) {
        logger.info("CQA create quality profile: account={}, name={}", accountId, profileName);

        String qpId = null;
        List<Map<String, Object>> createdCategories = new ArrayList<>();

        try {
            String host = getCqaHostUrl();
            validateHost(host);
            validateAccountId(accountId);
            String baseUrl = host + "/cqa/api/v1/accounts/" + accountId;

            Map<String, Object> qpBody = new LinkedHashMap<>();
            qpBody.put("name", profileName);
            if (description != null && !description.isBlank()) {
                qpBody.put("description", description);
            }
            qpBody.put("is_ai_analysis_enabled", true);

            String qpResponse = postJsonWithBearer(baseUrl + "/quality-profiles", qpBody, jwtToken);
            Map<String, Object> qpParsed = objectMapper.readValue(qpResponse, Map.class);
            Map<String, Object> qpData = extractResponseData(qpParsed);
            qpId = (String) qpData.get("id");
            if (qpId == null || qpId.isBlank()) {
                throw new RuntimeException("Failed to extract quality profile ID from response");
            }
            logger.info("QP shell created: id={}", qpId);

            if (categoriesJson != null && !categoriesJson.isBlank()) {
                List<Map<String, Object>> categories = objectMapper.readValue(categoriesJson, List.class);
                for (Map<String, Object> cat : categories) {
                    Map<String, Object> catPayload = new LinkedHashMap<>();
                    catPayload.put("name", cat.get("name"));
                    if (cat.containsKey("description")) catPayload.put("description", cat.get("description"));
                    Map<String, Object> catBody = new LinkedHashMap<>();
                    catBody.put("categories", catPayload);

                    String catResponse = postJsonWithBearer(
                        baseUrl + "/quality-profiles/" + qpId + "/categories",
                        catBody, jwtToken);
                    Map<String, Object> catParsed = objectMapper.readValue(catResponse, Map.class);
                    Map<String, Object> catData = extractResponseData(catParsed);
                    String catId = (String) catData.get("id");
                    logger.info("Category created: id={}, name={}", catId, cat.get("name"));

                    List<Map<String, Object>> subCategories = (List<Map<String, Object>>) cat.get("sub_categories");
                    List<Map<String, Object>> createdSubCats = new ArrayList<>();

                    if (subCategories != null) {
                        for (Map<String, Object> subCat : subCategories) {
                            Map<String, Object> subCatPayload = new LinkedHashMap<>();
                            subCatPayload.put("name", subCat.get("name"));
                            if (subCat.containsKey("description")) subCatPayload.put("description", subCat.get("description"));
                            Map<String, Object> subCatBody = new LinkedHashMap<>();
                            subCatBody.put("sub_category", subCatPayload);

                            String subCatResponse = postJsonWithBearer(
                                baseUrl + "/quality-profiles/" + qpId + "/categories/" + catId + "/sub-categories",
                                subCatBody, jwtToken);
                            Map<String, Object> subCatParsed = objectMapper.readValue(subCatResponse, Map.class);
                            Map<String, Object> subCatData = extractResponseData(subCatParsed);
                            String subCatId = (String) subCatData.get("id");
                            logger.info("Sub-category created: id={}, name={}", subCatId, subCat.get("name"));

                            List<Map<String, Object>> kpis = (List<Map<String, Object>>) subCat.get("kpis");
                            int kpisCreated = 0;

                            if (kpis != null) {
                                for (Map<String, Object> kpi : kpis) {
                                    validateKpiOptions(kpi);
                                    Map<String, Object> kpiPayload = new LinkedHashMap<>(kpi);
                                    Map<String, Object> kpiBody = new LinkedHashMap<>();
                                    kpiBody.put("kpi", kpiPayload);

                                    postJsonWithBearer(
                                        baseUrl + "/quality-profiles/" + qpId + "/categories/" + catId
                                            + "/sub-categories/" + subCatId + "/kpis",
                                        kpiBody, jwtToken);
                                    kpisCreated++;
                                    logger.info("KPI created: name={}", kpi.get("kpi_name"));
                                }
                            }

                            Map<String, Object> subCatResult = new LinkedHashMap<>();
                            subCatResult.put("id", subCatId);
                            subCatResult.put("name", subCat.get("name"));
                            subCatResult.put("kpis_created", kpisCreated);
                            createdSubCats.add(subCatResult);
                        }
                    }

                    Map<String, Object> catResult = new LinkedHashMap<>();
                    catResult.put("id", catId);
                    catResult.put("name", cat.get("name"));
                    catResult.put("sub_categories", createdSubCats);
                    createdCategories.add(catResult);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("profile_id", qpId);
            result.put("profile_name", profileName);
            result.put("is_ai_analysis_enabled", true);
            result.put("categories", createdCategories);
            result.put("_hint", "Quality profile created successfully with all categories, sub-categories, and KPIs.");
            return result;
        } catch (Exception e) {
            logger.error("CQA create quality profile error", e);
            if (qpId != null) {
                Map<String, Object> partial = new LinkedHashMap<>();
                partial.put("partial", true);
                partial.put("profile_id", qpId);
                partial.put("profile_name", profileName);
                partial.put("categories_created_before_failure", createdCategories);
                partial.put("error", e.getMessage());
                partial.put("_hint", "Profile was partially created. You can retry the failed parts or delete the profile via the console.");
                return partial;
            }
            return errorResult(e);
        }
    }

    private void validateKpiOptions(Map<String, Object> kpi) {
        String inputType = (String) kpi.get("input_type");
        if (inputType == null) return;
        if (inputType.equalsIgnoreCase("Text")) return;
        if (kpi.containsKey("options") && kpi.get("options") != null) return;

        throw new RuntimeException(
            "KPI '" + kpi.get("kpi_name") + "' has input_type '" + inputType
            + "' which requires an 'options' array. Each option needs 'label' (string) and 'weightage' (integer). "
            + "Yes/No example: [{\"label\":\"Yes\",\"weightage\":1},{\"label\":\"No\",\"weightage\":0}]. "
            + "Rating options also need 'type':'STAR'.");
    }

    @Tool(name = "exotel_cqa_create_api_key",
          description = "Generate a new API key for CQA data import and analysis operations. "
              + "Requires a JWT token from exotel_cqa_login. "
              + "Returns the generated api_key value from response.data. A 409 response means an API key with this name already exists.")
    public Map<String, Object> cqaCreateApiKey(String jwtToken, String accountId, String keyName) {
        logger.info("CQA create API key: account={}, name={}", accountId, keyName);
        try {
            String host = getCqaHostUrl();
            validateHost(host);
            validateAccountId(accountId);
            String url = host + "/cqa/api/v1/accounts/" + accountId + "/api-keys";

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", keyName);

            String response = postJsonWithBearer(url, body, jwtToken);
            return parseJsonResponse(response,
                "API key created. Use the key value from response.data for CQA ingestion and analysis tools.");
        } catch (Exception e) {
            logger.error("CQA create API key error", e);
            return errorResult(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Tool(name = "exotel_cqa_create_assignment_rule",
          description = "Create an assignment rule that routes interactions to quality profiles for analysis. "
              + "Requires a JWT token from exotel_cqa_login. "
              + "filterGroupJson is REQUIRED and defines matching criteria as a 2D array (OR of ANDs), e.g. "
              + "[[{\"attribute\":\"source\",\"operator\":\"IS\",\"value\":\"my-source\"}]]. "
              + "Supported operators: IS, IS_NOT, CONTAINS, NOT_CONTAINS, GREATER_THAN, LESS_THAN, GREATER_OR_EQUAL, LESS_OR_EQUAL. "
              + "qualityProfileIds is a comma-separated list of quality profile UUIDs to assign. "
              + "Tip: use a unique source value in the filter to avoid conflicts with existing rules.")
    public Map<String, Object> cqaCreateAssignmentRule(
            String jwtToken,
            String accountId,
            String ruleName,
            String description,
            String filterGroupJson,
            String qualityProfileIds,
            int samplingPercentage) {
        logger.info("CQA create assignment rule: account={}, name={}", accountId, ruleName);
        try {
            String host = getCqaHostUrl();
            validateHost(host);
            validateAccountId(accountId);

            if (filterGroupJson == null || filterGroupJson.isBlank()) {
                throw new RuntimeException(
                    "filterGroupJson is required. It must be a 2D JSON array defining filter conditions. "
                    + "Example: [[{\"attribute\":\"source\",\"operator\":\"IS\",\"value\":\"my-source\"}]]");
            }

            String url = host + "/cqa/api/v1/accounts/" + accountId + "/quality-analysis-rules";

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", ruleName);
            if (description != null && !description.isBlank()) {
                body.put("description", description);
            }

            body.put("filter_group", objectMapper.readValue(filterGroupJson, List.class));

            String[] qpIds = qualityProfileIds.split(",");
            List<String> profileIds = new ArrayList<>();
            for (String id : qpIds) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) profileIds.add(trimmed);
            }
            body.put("assign_quality_profiles", profileIds);

            if (samplingPercentage < 0) samplingPercentage = 100;
            if (samplingPercentage > 100) samplingPercentage = 100;
            body.put("sampling_percentage", samplingPercentage);

            String response = postJsonWithBearer(url, body, jwtToken);
            return parseJsonResponse(response,
                "Assignment rule created. Interactions matching the filter will be routed to the specified quality profiles.");
        } catch (Exception e) {
            logger.error("CQA create assignment rule error", e);
            return errorResult(e);
        }
    }

    // ===================== AUTH =====================

    private CqaAuthData getCqaAuth() {
        AuthCredentials creds = AuthContext.current();
        String error = AuthContext.requireCqa();
        if (error != null) {
            throw new IllegalStateException(error);
        }

        String host = creds.effectiveCqaHost("https://cqa-console.in.exotel.com");
        validateHost(host);

        String accountId = creds.getCqaAccountId();
        validateAccountId(accountId);

        return new CqaAuthData(creds.getCqaApiKey(), accountId, host);
    }

    private String getCqaHostUrl() {
        try {
            AuthCredentials creds = AuthContext.current();
            return creds.effectiveCqaHost("https://cqa-console.in.exotel.com");
        } catch (Exception e) {
            return "https://cqa-console.in.exotel.com";
        }
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

    private String postJsonWithBearer(String url, Object body, String jwtToken) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(body);
        logger.debug("CQA POST (Bearer) {} body={}", url, jsonBody);

        ClassicHttpRequest request = ClassicRequestBuilder.post(url)
            .setHeader("Authorization", "Bearer " + jwtToken)
            .setHeader("Content-Type", "application/json")
            .setHeader("Accept", "application/json")
            .setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8))
            .build();

        return executeRequest(request);
    }

    private String postJsonNoAuth(String url, Object body) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(body);
        logger.debug("CQA POST (no auth) {} body={}", url, jsonBody);

        ClassicHttpRequest request = ClassicRequestBuilder.post(url)
            .setHeader("Content-Type", "application/json")
            .setHeader("Accept", "application/json")
            .setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8))
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
                String bodySnippet = responseBody.length() > 500
                    ? responseBody.substring(0, 500) + "..." : responseBody;
                throw new RuntimeException("CQA API returned HTTP " + code + ": " + bodySnippet);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractResponseData(Map<String, Object> parsed) {
        Map<String, Object> response = (Map<String, Object>) parsed.get("response");
        if (response == null) throw new RuntimeException("Missing 'response' in API response: " + parsed);
        Object data = response.get("data");
        if (data == null) throw new RuntimeException("Missing 'response.data' in API response");
        if (data instanceof Map) return (Map<String, Object>) data;
        throw new RuntimeException("Expected 'response.data' to be an object, got: " + data.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractResponseDataList(Map<String, Object> parsed) {
        Map<String, Object> response = (Map<String, Object>) parsed.get("response");
        if (response == null) throw new RuntimeException("Missing 'response' in API response: " + parsed);
        Object data = response.get("data");
        if (data == null) throw new RuntimeException("Missing 'response.data' in API response");
        if (data instanceof List) return (List<Map<String, Object>>) data;
        throw new RuntimeException("Expected 'response.data' to be an array, got: " + data.getClass().getSimpleName());
    }
}
