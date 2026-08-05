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
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class CqaService {

    private static final Logger logger = LoggerFactory.getLogger(CqaService.class);
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_BATCH_PAYLOAD_CHARS = 2_000_000;
    private static final int MAX_LOG_BODY_CHARS = 300;
    /** Match CQA app.ingress.max-transcript-bytes-single default. */
    private static final int MAX_TRANSCRIPT_TEXT_BYTES = 204_800;
    private static final int MAX_CATEGORIES_JSON_CHARS = 500_000;
    /** Cap orchestration fanout for update/create profile tools. */
    private static final int MAX_OUTBOUND_API_CALLS = 200;
    /** Path-safe resource IDs (UUIDs / hex ids) — rejects /, ?, .., etc. */
    private static final Pattern RESOURCE_ID_PATTERN = Pattern.compile("^[0-9a-fA-F\\-]{1,64}$");
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
              + "Requires at least one of: audioUrl (public HTTPS URL to audio file), transcriptUrl (public HTTPS URL to transcript), "
              + "or transcriptText (inline transcript text — CQA uploads it to storage automatically). "
              + "Use transcriptText to ingest a local text transcript without needing to host it anywhere. "
              + "For local audio files, upload to the onboarding bucket and pass the presigned HTTPS URL as audioUrl. "
              + "Returns the interaction ID and processing status.")
    public Map<String, Object> cqaIngestInteraction(
            String externalInteractionId,
            String channelType,
            String audioUrl,
            String transcriptUrl,
            String transcriptText,
            String language,
            String source,
            String metadataJson) {
        logger.info("CQA ingest single interaction: externalId={}, channel={}", externalInteractionId, channelType);
        try {
            CqaAuthData auth = getCqaAuth();
            String url = auth.baseUrl() + "/ingress/interactions";

            boolean hasAudio = audioUrl != null && !audioUrl.isBlank();
            boolean hasTranscriptUrl = transcriptUrl != null && !transcriptUrl.isBlank();
            boolean hasTranscriptText = transcriptText != null && !transcriptText.isBlank();
            if (!hasAudio && !hasTranscriptUrl && !hasTranscriptText) {
                throw new IllegalArgumentException(
                    "At least one of audioUrl, transcriptUrl, or transcriptText is required");
            }
            if (hasTranscriptText) {
                int bytes = transcriptText.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > MAX_TRANSCRIPT_TEXT_BYTES) {
                    throw new IllegalArgumentException(
                        "transcriptText exceeds max " + MAX_TRANSCRIPT_TEXT_BYTES + " bytes (got " + bytes + ")");
                }
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("external_interaction_id", externalInteractionId);
            body.put("channel_type", channelType);

            if (hasAudio) body.put("audio_url", audioUrl);
            if (hasTranscriptUrl) body.put("transcript_url", transcriptUrl);
            if (hasTranscriptText) body.put("transcript_text", transcriptText);
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
              + "Returns bearer_token from response.data — use it as jwtToken for setup tools. "
              + "Use cqa_account_id from your MCP Authorization header as accountId. "
              + "SECURITY: Credentials are not stored on the server. The JWT token is short-lived. "
              + "Requires cqa_host to be configured in the MCP Authorization header.")
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
                "Login successful. Use response.data.bearer_token as jwtToken for setup tools.");
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
            String baseUrl = setupBaseUrl(jwtToken, accountId);
            enforceCategoriesJsonSize(categoriesJson);

            Map<String, Object> qpBody = new LinkedHashMap<>();
            qpBody.put("name", profileName);
            if (description != null && !description.isBlank()) {
                qpBody.put("description", description);
            }
            qpBody.put("is_ai_analysis_enabled", true);

            String qpResponse = postJsonWithBearer(baseUrl + "/quality-profiles", qpBody, jwtToken);
            Map<String, Object> qpParsed = objectMapper.readValue(qpResponse, Map.class);
            Map<String, Object> qpData = extractResponseData(qpParsed);
            qpId = extractId(qpData, "qualityProfileId");
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
                    String catId = extractId(catData, "categoryId");
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
                            String subCatId = extractId(subCatData, "subCategoryId");
                            logger.info("Sub-category created: id={}, name={}", subCatId, subCat.get("name"));

                            List<Map<String, Object>> kpis = (List<Map<String, Object>>) subCat.get("kpis");
                            int kpisCreated = 0;

                            if (kpis != null) {
                                for (Map<String, Object> kpi : kpis) {
                                    validateKpiOptions(kpi);
                                    Map<String, Object> kpiBody = new LinkedHashMap<>();
                                    kpiBody.put("kpi", sanitizeKpiPayload(kpi));

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
            String baseUrl = setupBaseUrl(jwtToken, accountId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", keyName);

            String response = postJsonWithBearer(baseUrl + "/api-keys", body, jwtToken);
            return parseJsonResponse(response,
                "API key created. Use the key value from response.data for CQA ingestion and analysis tools.");
        } catch (Exception e) {
            logger.error("CQA create API key error", e);
            return errorResult(e);
        }
    }

    @Tool(name = "exotel_cqa_list_api_keys",
          description = "List all active API keys for an account. "
              + "Requires a JWT token from exotel_cqa_login. "
              + "Returns name, created_by, and masked key value for each key. "
              + "Use this to find a key's ID before revoking it with exotel_cqa_revoke_api_key.")
    public Map<String, Object> cqaListApiKeys(String jwtToken, String accountId) {
        logger.info("CQA list API keys: account={}", accountId);
        try {
            String baseUrl = setupBaseUrl(jwtToken, accountId);
            String response = getJsonWithBearer(baseUrl + "/api-keys", jwtToken);
            return parseJsonResponse(response,
                "API keys listed. Use the 'id' field with exotel_cqa_revoke_api_key to revoke a key.");
        } catch (Exception e) {
            logger.error("CQA list API keys error", e);
            return errorResult(e);
        }
    }

    @Tool(name = "exotel_cqa_revoke_api_key",
          description = "Revoke (permanently delete) an API key by its ID. "
              + "Requires a JWT token from exotel_cqa_login. "
              + "Use exotel_cqa_list_api_keys to find the key ID. "
              + "This action is irreversible — any services using the revoked key will immediately lose access. "
              + "REQUIRED: pass confirm=true to proceed.")
    public Map<String, Object> cqaRevokeApiKey(String jwtToken, String accountId, String apiKeyId, Boolean confirm) {
        logger.info("CQA revoke API key: account={}, keyId={}", accountId, apiKeyId);
        try {
            requireConfirm(confirm, "revoke API key " + apiKeyId);
            validateResourceId(apiKeyId, "apiKeyId");
            String baseUrl = setupBaseUrl(jwtToken, accountId);
            String response = deleteWithBearer(baseUrl + "/api-keys/" + apiKeyId, jwtToken);
            return parseJsonResponse(response, "API key revoked successfully.");
        } catch (Exception e) {
            logger.error("CQA revoke API key error", e);
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
            Integer samplingPercentage) {
        logger.info("CQA create assignment rule: account={}, name={}", accountId, ruleName);
        try {
            String baseUrl = setupBaseUrl(jwtToken, accountId);

            if (filterGroupJson == null || filterGroupJson.isBlank()) {
                throw new RuntimeException(
                    "filterGroupJson is required. It must be a 2D JSON array defining filter conditions. "
                    + "Example: [[{\"attribute\":\"source\",\"operator\":\"IS\",\"value\":\"my-source\"}]]");
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", ruleName);
            if (description != null && !description.isBlank()) {
                body.put("description", description);
            }

            body.put("filter_group", objectMapper.readValue(filterGroupJson, List.class));
            body.put("assign_quality_profiles", parseProfileIds(qualityProfileIds));

            if (samplingPercentage != null) {
                body.put("sampling_percentage", requireSamplingPercentage(samplingPercentage));
            }

            String response = postJsonWithBearer(baseUrl + "/quality-analysis-rules", body, jwtToken);
            return parseJsonResponse(response,
                "Assignment rule created. Interactions matching the filter will be routed to the specified quality profiles.");
        } catch (Exception e) {
            logger.error("CQA create assignment rule error", e);
            return errorResult(e);
        }
    }

    @Tool(name = "exotel_cqa_get_quality_profile",
          description = "Retrieve a quality profile by ID, including its full hierarchy of categories, "
              + "sub-categories, and KPIs. Requires a JWT token from exotel_cqa_login. "
              + "Use exotel_cqa_list_quality_profiles to find profile IDs.")
    public Map<String, Object> cqaGetQualityProfile(String jwtToken, String accountId, String profileId) {
        logger.info("CQA get quality profile: account={}, profileId={}", accountId, profileId);
        try {
            validateResourceId(profileId, "profileId");
            String baseUrl = setupBaseUrl(jwtToken, accountId);
            String response = getJsonWithBearer(baseUrl + "/quality-profiles/" + profileId, jwtToken);
            return parseJsonResponse(response,
                "Quality profile retrieved. Use categories/sub_categories/kpis in response.data to inspect or plan updates.");
        } catch (Exception e) {
            logger.error("CQA get quality profile error", e);
            return errorResult(e);
        }
    }

    @Tool(name = "exotel_cqa_list_quality_profiles",
          description = "List quality profiles for an account with pagination. Requires a JWT token from exotel_cqa_login. "
              + "Optional: limit (1-100, default 10), offset (default 0), sortBy (e.g. name:asc,created_at:desc), "
              + "filter (JSON filter string per CQA API docs).")
    public Map<String, Object> cqaListQualityProfiles(
            String jwtToken,
            String accountId,
            Integer limit,
            Integer offset,
            String sortBy,
            String filter) {
        logger.info("CQA list quality profiles: account={}, limit={}, offset={}", accountId, limit, offset);
        try {
            String baseUrl = setupBaseUrl(jwtToken, accountId);
            int lim = normalizeLimit(limit);
            int off = normalizeOffset(offset);

            StringBuilder url = new StringBuilder(baseUrl).append("/quality-profiles?limit=").append(lim)
                .append("&offset=").append(off);
            if (sortBy != null && !sortBy.isBlank()) {
                url.append("&sort_by=").append(java.net.URLEncoder.encode(sortBy, StandardCharsets.UTF_8));
            }
            if (filter != null && !filter.isBlank()) {
                url.append("&filter=").append(java.net.URLEncoder.encode(filter, StandardCharsets.UTF_8));
            }

            String response = getJsonWithBearer(url.toString(), jwtToken);
            return parseJsonResponse(response,
                "Profiles listed. Use response.data[].id as profileId for get/update/delete tools.");
        } catch (Exception e) {
            logger.error("CQA list quality profiles error", e);
            return errorResult(e);
        }
    }

    @Tool(name = "exotel_cqa_delete_quality_profile",
          description = "Delete a quality profile by ID. Requires a JWT token from exotel_cqa_login. "
              + "This is irreversible — use only on test or disposable profiles. "
              + "REQUIRED: pass confirm=true to proceed.")
    public Map<String, Object> cqaDeleteQualityProfile(
            String jwtToken, String accountId, String profileId, Boolean confirm) {
        logger.info("CQA delete quality profile: account={}, profileId={}", accountId, profileId);
        try {
            requireConfirm(confirm, "delete quality profile " + profileId);
            validateResourceId(profileId, "profileId");
            String baseUrl = setupBaseUrl(jwtToken, accountId);
            String response = deleteWithBearer(baseUrl + "/quality-profiles/" + profileId, jwtToken);
            return parseJsonResponse(response, "Quality profile deleted.");
        } catch (Exception e) {
            logger.error("CQA delete quality profile error", e);
            return errorResult(e);
        }
    }

    @Tool(name = "exotel_cqa_delete_assignment_rule",
          description = "Delete (deactivate) a quality analysis assignment rule by ID. "
              + "Requires a JWT token from exotel_cqa_login. "
              + "REQUIRED: pass confirm=true to proceed.")
    public Map<String, Object> cqaDeleteAssignmentRule(
            String jwtToken, String accountId, String ruleId, Boolean confirm) {
        logger.info("CQA delete assignment rule: account={}, ruleId={}", accountId, ruleId);
        try {
            requireConfirm(confirm, "delete assignment rule " + ruleId);
            validateResourceId(ruleId, "ruleId");
            String baseUrl = setupBaseUrl(jwtToken, accountId);
            String response = deleteWithBearer(baseUrl + "/quality-analysis-rules/" + ruleId, jwtToken);
            return parseJsonResponse(response, "Assignment rule deleted.");
        } catch (Exception e) {
            logger.error("CQA delete assignment rule error", e);
            return errorResult(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Tool(name = "exotel_cqa_update_quality_profile",
          description = "Update an existing quality profile. Requires a JWT token from exotel_cqa_login. "
              + "This operation is NOT atomic — each category, sub-category, and KPI change is a separate API call. "
              + "If a later step fails, earlier changes may already be committed; partial responses include a 'changes' list. "
              + "Provide profileName and/or description to update profile metadata. "
              + "Provide categoriesJson to update the hierarchy — same structure as exotel_cqa_create_quality_profile, "
              + "but include 'id' on existing categories/sub-categories. "
              + "When 'kpis' is provided on a sub-category, all existing KPIs in that sub-category are replaced "
              + "(deleted and recreated with new IDs). Empty kpis requires confirmWipeKpis=true. "
              + "Omit categoriesJson to update only profile metadata. "
              + "Use exotel_cqa_get_quality_profile to verify the final state.")
    public Map<String, Object> cqaUpdateQualityProfile(
            String jwtToken,
            String accountId,
            String profileId,
            String profileName,
            String description,
            String categoriesJson,
            Boolean confirmWipeKpis) {
        logger.info("CQA update quality profile: account={}, profileId={}", accountId, profileId);
        List<String> changes = new ArrayList<>();
        int[] callBudget = {0};
        try {
            validateResourceId(profileId, "profileId");
            String baseUrl = setupBaseUrl(jwtToken, accountId);
            enforceCategoriesJsonSize(categoriesJson);

            boolean hasProfileMeta = (profileName != null && !profileName.isBlank())
                || (description != null && !description.isBlank());
            if (hasProfileMeta) {
                Map<String, Object> qpData = new LinkedHashMap<>();
                if (profileName != null && !profileName.isBlank()) qpData.put("name", profileName);
                if (description != null && !description.isBlank()) qpData.put("description", description);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("quality_profile", qpData);
                putJsonWithBearer(baseUrl + "/quality-profiles/" + profileId, body, jwtToken);
                bumpOutboundCalls(callBudget);
                changes.add("profile_metadata");
            }

            if (categoriesJson == null || categoriesJson.isBlank()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("profile_id", profileId);
                result.put("changes", changes);
                result.put("_hint", changes.isEmpty()
                    ? "No changes requested. Provide profileName, description, and/or categoriesJson."
                    : "Profile metadata updated.");
                return result;
            }

            List<Map<String, Object>> categories = objectMapper.readValue(categoriesJson, List.class);
            Map<String, Object> existingProfile = fetchQualityProfileData(baseUrl, profileId, jwtToken);
            bumpOutboundCalls(callBudget);

            for (Map<String, Object> cat : categories) {
                String catId = cat.get("id") != null ? cat.get("id").toString() : null;
                if (catId != null && !catId.isBlank()) {
                    validateResourceId(catId, "categoryId");
                    Map<String, Object> catPayload = new LinkedHashMap<>();
                    if (cat.get("name") != null) catPayload.put("name", cat.get("name"));
                    if (cat.containsKey("description")) catPayload.put("description", cat.get("description"));
                    if (!catPayload.isEmpty()) {
                        Map<String, Object> catBody = new LinkedHashMap<>();
                        catBody.put("categories", catPayload);
                        putJsonWithBearer(
                            baseUrl + "/quality-profiles/" + profileId + "/categories/" + catId,
                            catBody, jwtToken);
                        bumpOutboundCalls(callBudget);
                        changes.add("category:" + catId);
                    }
                } else {
                    Map<String, Object> catPayload = new LinkedHashMap<>();
                    catPayload.put("name", cat.get("name"));
                    if (cat.containsKey("description")) catPayload.put("description", cat.get("description"));
                    Map<String, Object> catBody = new LinkedHashMap<>();
                    catBody.put("categories", catPayload);
                    String catResponse = postJsonWithBearer(
                        baseUrl + "/quality-profiles/" + profileId + "/categories",
                        catBody, jwtToken);
                    bumpOutboundCalls(callBudget);
                    Map<String, Object> catParsed = objectMapper.readValue(catResponse, Map.class);
                    catId = extractId(extractResponseData(catParsed), "categoryId");
                    changes.add("created_category:" + catId);
                }

                List<Map<String, Object>> subCategories = (List<Map<String, Object>>) cat.get("sub_categories");
                if (subCategories == null) continue;

                for (Map<String, Object> subCat : subCategories) {
                    String subCatId = subCat.get("id") != null ? subCat.get("id").toString() : null;
                    if (subCatId != null && !subCatId.isBlank()) {
                        validateResourceId(subCatId, "subCategoryId");
                        Map<String, Object> subCatPayload = new LinkedHashMap<>();
                        if (subCat.get("name") != null) subCatPayload.put("name", subCat.get("name"));
                        if (subCat.containsKey("description")) {
                            subCatPayload.put("description", subCat.get("description"));
                        }
                        if (!subCatPayload.isEmpty()) {
                            Map<String, Object> subCatBody = new LinkedHashMap<>();
                            subCatBody.put("sub_category", subCatPayload);
                            putJsonWithBearer(
                                baseUrl + "/quality-profiles/" + profileId + "/categories/" + catId
                                    + "/sub-categories/" + subCatId,
                                subCatBody, jwtToken);
                            bumpOutboundCalls(callBudget);
                            changes.add("sub_category:" + subCatId);
                        }
                    } else {
                        Map<String, Object> subCatPayload = new LinkedHashMap<>();
                        subCatPayload.put("name", subCat.get("name"));
                        if (subCat.containsKey("description")) {
                            subCatPayload.put("description", subCat.get("description"));
                        }
                        Map<String, Object> subCatBody = new LinkedHashMap<>();
                        subCatBody.put("sub_category", subCatPayload);
                        String subCatResponse = postJsonWithBearer(
                            baseUrl + "/quality-profiles/" + profileId + "/categories/" + catId
                                + "/sub-categories",
                            subCatBody, jwtToken);
                        bumpOutboundCalls(callBudget);
                        Map<String, Object> subCatParsed = objectMapper.readValue(subCatResponse, Map.class);
                        subCatId = extractId(extractResponseData(subCatParsed), "subCategoryId");
                        changes.add("created_sub_category:" + subCatId);
                    }

                    if (!subCat.containsKey("kpis")) continue;
                    List<Map<String, Object>> kpis = (List<Map<String, Object>>) subCat.get("kpis");
                    if (kpis == null) continue;
                    if (kpis.isEmpty() && !Boolean.TRUE.equals(confirmWipeKpis)) {
                        throw new IllegalArgumentException(
                            "kpis is empty for subCategoryId=" + subCatId
                            + " — refusing to wipe KPIs. Pass confirmWipeKpis=true or omit 'kpis'.");
                    }

                    List<String> existingKpiIds = findKpiIds(existingProfile, catId, subCatId);
                    if (!existingKpiIds.isEmpty()) {
                        changes.add("deleting_kpis:" + subCatId + "(" + existingKpiIds.size() + ")");
                    }
                    for (String kpiId : existingKpiIds) {
                        validateResourceId(kpiId, "kpiId");
                        deleteWithBearer(
                            baseUrl + "/quality-profiles/" + profileId + "/categories/" + catId
                                + "/sub-categories/" + subCatId + "/kpis/" + kpiId,
                            jwtToken);
                        bumpOutboundCalls(callBudget);
                    }
                    int kpisCreated = 0;
                    for (Map<String, Object> kpi : kpis) {
                        validateKpiOptions(kpi);
                        Map<String, Object> kpiBody = new LinkedHashMap<>();
                        kpiBody.put("kpi", sanitizeKpiPayload(kpi));
                        postJsonWithBearer(
                            baseUrl + "/quality-profiles/" + profileId + "/categories/" + catId
                                + "/sub-categories/" + subCatId + "/kpis",
                            kpiBody, jwtToken);
                        bumpOutboundCalls(callBudget);
                        kpisCreated++;
                    }
                    changes.add("replaced_kpis:" + subCatId + "(" + kpisCreated + ")");
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("profile_id", profileId);
            result.put("changes", changes);
            result.put("_hint", "Quality profile updated. Use exotel_cqa_get_quality_profile to verify.");
            return result;
        } catch (Exception e) {
            logger.error("CQA update quality profile error", e);
            if (!changes.isEmpty()) {
                Map<String, Object> partial = new LinkedHashMap<>();
                partial.put("partial", true);
                partial.put("profile_id", profileId);
                partial.put("changes", changes);
                partial.put("error", e.getMessage());
                partial.put("_hint", "Profile was partially updated. Use exotel_cqa_get_quality_profile to verify, "
                    + "then retry failed parts or fix manually in the console.");
                return partial;
            }
            return errorResult(e);
        }
    }

    @Tool(name = "exotel_cqa_list_assignment_rules",
          description = "List quality analysis assignment rules for an account with pagination. "
              + "Requires a JWT token from exotel_cqa_login. "
              + "Optional: limit (1-100, default 10), offset (default 0), sortBy (e.g. name:asc,created_at:desc), "
              + "filter (JSON filter, e.g. {\"$and\":[{\"rules.status\":[\"ACTIVE\"]}]}), ruleIds (comma-separated UUIDs).")
    public Map<String, Object> cqaListAssignmentRules(
            String jwtToken,
            String accountId,
            Integer limit,
            Integer offset,
            String sortBy,
            String ruleIds,
            String filter) {
        logger.info("CQA list assignment rules: account={}, limit={}, offset={}", accountId, limit, offset);
        try {
            String baseUrl = setupBaseUrl(jwtToken, accountId);
            int lim = normalizeLimit(limit);
            int off = normalizeOffset(offset);

            StringBuilder url = new StringBuilder(baseUrl)
                .append("/quality-analysis-rules?limit=").append(lim)
                .append("&offset=").append(off);
            if (sortBy != null && !sortBy.isBlank()) {
                url.append("&sort_by=").append(java.net.URLEncoder.encode(sortBy, StandardCharsets.UTF_8));
            }
            if (ruleIds != null && !ruleIds.isBlank()) {
                url.append("&rule_uid=").append(java.net.URLEncoder.encode(ruleIds, StandardCharsets.UTF_8));
            }
            if (filter != null && !filter.isBlank()) {
                url.append("&filter=").append(java.net.URLEncoder.encode(filter, StandardCharsets.UTF_8));
            }

            String response = getJsonWithBearer(url.toString(), jwtToken);
            return parseJsonResponse(response,
                "Assignment rules listed. Use response.data[].id as ruleId for update/delete tools.");
        } catch (Exception e) {
            logger.error("CQA list assignment rules error", e);
            return errorResult(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Tool(name = "exotel_cqa_update_assignment_rule",
          description = "Update an existing quality analysis assignment rule. "
              + "Requires a JWT token from exotel_cqa_login. "
              + "Provide any combination of: ruleName, description, filterGroupJson (2D array, same format as create), "
              + "qualityProfileIds (comma-separated UUIDs), samplingPercentage (0-100). "
              + "Only provided fields are updated.")
    public Map<String, Object> cqaUpdateAssignmentRule(
            String jwtToken,
            String accountId,
            String ruleId,
            String ruleName,
            String description,
            String filterGroupJson,
            String qualityProfileIds,
            Integer samplingPercentage) {
        logger.info("CQA update assignment rule: account={}, ruleId={}", accountId, ruleId);
        try {
            validateResourceId(ruleId, "ruleId");
            String baseUrl = setupBaseUrl(jwtToken, accountId);

            Map<String, Object> body = new LinkedHashMap<>();
            if (ruleName != null && !ruleName.isBlank()) body.put("name", ruleName);
            if (description != null && !description.isBlank()) body.put("description", description);
            if (filterGroupJson != null && !filterGroupJson.isBlank()) {
                body.put("filter_group", objectMapper.readValue(filterGroupJson, List.class));
            }
            if (qualityProfileIds != null && !qualityProfileIds.isBlank()) {
                body.put("assign_quality_profiles", parseProfileIds(qualityProfileIds));
            }
            if (samplingPercentage != null) {
                body.put("sampling_percentage", requireSamplingPercentage(samplingPercentage));
            }

            if (body.isEmpty()) {
                throw new IllegalArgumentException("At least one field must be provided to update.");
            }

            String response = putJsonWithBearer(baseUrl + "/quality-analysis-rules/" + ruleId, body, jwtToken);
            return parseJsonResponse(response, "Assignment rule updated.");
        } catch (Exception e) {
            logger.error("CQA update assignment rule error", e);
            return errorResult(e);
        }
    }

    @Tool(name = "exotel_cqa_duplicate_quality_profile",
          description = "Duplicate an existing quality profile including all categories, sub-categories, and KPIs. "
              + "Requires a JWT token from exotel_cqa_login. "
              + "The new profile gets a new ID and '(Duplicate)' appended to its name.")
    public Map<String, Object> cqaDuplicateQualityProfile(
            String jwtToken,
            String accountId,
            String profileId) {
        logger.info("CQA duplicate quality profile: account={}, profileId={}", accountId, profileId);
        try {
            validateResourceId(profileId, "profileId");
            String baseUrl = setupBaseUrl(jwtToken, accountId);
            Map<String, Object> body = Map.of("action", "duplicate");
            String response = postJsonWithBearer(
                baseUrl + "/quality-profiles/" + profileId, body, jwtToken);
            return parseJsonResponse(response,
                "Profile duplicated. Use response.data.id as the new profileId.");
        } catch (Exception e) {
            logger.error("CQA duplicate quality profile error", e);
            return errorResult(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Tool(name = "exotel_cqa_configure_metadata",
          description = "List or create metadata field mappings for interactions. "
              + "Requires a JWT token from exotel_cqa_login. "
              + "action: 'list' (GET all configs) or 'create' (POST new mapping). "
              + "For create, configJson is required — JSON with fields: cqa_key, mapped_from_external_value, "
              + "display_name, is_enabled, is_used_for_access_control, is_used_for_filtering, data_type "
              + "(string/number/boolean/date), description (optional). "
              + "If is_used_for_access_control is true, pass confirm=true.")
    public Map<String, Object> cqaConfigureMetadata(
            String jwtToken,
            String accountId,
            String action,
            String configJson,
            Boolean confirm) {
        logger.info("CQA configure metadata: account={}, action={}", accountId, action);
        try {
            String baseUrl = setupBaseUrl(jwtToken, accountId);
            String url = baseUrl + "/metadata_config";
            String normalizedAction = action != null ? action.trim().toLowerCase() : "";

            return switch (normalizedAction) {
                case "list" -> parseJsonResponse(getJsonWithBearer(url, jwtToken),
                    "Metadata configurations retrieved.");
                case "create" -> {
                    if (configJson == null || configJson.isBlank()) {
                        throw new IllegalArgumentException("configJson is required for action=create");
                    }
                    Map<String, Object> body = objectMapper.readValue(configJson, Map.class);
                    Object ac = body.get("is_used_for_access_control");
                    if (Boolean.TRUE.equals(ac) || "true".equalsIgnoreCase(String.valueOf(ac))) {
                        requireConfirm(confirm,
                            "create metadata with is_used_for_access_control=true");
                    }
                    yield parseJsonResponse(postJsonWithBearer(url, body, jwtToken),
                        "Metadata configuration created.");
                }
                default -> throw new IllegalArgumentException(
                    "action must be 'list' or 'create', got: " + action);
            };
        } catch (Exception e) {
            logger.error("CQA configure metadata error", e);
            return errorResult(e);
        }
    }

    @SuppressWarnings("unchecked")
    @Tool(name = "exotel_cqa_list_analyses",
          description = "List interaction analyses (scored conversations) with optional filters. "
              + "Requires a JWT token from exotel_cqa_login. "
              + "Uses POST with optional filterJson body containing metadata_filter_group, "
              + "quality_profile_uid, and other_filters. Pagination via limit and offset. "
              + "Access is ABAC-scoped: Admin sees all, Supervisor sees team/campaign data, Agent sees own interactions only.")
    public Map<String, Object> cqaListAnalyses(
            String jwtToken,
            String accountId,
            Integer limit,
            Integer offset,
            String filterJson,
            String qualityProfileUid) {
        logger.info("CQA list analyses: account={}, limit={}, offset={}", accountId, limit, offset);
        try {
            String baseUrl = setupBaseUrl(jwtToken, accountId);
            int lim = normalizeLimit(limit);
            int off = normalizeOffset(offset);

            String url = baseUrl + "/interaction-analysis?limit=" + lim + "&offset=" + off;
            Map<String, Object> body = new LinkedHashMap<>();
            if (filterJson != null && !filterJson.isBlank()) {
                Map<String, Object> filters = objectMapper.readValue(filterJson, Map.class);
                body.putAll(filters);
            }
            if (qualityProfileUid != null && !qualityProfileUid.isBlank()) {
                validateResourceId(qualityProfileUid, "qualityProfileUid");
                body.put("quality_profile_uid", qualityProfileUid);
            }

            String response = postJsonWithBearer(url, body.isEmpty() ? Map.of() : body, jwtToken);
            return parseJsonResponse(response,
                "Analyses listed. Use exotel_cqa_get_analysis (API-key auth, not JWT) with analysis ID for full detail.");
        } catch (Exception e) {
            logger.error("CQA list analyses error", e);
            return errorResult(e);
        }
    }


    // ===================== AUTH =====================

    private String setupBaseUrl(String jwtToken, String accountId) {
        if (jwtToken == null || jwtToken.isBlank()) {
            throw new IllegalArgumentException("jwtToken is required — obtain it via exotel_cqa_login");
        }
        String host = getCqaHostUrl();
        validateHost(host);
        validateAccountId(accountId);
        AuthCredentials creds = AuthContext.current();
        if (creds != null && creds.isParsed()
                && creds.getCqaAccountId() != null && !creds.getCqaAccountId().isBlank()
                && !creds.getCqaAccountId().equals(accountId)) {
            throw new IllegalArgumentException(
                "accountId does not match cqa_account_id in MCP Authorization header");
        }
        return host + "/cqa/api/v1/accounts/" + accountId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchQualityProfileData(String baseUrl, String profileId, String jwtToken)
            throws Exception {
        validateResourceId(profileId, "profileId");
        String response = getJsonWithBearer(baseUrl + "/quality-profiles/" + profileId, jwtToken);
        Map<String, Object> parsed = objectMapper.readValue(response, Map.class);
        return extractResponseData(parsed);
    }

    @SuppressWarnings("unchecked")
    private List<String> findKpiIds(Map<String, Object> profileData, String categoryId, String subCategoryId) {
        List<String> kpiIds = new ArrayList<>();
        Object categoriesObj = profileData.get("categories");
        if (!(categoriesObj instanceof List<?> categories)) return kpiIds;

        for (Object catObj : categories) {
            if (!(catObj instanceof Map<?, ?> cat)) continue;
            if (!categoryId.equals(String.valueOf(cat.get("id")))) continue;
            Object subCatsObj = cat.get("sub_categories");
            if (!(subCatsObj instanceof List<?> subCats)) break;

            for (Object subObj : subCats) {
                if (!(subObj instanceof Map<?, ?> sub)) continue;
                if (!subCategoryId.equals(String.valueOf(sub.get("id")))) continue;
                Object kpisObj = sub.get("kpis");
                if (!(kpisObj instanceof List<?> kpis)) break;

                for (Object kpiObj : kpis) {
                    if (kpiObj instanceof Map<?, ?> kpi && kpi.get("id") != null) {
                        kpiIds.add(kpi.get("id").toString());
                    }
                }
                break;
            }
            break;
        }
        return kpiIds;
    }

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
        AuthCredentials creds = AuthContext.current();
        if (!creds.isParsed() || creds.getCqaHost() == null || creds.getCqaHost().isBlank()) {
            throw new IllegalStateException(
                "CQA setup tools require cqa_host in your MCP Authorization header. "
                + "Add \"cqa_host\":\"https://cqa-console.in.exotel.com\" to your mcp.json config. "
                + "For setup help, use the tool: exotel_setup_guide");
        }
        return creds.effectiveCqaHost("https://cqa-console.in.exotel.com");
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
        if (accountId == null || !accountId.matches("[a-zA-Z0-9\\-]+")) {
            throw new IllegalArgumentException("cqa_account_id contains invalid characters");
        }
    }

    private void requireConfirm(Boolean confirm, String action) {
        if (!Boolean.TRUE.equals(confirm)) {
            throw new IllegalArgumentException("Set confirm=true to " + action);
        }
    }

    private int requireSamplingPercentage(int pct) {
        if (pct < 0 || pct > 100) {
            throw new IllegalArgumentException("samplingPercentage must be 0-100, got: " + pct);
        }
        return pct;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) return 10;
        return Math.min(limit, 100);
    }

    private int normalizeOffset(Integer offset) {
        if (offset == null || offset < 0) return 0;
        return offset;
    }

    private void enforceCategoriesJsonSize(String categoriesJson) {
        if (categoriesJson != null && categoriesJson.length() > MAX_CATEGORIES_JSON_CHARS) {
            throw new IllegalArgumentException(
                "categoriesJson exceeds max " + MAX_CATEGORIES_JSON_CHARS + " chars");
        }
    }

    private void bumpOutboundCalls(int[] callBudget) {
        callBudget[0]++;
        if (callBudget[0] > MAX_OUTBOUND_API_CALLS) {
            throw new IllegalStateException(
                "Exceeded max outbound API calls (" + MAX_OUTBOUND_API_CALLS
                + ") for this tool invocation — split the update");
        }
    }

    private String extractId(Map<String, Object> data, String name) {
        Object id = data == null ? null : data.get("id");
        String value = id == null ? null : id.toString();
        validateResourceId(value, name);
        return value;
    }

    private List<String> parseProfileIds(String qualityProfileIds) {
        if (qualityProfileIds == null || qualityProfileIds.isBlank()) {
            throw new IllegalArgumentException("qualityProfileIds is required");
        }
        List<String> profileIds = new ArrayList<>();
        for (String id : qualityProfileIds.split(",")) {
            String trimmed = id.trim();
            if (trimmed.isEmpty()) continue;
            validateResourceId(trimmed, "qualityProfileId");
            profileIds.add(trimmed);
        }
        if (profileIds.isEmpty()) {
            throw new IllegalArgumentException("qualityProfileIds must contain at least one id");
        }
        return profileIds;
    }

    private Map<String, Object> sanitizeKpiPayload(Map<String, Object> kpi) {
        Map<String, Object> payload = new LinkedHashMap<>(kpi);
        payload.remove("id");
        payload.remove("created_at");
        payload.remove("updated_at");
        payload.remove("createdAt");
        payload.remove("updatedAt");
        return payload;
    }

    /**
     * Validates IDs used as URL path segments. Rejects path traversal / query injection
     * characters (/, ?, #, ., etc.).
     */
    private void validateResourceId(String value, String name) {
        if (!isPathSafeResourceId(value)) {
            throw new IllegalArgumentException(
                value == null || value.isBlank()
                    ? name + " is required"
                    : name + " contains invalid characters");
        }
    }

    /** ponytail: package-visible for unit check of path-id regex. */
    static boolean isPathSafeResourceId(String value) {
        return value != null && !value.isBlank() && RESOURCE_ID_PATTERN.matcher(value).matches();
    }

    /** Redact customer content fields before writing request bodies to logs. */
    private String redactBodyForLog(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return "{}";
        }
        try {
            Map<String, Object> copy = new LinkedHashMap<>(body);
            for (String key : List.of(
                    "transcript_text", "transcriptText", "password", "username",
                    "audio_url", "transcript_url", "audioUrl", "transcriptUrl")) {
                if (copy.containsKey(key) && copy.get(key) != null) {
                    copy.put(key, "[REDACTED]");
                }
            }
            return objectMapper.writeValueAsString(copy);
        } catch (Exception e) {
            return "[UNLOGGABLE]";
        }
    }

    private String truncateForLog(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= MAX_LOG_BODY_CHARS) {
            return value;
        }
        return value.substring(0, MAX_LOG_BODY_CHARS) + "...[truncated]";
    }

    // ===================== HTTP =====================

    private String postJson(String url, Map<String, Object> body, String apiKey) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(body);
        logger.debug("CQA POST {} body={}", url, redactBodyForLog(body));

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
        logger.debug("CQA POST (Bearer) {}", url);

        ClassicHttpRequest request = ClassicRequestBuilder.post(url)
            .setHeader("Authorization", "Bearer " + jwtToken)
            .setHeader("Content-Type", "application/json")
            .setHeader("Accept", "application/json")
            .setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8))
            .build();

        return executeRequest(request);
    }

    private String getJsonWithBearer(String url, String jwtToken) throws Exception {
        logger.debug("CQA GET (Bearer) {}", url);

        ClassicHttpRequest request = ClassicRequestBuilder.get(url)
            .setHeader("Authorization", "Bearer " + jwtToken)
            .setHeader("Accept", "application/json")
            .build();

        return executeRequest(request);
    }

    private String putJsonWithBearer(String url, Object body, String jwtToken) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(body);
        logger.debug("CQA PUT (Bearer) {}", url);

        ClassicHttpRequest request = ClassicRequestBuilder.put(url)
            .setHeader("Authorization", "Bearer " + jwtToken)
            .setHeader("Content-Type", "application/json")
            .setHeader("Accept", "application/json")
            .setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8))
            .build();

        return executeRequest(request);
    }

    private String deleteWithBearer(String url, String jwtToken) throws Exception {
        logger.debug("CQA DELETE (Bearer) {}", url);

        ClassicHttpRequest request = ClassicRequestBuilder.delete(url)
            .setHeader("Authorization", "Bearer " + jwtToken)
            .setHeader("Accept", "application/json")
            .build();

        return executeRequest(request);
    }

    private String postJsonNoAuth(String url, Object body) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(body);
        logger.debug("CQA POST (no auth) {} body=[REDACTED]", url);

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
                logger.warn("CQA API error: status={} body={}", code, truncateForLog(responseBody));
                String safeMessage = "CQA API returned HTTP " + code;
                try {
                    Map<?, ?> errBody = objectMapper.readValue(responseBody, Map.class);
                    Object resp = errBody.get("response");
                    if (resp instanceof Map<?, ?> respMap) {
                        Object msg = respMap.get("message");
                        if (msg != null) safeMessage += ": " + msg;
                    }
                } catch (Exception ignored) {}
                throw new RuntimeException(safeMessage);
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
