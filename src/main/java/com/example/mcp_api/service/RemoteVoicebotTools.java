package com.example.mcp_api.service;

import com.example.mcp_api.auth.AuthContext;
import com.example.mcp_api.auth.AuthCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Voicebot tools that are not built into this server: they are listed and run by the MCP endpoints of the
 * services that own the APIs, called with the caller's own VoiceBot key (CV-3871 spike). Three sources:
 * <ul>
 *   <li>voicebot: {@code {voicebot base}/api/v1/accounts/{account}/mcp}</li>
 *   <li>te (Tools Extension stamp): {@code {te base}/api/v1/accounts/{account}/mcp}</li>
 *   <li>tools-server: {@code {tools server base}/tools/api/v1/tenants/{tenant}/mcp}</li>
 * </ul>
 *
 * This server decides which of their tools it offers: by default only tools the endpoint marks read-only,
 * plus the names in that source's {@code allow.tools}, minus its {@code deny.tools}. A built-in tool with
 * the same name always wins. A source that fails is skipped; the others and the built-in tools still work.
 */
@Service
public class RemoteVoicebotTools {

    private static final Logger logger = LoggerFactory.getLogger(RemoteVoicebotTools.class);
    private static final Pattern ACCOUNT_ID = Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-zA-Z0-9_]{1,64}$");
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {};
    /** How long a failed tools/list is remembered, so a broken upstream does not slow every listing. */
    private static final long FAILURE_CACHE_MILLIS = 15_000;

    @Value("${exotel.remote.voicebot.enabled:false}")
    boolean enabled;

    @Value("${exotel.remote.voicebot.prefix:exotel_voicebot_}")
    String prefix;

    @Value("${exotel.remote.voicebot.allow.read-only:true}")
    boolean readOnly;

    @Value("${exotel.remote.voicebot.allow.tools:}")
    String allowTools;

    @Value("${exotel.remote.voicebot.deny.tools:}")
    String denyTools;

    @Value("${exotel.remote.voicebot.refresh-seconds:60}")
    long refreshSeconds;

    @Value("${exotel.remote.te.enabled:false}")
    boolean teEnabled;

    @Value("${exotel.remote.te.prefix:exotel_voicebot_te_}")
    String tePrefix;

    @Value("${exotel.remote.te.allow.tools:}")
    String teAllowTools;

    @Value("${exotel.remote.te.deny.tools:}")
    String teDenyTools;

    @Value("${exotel.voicebot.te.base.url:https://voicebot-te.in.exotel.com/voicebot}")
    String teDefaultBaseUrl;

    @Value("${exotel.remote.tools-server.enabled:false}")
    boolean toolsServerEnabled;

    @Value("${exotel.remote.tools-server.prefix:exotel_tools_server_}")
    String toolsServerPrefix;

    @Value("${exotel.remote.tools-server.allow.tools:}")
    String toolsServerAllowTools;

    @Value("${exotel.remote.tools-server.deny.tools:}")
    String toolsServerDenyTools;

    @Value("${exotel.tools.server.base.url:https://tools-server-prod.mum1.exotel.com}")
    String toolsServerDefaultBaseUrl;

    @Value("${exotel.voicebot.base.url:https://voicebot.in.exotel.com/voicebot/api/v2}")
    String defaultBaseUrl;

    @Value("${exotel.voicebot.ssl.verify:true}")
    boolean sslVerify;

    private RestTemplate restTemplate;
    private final Map<String, CachedList> cache = new ConcurrentHashMap<>();

    private record CachedList(List<Map<String, Object>> tools, long expiresAtMillis) {
    }

    /** One service that serves tools: where its endpoint is for this caller and what this server offers of it. */
    private record Source(String key, String prefix, String allowTools, String denyTools,
                          java.util.function.Supplier<String> endpoint, java.util.function.Supplier<String> basicToken) {
    }

    /** The sources that are switched on, in listing order. */
    private List<Source> sources() {
        List<Source> sources = new ArrayList<>();
        AuthCredentials creds = AuthContext.current();
        if (enabled && creds.hasVoicebotCredentials()) {
            sources.add(new Source("voicebot", prefix, allowTools, denyTools,
                    () -> accountEndpoint(creds.effectiveVoicebotBaseUrl(defaultBaseUrl)
                            .replaceFirst("/api/v\\d+/?$", ""), creds.getVoicebotAccountId()),
                    creds::voicebotBasicToken));
        }
        if (teEnabled && creds.hasVoicebotCredentials() && teDefaultBaseUrl != null && !teDefaultBaseUrl.isBlank()) {
            sources.add(new Source("te", tePrefix, teAllowTools, teDenyTools,
                    () -> accountEndpoint(creds.effectiveTeBaseUrl(teDefaultBaseUrl).replaceAll("/+$", ""),
                            creds.getVoicebotAccountId()),
                    creds::voicebotBasicToken));
        }
        if (toolsServerEnabled && (creds.hasVoicebotCredentials() || creds.hasToolsServerCredentials())) {
            sources.add(new Source("tools-server", toolsServerPrefix, toolsServerAllowTools, toolsServerDenyTools,
                    () -> {
                        String tenant = creds.getToolsServerTenantId() != null
                                ? creds.getToolsServerTenantId() : creds.getVoicebotAccountId();
                        return creds.effectiveToolsServerBaseUrl(toolsServerDefaultBaseUrl).replaceAll("/+$", "")
                                + "/tools/api/v1/tenants/" + safeId(tenant) + "/mcp";
                    },
                    () -> {
                        // Same rule as the built-in Tools Server tools: tools_server_* fields, else the VoiceBot key.
                        String key = creds.getToolsServerApiKey() != null ? creds.getToolsServerApiKey() : creds.getVoicebotApiKey();
                        String token = creds.getToolsServerApiToken() != null ? creds.getToolsServerApiToken() : creds.getVoicebotApiToken();
                        return java.util.Base64.getEncoder().encodeToString(
                                (key + ":" + (token == null ? "" : token)).getBytes(StandardCharsets.UTF_8));
                    }));
        }
        return sources;
    }

    private static String accountEndpoint(String root, String account) {
        return root + "/api/v1/accounts/" + safeId(account) + "/mcp";
    }

    private static String safeId(String id) {
        if (id == null || !ACCOUNT_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("account id is not valid");
        }
        return URLEncoder.encode(id, StandardCharsets.UTF_8);
    }

    @jakarta.annotation.PostConstruct
    void init() {
        this.restTemplate = VoiceBotBuilderTools.createRestTemplate(sslVerify);
    }

    /** The remote tools this caller may use, named with their source's prefix. A failing source adds nothing. */
    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Source source : sources()) {
            all.addAll(listTools(source));
        }
        return all;
    }

    private List<Map<String, Object>> listTools(Source source) {
        String endpoint;
        try {
            endpoint = source.endpoint().get();
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        CachedList cached = cache.get(endpoint);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.tools();
        }
        try {
            Map<String, Object> result = rpc(endpoint, source.basicToken().get(), "tools/list", Map.of());
            List<Map<String, Object>> offered = filterAndPrefix(source, result.get("tools"));
            cache.put(endpoint, new CachedList(offered, now + refreshSeconds * 1000));
            return offered;
        } catch (Exception e) {
            logger.warn("Remote {} tools/list failed: {}", source.key(), describe(e));
            cache.put(endpoint, new CachedList(List.of(), now + FAILURE_CACHE_MILLIS));
            return List.of();
        }
    }

    /**
     * Runs a remote tool. Returns null when the name is not a remote tool offered to this caller, so the
     * caller of this method can answer "unknown tool"; otherwise the MCP result object ({@code content},
     * {@code isError}).
     */
    public Map<String, Object> callTool(String prefixedName, Map<String, Object> arguments) {
        if (prefixedName == null) {
            return null;
        }
        for (Source source : sources()) {
            if (!prefixedName.startsWith(source.prefix())
                    || listTools(source).stream().noneMatch(tool -> prefixedName.equals(tool.get("name")))) {
                continue;
            }
            String remoteName = prefixedName.substring(source.prefix().length());
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("name", remoteName);
                params.put("arguments", arguments == null ? Map.of() : arguments);
                return rpc(source.endpoint().get(), source.basicToken().get(), "tools/call", params);
            } catch (Exception e) {
                logger.warn("Remote {} tool {} failed: {}", source.key(), remoteName, describe(e));
                return Map.of(
                        "content", List.of(Map.of("type", "text", "text", prefixedName + " failed: " + describe(e))),
                        "isError", true);
            }
        }
        return null;
    }

    private List<Map<String, Object>> filterAndPrefix(Source source, Object remoteTools) {
        Set<String> allowed = names(source.allowTools());
        Set<String> denied = names(source.denyTools());
        List<Map<String, Object>> offered = new ArrayList<>();
        if (!(remoteTools instanceof List<?> list)) {
            return offered;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> remote) || !(remote.get("name") instanceof String name)
                    || !TOOL_NAME.matcher(name).matches()) {
                continue;
            }
            if (denied.contains(name)) {
                continue;
            }
            if (readOnly && !isReadOnly(remote) && !allowed.contains(name)) {
                continue;
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            remote.forEach((key, value) -> tool.put(String.valueOf(key), value));
            tool.put("name", source.prefix() + name);
            offered.add(tool);
        }
        return List.copyOf(offered);
    }

    /** Read-only only when the endpoint says so; a tool with no hint counts as a write. */
    private static boolean isReadOnly(Map<?, ?> tool) {
        return tool.get("annotations") instanceof Map<?, ?> annotations
                && Boolean.TRUE.equals(annotations.get("readOnlyHint"));
    }

    private static Set<String> names(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rpc(String endpoint, String basicToken, String method, Map<String, Object> params) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + basicToken);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        request.put("params", params);

        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(endpoint, HttpMethod.POST, new HttpEntity<>(request, headers), JSON_OBJECT);
        Map<String, Object> body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("empty answer");
        }
        if (body.get("error") instanceof Map<?, ?> error) {
            throw new IllegalStateException(String.valueOf(error.get("message")));
        }
        if (!(body.get("result") instanceof Map<?, ?> result)) {
            throw new IllegalStateException("answer has no result");
        }
        return (Map<String, Object>) result;
    }

    /** Status code or exception type only: never a URL, header or upstream body. */
    private static String describe(Exception e) {
        if (e instanceof HttpStatusCodeException status) {
            return "HTTP " + status.getStatusCode().value();
        }
        if (e instanceof IllegalStateException || e instanceof IllegalArgumentException) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName();
    }
}
