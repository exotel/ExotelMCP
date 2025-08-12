package com.example.mcp_api.service;

import org.apache.hc.client5.http.classic.HttpClient;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.mcp_api.repository.SmsCallbackRepository;
import com.example.mcp_api.repository.VoiceCallbackRepository;
import com.example.mcp_api.entity.SmsCallback;
import com.example.mcp_api.entity.VoiceCallback;
import com.example.mcp_api.dto.AuthData;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

@Service
public class ExotelService {
    
    private static final Logger logger = LoggerFactory.getLogger(ExotelService.class);
    
    @Autowired
    private SmsCallbackRepository smsCallbackRepository;
    
    @Autowired
    private VoiceCallbackRepository voiceCallbackRepository;
    
    @Value("${exotel.base.url:http://localhost:8085}")
    private String baseUrl;
    
    private final HttpClient httpClient;
    private final String callbackId = UUID.randomUUID().toString();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // In-memory cache for metadata (simple optimization without Redis)
    private final Map<String, CacheEntry> metadataCache = new ConcurrentHashMap<>();
    
    public ExotelService() {
        this.httpClient = createOptimizedHttpClient();
    }
    
    // Create optimized HTTP client with connection pooling and timeouts
    private CloseableHttpClient createOptimizedHttpClient() {
        // Configure connection pool
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(50); // Maximum total connections
        connectionManager.setDefaultMaxPerRoute(20); // Maximum connections per route
        
        // Configure request timeouts
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofSeconds(5)) // Timeout for getting connection from pool
            .setResponseTimeout(Timeout.ofSeconds(30)) // Socket timeout for response
            .build();
        
