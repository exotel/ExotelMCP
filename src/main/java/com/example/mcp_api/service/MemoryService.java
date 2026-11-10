package com.example.mcp_api.service;

import com.example.mcp_api.auth.AuthContext;
import com.example.mcp_api.auth.AuthCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Exotel Memory: remember every contact across calls and chats.
 * API contract: Confluence "Exotel Memory API" (base {memory_base_url}/memory/api/v1/accounts/{sid}).
 * Works with any bot: read memory when an interaction starts, save it when it ends. For Exotel VoiceBots,
 * exotel_voicebot_memory_enable wires both automatically through the bot's session_start / session_end webhooks.
 */
@Service
public class MemoryService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryService.class);
    static final String DEFAULT_BASE_URL = "https://memory.in.exotel.com";
    private static final Pattern HOST_PATTERN = Pattern.compile("^memory(\\.[a-z0-9-]+)*\\.(exotel\\.com|exotel\\.in|qaexotel\\.com)$");
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_.:\\-]{1,128}$");
    private static final Set<String> CHANNELS = Set.of("VOICE", "CHAT", "WHATSAPP", "SMS", "EMAIL", "VIDEO", "SOCIAL_MEDIA");
    private static final Set<String> EVENT_TYPES = Set.of("payment", "ticket_status", "delivery", "other");
    private static final int MAX_BODY_CHARS = 2_000_000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired
    private VoiceBotService voiceBotService;

    @Autowired
    private VoiceBotAdminTools voiceBotAdminTools;

    // ======================== MEMORY TOOLS ========================

    @Tool(name = "exotel_memory_get",
          description = "Reads what Exotel Memory knows about a contact: name, summary, open topics (cases) with status and promises, "
              + "notes, facts and recent interactions. Identify the contact by phone (E.164, e.g. +919876543210), email, or one of your "
              + "own IDs (idType + idValue). Use before calling or replying to a contact. Returns found=false for a contact Memory has never seen.")
    public String getMemory(
            @ToolParam(description = "Contact phone, e.g. +919876543210 (0XXXXXXXXXX and 91XXXXXXXXXX are accepted for India)", required = false) String phone,
            @ToolParam(description = "Contact email", required = false) String email,
            @ToolParam(description = "Your own ID type, e.g. customer_id (use with idValue)", required = false) String idType,
            @ToolParam(description = "Your own ID value (use with idType)", required = false) String idValue,
            @ToolParam(description = "Optional: what the contact is asking about; matching facts are listed first", required = false) String query,
            @ToolParam(description = "internal (default; includes agent-only notes) or customer (what a bot may say to the contact)", required = false) String audience) {
        try {
            Ctx c = ctx();
            StringBuilder q = new StringBuilder("format=json&audience=");
            q.append("customer".equalsIgnoreCase(audience) ? "customer" : "internal");
            if (notBlank(phone)) q.append("&phone=").append(enc(normalizePhone(phone)));
            else if (notBlank(email)) q.append("&email=").append(enc(email.trim()));
            else if (notBlank(idType) && notBlank(idValue)) q.append("&type=").append(enc(idType.trim())).append("&value=").append(enc(idValue.trim()));
            else return "Error: give phone, email, or idType + idValue";
            if (notBlank(query)) q.append("&query=").append(enc(query));
            Resp r = send(c, "GET", c.apiBase + "/memory?" + q, null);
            if (r.status == 404) return "{\"found\":false,\"note\":\"Memory has nothing for this contact yet. Start without memory.\"}";
            return r.body;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_memory_search_contacts",
          description = "Finds contacts in Exotel Memory by an exact phone, email or ID (never by name). Without q, lists the most recently active contacts.")
    public String searchContacts(
            @ToolParam(description = "Exact phone, email or ID; empty lists recent contacts", required = false) String q,
            @ToolParam(description = "Max results, 1-200 (default 20)", required = false) Integer limit) {
        try {
            Ctx c = ctx();
            int lim = limit == null ? 20 : Math.max(1, Math.min(200, limit));
            String query = "limit=" + lim;
            if (notBlank(q)) query += "&q=" + enc(looksLikePhone(q) ? normalizePhone(q) : q.trim());
            return send(c, "GET", c.apiBase + "/profiles?" + query, null).body;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_memory_save_interaction",
          description = "Saves a finished interaction (call, chat, WhatsApp, SMS, email) to Exotel Memory so the next bot or agent knows it. "
              + "Give the turns as messagesJson ([{\"role\":\"customer|bot|agent|system\",\"text\":\"...\"}]; 'user' and 'contact' are accepted as customer), "
              + "or transcriptText, or a recording audioUrl. Sending the same externalInteractionId again updates it. "
              + "Memory learns from it in the background; for something the bot must know right away, also use exotel_memory_add_note.")
    public String saveInteraction(
            @ToolParam(description = "Contact phone") String phone,
            @ToolParam(description = "Your ID for this interaction, e.g. the call SID or session ID") String externalInteractionId,
            @ToolParam(description = "VOICE (default), CHAT, WHATSAPP, SMS, EMAIL, VIDEO or SOCIAL_MEDIA", required = false) String channelType,
            @ToolParam(description = "JSON array of turns: [{\"role\":\"customer\",\"text\":\"...\"},{\"role\":\"bot\",\"text\":\"...\"}]", required = false) String messagesJson,
            @ToolParam(description = "Transcript as text, one turn per line (Contact: ... / Bot: ...)", required = false) String transcriptText,
            @ToolParam(description = "HTTPS link to the call recording; Memory transcribes it", required = false) String audioUrl,
            @ToolParam(description = "inbound (default) or outbound", required = false) String direction,
            @ToolParam(description = "What handled it, e.g. voicebot, chatbot, agent (default voicebot)", required = false) String source) {
        try {
            Ctx c = ctx();
            if (!notBlank(externalInteractionId) || externalInteractionId.length() > 255) return "Error: externalInteractionId is required (max 255 chars)";
            String channel = notBlank(channelType) ? channelType.trim().toUpperCase() : "VOICE";
            if (!CHANNELS.contains(channel)) return "Error: channelType must be one of " + CHANNELS;
            ObjectNode body = objectMapper.createObjectNode();
            body.put("external_interaction_id", externalInteractionId.trim());
            body.put("channel_type", channel);
            body.put("source", notBlank(source) ? source.trim() : "voicebot");
            body.put("direction", "outbound".equalsIgnoreCase(direction) ? "outbound" : "inbound");
            body.set("contact_details", contact(phone));
            if (notBlank(messagesJson)) body.set("messages", normalizeMessages(messagesJson));
            else if (notBlank(transcriptText)) body.put("transcript_text", transcriptText);
            else if (notBlank(audioUrl)) {
                if (!audioUrl.startsWith("https://")) return "Error: audioUrl must be an https:// link";
                body.put("audio_url", audioUrl);
            } else return "Error: give messagesJson, transcriptText or audioUrl";
            return send(c, "POST", c.apiBase + "/memory", body).body;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_memory_add_note",
          description = "Adds a short note to a contact's memory. Notes show up immediately, even on the very next call (saved interactions are learned "
              + "in the background). Use for things the bot or agent must know now: a CRM update, an offer, a promised callback. Write a plain fact, "
              + "not an instruction (max 1000 characters).")
    public String addNote(
            @ToolParam(description = "Contact phone") String phone,
            @ToolParam(description = "The note, a plain fact, max 1000 characters") String text,
            @ToolParam(description = "true (default) lets bots read the note; false keeps it for human agents only", required = false) Boolean visibleToBots) {
        try {
            Ctx c = ctx();
            if (!notBlank(text) || text.length() > 1000) return "Error: text is required (max 1000 characters)";
            ObjectNode body = objectMapper.createObjectNode();
            body.set("contact_details", contact(phone));
            body.put("text", text.trim());
            body.put("visible_to_bots", visibleToBots == null || visibleToBots);
            return send(c, "POST", c.apiBase + "/notes", body).body;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_memory_record_event",
          description = "Records a fact from your own system against a contact: a payment, a ticket status change, a delivery, or another business "
              + "event (type other, details in payloadJson). Events are trusted over what the contact said and settle promises. "
              + "Sending the same eventId again is ignored.")
    public String recordEvent(
            @ToolParam(description = "Contact phone") String phone,
            @ToolParam(description = "payment, ticket_status, delivery or other") String type,
            @ToolParam(description = "Your ID for the event (idempotency key)") String eventId,
            @ToolParam(description = "When it happened, ISO 8601 UTC (default now)", required = false) String at,
            @ToolParam(description = "Amount, for payments, e.g. 4500", required = false) String amount,
            @ToolParam(description = "true when a payment has cleared", required = false) Boolean settled,
            @ToolParam(description = "Status for tickets and deliveries, e.g. resolved, delivered", required = false) String status,
            @ToolParam(description = "Your reference, e.g. an order, ticket or offer number", required = false) String externalRef,
            @ToolParam(description = "Any other details as a JSON object", required = false) String payloadJson) {
        try {
            Ctx c = ctx();
            String t = type == null ? "" : type.trim().toLowerCase();
            if (!EVENT_TYPES.contains(t)) return "Error: type must be one of " + EVENT_TYPES;
            if (!notBlank(eventId) || !SAFE_ID.matcher(eventId.trim()).matches()) return "Error: eventId is required (letters, digits, _ . : -; max 128)";
            ObjectNode body = objectMapper.createObjectNode();
            body.put("type", t);
            body.put("at", notBlank(at) ? at.trim() : Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
            body.set("contact_details", contact(phone));
            body.put("event_id", eventId.trim());
            if (notBlank(amount)) {
                try { body.put("amount", new java.math.BigDecimal(amount.trim())); }
                catch (NumberFormatException e) { return "Error: amount must be a number"; }
            }
            if (settled != null) body.put("settled", settled);
            if (notBlank(status)) body.put("status", status.trim());
            if (notBlank(externalRef)) body.put("external_ref", externalRef.trim());
            if (notBlank(payloadJson)) {
                JsonNode p = objectMapper.readTree(payloadJson);
                if (!p.isObject()) return "Error: payloadJson must be a JSON object";
                body.set("payload", p);
            }
            return send(c, "POST", c.apiBase + "/events", body).body;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ======================== VOICEBOT INTEGRATION ========================

    @Tool(name = "exotel_voicebot_memory_enable",
          description = "Gives any Exotel VoiceBot memory: sets the bot's session_start and session_end webhooks to Exotel Memory, so every call "
              + "starts with what Memory knows about the caller and is saved when it ends. Works for inbound and outbound calls. "
              + "In the bot's prompt, use {{entities.person_name}}, {{entities.memory_summary}}, {{entities.current_topic}} and "
              + "{{entities.memory_context}} (notes and open topics). Other webhooks on the bot are kept; a different existing "
              + "session_start/session_end webhook is only replaced when replaceExisting=true.")
    public String enableVoicebotMemory(
            @ToolParam(description = "VoiceBot ID") String voiceBotId,
            @ToolParam(description = "true to replace a different existing session_start/session_end webhook", required = false) Boolean replaceExisting) {
        try {
            Ctx c = ctx();
            ObjectNode hooks = currentWebhooks(voiceBotId);
            String start = c.hookBase + "/session_start";
            String end = c.hookBase + "/session_end";
            for (String k : new String[] {"session_start", "session_end"}) {
                String url = hooks.path(k).path("url").asText("");
                if (!url.isBlank() && !url.equals(start) && !url.equals(end) && !Boolean.TRUE.equals(replaceExisting)) {
                    return "Error: bot already sends " + k + " to another URL (" + hostOf(url) + "). Nothing changed. "
                        + "Call again with replaceExisting=true to switch it to Exotel Memory.";
                }
            }
            hooks.set("session_start", objectMapper.createObjectNode().put("url", start));
            hooks.set("session_end", objectMapper.createObjectNode().put("url", end));
            return updateWebhooks(voiceBotId, hooks, "Memory is ON for this bot. New calls load and save memory.");
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_voicebot_memory_disable",
          description = "Turns Exotel Memory off for a VoiceBot: removes only the session_start/session_end webhooks that point to Exotel Memory. "
              + "Saved memory is kept; turn it on again with exotel_voicebot_memory_enable.")
    public String disableVoicebotMemory(@ToolParam(description = "VoiceBot ID") String voiceBotId) {
        try {
            ObjectNode hooks = currentWebhooks(voiceBotId);
            Iterator<Map.Entry<String, JsonNode>> it = hooks.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (isMemoryHook(e.getValue().path("url").asText(""))) it.remove();
            }
            return updateWebhooks(voiceBotId, hooks, "Memory is OFF for this bot. Saved memory is kept.");
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private ObjectNode currentWebhooks(String voiceBotId) throws Exception {
        if (!notBlank(voiceBotId) || !SAFE_ID.matcher(voiceBotId.trim()).matches()) throw new IllegalArgumentException("voiceBotId is invalid");
        String raw = voiceBotService.getVoiceBot(voiceBotId.trim());
        JsonNode root;
        try { root = objectMapper.readTree(raw); } catch (Exception e) { throw new IllegalStateException(raw); }
        // VoiceBot v2 returns {"http_code":200,"response":{...bot}}; tolerate {"response":{"data":{...}}} and a bare bot too.
        JsonNode data = null;
        for (JsonNode candidate : new JsonNode[] {root.path("response").path("data"), root.path("response"), root.path("data"), root}) {
            if (candidate.isObject() && candidate.has("id")) { data = candidate; break; }
        }
        if (data == null) throw new IllegalStateException("could not read the bot: " + truncate(raw, 300));
        JsonNode w = data.path("webhook_config");
        return w.isObject() ? (ObjectNode) w.deepCopy() : objectMapper.createObjectNode();
    }

    private String updateWebhooks(String voiceBotId, ObjectNode hooks, String okMessage) throws Exception {
        ObjectNode patch = objectMapper.createObjectNode();
        patch.set("webhook_config", hooks);
        String res = voiceBotAdminTools.updateBotConfig(voiceBotId.trim(), objectMapper.writeValueAsString(patch));
        if (res == null || res.startsWith("Error")) return res;
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ok", true);
        out.put("voiceBotId", voiceBotId.trim());
        out.put("message", okMessage);
        out.set("webhook_config", hooks);
        return objectMapper.writeValueAsString(out);
    }

    // ======================== HELPERS ========================

    private record Ctx(String apiKey, String apiBase, String hookBase) {}
    private record Resp(int status, String body) {}

    private Ctx ctx() {
        String err = AuthContext.requireMemory();
        if (err != null) throw new IllegalStateException(err);
        AuthCredentials creds = AuthContext.current();
        String sid = creds.getMemoryAccountSid().trim();
        if (!SAFE_ID.matcher(sid).matches()) throw new IllegalArgumentException("memory_account_sid has invalid characters");
        String base = trimSlash(notBlank(creds.getMemoryBaseUrl()) ? creds.getMemoryBaseUrl() : DEFAULT_BASE_URL);
        validateHost(base);
        String hookBase = notBlank(creds.getMemoryWebhookBaseUrl())
            ? trimSlash(creds.getMemoryWebhookBaseUrl())
            : base + "/memory-client/memory/api/v1/accounts/" + sid + "/webhooks/voicebot";
        validateHost(hookBase);
        return new Ctx(creds.getMemoryApiKey(), base + "/memory/api/v1/accounts/" + sid, hookBase);
    }

    private boolean isMemoryHook(String url) {
        try {
            return url.contains("/webhooks/voicebot/") && HOST_PATTERN.matcher(URI.create(url).getHost().toLowerCase()).matches();
        } catch (Exception e) {
            return false;
        }
    }

    private Resp send(Ctx c, String method, String url, JsonNode body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + c.apiKey())
            .header("Accept", "application/json");
        if (body != null) {
            String json = objectMapper.writeValueAsString(body);
            if (json.length() > MAX_BODY_CHARS) throw new IllegalArgumentException("request too large");
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        logger.info("Memory {} {} -> {}", method, url.replaceAll("\\?.*", ""), r.statusCode());
        if (r.statusCode() >= 400 && r.statusCode() != 404) {
            throw new IllegalStateException("Memory API " + r.statusCode() + ": " + truncate(r.body(), 500)
                + (r.statusCode() == 401 ? " (check memory_api_key and memory_account_sid)" : "")
                + (r.statusCode() == 403 ? " (the API key lacks the scope for this call)" : ""));
        }
        return new Resp(r.statusCode(), r.body());
    }

    private ArrayNode contact(String phone) {
        ArrayNode a = objectMapper.createArrayNode();
        a.addObject().put("type", "phone").put("value", normalizePhone(phone));
        return a;
    }

    private ArrayNode normalizeMessages(String messagesJson) throws Exception {
        JsonNode m = objectMapper.readTree(messagesJson);
        if (!m.isArray() || m.isEmpty()) throw new IllegalArgumentException("messagesJson must be a non-empty JSON array");
        if (m.size() > 2000) throw new IllegalArgumentException("messagesJson has more than 2000 turns");
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode t : m) {
            String text = t.path("text").asText("");
            if (text.isBlank()) continue;
            out.addObject().put("role", normalizeRole(t.path("role").asText(""))).put("text", text);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("messagesJson has no text");
        return out;
    }

    /** Memory roles are customer, agent, bot, system; callers often say user or contact for the customer. */
    static String normalizeRole(String role) {
        String r = role == null ? "" : role.trim().toLowerCase();
        return switch (r) {
            case "bot", "assistant", "ai" -> "bot";
            case "agent", "human" -> "agent";
            case "system" -> "system";
            default -> "customer";
        };
    }

    /** E.164; Indian numbers are accepted as 0XXXXXXXXXX, XXXXXXXXXX or 91XXXXXXXXXX so inbound From and outbound To match. */
    static String normalizePhone(String phone) {
        if (phone == null) throw new IllegalArgumentException("phone is required");
        String s = phone.replaceAll("[\\s()\\-]", "");
        if (s.matches("0\\d{10}")) s = "+91" + s.substring(1);
        else if (s.matches("\\d{10}")) s = "+91" + s;
        else if (s.matches("91\\d{10}")) s = "+" + s;
        if (!s.matches("\\+\\d{8,15}")) throw new IllegalArgumentException("phone must be in international format, e.g. +919876543210");
        return s;
    }

    static boolean looksLikePhone(String q) {
        return q != null && q.replaceAll("[\\s()\\-+]", "").matches("\\d{10,15}");
    }

    static void validateHost(String url) {
        URI u;
        try { u = URI.create(url); } catch (Exception e) { throw new IllegalArgumentException("Memory URL is not valid: " + url); }
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || !HOST_PATTERN.matcher(u.getHost().toLowerCase()).matches()) {
            throw new IllegalArgumentException("Memory URLs must be https://memory.*.exotel.com (got " + hostOf(url) + ")");
        }
    }

    private static String hostOf(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return "invalid URL"; }
    }

    private static String trimSlash(String s) {
        String t = s.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
