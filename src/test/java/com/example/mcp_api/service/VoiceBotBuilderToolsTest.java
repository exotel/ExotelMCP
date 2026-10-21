package com.example.mcp_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pure validation and guard checks for VoiceBotBuilderTools. No network: no tool below reaches an HTTP call. */
class VoiceBotBuilderToolsTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String BOT_ID = "00000000-0000-4000-8000-000000000001";
    private static final String VALID_BOT = "{\"name\":\"t\",\"supported_languages\":[\"en\"],"
            + "\"assistant_config\":{\"id\":\"00000000-0000-4000-8000-000000000002\",\"version\":\"stable\","
            + "\"input\":{\"modality\":\"text\",\"mime_type\":\"text/plain\"},"
            + "\"output\":{\"modality\":\"text\",\"mime_type\":\"text/plain\"}},"
            + "\"voice_config\":{\"provider\":{\"id\":\"p\"},\"model\":{\"id\":\"m\"},\"voice\":{\"id\":\"v\"}},"
            + "\"asr_config\":{\"provider\":{\"id\":\"p\"},\"model\":{\"id\":\"m\"}}}";

    private static JsonNode json(String text) throws Exception {
        return M.readTree(text);
    }

    private static void assertErrorShape(Map<String, Object> e, String code) {
        assertNotNull(e, "expected an error");
        assertEquals(code, e.get("error_code"));
        assertEquals(false, e.get("ok"));
        for (String key : List.of("human_readable_reason", "suggested_action", "http_status", "body")) {
            assertTrue(e.containsKey(key), "missing " + key);
        }
    }

    // ---------- bot create allowlist ----------

    @Test
    void botCreateAcceptsAValidV1Config() throws Exception {
        assertNull(VoiceBotBuilderTools.validateBotCreateConfig(json(VALID_BOT)));
    }

    @Test
    void botCreateRejectsUnknownAndServerManagedKeysAndListsAllowedKeys() throws Exception {
        ObjectNode cfg = (ObjectNode) json(VALID_BOT);
        cfg.put("zz_unknown_field", 1);
        cfg.put("status", "active");
        Map<String, Object> e = VoiceBotBuilderTools.validateBotCreateConfig(cfg);
        assertErrorShape(e, "UNKNOWN_KEYS");
        assertEquals(List.of("zz_unknown_field"), e.get("unknown_keys"));
        assertEquals(List.of("status"), e.get("server_managed_keys"));
        Collection<?> allowed = (Collection<?>) e.get("allowed_keys");
        assertTrue(allowed.contains("greeting_message"));
        assertTrue(allowed.contains("insights_assistant_id"));
        assertFalse(allowed.contains("status"));
    }

    @Test
    void botCreateNeedsAssistantInputAndAsrForTextInputAndV1Shapes() throws Exception {
        ObjectNode noInput = (ObjectNode) json(VALID_BOT);
        ((ObjectNode) noInput.get("assistant_config")).remove("input");
        Map<String, Object> e = VoiceBotBuilderTools.validateBotCreateConfig(noInput);
        assertErrorShape(e, "MISSING_REQUIRED_FIELDS");
        assertEquals(List.of("assistant_config.input.modality"), e.get("missing_fields"));

        ObjectNode noAsr = (ObjectNode) json(VALID_BOT);
        noAsr.remove("asr_config");
        assertErrorShape(VoiceBotBuilderTools.validateBotCreateConfig(noAsr), "ASR_CONFIG_REQUIRED");

        ObjectNode v2Voice = (ObjectNode) json(VALID_BOT);
        v2Voice.set("voice_config", json("{\"model_id\":\"m\",\"voice_id\":\"v\"}"));
        assertErrorShape(VoiceBotBuilderTools.validateBotCreateConfig(v2Voice), "V1_SHAPE_REQUIRED");
    }

    @Test
    void botCreateToolRejectsBeforeAnyCallAndAcceptsObjectArguments() throws Exception {
        VoiceBotBuilderTools tools = new VoiceBotBuilderTools();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("name", "x");
        cfg.put("zz", 1);
        JsonNode r = json(tools.botCreate(cfg));
        assertEquals("UNKNOWN_KEYS", r.get("error_code").asText());
        assertEquals("zz", r.get("unknown_keys").get(0).asText());

        JsonNode bad = json(tools.botCreate("{not json"));
        assertEquals("INVALID_JSON", bad.get("error_code").asText());
    }

    // ---------- version create ----------

    @Test
    void versionCreateRefusesMarkAsStable() throws Exception {
        assertErrorShape(VoiceBotBuilderTools.validateVersionData(json("{}"), true), "MARK_AS_STABLE_REFUSED");
        assertErrorShape(VoiceBotBuilderTools.validateVersionData(json("{\"mark_as_stable\": true}"), false),
                "MARK_AS_STABLE_REFUSED");

        VoiceBotBuilderTools tools = new VoiceBotBuilderTools();
        JsonNode r = json(tools.botVersionCreate(BOT_ID, "{\"user_speech_timeout_seconds\":1.2}", null, null, true));
        assertEquals("MARK_AS_STABLE_REFUSED", r.get("error_code").asText());
        assertTrue(r.get("suggested_action").asText().contains("exotel_voicebot_bot_version_promote"));
    }

    @Test
    void versionCreateRejectsUnknownDataKeys() throws Exception {
        Map<String, Object> e = VoiceBotBuilderTools.validateVersionData(
                json("{\"greeting_message\":{\"text\":\"hi\",\"enabled\":true},\"zz_unknown_field\":1}"), false);
        assertErrorShape(e, "UNKNOWN_DATA_KEYS");
        assertEquals(List.of("zz_unknown_field"), e.get("unknown_keys"));
        assertTrue(((Collection<?>) e.get("allowed_keys")).contains("nudge_config"));

        Map<String, Object> wrapped = VoiceBotBuilderTools.validateVersionData(
                json("{\"source_version\":\"latest\",\"data\":{}}"), false);
        assertTrue(((String) wrapped.get("suggested_action")).contains("inner data object"));

        assertNull(VoiceBotBuilderTools.validateVersionData(json("{\"nudge_config\":null}"), false));
    }

    @Test
    void versionCreateWarnsOnAssistantForkTagsAndOldSource() throws Exception {
        JsonNode data = json("{\"assistant_config\":{\"instruction\":\"Greet <customer_name> by name\",\"agents\":[]}}");
        assertNull(VoiceBotBuilderTools.validateVersionData(data, false));
        List<String> warnings = VoiceBotBuilderTools.versionDataWarnings(data, "v1");
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("ASSISTANT_FORK")));
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("AGENTS_FULL_LIST")));
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("OLD_SOURCE")));

        List<String> tags = VoiceBotBuilderTools.tagWarnings(data);
        assertEquals(1, tags.size());
        assertTrue(tags.get(0).contains("assistant_config.instruction"));
        assertTrue(VoiceBotBuilderTools.isValidSourceVersion("latest"));
        assertFalse(VoiceBotBuilderTools.isValidSourceVersion("v2; drop"));
    }

    @Test
    void tagScanIgnoresComparisonsCurlyPlaceholdersAndExemptKeys() throws Exception {
        assertTrue(VoiceBotBuilderTools.findTagPaths(
                json("{\"a\":\"if x < 5 and y > 3\",\"b\":\"Hello {customer_name}\",\"endpoint\":\"<kept>\"}")).isEmpty());
        assertEquals(1, VoiceBotBuilderTools.findTagPaths(json("{\"a\":[\"ok\",\"</b> end\"]}")).size());
    }

    // ---------- traffic ----------

    @Test
    void trafficAllocationMustBeWholePercentagesSummingTo100() throws Exception {
        Map<String, Object> e = VoiceBotBuilderTools.parseTrafficAllocation(json("{\"v1\":50,\"v2\":40}"), new LinkedHashMap<>());
        assertErrorShape(e, "TRAFFIC_SUM_NOT_100");
        assertEquals(90, e.get("sum"));
        assertErrorShape(VoiceBotBuilderTools.parseTrafficAllocation(json("{\"stable\":100}"), new LinkedHashMap<>()),
                "INVALID_ALLOCATION");
        assertErrorShape(VoiceBotBuilderTools.parseTrafficAllocation(json("{\"v1\":50.5,\"v2\":49.5}"), new LinkedHashMap<>()),
                "INVALID_ALLOCATION");

        Map<String, Integer> good = new LinkedHashMap<>();
        assertNull(VoiceBotBuilderTools.parseTrafficAllocation(json("{\"allocation\":{\"v1\":90,\"v2\":10}}"), good));
        assertEquals(Map.of("v1", 90, "v2", 10), good);
    }

    @Test
    void trafficToolValidatesBeforeTheConfirmGateAndAnyCall() throws Exception {
        VoiceBotBuilderTools tools = new VoiceBotBuilderTools();
        JsonNode r = json(tools.botTrafficSet(BOT_ID, "{\"v1\":60,\"v2\":30}", true));
        assertEquals("TRAFFIC_SUM_NOT_100", r.get("error_code").asText());
        assertTrue(r.has("http_status") && r.get("http_status").isNull());
    }

    // ---------- confirm guard ----------

    @Test
    void confirmGateBlocksUnlessConfirmIsTrue() {
        Map<String, Object> preview = VoiceBotBuilderTools.promotePreview(BOT_ID, "v2");
        Map<String, Object> e = VoiceBotBuilderTools.confirmGate(null, "promote v2", preview);
        assertErrorShape(e, "CONFIRM_REQUIRED");
        assertSame(preview, e.get("preview"));
        assertNotNull(VoiceBotBuilderTools.confirmGate(false, "promote v2", preview));
        assertNull(VoiceBotBuilderTools.confirmGate(true, "promote v2", preview));
        assertTrue(preview.get("effects").toString().contains("{\"v2\": 100}"));
    }

    @Test
    void promoteWithoutConfirmReturnsPreviewAndChangesNothing() throws Exception {
        VoiceBotBuilderTools tools = new VoiceBotBuilderTools();
        JsonNode r = json(tools.botVersionPromote(BOT_ID, "v2", null));
        assertEquals("CONFIRM_REQUIRED", r.get("error_code").asText());
        assertTrue(r.path("preview").path("request").asText().contains("/versions/v2"));
        // No envelope in a unit test, so the best-effort current-state read is skipped, not attempted.
        assertTrue(r.path("preview").has("current_state_error"));

        JsonNode badVersion = json(tools.botVersionPromote(BOT_ID, "stable", true));
        assertEquals("INVALID_PARAMETER", badVersion.get("error_code").asText());
    }

    // ---------- assistant create ----------

    @Test
    void assistantCreateNeedsLlmConfigOnEveryAgentAndUniqueNames() throws Exception {
        String agentA = "{\"name\":\"main\",\"description\":\"d\",\"instruction\":\"i\",\"llm_config\":{}}";
        String agentB = "{\"name\":\"billing\",\"description\":\"d\",\"instruction\":\"i\"}";
        String head = "{\"name\":\"a\",\"description\":\"d\",\"instruction\":\"i\",\"agents\":[";

        Map<String, Object> e = VoiceBotBuilderTools.validateAssistantCreate(json(head + agentA + "," + agentB + "]}"));
        assertErrorShape(e, "AGENT_LLM_CONFIG_MISSING");
        assertEquals(List.of("agents[1] (billing)"), e.get("agents_missing_llm_config"));

        assertErrorShape(VoiceBotBuilderTools.validateAssistantCreate(json(head + agentA + "," + agentA + "]}")),
                "DUPLICATE_AGENT_NAMES");
        assertErrorShape(VoiceBotBuilderTools.validateAssistantCreate(
                json("{\"name\":\"a\",\"description\":\"d\",\"instruction\":\"i\",\"pre_agent_hooks\":[],\"agents\":[" + agentA + "]}")),
                "ASSISTANT_LEVEL_HOOKS");
        assertNull(VoiceBotBuilderTools.validateAssistantCreate(json(head + agentA + "]}")));
    }

    // ---------- secrets, URLs, dates, upstream errors ----------

    @Test
    void redactionHidesSecretsButKeepsTokenCountsAndEmptyConfigs() throws Exception {
        JsonNode n = json("{\"llm_config\":{\"auth_config\":{\"config\":{\"api_key\":\"k\"}},"
                + "\"decrypted_auth_config\":{\"config\":\"blob\"},\"llm_model_config\":{\"max_tokens\":1024}},"
                + "\"headers\":{\"Authorization\":\"Bearer abcdefghijkl\"},\"note\":\"Bearer abcdefghijklmnop\","
                + "\"voice\":{\"auth_config\":null},\"x\":{\"auth_config\":{}},\"instruction\":\"basic understanding of billing\"}");
        assertEquals(4, VoiceBotBuilderTools.redactSecrets(n));
        assertEquals("<redacted>", n.at("/llm_config/auth_config").asText());
        assertEquals("<redacted>", n.at("/llm_config/decrypted_auth_config").asText());
        assertEquals(1024, n.at("/llm_config/llm_model_config/max_tokens").asInt());
        assertEquals("<redacted>", n.at("/headers/Authorization").asText());
        assertTrue(n.at("/voice/auth_config").isNull());
        assertTrue(n.at("/x/auth_config").isObject());
        assertEquals("basic understanding of billing", n.at("/instruction").asText());
    }

    @Test
    void baseUrlMustBeHttpsExotelAndV1IsDerivedFromV2() {
        List<String> allowed = List.of(".exotel.com", ".exotel.in");
        assertNull(VoiceBotBuilderTools.validateBaseUrl("https://voicebot-staging.exotel.com/voicebot/api/v2", allowed));
        assertNull(VoiceBotBuilderTools.validateBaseUrl("https://voicebot.in.exotel.com/voicebot/api/v2/", allowed));
        assertNotNull(VoiceBotBuilderTools.validateBaseUrl("http://voicebot.in.exotel.com/voicebot/api/v2", allowed));
        assertNotNull(VoiceBotBuilderTools.validateBaseUrl("https://evil-exotel.com/voicebot/api/v2", allowed));
        assertNotNull(VoiceBotBuilderTools.validateBaseUrl("https://169.254.169.254/latest/api/v2", allowed));
        assertNotNull(VoiceBotBuilderTools.validateBaseUrl("https://voicebot.in.exotel.com/voicebot", allowed));
        assertEquals("https://voicebot.in.exotel.com/voicebot/api/v1",
                VoiceBotBuilderTools.apiBase("https://voicebot.in.exotel.com/voicebot/api/v2/", 1));
        assertEquals("https://voicebot.in.exotel.com/voicebot/api/v2",
                VoiceBotBuilderTools.apiBase("https://voicebot.in.exotel.com/voicebot/api/v2", 2));
    }

    @Test
    void datesAndFieldsAreNormalisedForTheJavaApi() {
        assertEquals("2026-10-21T00:00:00.000Z", VoiceBotBuilderTools.normalizeDate("2026-10-21", false));
        assertEquals("2026-10-21T23:59:59.999Z", VoiceBotBuilderTools.normalizeDate("2026-10-21", true));
        assertEquals("2026-10-21T03:30:00.000Z", VoiceBotBuilderTools.normalizeDate("2026-10-21T09:00:00+05:30", false));
        assertThrows(IllegalArgumentException.class, () -> VoiceBotBuilderTools.normalizeDate("21/10/2026", false));
        assertEquals("transcript,insights", VoiceBotBuilderTools.normalizeFields(" Transcript , insights,transcript"));
        assertThrows(IllegalArgumentException.class, () -> VoiceBotBuilderTools.normalizeFields("internal"));
        assertNull(VoiceBotBuilderTools.normalizeFields(" "));
        assertEquals(20, VoiceBotBuilderTools.nextOffset(0, 20, 20, 57));
        assertNull(VoiceBotBuilderTools.nextOffset(40, 20, 17, 57));
    }

    @Test
    void catalogueModelFollowsJavasModelConfigCoalesce() throws Exception {
        JsonNode provider = json("{\"model_config\":{\"model\":\"gemma4\"}}");
        assertEquals("gemma4", VoiceBotBuilderTools.extractModelName(json("{\"overrideConfig\":null}"), provider));
        assertEquals("qwen3.5", VoiceBotBuilderTools.extractModelName(
                json("{\"overrideConfig\":{\"model_config\":{\"model\":\"qwen3.5\"}}}"), provider));
        // An override model_config replaces the provider's whole object, so the provider model is not used.
        assertNull(VoiceBotBuilderTools.extractModelName(
                json("{\"override_config\":{\"model_config\":{\"max_tokens\":10}}}"), provider));
        assertEquals("gpt-4.1-mini", VoiceBotBuilderTools.extractModelName(
                json("{\"overrideConfig\":{\"model_name\":\"gpt-4.1-mini\"}}"), null));
    }

    @Test
    void upstreamErrorsAreStructuredRedactedAndTruncated() {
        String body = "{\"http_code\":403,\"error_data\":{\"error_code\":\"AUTHORIZATION_DENIED\",\"message\":\"Access denied\"}}";
        Map<String, Object> e = VoiceBotBuilderTools.upstreamError(403, body);
        assertErrorShape(e, "FORBIDDEN");
        assertEquals(403, e.get("http_status"));
        assertEquals("AUTHORIZATION_DENIED", e.get("upstream_error_code"));
        assertTrue(((String) e.get("human_readable_reason")).contains("Access denied"));

        assertTrue(((String) VoiceBotBuilderTools.upstreamError(500, "x".repeat(5000)).get("body")).length() <= 2048);
        String leaked = (String) VoiceBotBuilderTools.upstreamError(400, "{\"api_key\":\"sk-123\",\"message\":\"bad\"}").get("body");
        assertFalse(leaked.contains("sk-123"));
    }

    @org.junit.jupiter.api.Test
    void redactJsonTextHidesNestedLlmCredentials() {
        String body = "{\"data\":{\"llm_config\":{\"name\":\"g\",\"auth_config\":{\"config\":{\"api_key\":\"AIzaSECRETVALUE123\"}},"
                + "\"decrypted_auth_config\":{\"api_key\":\"AIzaSECRETVALUE123\"}},\"agents\":[{\"llm_config\":{\"api_key\":\"k-123456789\"}}]}}";
        String out = VoiceBotBuilderTools.redactJsonText(body);
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("SECRETVALUE"), out);
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("k-123456789"), out);
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("\"name\":\"g\""), out);
        org.junit.jupiter.api.Assertions.assertEquals("plain text", VoiceBotBuilderTools.redactJsonText("plain text"));
    }
}