        return HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .setKeepAliveStrategy((response, context) -> Timeout.ofSeconds(30)) // Keep-alive for 30 seconds
            .build();
    }
    
    // Simple cache entry for in-memory caching
    private static class CacheEntry {
        private final String value;
        private final long expiryTime;
        
        public CacheEntry(String value, long ttlMillis) {
            this.value = value;
            this.expiryTime = System.currentTimeMillis() + ttlMillis;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
        
        public String getValue() {
            return value;
        }
    }
    
    // HTTP endpoint method (uses session-based auth) - calls the Tool method
    public String sendSmsToUserEndpoint(String toNumber, String message, String dltTemplateId, String dltEntityId) {
        return sendSmsToUser(toNumber, message, dltTemplateId, dltEntityId);
    }
    
    @Tool(name = "sendSmsToUser", 
          description = "Send an SMS message to a single user using DLT-compliant parameters. Requires phone number, DLT template ID, DLT entity ID, and message content. Authentication is handled automatically from the session.")
    public String sendSmsToUser(String toNumber, String message, String dltTemplateId, String dltEntityId) {
        logger.info("Sending SMS to: {}", toNumber);
        try {
            String authHeader = getCurrentAuthHeader();
            AuthData authData = parseAuthHeader(authHeader);
            String statusCallbackUrl = baseUrl + "/sms-status-callback/" + callbackId + "/" + authData.tokenMd5();
            logger.info("Sending SMS. Callback URL: {}", statusCallbackUrl);
            
            Map<String, String> data = new HashMap<>();
            data.put("From", authData.fromNumber());
            data.put("To", toNumber);
            data.put("Body", message);
            data.put("StatusCallback", statusCallbackUrl);
            data.put("StatusCallbackContentType", "application/json");
            data.put("SmsType", "promotional");
            data.put("DltTemplateId", dltTemplateId);
            data.put("DltEntityId", dltEntityId);
            
            String smsUrl = authData.apiDomain() + "/v1/Accounts/" + authData.accountSid() + "/Sms/send.json";
            String response = makeHttpRequest(smsUrl, data, authData);
            logger.info("SMS response: {}", response);
            
            // Save initial SMS callback from response (async for better performance)
            CompletableFuture.runAsync(() -> {
                try {
            saveInitialSmsCallback(response, authData.tokenMd5());
                } catch (Exception e) {
                    logger.warn("Async SMS callback save failed: {}", e.getMessage());
                }
            });
            
            return response;
        } catch (Exception e) {
            logger.error("Error sending SMS", e);
            return "{\"message\":\"" + e.getMessage() + "\"}";
        }
    }
    
    // HTTP endpoint method (uses session-based auth) - calls the Tool method
    public String sendVoiceCallToUserEndpoint(String toNumber) {
        return sendVoiceCallToUser(toNumber);
    }
    
    @Tool(name = "sendVoiceCallToUser", 
          description = "Initiates a voice call to the specified user number using a fixed source number. Requires phone number. Authentication is handled automatically from the session.")
    public String sendVoiceCallToUser(String toNumber) {
        logger.info("Sending voice call to: {}", toNumber);
        try {
            String authHeader = getCurrentAuthHeader();
            AuthData authData = parseAuthHeader(authHeader);
            String statusCallbackUrl = baseUrl + "/call-status/" + callbackId + "/" + authData.tokenMd5();
            logger.info("Sending call. Callback URL: {}", statusCallbackUrl);
            
            Map<String, String> data = new HashMap<>();
            data.put("From", toNumber);
            data.put("To", authData.fromNumber());
            data.put("CallerId", authData.callerId());
            data.put("StatusCallback", statusCallbackUrl);
            data.put("StatusCallbackContentType", "application/json");
            data.put("Record", "true");
            
            String voiceUrl = authData.apiDomain() + "/v1/Accounts/" + authData.accountSid() + "/Calls/connect.json";
            String response = makeHttpRequest(voiceUrl, data, authData);
            logger.info("Voice call response: {}", response);
            
            // Save initial call data to database
            try {
                saveInitialVoiceCallback(response, authData.tokenMd5());
            } catch (Exception e) {
                logger.error("Error saving initial voice callback", e);
            }
            
            return response;
        } catch (Exception e) {
            logger.error("Error sending voice call", e);
            return "{\"message\":\"" + e.getMessage() + "\"}";
        }
    }
    
    // HTTP endpoint method (uses session-based auth) - calls the Tool method
    public String outgoingCallToConnectNumberEndpoint(String fromNumber, String toNumber) {
        return outgoingCallToConnectNumber(fromNumber, toNumber);
    }
    
    @Tool(name = "outgoingCallToConnectNumber", 
          description = "Initiates an outgoing voice call from a specified number to a target number. Requires from number and to number. Authentication is handled automatically from the session.")
    public String outgoingCallToConnectNumber(String fromNumber, String toNumber) {
        logger.info("Sending voice call from: {} to: {}", fromNumber, toNumber);
        try {
            String authHeader = getCurrentAuthHeader();
            AuthData authData = parseAuthHeader(authHeader);
            String statusCallbackUrl = baseUrl + "/call-status/" + callbackId + "/" + authData.tokenMd5();
            logger.info("Sending call. Callback URL: {}", statusCallbackUrl);
            
            Map<String, String> data = new HashMap<>();
            data.put("From", fromNumber);
            data.put("To", toNumber);
            data.put("CallerId", authData.callerId());
            data.put("StatusCallback", statusCallbackUrl);
            data.put("StatusCallbackContentType", "application/json");
            data.put("Record", "true");
            String voiceUrl = authData.apiDomain() + "/v1/Accounts/" + authData.accountSid() + "/Calls/connect.json";
            String response = makeHttpRequest(voiceUrl, data, authData);
            logger.info("Voice call response: {}", response);
            
            // Save initial call data to database
            try {
                saveInitialVoiceCallback(response, authData.tokenMd5());
            } catch (Exception e) {
                logger.error("Error saving initial voice callback", e);
            }
            
            return response;
        } catch (Exception e) {
            logger.error("Error connecting call", e);
            return "{\"message\":\"" + e.getMessage() + "\"}";
        }
    }
    
    // HTTP endpoint method (uses session-based auth) - calls the Tool method
    public String sendMessageToBulkNumbersEndpoint(List<String> toNumbers, String message) {
        return sendMessageToBulkNumbers(toNumbers, message);
    }
    
    @Tool(name = "sendMessageToBulkNumbers", 
          description = "Send same SMS to multiple phone numbers at once. Requires phone numbers list and message. Authentication is handled automatically from the session.")
    public String sendMessageToBulkNumbers(List<String> toNumbers, String message) {
        logger.info("Sending bulk SMS to: {}", toNumbers);
        try {
            String authHeader = getCurrentAuthHeader();
            AuthData authData = parseAuthHeader(authHeader);
            String statusCallbackUrl = baseUrl + "/sms-status-callback/" + callbackId + "/" + authData.tokenMd5();
            logger.info("Sending bulk SMS. Callback URL: {}", statusCallbackUrl);
            
            Map<String, Object> data = new HashMap<>();
            data.put("From", authData.fromNumber());
            for (int i = 0; i < toNumbers.size(); i++) {
                data.put("To[" + i + "]", toNumbers.get(i));
            }
            data.put("Body", message);
            data.put("StatusCallback", statusCallbackUrl);
            data.put("StatusCallbackContentType", "application/json");
            String smsUrl = authData.apiDomain() + "/v1/Accounts/" + authData.accountSid() + "/Sms/send.json";
            String response = makeHttpRequest(smsUrl, data, authData);
            logger.info("Bulk SMS response: {}", response);
            
            // Save initial bulk SMS callbacks from response
            saveInitialBulkSmsCallback(response, authData.tokenMd5());
            
            return response;
        } catch (Exception e) {
            logger.error("Error sending bulk SMS", e);
            return "{\"message\":\"" + e.getMessage() + "\"}";
        }
    }
    
    // HTTP endpoint method (uses session-based auth) - calls the Tool method
    public String connectNumberToCallFlowEndpoint(String appId, String fromNumber) {
        return connectNumberToCallFlow(appId, fromNumber);
    }
    
    // HTTP endpoint method (uses session-based auth) - calls the Tool method
    public String sendDynamicBulkSmsEndpoint(List<com.example.mcp_api.dto.Message> messages) {
        return sendDynamicBulkSms(messages);
    }
    
    @Tool(name = "sendDynamicBulkSms", 
          description = "Send dynamic SMS to multiple numbers in one request. Each message can have different content. Requires list of messages with Body and To fields. Authentication is handled automatically from the session.")
    public String sendDynamicBulkSms(List<com.example.mcp_api.dto.Message> messages) {
        logger.info("Sending dynamic bulk SMS with {} messages", messages.size());
        try {
            String authHeader = getCurrentAuthHeader();
            AuthData authData = parseAuthHeader(authHeader);
            String statusCallbackUrl = baseUrl + "/sms-status-callback/" + callbackId + "/" + authData.tokenMd5();
            logger.info("Sending dynamic bulk SMS. Callback URL: {}", statusCallbackUrl);
            
            Map<String, Object> data = new HashMap<>();
            data.put("From", authData.fromNumber());
            data.put("StatusCallback", statusCallbackUrl);
            data.put("StatusCallbackContentType", "application/json");
            
            // Add dynamic message data in Message[idx][key] format
            for (int idx = 0; idx < messages.size(); idx++) {
                com.example.mcp_api.dto.Message message = messages.get(idx);
                data.put("Message[" + idx + "][Body]", message.Body());
                data.put("Message[" + idx + "][To]", message.To());
            }
            
            logger.info("Created dynamic bulk SMS data with {} messages", messages.size());
            String smsUrl = authData.apiDomain() + "/v1/Accounts/" + authData.accountSid() + "/Sms/bulksend.json";
            String response = makeHttpRequest(smsUrl, data, authData);
            logger.info("Dynamic bulk SMS response: {}", response);
            
            // Save initial dynamic bulk SMS callbacks from response
            saveInitialBulkSmsCallback(response, authData.tokenMd5());
            
            return response;
        } catch (Exception e) {
            logger.error("Error sending dynamic bulk SMS", e);
            return "{\"message\":\"" + e.getMessage() + "\"}";
        }
    }
    
    @Tool(name = "connectNumberToCallFlow", 
          description = "Initiate a voice call to connect a number to a predefined call flow using the provided app ID. Requires app ID and from number. Authentication is handled automatically from the session.")
    public String connectNumberToCallFlow(String appId, String fromNumber) {
        logger.info("Connecting call flow: {}", appId);
        try {
            String authHeader = getCurrentAuthHeader();
            AuthData authData = parseAuthHeader(authHeader);
            String statusCallbackUrl = baseUrl + "/call-status/" + callbackId + "/" + authData.tokenMd5();
            logger.info("Sending call. Callback URL: {}", statusCallbackUrl);
            
            Map<String, String> data = new HashMap<>();
            data.put("From", fromNumber);
            data.put("CallerId", authData.callerId());
            data.put("StatusCallback", statusCallbackUrl);
            data.put("Url", authData.exotelPortalUrl() + "/" + authData.accountSid() + "/exoml/start_voice/" + appId);
            data.put("StatusCallbackContentType", "application/json");
            data.put("Record", "true");
            String voiceUrl = authData.apiDomain() + "/v1/Accounts/" + authData.accountSid() + "/Calls/connect.json";
            String response = makeHttpRequest(voiceUrl, data, authData);
            logger.info("Call flow response: {}", response);
            
            // Save initial call data to database
            try {
                saveInitialVoiceCallback(response, authData.tokenMd5());
            } catch (Exception e) {
                logger.error("Error saving initial voice callback", e);
            }
            
            return response;
        } catch (Exception e) {
            logger.error("Error connecting call flow", e);
            return "{\"message\":\"" + e.getMessage() + "\"}";
        }
    }
    
    // HTTP endpoint method (uses session-based auth) - calls the Tool method
    public Map<String, Object> getSmsCallbacksEndpoint(String toNumber) {
        return getSmsCallbacks(toNumber);
    }
    
    @Tool(name = "getSmsCallbacks", 
          description = "Fetch all SMS callback with status records from the database for the given user and phone number. Searches in to_number field with user_id security. Requires phone number. Authentication is handled automatically from the session.")
    public Map<String, Object> getSmsCallbacks(String phoneNumber) {
        logger.info("Fetching SMS callbacks for phone number: {}", phoneNumber);
        try {
            String authHeader = getCurrentAuthHeader();
            AuthData authData = parseAuthHeader(authHeader);
            String formattedNumber = formatPhoneNumberForQuery(phoneNumber);
            String userId = authData.tokenMd5();
            
            logger.info("SMS search: formatted phone number='{}', user_id='{}'", formattedNumber, userId);
            
            // Use enhanced search that includes user_id for security
            List<SmsCallback> callbacks = smsCallbackRepository.findByPhoneNumberAndUserId(formattedNumber, userId);
            
            logger.info("Found {} SMS callbacks for phone number: {}", callbacks.size(), formattedNumber);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status_data", callbacks);
            result.put("search_info", Map.of(
                "phone_number", phoneNumber,
                "formatted_number", formattedNumber,
                "records_found", callbacks.size(),
                "search_type", "SMS to_number with user_id security"
            ));
            
            return result;
        } catch (Exception e) {
            logger.error("Database error in getSmsCallbacks: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("status_data", "Not found because " + e.getMessage());
            error.put("error_details", e.getClass().getSimpleName() + ": " + e.getMessage());
            return error;
        }
    }
    
    // HTTP endpoint method (uses session-based auth) - calls the Tool method
    public Map<String, Object> getVoiceCallCallbacksEndpoint(String toNumber) {
        return getVoiceCallCallbacks(toNumber);
    }
    
    @Tool(name = "getVoiceCallCallbacks", 
          description = "Fetch all voice call callback with status records from the database for the given phone number. Searches in BOTH to_number OR from_number with user_id security. Requires phone number. Authentication is handled automatically from the session.")
    public Map<String, Object> getVoiceCallCallbacks(String phoneNumber) {
        logger.info("=== ENHANCED VOICE CALLBACKS SEARCH ===");
        logger.info("Input phoneNumber: '{}'", phoneNumber);
        
        try {
            String authHeader = getCurrentAuthHeader();
            AuthData authData = parseAuthHeader(authHeader);
            String formattedNumber = formatPhoneNumberForQuery(phoneNumber);
            String userId = authData.tokenMd5();
            
            // Enhanced debugging logs
            logger.info("Original phoneNumber: '{}'", phoneNumber);
            logger.info("Formatted phoneNumber: '{}'", formattedNumber);
            logger.info("UserID (tokenMd5): '{}'", userId);
            logger.info("Executing enhanced query: SELECT * FROM voice_callbacks WHERE (to_number = '{}' OR from_number = '{}') AND user_id = '{}'", formattedNumber, formattedNumber, userId);
            
            // Execute the enhanced query that searches BOTH to_number OR from_number
            logger.info("Executing enhanced search with parameters: phoneNumber='{}', userId='{}'", formattedNumber, userId);
            List<VoiceCallback> callbacks = voiceCallbackRepository.findByPhoneNumberInToOrFromAndUserId(formattedNumber, userId);
            logger.info("Enhanced search returned {} records", callbacks.size());
            
            // If no results, try debugging queries
            if (callbacks.isEmpty()) {
                logger.warn("No records found with exact match. Running diagnostic queries...");
                
                // Check total records in table
                List<VoiceCallback> allRecords = voiceCallbackRepository.findAll();
                logger.info("Total voice_callbacks records in database: {}", allRecords.size());
                
                // Show sample records for debugging
                if (!allRecords.isEmpty()) {
                    logger.info("Sample records from voice_callbacks table:");
                    int sampleSize = Math.min(3, allRecords.size());
                    for (int i = 0; i < sampleSize; i++) {
                        VoiceCallback sample = allRecords.get(i);
                        logger.info("  Record {}: to_number='{}', user_id='{}', call_sid='{}', status='{}'", 
                                   i+1, sample.getToNumber(), sample.getUserId(), sample.getCallSid(), sample.getStatus());
                    }
                }
                
                // Try finding by to_number only using repository method
                logger.info("Testing query with to_number only: '{}'", formattedNumber);
                List<VoiceCallback> byToNumberOnly = voiceCallbackRepository.findByToNumberOnly(formattedNumber);
                logger.info("Records with matching to_number '{}' (any user_id): {}", formattedNumber, byToNumberOnly.size());
                
                if (!byToNumberOnly.isEmpty()) {
                    logger.info("Found records with matching to_number but different user_id:");
                    for (VoiceCallback callback : byToNumberOnly) {
                        logger.info("  - to_number='{}', user_id='{}', call_sid='{}'", 
                                   callback.getToNumber(), callback.getUserId(), callback.getCallSid());
                    }
                }
                
                // Try finding by user_id only using repository method
                logger.info("Testing query with user_id only: '{}'", userId);
                List<VoiceCallback> byUserIdOnly = voiceCallbackRepository.findByUserIdOnly(userId);
                logger.info("Records with matching user_id '{}' (any to_number): {}", userId, byUserIdOnly.size());
                
                if (!byUserIdOnly.isEmpty()) {
                    logger.info("Found records with matching user_id but different to_number:");
                    for (VoiceCallback callback : byUserIdOnly) {
                        logger.info("  - to_number='{}', user_id='{}', call_sid='{}'", 
                                   callback.getToNumber(), callback.getUserId(), callback.getCallSid());
                    }
                }
                
                // Show unique values for comparison
                Set<String> uniqueToNumbers = allRecords.stream()
                    .map(VoiceCallback::getToNumber)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
                logger.info("All unique to_numbers in database: {}", uniqueToNumbers);
                
                Set<String> uniqueUserIds = allRecords.stream()
                    .map(VoiceCallback::getUserId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
                logger.info("All unique user_ids in database: {}", uniqueUserIds);
                
                // Check for exact matches manually
                boolean toNumberExists = uniqueToNumbers.contains(formattedNumber);
                boolean userIdExists = uniqueUserIds.contains(userId);
                logger.info("Manual verification: to_number '{}' exists={}, user_id '{}' exists={}", 
                           formattedNumber, toNumberExists, userId, userIdExists);
            } else {
                logger.info("Successfully found {} voice callback records using enhanced search", callbacks.size());
                // Log details of found records
                if (!callbacks.isEmpty()) {
                    logger.info("Found records breakdown:");
                    for (VoiceCallback callback : callbacks) {
                        String direction = formattedNumber.equals(callback.getToNumber()) ? "OUTGOING" : "INCOMING";
                        logger.info("  - {} call: to_number='{}', from_number='{}', call_sid='{}', status='{}'", 
                                   direction, callback.getToNumber(), callback.getFromNumber(), 
                                   callback.getCallSid(), callback.getStatus());
                    }
                }
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status_data", callbacks);
            result.put("search_info", Map.of(
                "phone_number", phoneNumber,
                "formatted_number", formattedNumber,
                "user_id", userId,
                "records_found", callbacks.size(),
                "search_type", "Enhanced: to_number OR from_number with user_id security",
                "sql_query", "WHERE (to_number = '" + formattedNumber + "' OR from_number = '" + formattedNumber + "') AND user_id = '" + userId + "'"
            ));
            
            logger.info("Enhanced voice call search result: {} records", callbacks.size());
            return result;
            
        } catch (Exception e) {
            logger.error("Database error in getVoiceCallCallbacks: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("status_data", "Not found because " + e.getMessage());
            error.put("error_details", e.getClass().getSimpleName() + ": " + e.getMessage());
            return error;
        }
    }
    
    // HTTP endpoint method (uses session-based auth) - calls the Tool method
    public String getBulkCallDetailsEndpoint(String fromNumber) {
        return getBulkCallDetails(fromNumber);
    }
    
    @Tool(name = "getBulkCallDetails", 
          description = "Fetch bulk voice call details based on passed from number. Requires from number. Authentication is handled automatically from the session.")
    public String getBulkCallDetails(String fromNumber) {
        logger.info("Fetching bulk voice call details...");
        try {
            String authHeader = getCurrentAuthHeader();
            AuthData authData = parseAuthHeader(authHeader);
            String bulkCallUrl = authData.apiDomain() + "/v1/Accounts/" + authData.accountSid() + "/Calls";
            String url = bulkCallUrl + "?From=0" + (fromNumber != null ? fromNumber.replace("+91", "") : "");
            
            String response = makeGetRequest(url, authData);
            return response;
        } catch (Exception e) {
            return "{\"data\":\"Not able to fetch bulk call details due to " + e.getMessage() + "\"}";
        }
    }
    
    // HTTP endpoint method (uses session-based auth) - calls the Tool method
    public String getNumberMetadataEndpoint(String number) {
        return getNumberMetadata(number);
    }
    
    @Tool(name = "getNumberMetadata", 
          description = "Retrieve metadata details for a given phone number with caching for better performance. Requires phone number. Authentication is handled automatically from the session.")
    public String getNumberMetadata(String number) {
        logger.info("Fetching number metadata for: {}", number);
        try {
            String authHeader = getCurrentAuthHeader();
            AuthData authData = parseAuthHeader(authHeader);
            String numberMetaUrl = authData.apiDomain() + "/v1/Accounts/" + authData.accountSid() + "/Numbers/" + number;
            
            // Use caching with 15-minute TTL for metadata
            String cacheKey = "metadata:" + authData.accountSid() + ":" + number;
            return getCachedMetadata(cacheKey, 
                () -> makeGetRequest(numberMetaUrl, authData), 
                15); // 15 minutes TTL
                
        } catch (Exception e) {
            logger.error("Error fetching number metadata for {}: {}", number, e.getMessage());
            return "Not able to fetch number metadata due to " + e.getMessage();
        }
    }
    
    // Helper method to parse authorization header
    private AuthData parseAuthHeader(String authHeader) throws Exception {
        logger.debug("=== AUTH HEADER PARSING DEBUG ===");
        logger.debug("Raw Auth Header: {}", (authHeader));
        
        if (authHeader == null || authHeader.trim().isEmpty()) {
            logger.error("AUTH PARSING ERROR: Authorization header is null or empty");
            throw new IllegalArgumentException("Authorization header is required");
        }
        
        try {
            logger.debug("Parsing auth string from header...");
            Map<String, String> authMap = parseAuthString(authHeader);
            logger.debug("Parsed auth map: {}", authMap.keySet()); // Log keys only for security
            
            String token = authMap.getOrDefault("token", "default_token");
            logger.debug("Extracted token: {}", maskTokenForLogging(token));
            
            // Validate required fields
            if (token == null || token.equals("default_token")) {
                logger.warn("AUTH WARNING: Using default token as auth header parsing returned null/default");
            }
            
            String tokenMd5 = generateMd5(token);
            logger.debug("Generated token MD5: {}", tokenMd5);
            
            String authType = authMap.getOrDefault("auth_type", "Basic");
            
            AuthData authData = new AuthData(
                token,
                tokenMd5,
                authMap.getOrDefault("from_number", "default_from"),
                authMap.getOrDefault("caller_id", "default_caller"),
                authMap.getOrDefault("api_domain", "https://api.exotel.com"),
                authMap.getOrDefault("account_sid", "default_account"),
                authMap.getOrDefault("exotel_portal_url", "https://my.exotel.com"),
                authType
            );
            
            logger.debug("Created AuthData: fromNumber={}, callerId={}, apiDomain={}, accountSid={}", 
                        authData.fromNumber(), authData.callerId(), authData.apiDomain(), authData.accountSid());
            
            return authData;
        } catch (Exception e) {
            logger.error("AUTH PARSING ERROR: Error parsing auth header: {}", e.getMessage(), e);
            logger.debug("Falling back to default auth data due to parsing error");
            // Return default auth data to prevent complete failure
            return createDefaultAuthData();
        }
    }
    
    // Helper method to create default auth data
    private AuthData createDefaultAuthData() throws Exception {
        String defaultToken = "default_token_" + System.currentTimeMillis();
        return new AuthData(
            defaultToken,
            generateMd5(defaultToken),
            "default_from",
            "default_caller",
            "https://api.exotel.com",
            "default_account",
            "https://my.exotel.com"
        );
    }
    
    // Helper method to generate MD5 hash
    private String generateMd5(String input) throws Exception {
        if (input == null) {
            input = "default_input";
        }
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    // Enhanced HTTP POST with retry mechanism and circuit breaker
    private String makeHttpRequest(String url, Map<String, ?> data, AuthData authData) throws Exception {
        return executeWithRetry(() -> performHttpRequest(url, data, authData, "POST"), 3);
    }
    
    // Enhanced HTTP GET with retry mechanism
    private String makeGetRequest(String url, AuthData authData) throws Exception {
        return executeWithRetry(() -> performHttpRequest(url, null, authData, "GET"), 3);
    }
    
    // Backward compatibility methods (deprecated)
    @Deprecated
    private String makeHttpRequest(String url, Map<String, ?> data, String token) throws Exception {
        AuthData authData = new AuthData(token, "", "", "", "", "", "", "Basic");
        return makeHttpRequest(url, data, authData);
    }
    
    @Deprecated  
    private String makeGetRequest(String url, String token) throws Exception {
        AuthData authData = new AuthData(token, "", "", "", "", "", "", "Basic");
        return makeGetRequest(url, authData);
    }
    
    // Generic retry mechanism with exponential backoff
    private String executeWithRetry(HttpOperation operation, int maxAttempts) throws Exception {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String result = operation.execute();
                if (attempt > 1) {
                    logger.info("Request succeeded on attempt {}/{}", attempt, maxAttempts);
                }
                return result;
            } catch (Exception e) {
                lastException = e;
                
                if (attempt == maxAttempts) {
                    logger.error("Request failed after {} attempts. Final error: {}", maxAttempts, e.getMessage());
                    break;
                }
                
                // Check if error is retryable
                if (!isRetryableError(e)) {
                    logger.warn("Non-retryable error encountered: {}", e.getMessage());
                    throw e;
                }
                
                // Calculate backoff delay (exponential with jitter)
                long baseDelay = 1000 * (long) Math.pow(2, attempt - 1); // 1s, 2s, 4s...
                long jitter = (long) (Math.random() * 500); // Add 0-500ms jitter
                long delay = baseDelay + jitter;
                
                logger.warn("Request attempt {}/{} failed: {}. Retrying in {}ms", 
                           attempt, maxAttempts, e.getMessage(), delay);
                
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }
            }
        }
        
        throw new RuntimeException("Request failed after " + maxAttempts + " attempts", lastException);
    }
    
    // Check if error is retryable (network errors, timeouts, 5xx responses)
    private boolean isRetryableError(Exception e) {
        String message = e.getMessage().toLowerCase();
        return message.contains("timeout") || 
               message.contains("connection") || 
               message.contains("socket") ||
               message.contains("5") && message.contains("server error");
    }
    
    // Functional interface for HTTP operations
    @FunctionalInterface
    private interface HttpOperation {
        String execute() throws Exception;
    }
    
    // Unified HTTP request execution with Bearer/Basic auth support
    private String performHttpRequest(String url, Map<String, ?> data, AuthData authData, String method) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("=== HTTP {} REQUEST DEBUG ===", method);
        logger.debug("Request URL: {}", url);
            logger.debug("Request Token (masked): {}", maskTokenForLogging(authData.token()));
            logger.debug("Auth Type: {}", authData.authType());
        }
        
        // Prepare authorization header based on auth type
        String authorizationHeader;
        // if ("Bearer".equals(authData.authType())) {
        //     authorizationHeader = "Bearer " + authData.token();
        //     logger.debug("Using Bearer authentication");
        // } else {
        //     authorizationHeader = "Basic " + authData.token();
        //     logger.debug("Using Basic authentication");
        // }
        authorizationHeader = "Basic " + authData.token();
        
        ClassicHttpRequest request;
        
        if ("POST".equals(method) && data != null) {
        String formData = buildFormData(data);
            if (logger.isDebugEnabled()) {
        logger.debug("Request Body: {}", formData);
            }
        
            request = ClassicRequestBuilder.post(url)
                .setHeader("Authorization", authorizationHeader)
            .setHeader("accept", "application/json")
            .setHeader("Content-Type", "application/x-www-form-urlencoded")
            .setEntity(new StringEntity(formData))
            .build();
        } else {
            request = ClassicRequestBuilder.get(url)
                .setHeader("Authorization", authorizationHeader)
                .setHeader("accept", "application/json")
                .build();
        }
        
        if (logger.isDebugEnabled()) {
            logger.debug("Authorization Header: {}", maskAuthHeader(authorizationHeader));
        }
        
        long startTime = System.currentTimeMillis();
        
        return httpClient.execute(request, response -> {
            long duration = System.currentTimeMillis() - startTime;
            int statusCode = response.getCode();
            String reasonPhrase = response.getReasonPhrase();
            
            if (logger.isDebugEnabled()) {
                logger.debug("=== HTTP {} RESPONSE DEBUG ===", method);
                logger.debug("Response Status: {} {} ({}ms)", statusCode, reasonPhrase, duration);
            }
            
            // Check for HTTP errors
            if (statusCode >= 400) {
                String errorMessage = String.format("HTTP %d %s for %s %s", 
                                                   statusCode, reasonPhrase, method, url);
                if (statusCode >= 500) {
                    throw new RuntimeException("Server error: " + errorMessage);
                } else {
                    throw new IllegalArgumentException("Client error: " + errorMessage);
                }
            }
            
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                String responseBody = new String(entity.getContent().readAllBytes(), StandardCharsets.UTF_8);
                if (logger.isDebugEnabled()) {
                    logger.debug("Response Body: {} ({}ms)", responseBody, duration);
                }
                return responseBody;
            }
            
            if (logger.isDebugEnabled()) {
                logger.debug("Response Body: Empty (null entity) ({}ms)", duration);
            }
            return "{}";
        });
    }
    
    // In-memory caching for metadata and auth tokens (without Redis)
    private String getCachedMetadata(String cacheKey, HttpOperation supplier, long ttlMinutes) {
        try {
            CacheEntry cached = metadataCache.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                logger.debug("Cache HIT for metadata key: {}", cacheKey);
                return cached.getValue();
            }
            
            // Cache miss or expired - fetch fresh data
            logger.debug("Cache MISS for metadata key: {}", cacheKey);
            String freshData = supplier.execute();
            
            // Store in cache with TTL
            metadataCache.put(cacheKey, new CacheEntry(freshData, ttlMinutes * 60 * 1000));
            
            // Clean up expired entries periodically (simple cleanup)
            if (metadataCache.size() > 100) {
                cleanupExpiredCacheEntries(metadataCache);
            }
            
            return freshData;
        } catch (Exception e) {
            logger.warn("Error in cached metadata fetch for key {}: {}", cacheKey, e.getMessage());
            throw new RuntimeException("Failed to fetch metadata", e);
        }
    }
    
    // Simple cache cleanup (removes expired entries)
    private void cleanupExpiredCacheEntries(Map<String, CacheEntry> cache) {
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        logger.debug("Cleaned up expired cache entries. Cache size: {}", cache.size());
    }
    
    // ===== PHONE NUMBER FORMATTING UTILITIES =====
    
    /**
     * Smart phone number formatter for database queries
     * Handles various input formats and ensures consistent 11-digit format with leading 0
     * 
     * Examples:
     * - "9876543210" -> "09876543210" (add leading 0)
     * - "9876543210" -> "09876543210" (already correct)
     * - "+919876543210" -> "09876543210" (remove country code, ensure single leading 0)
     * - "919876543210" -> "09876543210" (remove country code, add leading 0)
     * - "008826795046" -> "09876543210" (remove extra leading 0)
     */
    private String formatPhoneNumberForQuery(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            logger.warn("Phone number is null or empty");
            return "";
        }
        
        String original = phoneNumber;
        logger.debug("Formatting phone number: original='{}'", original);
        
        // Remove all non-digits
        String cleanNumber = phoneNumber.replaceAll("\\D", "");
        logger.debug("After removing non-digits: '{}'", cleanNumber);
        
        // Handle different lengths
        String formatted;
        if (cleanNumber.length() == 10) {
            // Standard 10-digit number, add leading 0
            formatted = "0" + cleanNumber;
            logger.debug("10-digit number, added leading 0: '{}'", formatted);
        } else if (cleanNumber.length() == 11 && cleanNumber.startsWith("0")) {
            // Already has leading 0, use as-is
            formatted = cleanNumber;
            logger.debug("11-digit number with leading 0, using as-is: '{}'", formatted);
        } else if (cleanNumber.length() == 11 && !cleanNumber.startsWith("0")) {
            // 11 digits without leading 0, likely missing 0
            formatted = "0" + cleanNumber.substring(cleanNumber.length() - 10);
            logger.debug("11-digit number without leading 0, extracted last 10 and added 0: '{}'", formatted);
        } else if (cleanNumber.length() == 12 && cleanNumber.startsWith("91")) {
            // Country code 91 prefix, extract 10 digits and add 0
            String last10 = cleanNumber.substring(2);
            formatted = "0" + last10;
            logger.debug("12-digit with country code 91, extracted last 10 and added 0: '{}'", formatted);
        } else if (cleanNumber.length() == 13 && cleanNumber.startsWith("91")) {
            // Country code 91 + 11 digits, extract last 10 and add 0
            String last10 = cleanNumber.substring(cleanNumber.length() - 10);
            formatted = "0" + last10;
            logger.debug("13-digit with country code, extracted last 10 and added 0: '{}'", formatted);
        } else if (cleanNumber.length() > 11) {
            // Too many digits, extract last 10 and add leading 0
            String last10 = cleanNumber.substring(cleanNumber.length() - 10);
            formatted = "0" + last10;
            logger.debug("Number too long ({}), extracted last 10 and added 0: '{}'", cleanNumber.length(), formatted);
        } else {
            // Shorter than expected, pad with 0s if needed
            formatted = "0" + cleanNumber;
            logger.warn("Unusual phone number length ({}), adding leading 0: '{}'", cleanNumber.length(), formatted);
        }
        
        // Validation
        if (formatted.length() != 11 || !formatted.startsWith("0")) {
            logger.error("Phone number formatting failed: original='{}' -> formatted='{}' (expected 11 digits starting with 0)", original, formatted);
        } else {
            logger.debug("Phone number formatting success: '{}' -> '{}'", original, formatted);
        }
        
        return formatted;
    }
    
    /**
     * Format phone number for display/storage (without leading 0)
     * Returns clean 10-digit number
     */
    private String formatPhoneNumberForDisplay(String phoneNumber) {
        String queryFormat = formatPhoneNumberForQuery(phoneNumber);
        if (queryFormat.length() == 11 && queryFormat.startsWith("0")) {
            return queryFormat.substring(1); // Remove leading 0
        }
        return queryFormat;
    }
    
    // Helper method to build form data
    private String buildFormData(Map<String, ?> data) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ?> entry : data.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }
    
    // Enhanced method to parse auth string with Bearer and Basic support
    private Map<String, String> parseAuthString(String authHeader) {
        Map<String, String> result = new HashMap<>();
        
        logger.debug("=== AUTH STRING PARSING DEBUG ===");
        logger.debug("Input auth header: {}", maskAuthHeader(authHeader));
        
        if (authHeader == null || authHeader.trim().isEmpty()) {
            logger.debug("Auth header is null or empty, returning empty map");
            return result;
        }
        
        try {
            String jsonString = authHeader.trim();
            String authType = "Basic"; // Default auth type
            
            // Check for Bearer token format
            if (jsonString.startsWith("Bearer ")) {
                logger.debug("Detected Bearer token format");
                authType = "Bearer";
                jsonString = jsonString.substring(7); // Remove "Bearer " prefix
                logger.debug("After Bearer prefix removal: {}", maskAuthHeader(jsonString));
            }
            
            // Check for Basic token format
            else if (jsonString.startsWith("Basic ")) {
                logger.debug("Detected Basic token format");
                authType = "Basic";
                jsonString = jsonString.substring(6); // Remove "Basic " prefix
                logger.debug("After Basic prefix removal: {}", maskAuthHeader(jsonString));
            }
            
            // Convert single quotes to double quotes for valid JSON
            if (jsonString.contains("'")) {
                logger.debug("Converting single quotes to double quotes for JSON parsing");
                jsonString = jsonString.replaceAll("'", "\"");
                logger.debug("After quote conversion: {}", maskAuthHeader(jsonString));
            }
            
            // Ensure it looks like a JSON object
            if (!jsonString.startsWith("{")) {
                jsonString = "{" + jsonString + "}";
            }
            
            // Parse JSON using ObjectMapper
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(jsonString);
            
            // Extract all fields from JSON
            Iterator<String> fieldNames = jsonNode.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                String value = jsonNode.get(key).asText();
                    result.put(key, value);
                    logger.debug("Parsed key-value: {} = {}", key, "token".equals(key) ? maskTokenForLogging(value) : value);
            }
            
            // Store the detected auth type
            result.put("auth_type", authType);
            logger.debug("Auth type detected: {}", authType);
            
            logger.debug("Successfully parsed {} fields using JSON parser", result.size());
            
            // Log all parsed parameters for verification
            if (!result.isEmpty()) {
                logger.debug("Parsed parameters:");
                result.forEach((key, value) -> {
                    logger.debug("  {} = {}", key, "token".equals(key) ? maskTokenForLogging(value) : value);
                });
            }
            
            // If parsing failed, try basic authorization header
            if (result.isEmpty() && authHeader.startsWith("Basic ")) {
                logger.debug("Detected Basic auth format");
                String token = authHeader.substring(6);
                result.put("token", token);
                // Add some default values for testing
                result.put("from_number", "default_from");
                result.put("caller_id", "default_caller");
                result.put("api_domain", "https://api.exotel.com");
                result.put("account_sid", "default_account");
                result.put("exotel_portal_url", "https://my.exotel.com");
                logger.debug("Added Basic auth token and default values");
            }
            
            // If still empty, use the whole header as token
            if (result.isEmpty()) {
                logger.debug("No specific format detected, using entire header as token");
                result.put("token", authHeader);
                result.put("from_number", "default_from");
                result.put("caller_id", "default_caller");
                result.put("api_domain", "https://api.exotel.com");
                result.put("account_sid", "default_account");
                result.put("exotel_portal_url", "https://my.exotel.com");
                logger.debug("Added fallback token and default values");
            }
            
            logger.debug("Final parsed result contains {} keys: {}", result.size(), result.keySet());
            
        } catch (Exception e) {
            logger.warn("AUTH PARSING ERROR: Failed to parse auth header: {}, using fallback defaults", e.getMessage());
            // Fallback: use the header as token
            result.put("token", authHeader != null ? authHeader : "default_token");
            result.put("from_number", "default_from");
            result.put("caller_id", "default_caller");
            result.put("api_domain", "https://api.exotel.com");
            result.put("account_sid", "default_account");
            result.put("exotel_portal_url", "https://my.exotel.com");
            logger.debug("Exception fallback: added token and defaults");
        }
        
        return result;
    }
    
    // Session-based auth storage for MCP context
    private final Map<String, String> sessionAuthHeaders = new HashMap<>();
    private volatile String lastKnownAuthHeader = null;
    
    // Helper method to get current authorization header from request
    private String getCurrentAuthHeader() {
        String sessionId = getCurrentSessionId();
        logger.debug("=== AUTH HEADER RETRIEVAL DEBUG ===");
        logger.debug("Getting auth header for session: {}", sessionId);
        logger.debug("Current session auth headers stored: {}", sessionAuthHeaders.keySet());
        logger.debug("Last known auth header exists: {}", lastKnownAuthHeader != null ? "YES" : "NO");
        
        try {
            // Try to get from HTTP request context first
            ServletRequestAttributes requestAttributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpServletRequest request = requestAttributes.getRequest();
            String authHeader = request.getHeader("Authorization");
            
            logger.debug("HTTP Request context available - checking Authorization header");
            logger.debug("Authorization header present: {}", authHeader != null ? "YES" : "NO");
            
            if (authHeader != null && !authHeader.trim().isEmpty()) {
                logger.debug("Found Authorization header in HTTP request: {} (Session: {})", 
                           maskAuthHeader(authHeader), sessionId);
                // Store for this session
                sessionAuthHeaders.put(sessionId, authHeader);
                lastKnownAuthHeader = authHeader;
                logger.debug("Stored auth header for session and updated lastKnownAuthHeader");
                return authHeader;
            } else {
                logger.warn("AUTH RETRIEVAL WARNING: No Authorization header found in HTTP request (Session: {})", sessionId);
            }
        } catch (Exception e) {
            logger.debug("No HTTP request context available (likely MCP call), checking session storage (Session: {}). Exception: {}", sessionId, e.getMessage());
        }
        
        // Try to get from session storage
        String storedAuthHeader = sessionAuthHeaders.get(sessionId);
        logger.debug("Checking session storage for sessionId: {} -> Found: {}", sessionId, storedAuthHeader != null ? "YES" : "NO");
        
        if (storedAuthHeader != null) {
            logger.debug("Using stored Authorization header for session: {} -> {}", 
                       sessionId, maskAuthHeader(storedAuthHeader));
            return storedAuthHeader;
        }
        
        // Try alternative MCP session patterns
        if (sessionId.startsWith("MCP-")) {
            // Try global MCP key first
            String globalMcpAuth = sessionAuthHeaders.get("MCP-GLOBAL");
            if (globalMcpAuth != null) {
                logger.info("Using global MCP auth for session: {}", sessionId);
                return globalMcpAuth;
            }
            
            // Try different thread-based patterns
            for (String key : sessionAuthHeaders.keySet()) {
                if (key.startsWith("MCP-")) {
                    logger.info("Found alternative MCP session auth: {} for requested session: {}", 
                               key, sessionId);
                    return sessionAuthHeaders.get(key);
                }
            }
        }
        
        // Try last known auth header
        if (lastKnownAuthHeader != null) {
            logger.info("Using last known Authorization header for session: {} -> {}", 
                       sessionId, maskAuthHeader(lastKnownAuthHeader));
            return lastKnownAuthHeader;
        }
        
        logger.warn("No Authorization header available for session: {}, using default", sessionId);
        return "default_auth_header";
    }
    
    // Helper method to get current session ID
    private String getCurrentSessionId() {
        try {
            ServletRequestAttributes requestAttributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpServletRequest request = requestAttributes.getRequest();
            String sessionId = request.getSession().getId();
            logger.debug("HTTP Session ID: {}", sessionId);
            return sessionId;
        } catch (Exception e) {
            // In MCP context, use thread name as session identifier
            String threadSession = "MCP-" + Thread.currentThread().getId();
            logger.debug("MCP Thread Session: {}", threadSession);
            return threadSession;
        }
    }
    
    // Helper method to mask auth header for logging
    private String maskAuthHeader(String authHeader) {
        if (authHeader == null || authHeader.length() < 10) {
            return "***";
        }
        return authHeader.substring(0, 8) + "..." + authHeader.substring(authHeader.length() - 4);
    }
    
    // Helper method to mask token for logging (more detailed for debugging)
    private String maskTokenForLogging(String token) {
        if (token == null) {
            return "[NULL]";
        }
        if (token.trim().isEmpty()) {
            return "[EMPTY]";
        }
        if (token.length() < 8) {
            return "***";
        }
        return token.substring(0, 6) + "***" + token.substring(token.length() - 4);
    }
    
    // Helper method to get auth header for MCP calls
    private String getAuthHeaderForMCP(String authToken) {
        String sessionId = getCurrentSessionId();
        logger.info("Getting auth header for MCP call (Session: {})", sessionId);
        
        if (authToken != null && !authToken.trim().isEmpty() && !authToken.equals("null")) {
            logger.info("Using provided auth token for MCP call: {} (Session: {})", 
                       maskAuthHeader(authToken), sessionId);
            // Store this token for future use
            sessionAuthHeaders.put(sessionId, authToken);
            lastKnownAuthHeader = authToken;
            return authToken;
        }
        
        // Try fallback to existing logic
        return getCurrentAuthHeader();
    }
    
    // Public method to set auth header for session (for controller use)
    public void setAuthHeaderForSession(String authHeader) {
        String sessionId = getCurrentSessionId();
        logger.debug("=== SETTING AUTH HEADER FOR SESSION DEBUG ===");
        logger.debug("Setting Authorization header for session: {} -> {}", 
                   sessionId, maskAuthHeader(authHeader));
        logger.debug("Auth header length: {}", authHeader != null ? authHeader.length() : 0);
        logger.debug("Session storage before: {}", sessionAuthHeaders.keySet());
        
        sessionAuthHeaders.put(sessionId, authHeader);
        lastKnownAuthHeader = authHeader;
        logger.debug("Updated lastKnownAuthHeader");
        
        // Also store with persistent MCP session key for thread-based sessions
        String mcpSessionKey = "MCP-" + Thread.currentThread().getId();
        sessionAuthHeaders.put(mcpSessionKey, authHeader);
        logger.debug("Also storing auth header for MCP session key: {}", mcpSessionKey);
        logger.debug("Session storage after: {}", sessionAuthHeaders.keySet());
    }
    
    // Public method to set auth header for specific session key
    public void setAuthHeaderForSessionKey(String sessionKey, String authHeader) {
        logger.info("Setting Authorization header for session key: {} -> {}", 
                   sessionKey, maskAuthHeader(authHeader));
        sessionAuthHeaders.put(sessionKey, authHeader);
        lastKnownAuthHeader = authHeader;
    }
    
    // Method to update SMS callback from webhook (new signature)
    public void saveSmsCallback(Map<String, String> callbackData, String userId) {
        String smsSid = callbackData.get("SmsSid");
        
        logger.info("Processing SMS callback for SmsSid: {}", smsSid);
        logger.info("SMS callback data: {}", callbackData);
        
        if (smsSid != null && !smsSid.isEmpty()) {
            // Try to find existing callback by SmsSid
            Optional<SmsCallback> existingCallback = smsCallbackRepository.findBySmsSid(smsSid);
            
            if (existingCallback.isPresent()) {
                // Update existing callback
                SmsCallback callback = existingCallback.get();
                logger.info("Existing SMS callback found: true");
                
                // Update fields from callback data
                callback.setStatus(cleanValue(callbackData.get("Status")));
                callback.setDetailedStatus(cleanValue(callbackData.get("DetailedStatus")));
                callback.setDetailedStatusCode(cleanValue(callbackData.get("DetailedStatusCode")));
                callback.setSmsUnits(cleanValue(callbackData.get("SmsUnits")));
                callback.setDateSent(cleanValue(callbackData.get("DateSent")));
                
                smsCallbackRepository.save(callback);
                logger.info("Updated existing SMS callback with SmsSid: {}", smsSid);
            } else {
                // No existing record found - create initial record from callback data
                logger.warn("No existing SMS callback found for SmsSid: {}, creating initial record from callback", smsSid);
                createInitialSmsRecordFromCallback(callbackData, userId);
            }
        } else {
            logger.error("SmsSid is null or empty in callback data");
        }
    }
    
    // Method to create initial SMS record from callback data (when no initial record exists)
    private void createInitialSmsRecordFromCallback(Map<String, String> callbackData, String userId) {
        String smsSid = callbackData.get("SmsSid");
        logger.info("Creating initial SMS record from callback data for SmsSid: {}", smsSid);
        
        String toNumber = callbackData.get("To");
        String formattedToNumber = formatPhoneNumberForQuery(toNumber);
        logger.debug("SMS callback: original to_number='{}' -> formatted='{}'", toNumber, formattedToNumber);
        
        SmsCallback callback = new SmsCallback();
        callback.setUserId(userId);
        callback.setSmsSid(cleanValue(callbackData.get("SmsSid")));
        callback.setToNumber(formattedToNumber);
        callback.setStatus(cleanValue(callbackData.get("Status")));
        callback.setDetailedStatus(cleanValue(callbackData.get("DetailedStatus")));
        callback.setDetailedStatusCode(cleanValue(callbackData.get("DetailedStatusCode")));
        callback.setSmsUnits(cleanValue(callbackData.get("SmsUnits")));
        callback.setDateSent(cleanValue(callbackData.get("DateSent")));
        
        smsCallbackRepository.save(callback);
        logger.info("Created initial SMS record from callback for SmsSid: {} with to_number: {}", smsSid, formattedToNumber);
    }
    
    // Method to save initial SMS callback from SMS response
    public void saveInitialSmsCallback(String smsResponse, String userId) {
        try {
            JsonNode jsonNode = objectMapper.readTree(smsResponse);
            JsonNode smsNode = jsonNode.get("SMSMessage");
            
            if (smsNode != null) {
                String toNumber = smsNode.has("To") ? smsNode.get("To").asText() : "";
                String formattedToNumber = formatPhoneNumberForQuery(toNumber);
                logger.debug("Initial SMS callback: original to_number='{}' -> formatted='{}'", toNumber, formattedToNumber);
                
                SmsCallback callback = new SmsCallback();
                callback.setUserId(userId);
                callback.setSmsSid(smsNode.has("Sid") ? smsNode.get("Sid").asText() : "");
                callback.setToNumber(formattedToNumber);
                callback.setStatus(smsNode.has("Status") ? smsNode.get("Status").asText() : "");
                callback.setDetailedStatus(smsNode.has("DetailedStatus") ? smsNode.get("DetailedStatus").asText() : "");
                callback.setDetailedStatusCode(smsNode.has("DetailedStatusCode") ? smsNode.get("DetailedStatusCode").asText() : "");
                callback.setSmsUnits(smsNode.has("SmsUnits") ? smsNode.get("SmsUnits").asText() : "");
                callback.setDateSent(smsNode.has("DateCreated") ? smsNode.get("DateCreated").asText() : "");
                
                smsCallbackRepository.save(callback);
                logger.info("Saved initial SMS callback with SmsSid: {} and to_number: {}", callback.getSmsSid(), formattedToNumber);
            }
        } catch (Exception e) {
            logger.error("Error saving initial SMS callback", e);
        }
    }
    
    // Method to save initial bulk SMS callbacks from bulk SMS response array
    public void saveInitialBulkSmsCallback(String bulkSmsResponse, String userId) {
        try {
            JsonNode jsonNode = objectMapper.readTree(bulkSmsResponse);
            
            // Check if response is an array
            if (jsonNode.isArray()) {
                logger.info("Processing bulk SMS response with {} messages", jsonNode.size());
                
                for (JsonNode messageNode : jsonNode) {
                    JsonNode smsNode = messageNode.get("SMSMessage");
                    
                    if (smsNode != null) {
                        String toNumber = smsNode.has("To") ? smsNode.get("To").asText() : "";
                        String formattedToNumber = formatPhoneNumberForQuery(toNumber);
                        logger.debug("Bulk SMS callback: original to_number='{}' -> formatted='{}'", toNumber, formattedToNumber);
                        
                        SmsCallback callback = new SmsCallback();
                        callback.setUserId(userId);
                        callback.setSmsSid(smsNode.has("Sid") ? smsNode.get("Sid").asText() : "");
                        callback.setToNumber(formattedToNumber);
                        callback.setStatus(smsNode.has("Status") ? smsNode.get("Status").asText() : "");
                        callback.setDetailedStatus(smsNode.has("DetailedStatus") ? smsNode.get("DetailedStatus").asText() : "");
                        callback.setDetailedStatusCode(smsNode.has("DetailedStatusCode") ? smsNode.get("DetailedStatusCode").asText() : "");
                        callback.setSmsUnits(smsNode.has("SmsUnits") ? smsNode.get("SmsUnits").asText() : "");
                        callback.setDateSent(smsNode.has("DateCreated") ? smsNode.get("DateCreated").asText() : "");
                        
                        smsCallbackRepository.save(callback);
                        logger.info("Saved bulk SMS callback with SmsSid: {} for number: {}", 
                                   callback.getSmsSid(), formattedToNumber);
                    }
                }
            } else {
                logger.warn("Bulk SMS response is not an array, falling back to single SMS parsing");
                // Fallback to single SMS parsing if it's not an array
                saveInitialSmsCallback(bulkSmsResponse, userId);
            }
        } catch (Exception e) {
            logger.error("Error saving bulk SMS callbacks", e);
        }
    }
    
    // Legacy method to save SMS callback (keep for backward compatibility)
    public void saveSmsCallback(String smsSid, String toNumber, String status, String detailedStatus,
                               String detailedStatusCode, String smsUnits, String dateSent, String userId) {
        String formattedToNumber = formatPhoneNumberForQuery(toNumber);
        logger.debug("Legacy SMS callback: original to_number='{}' -> formatted='{}'", toNumber, formattedToNumber);
        
        SmsCallback callback = new SmsCallback(userId, smsSid, formattedToNumber, status, 
                                             detailedStatus, detailedStatusCode, smsUnits, dateSent);
        smsCallbackRepository.save(callback);
        logger.info("Saved legacy SMS callback with SmsSid: {} and to_number: {}", smsSid, formattedToNumber);
    }
    
    // Method to save initial voice callback from call response
    public void saveInitialVoiceCallback(String callResponse, String userId) {
        try {
            JsonNode jsonNode = objectMapper.readTree(callResponse);
            JsonNode callNode = jsonNode.get("Call");
            
            if (callNode != null) {
                String toNumber = callNode.has("To") ? callNode.get("To").asText() : "";
                String formattedToNumber = formatPhoneNumberForQuery(toNumber);
                String fromNumber = callNode.has("From") ? callNode.get("From").asText() : "";
                String formattedFromNumber = formatPhoneNumberForQuery(fromNumber);
                logger.debug("Initial voice callback: to_number '{}'->'{}', from_number '{}'->'{}' ", 
                           toNumber, formattedToNumber, fromNumber, formattedFromNumber);
                
                VoiceCallback callback = new VoiceCallback(
                    userId,
                    callNode.has("Sid") ? callNode.get("Sid").asText() : "",
                    callNode.has("ParentCallSid") ? callNode.get("ParentCallSid").asText() : "",
                    callNode.has("DateCreated") ? callNode.get("DateCreated").asText() : "",
                    callNode.has("DateUpdated") ? callNode.get("DateUpdated").asText() : "",
                    callNode.has("AccountSid") ? callNode.get("AccountSid").asText() : "",
                    formattedToNumber,
                    formattedFromNumber,
                    callNode.has("PhoneNumberSid") ? callNode.get("PhoneNumberSid").asText() : "",
                    callNode.has("StartTime") ? callNode.get("StartTime").asText() : "",
                    callNode.has("EndTime") ? callNode.get("EndTime").asText() : "",
                    callNode.has("Duration") ? callNode.get("Duration").asText() : "",
                    callNode.has("Price") ? callNode.get("Price").asText() : "",
                    callNode.has("Direction") ? callNode.get("Direction").asText() : "",
                    callNode.has("AnsweredBy") ? callNode.get("AnsweredBy").asText() : "",
                    callNode.has("ForwardedFrom") ? callNode.get("ForwardedFrom").asText() : "",
                    callNode.has("CallerName") ? callNode.get("CallerName").asText() : "",
                    callNode.has("Uri") ? callNode.get("Uri").asText() : "",
                    callNode.has("RecordingUrl") ? callNode.get("RecordingUrl").asText() : "",
                    callNode.has("Sid") ? callNode.get("Sid").asText() : "", // Using Sid as CallSid
                    callNode.has("Status") ? callNode.get("Status").asText() : ""
                );
                
                voiceCallbackRepository.save(callback);
                logger.info("Saved initial voice callback with CallSid: {}, to_number: {}, from_number: {}", 
                           callback.getCallSid(), formattedToNumber, formattedFromNumber);
            }
        } catch (Exception e) {
            logger.error("Error parsing and saving initial voice callback: {}", e.getMessage());
        }
    }
    
    // Method to update voice callback from webhook
    public void saveVoiceCallback(Map<String, String> callbackData, String userId) {
        String callSid = callbackData.get("CallSid");
        
        logger.info("Processing callback for CallSid: {}", callSid);
        logger.info("Callback data: {}", callbackData);
        
        if (callSid != null && !callSid.isEmpty()) {
            // Try to find existing callback by CallSid
            Optional<VoiceCallback> existingCallback = voiceCallbackRepository.findByCallSid(callSid);
            
            logger.info("Existing callback found: {}", existingCallback.isPresent());
            
            if (existingCallback.isPresent()) {
                // Update existing callback
                VoiceCallback callback = existingCallback.get();
                
                // Update fields that might have changed
                if (callbackData.containsKey("Status")) {
                    callback.setStatus(cleanValue(callbackData.get("Status")));
                }
                if (callbackData.containsKey("RecordingUrl")) {
                    callback.setRecordingUrl(cleanValue(callbackData.get("RecordingUrl")));
                }
                if (callbackData.containsKey("DateUpdated")) {
                    callback.setDateUpdated(cleanValue(callbackData.get("DateUpdated")));
                }
                if (callbackData.containsKey("EndTime")) {
                    callback.setEndTime(cleanValue(callbackData.get("EndTime")));
                }
                if (callbackData.containsKey("Duration")) {
                    callback.setDuration(cleanValue(callbackData.get("Duration")));
                }
                if (callbackData.containsKey("Price")) {
                    callback.setPrice(cleanValue(callbackData.get("Price")));
                }
                if (callbackData.containsKey("AnsweredBy")) {
                    callback.setAnsweredBy(cleanValue(callbackData.get("AnsweredBy")));
                }
                
                voiceCallbackRepository.save(callback);
                logger.info("Updated existing voice callback with CallSid: {}", callSid);
            } else {
                // No existing record found - create initial record from callback data
                logger.warn("No existing callback found for CallSid: {}, creating initial record from callback", callSid);
                createInitialRecordFromCallback(callbackData, userId);
            }
        } else {
            // Create new callback if no CallSid provided (fallback)
            logger.warn("No CallSid provided in callback data, creating new record");
            createNewVoiceCallback(callbackData, userId);
        }
    }
    
    // Method to create initial call record when call is first initiated
    public void createInitialCallRecord(String callSid, String fromNumber, String toNumber, String userId) {
        logger.info("Creating initial call record for CallSid: {}", callSid);
        
        // Check if record already exists
        Optional<VoiceCallback> existingCallback = voiceCallbackRepository.findByCallSid(callSid);
        if (existingCallback.isPresent()) {
            logger.info("Initial record already exists for CallSid: {}", callSid);
            return;
        }
        
        // Create initial record with basic information
        VoiceCallback initialCallback = new VoiceCallback();
        initialCallback.setCallSid(callSid);
        initialCallback.setFromNumber(fromNumber);
        initialCallback.setToNumber(toNumber != null ? toNumber.replaceAll("\\D", "") : "");
        initialCallback.setUserId(userId);
        initialCallback.setStatus("initiated"); // Initial status
        initialCallback.setDateCreated(java.time.Instant.now().toString());
        initialCallback.setDateUpdated(java.time.Instant.now().toString());
        
        voiceCallbackRepository.save(initialCallback);
        logger.info("Created initial call record for CallSid: {}", callSid);
    }
    
    // Method to create initial record from callback data (when no initial record exists)
    private void createInitialRecordFromCallback(Map<String, String> callbackData, String userId) {
        String callSid = callbackData.get("CallSid");
        logger.info("Creating initial record from callback data for CallSid: {}", callSid);
        
        String toNumber = callbackData.get("To");
        String cleanToNumber = (toNumber != null) ? toNumber.replaceAll("\\D", "") : "";
        
        VoiceCallback callback = new VoiceCallback();
        callback.setUserId(userId);
        callback.setSid(cleanValue(callbackData.get("Sid")));
        callback.setParentCallSid(cleanValue(callbackData.get("ParentCallSid")));
        callback.setDateCreated(cleanValue(callbackData.get("DateCreated")));
        callback.setDateUpdated(cleanValue(callbackData.get("DateUpdated")));
        callback.setAccountSid(cleanValue(callbackData.get("AccountSid")));
        callback.setToNumber(cleanToNumber);
        callback.setFromNumber(cleanValue(callbackData.get("From")));
        callback.setPhoneNumberSid(cleanValue(callbackData.get("PhoneNumberSid")));
        callback.setStartTime(cleanValue(callbackData.get("StartTime")));
        callback.setEndTime(cleanValue(callbackData.get("EndTime")));
        callback.setDuration(cleanValue(callbackData.get("Duration")));
        callback.setPrice(cleanValue(callbackData.get("Price")));
        callback.setDirection(cleanValue(callbackData.get("Direction")));
        callback.setAnsweredBy(cleanValue(callbackData.get("AnsweredBy")));
        callback.setForwardedFrom(cleanValue(callbackData.get("ForwardedFrom")));
        callback.setCallerName(cleanValue(callbackData.get("CallerName")));
        callback.setUri(cleanValue(callbackData.get("Uri")));
        callback.setRecordingUrl(cleanValue(callbackData.get("RecordingUrl")));
        callback.setCallSid(callSid); // Use the original CallSid
        callback.setStatus(cleanValue(callbackData.get("Status")));
        
        voiceCallbackRepository.save(callback);
        logger.info("Created initial record from callback for CallSid: {}", callSid);
    }
    
    // Helper method to create new voice callback
    private void createNewVoiceCallback(Map<String, String> callbackData, String userId) {
        String toNumber = callbackData.get("To");
        String cleanToNumber = (toNumber != null) ? toNumber.replaceAll("\\D", "") : "";
        
        VoiceCallback callback = new VoiceCallback(
            userId,
            cleanValue(callbackData.get("Sid")),
            cleanValue(callbackData.get("ParentCallSid")),
            cleanValue(callbackData.get("DateCreated")),
            cleanValue(callbackData.get("DateUpdated")),
            cleanValue(callbackData.get("AccountSid")),
            cleanToNumber,
            cleanValue(callbackData.get("From")),
            cleanValue(callbackData.get("PhoneNumberSid")),
            cleanValue(callbackData.get("StartTime")),
            cleanValue(callbackData.get("EndTime")),
            cleanValue(callbackData.get("Duration")),
            cleanValue(callbackData.get("Price")),
            cleanValue(callbackData.get("Direction")),
            cleanValue(callbackData.get("AnsweredBy")),
            cleanValue(callbackData.get("ForwardedFrom")),
            cleanValue(callbackData.get("CallerName")),
            cleanValue(callbackData.get("Uri")),
            cleanValue(callbackData.get("RecordingUrl")),
            cleanValue(callbackData.get("CallSid")),
            cleanValue(callbackData.get("Status"))
        );
        
        voiceCallbackRepository.save(callback);
    }
    
    // Helper method to clean values (remove array brackets)
    private String cleanValue(String value) {
        if (value == null) return "";
        return value.replaceAll("^\\['|'\\]$", "");
    }
    
    // @Tool(name = "checkCallRecord", description = "Check if a call record exists by CallSid and optionally create initial record")
    // public String checkCallRecord(String callSid, String userId, boolean createIfMissing) {
    //     logger.info("Checking call record for CallSid: {}", callSid);
        
    //     Optional<VoiceCallback> existingCallback = voiceCallbackRepository.findByCallSid(callSid);
        
    //     if (existingCallback.isPresent()) {
    //         VoiceCallback callback = existingCallback.get();
    //         return String.format("Record found for CallSid: %s, Status: %s, Created: %s, Updated: %s", 
    //             callSid, callback.getStatus(), callback.getDateCreated(), callback.getDateUpdated());
    //     } else {
    //         if (createIfMissing) {
    //             createInitialCallRecord(callSid, "", "", userId);
    //             return String.format("No record found for CallSid: %s. Created initial record.", callSid);
    //         } else {
    //             return String.format("No record found for CallSid: %s", callSid);
    //         }
    //     }
    // }
    
    // @Tool(name = "getRecentVoiceCallbacks", description = "Get recent voice callbacks for debugging (limited to 10 records)")
    // public String getRecentVoiceCallbacks() {
    //     List<VoiceCallback> allCallbacks = voiceCallbackRepository.findAll();
    //     StringBuilder result = new StringBuilder();
    //     result.append("Total records: ").append(allCallbacks.size()).append("\n");
    //     result.append("Showing last 10 records:\n\n");
        
    //     // Limit to last 10 records to prevent buffer overflow
    //     int startIndex = Math.max(0, allCallbacks.size() - 10);
    //     List<VoiceCallback> recentCallbacks = allCallbacks.subList(startIndex, allCallbacks.size());
        
    //     for (VoiceCallback callback : recentCallbacks) {
    //         result.append(String.format("CallSid: %s, Status: %s, From: %s, To: %s, Updated: %s\n",
    //             callback.getCallSid(), callback.getStatus(), callback.getFromNumber(), 
    //             callback.getToNumber(), callback.getDateUpdated()));
    //     }
        
    //     return result.toString();
    // }
    
    // @Tool(name = "getVoiceCallbackStats", description = "Count voice callbacks by status")
    // public String getVoiceCallbackStats() {
    //     List<VoiceCallback> allCallbacks = voiceCallbackRepository.findAll();
    //     Map<String, Integer> statusCounts = new HashMap<>();
        
    //     for (VoiceCallback callback : allCallbacks) {
    //         String status = callback.getStatus() != null ? callback.getStatus() : "unknown";
    //         statusCounts.put(status, statusCounts.getOrDefault(status, 0) + 1);
    //     }
        
    //     StringBuilder result = new StringBuilder();
    //     result.append("Voice Callback Statistics:\n");
    //     result.append("Total records: ").append(allCallbacks.size()).append("\n\n");
    //     result.append("Status breakdown:\n");
        
    //     for (Map.Entry<String, Integer> entry : statusCounts.entrySet()) {
    //         result.append(String.format("- %s: %d\n", entry.getKey(), entry.getValue()));
    //     }
        
    //     return result.toString();
    // }
    
    @Tool(name = "searchVoiceCallbacksByNumber", description = "Search voice callbacks by phone number in BOTH to_number OR from_number with user_id security (limited results)")
    public String searchVoiceCallbacksByNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return "Error: Phone number is required";
        }
        
        try {
            String authHeader = getCurrentAuthHeader();
            AuthData authData = parseAuthHeader(authHeader);
            String formattedNumber = formatPhoneNumberForQuery(phoneNumber);
            String userId = authData.tokenMd5();
            
            logger.info("Enhanced voice search: original='{}' -> formatted='{}', user_id='{}'", phoneNumber, formattedNumber, userId);
            
            // Use enhanced search that looks in BOTH to_number OR from_number with user_id security
            List<VoiceCallback> callbacks = voiceCallbackRepository.findByPhoneNumberInToOrFromAndUserId(formattedNumber, userId);
        
            StringBuilder result = new StringBuilder();
            result.append("Enhanced Search Results for Phone Number: ").append(phoneNumber).append("\n");
            result.append("Formatted Number: ").append(formattedNumber).append("\n");
            result.append("Search Type: to_number OR from_number with user_id security\n");
            result.append("Found ").append(callbacks.size()).append(" records\n\n");
            
            if (callbacks.isEmpty()) {
                result.append("No voice callbacks found for this phone number.\n");
                result.append("This could mean:\n");
                result.append("- No calls made to/from this number with your auth token\n");
                result.append("- Phone number format mismatch\n");
                result.append("- Use debugVoiceCallbackQuery('").append(phoneNumber).append("') for detailed analysis\n");
                return result.toString();
            }
            
            // Limit to 5 records to prevent buffer overflow
            int limit = Math.min(5, callbacks.size());
            for (int i = 0; i < limit; i++) {
                VoiceCallback callback = callbacks.get(i);
                String direction = formattedNumber.equals(callback.getToNumber()) ? "OUTGOING" : "INCOMING";
                
                result.append(String.format("=== Record %d (%s Call) ===\n", i+1, direction));
                result.append(String.format("CallSid: %s\n", callback.getCallSid()));
                result.append(String.format("Status: %s\n", callback.getStatus()));
                result.append(String.format("From: %s\n", callback.getFromNumber()));
                result.append(String.format("To: %s\n", callback.getToNumber()));
                result.append(String.format("Date: %s\n", callback.getDateUpdated()));
                result.append(String.format("Recording: %s\n", 
                    callback.getRecordingUrl() != null ? "Available" : "Not available"));
                result.append("\n");
            }
            
            if (callbacks.size() > 5) {
                result.append("... and ").append(callbacks.size() - 5).append(" more records\n");
                result.append("Use getVoiceCallCallbacks('").append(phoneNumber).append("') for complete list\n");
            }
            
            return result.toString();
            
        } catch (Exception e) {
            return "Search Error: " + e.getMessage();
        }
    }
    
    @Tool(name = "getCallDetails", description = "Get detailed information for a specific CallSid")
    public String getCallDetails(String callSid) {
        if (callSid == null || callSid.trim().isEmpty()) {
            return "Error: CallSid is required";
        }
        
        Optional<VoiceCallback> callbackOpt = voiceCallbackRepository.findByCallSid(callSid);
        if (!callbackOpt.isPresent()) {
            return "No record found for CallSid: " + callSid;
        }
        
        VoiceCallback callback = callbackOpt.get();
        StringBuilder result = new StringBuilder();
        result.append("Call Details for CallSid: ").append(callSid).append("\n");
        result.append("=====================================\n");
        result.append("Status: ").append(callback.getStatus()).append("\n");
        result.append("From Number: ").append(callback.getFromNumber()).append("\n");
        result.append("To Number: ").append(callback.getToNumber()).append("\n");
        result.append("Start Time: ").append(callback.getStartTime()).append("\n");
        result.append("End Time: ").append(callback.getEndTime()).append("\n");
        result.append("Duration: ").append(callback.getDuration()).append("\n");
        result.append("Direction: ").append(callback.getDirection()).append("\n");
        result.append("Answered By: ").append(callback.getAnsweredBy()).append("\n");
        result.append("Date Created: ").append(callback.getDateCreated()).append("\n");
        result.append("Date Updated: ").append(callback.getDateUpdated()).append("\n");
        result.append("Recording URL: ").append(callback.getRecordingUrl() != null ? callback.getRecordingUrl() : "Not available").append("\n");
        result.append("Price: ").append(callback.getPrice()).append("\n");
        
        return result.toString();
    }
    
    // Public method to get voice callbacks by from number with user_id security (for controller)
    public Map<String, Object> getCallFlowCallbacks(String fromNumber) {
        logger.info("Fetching call flow callbacks for from_number: {}", fromNumber);
        try {
            String authHeader = getCurrentAuthHeader();
            AuthData authData = parseAuthHeader(authHeader);
            String formattedNumber = formatPhoneNumberForQuery(fromNumber);
            String userId = authData.tokenMd5();
            
            logger.info("Call flow search: formatted from_number='{}', user_id='{}'", formattedNumber, userId);
            
            // Use enhanced search with user_id security for from_number
            var callbacks = voiceCallbackRepository.findByFromNumberAndUserId(formattedNumber, userId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status_data", callbacks);
            result.put("search_info", Map.of(
                "from_number", fromNumber,
                "formatted_number", formattedNumber,
                "user_id", userId,
                "records_found", callbacks.size(),
                "search_type", "Call flow: from_number with user_id security"
            ));
            
            logger.info("Call flow data: {} records found", callbacks.size());
            return result;
        } catch (Exception e) {
            logger.error("Database error in getCallFlowCallbacks: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("status_data", "Not found because " + e.getMessage());
            error.put("error_details", e.getClass().getSimpleName() + ": " + e.getMessage());
            return error;
        }
    }
}