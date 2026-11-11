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

    /** Prompt section added by exotel_voicebot_memory_enable. No angle brackets: the VoiceBot API strips them. */
    static final String MEMORY_BLOCK_START = "### EXOTEL MEMORY (managed by Exotel Memory; removed by exotel_voicebot_memory_disable) ###";
    static final String MEMORY_BLOCK_END = "### END EXOTEL MEMORY ###";
    /** Every version of the section starts with this, so older sections are found and replaced, never duplicated. */
    static final String MEMORY_BLOCK_PREFIX = "### EXOTEL MEMORY (";
    static final String MEMORY_BLOCK = MEMORY_BLOCK_START + "\n"
        + "Your first reply, in this order:\n"
        + "1. Who called whom: find the first line in the memory below that starts with \"- note:\". If it starts with "
        + "\"- note: Outbound call:\", you placed this call to tell them that: say \"Hi\" and their first name, who you are in a few words, that reason in one sentence, and ask "
        + "if now is a good time. This comes before your usual task. Never ask how you can help, and do not bring up older topics "
        + "unless they do. Otherwise the caller called you.\n"
        + "2. Returning caller who called you: say \"Hi\" and their first name, who you are in a few words, then recap in one sentence what you already know (newest notes first, then "
        + "the open topic), and never ask again for anything you already know.\n"
        + "3. If the memory below is empty or still shows curly braces, you know nothing about this caller: treat them as new.\n"
        + "What you remember about this caller (filled in by Exotel Memory at the start of every call):\n"
        + "- name: {{entities.person_name}}\n"
        + "- notes (newest first) and open topics: {{entities.memory_context}}\n"
        + "- summary: {{entities.memory_summary}}\n"
        + "- current topic: {{entities.current_topic}}\n"
        + "Rules:\n"
        + "- Never say something was done (a card blocked, a refund sent, an appointment booked) unless a tool result or a note confirms it.\n"
        + "- Never use titles or guess gender: no sir, madam, Mr, Mrs or Ms. Use the first name only.\n"
        + "- Do not read back phone numbers, account numbers or IDs from memory unless the caller asks.\n"
        + MEMORY_BLOCK_END;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired
    private VoiceBotAdminTools voiceBotAdminTools;

    @Autowired
    private VoiceBotService voiceBotService;

    static final String OUTBOUND_PREFIX = "Outbound call: ";
    static final String OUTBOUND_DONE_PREFIX = "Earlier call, finished: ";

    // ======================== MEMORY TOOLS ========================

    @Tool(name = "exotel_memory_get",
          description = "Reads what Exotel Memory knows about a contact as a short brief: name, summary, open topics and promises, notes, "
              + "recent interactions. Identify the contact by profileId (from exotel_memory_search_contacts), phone (E.164, e.g. "
              + "+919876543210), email, or idType + idValue. "
              + "detail=full returns the structured data. found=false: Memory has never seen this contact.")
    public String getMemory(
            @ToolParam(description = "Memory profile ID, e.g. from exotel_memory_search_contacts", required = false) String profileId,
            @ToolParam(description = "Contact phone, e.g. +919876543210 (0XXXXXXXXXX and 91XXXXXXXXXX are accepted for India)", required = false) String phone,
            @ToolParam(description = "Contact email", required = false) String email,
            @ToolParam(description = "Your own ID type, e.g. customer_id (use with idValue)", required = false) String idType,
            @ToolParam(description = "Your own ID value (use with idType)", required = false) String idValue,
            @ToolParam(description = "Optional: what the contact is asking about; matching facts are listed first", required = false) String query,
            @ToolParam(description = "internal (default; includes agent-only notes) or customer (what a bot may say to the contact)", required = false) String audience,
            @ToolParam(description = "brief (default: a short ready-to-read summary, about 400 tokens) or full (all structured data)", required = false) String detail) {
        try {
            Ctx c = ctx();
            boolean full = "full".equalsIgnoreCase(detail);
            StringBuilder q = new StringBuilder(full ? "format=json&audience=" : "format=markdown&budget=500&audience=");
            q.append("customer".equalsIgnoreCase(audience) ? "customer" : "internal");
            if (notBlank(profileId)) {
                if (!SAFE_ID.matcher(profileId.trim()).matches()) return "Error: profileId has invalid characters";
                q.append("&profile_id=").append(enc(profileId.trim()));
            } else if (notBlank(phone)) q.append("&phone=").append(enc(normalizePhone(phone)));
            else if (notBlank(email)) q.append("&email=").append(enc(email.trim()));
            else if (notBlank(idType) && notBlank(idValue)) q.append("&type=").append(enc(idType.trim())).append("&value=").append(enc(idValue.trim()));
            else return "Error: give profileId, phone, email, or idType + idValue";
            if (notBlank(query)) q.append("&query=").append(enc(query));
            Resp r = send(c, "GET", c.apiBase + "/memory?" + q, null);
            if (r.status == 404) return "{\"found\":false,\"note\":\"Memory has nothing for this contact yet. Start without memory.\"}";
            if (full) return r.body;
            JsonNode b = objectMapper.readTree(r.body);
            ObjectNode out = objectMapper.createObjectNode();
            out.put("found", true);
            out.put("profileId", b.path("profileId").asText(""));
            out.put("memory", b.path("memory").asText(""));
            if (b.path("truncated").asBoolean(false)) out.put("more", "Shortened; pass detail=full for everything.");
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_memory_delete_contact",
          description = "Deletes everything Exotel Memory knows about one contact (profile, notes, interactions), so the next call or "
              + "chat starts from zero. Cannot be undone: tell the user what will be deleted and get a clear yes first. Identify the "
              + "contact by phone or profileId. Needs the profile:delete scope on the Memory API key.")
    public String deleteContact(
            @ToolParam(description = "Contact phone, e.g. +919876543210", required = false) String phone,
            @ToolParam(description = "Memory profile ID (from exotel_memory_search_contacts)", required = false) String profileId) {
        try {
            Ctx c = ctx();
            String id = notBlank(profileId) ? profileId.trim() : null;
            if (id == null) {
                if (!notBlank(phone)) return "Error: give phone or profileId";
                Resp r = send(c, "GET", c.apiBase + "/memory?format=markdown&budget=100&audience=internal&phone=" + enc(normalizePhone(phone)), null);
                if (r.status == 404) return "{\"deleted\":false,\"note\":\"Memory has nothing for this contact: it already starts from zero.\"}";
                id = objectMapper.readTree(r.body).path("profileId").asText("");
            }
            if (!SAFE_ID.matcher(id).matches()) return "Error: profileId has invalid characters";
            Resp d = send(c, "DELETE", c.apiBase + "/profiles/" + enc(id), null);
            if (d.status == 404) return "{\"deleted\":false,\"note\":\"No such contact in Memory.\"}";
            return objectMapper.writeValueAsString(objectMapper.createObjectNode().put("deleted", true).put("profileId", id)
                .put("next", "The next call or chat with this contact starts from zero. Memory stays on for your bots."));
        } catch (IllegalStateException e) {
            if (String.valueOf(e.getMessage()).contains("Memory API 403"))
                return "Error: this Memory API key may not delete contacts (it lacks the profile:delete scope). Nothing was deleted. "
                    + "Ask the Exotel Memory team to add profile:delete to the key.";
            return "Error: " + e.getMessage();
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
          description = "Gives any Exotel VoiceBot memory in one step, so callers are remembered across calls (inbound and outbound): "
              + "1) points the bot's session_start/session_end webhooks at Exotel Memory, 2) adds a memory section to the bot's prompt "
              + "(every place the prompt lives) that greets returning callers by name and puts new notes first, 3) turns off the fixed "
              + "opening line, so the bot's first line is written with memory (\"Hi Saurabh, this is ...\"). Pass greeting to use a fixed "
              + "line instead, or keepGreeting=true to leave it. Safe to run again. A different existing session_start/session_end webhook is only "
              + "replaced when replaceExisting=true.")
    public String enableVoicebotMemory(
            @ToolParam(description = "VoiceBot ID") String voiceBotId,
            @ToolParam(description = "Optional new opening line, e.g. 'Hi, this is Exotel Bank support.'", required = false) String greeting,
            @ToolParam(description = "true keeps the current opening line unchanged", required = false) Boolean keepGreeting,
            @ToolParam(description = "true to replace a different existing session_start/session_end webhook", required = false) Boolean replaceExisting) {
        try {
            Ctx c = ctx();
            String botId = checkBotId(voiceBotId);
            JsonNode bot = voiceBotAdminTools.getBotV1(botId);
            ObjectNode hooks = bot.path("webhook_config").isObject() ? (ObjectNode) bot.path("webhook_config").deepCopy() : objectMapper.createObjectNode();
            String start = c.hookBase + "/session_start";
            String end = c.hookBase + "/session_end";
            for (String k : new String[] {"session_start", "session_end"}) {
                String url = hooks.path(k).path("url").asText("");
                if (!url.isBlank() && !isMemoryHook(url) && !Boolean.TRUE.equals(replaceExisting)) {
                    return "Error: bot already sends " + k + " to another URL (" + hostOf(url) + "). Nothing changed. "
                        + "Call again with replaceExisting=true to switch it to Exotel Memory.";
                }
            }
            hooks.set("session_start", objectMapper.createObjectNode().put("url", start));
            hooks.set("session_end", objectMapper.createObjectNode().put("url", end));
            ObjectNode patch = objectMapper.createObjectNode();
            patch.set("webhook_config", hooks);

            // A fixed opening line plays before memory is loaded, so it can never use the caller's name. By default it is turned
            // off and the bot's first line is written with memory; its text is kept, and memory off turns it back on.
            JsonNode gm = bot.path("greeting_message");
            ObjectNode g = gm.isObject() ? (ObjectNode) gm.deepCopy() : objectMapper.createObjectNode();
            String greetingNote = "unchanged";
            if (notBlank(greeting)) {
                g.put("text", greeting.trim()).put("enabled", true);
                greetingNote = "fixed line: " + greeting.trim();
            } else if (!Boolean.TRUE.equals(keepGreeting) && gm.path("enabled").asBoolean(true)) {
                g.put("enabled", false);
                greetingNote = "off: the bot's first line now uses memory (Hi <name>, ...)";
            }
            if (!"unchanged".equals(greetingNote)) patch.set("greeting_message", g);
            String res = voiceBotAdminTools.updateBotConfig(botId, objectMapper.writeValueAsString(patch));
            if (failed(res)) return res;

            ObjectNode prompt;
            try {
                prompt = updatePrompt(bot, true);
            } catch (Exception e) {
                return "Error: memory webhooks are ON, but the prompt could not be updated (" + e.getMessage() + "). "
                    + "Run exotel_voicebot_memory_enable again; it only changes what is missing.";
            }
            ObjectNode out = objectMapper.createObjectNode();
            out.put("ok", true);
            out.put("voiceBotId", botId);
            out.put("memory", "on");
            out.put("greeting", greetingNote);
            out.set("prompt", prompt);
            out.put("next", "Call the bot (exotel_voicebot_place_call). After a call: exotel_voicebot_call_result shows the transcript and what Memory learned.");
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_voicebot_memory_disable",
          description = "Turns Exotel Memory off for a VoiceBot: removes the Memory webhooks and the memory section from its prompt. "
              + "Turns the fixed opening line back on. Other webhooks and saved memory are kept.")
    public String disableVoicebotMemory(@ToolParam(description = "VoiceBot ID") String voiceBotId) {
        try {
            String botId = checkBotId(voiceBotId);
            JsonNode bot = voiceBotAdminTools.getBotV1(botId);
            ObjectNode hooks = bot.path("webhook_config").isObject() ? (ObjectNode) bot.path("webhook_config").deepCopy() : objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = hooks.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (isMemoryHook(e.getValue().path("url").asText(""))) it.remove();
            }
            ObjectNode patch = objectMapper.createObjectNode();
            patch.set("webhook_config", hooks);
            JsonNode gm = bot.path("greeting_message");
            if (gm.isObject() && !gm.path("enabled").asBoolean(true) && notBlank(gm.path("text").asText("")))
                patch.set("greeting_message", ((ObjectNode) gm.deepCopy()).put("enabled", true));
            String res = voiceBotAdminTools.updateBotConfig(botId, objectMapper.writeValueAsString(patch));
            if (failed(res)) return res;
            ObjectNode prompt;
            try {
                prompt = updatePrompt(bot, false);
            } catch (Exception e) {
                return "Error: memory webhooks are OFF, but the memory section could not be removed from the prompt (" + e.getMessage() + "). "
                    + "Run exotel_voicebot_memory_disable again.";
            }
            ObjectNode out = objectMapper.createObjectNode();
            out.put("ok", true);
            out.put("voiceBotId", botId);
            out.put("memory", "off");
            out.set("prompt", prompt);
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_voicebot_call_result",
          description = "Everything about a finished VoiceBot call in one step: duration, who hung up, the transcript (bot/caller lines), "
              + "and, when Exotel Memory is set up, what Memory now knows about the caller. Give the Call SID from exotel_voicebot_place_call. "
              + "found=false: the call is still running, was not answered, or has not been indexed yet (retry in about 10 seconds).")
    public String callResult(
            @ToolParam(description = "Call SID") String callSid,
            @ToolParam(description = "false to skip the Memory lookup", required = false) Boolean includeMemory) {
        try {
            String found = voiceBotAdminTools.findSessionByCallSid(callSid, "100", "0");
            JsonNode f;
            try { f = objectMapper.readTree(found); } catch (Exception e) { return found; }
            if (!f.path("found").asBoolean(false)) return noSessionResult(callSid, f);
            JsonNode conv = f.path("conversation");
            String session = conv.path("session_id").asText("");
            ObjectNode out = objectMapper.createObjectNode();
            out.put("callSid", callSid.trim());
            out.put("sessionId", session);
            out.put("durationSec", conv.path("call_duration").asText(""));
            out.put("outcome", conv.path("outcome").asText(""));
            out.put("hungUpBy", conv.path("disconnected_by").asText(""));
            ArrayNode lines = objectMapper.createArrayNode();
            if (!session.isBlank()) {
                String raw = voiceBotAdminTools.getBotSessionTranscript(session);
                try { collectTurns(objectMapper.readTree(raw), lines, 0); } catch (Exception ignored) { /* transcript not ready */ }
            }
            out.set("transcript", lines);
            String caller = conv.path("contact_uri").asText("");
            if (!Boolean.FALSE.equals(includeMemory) && AuthContext.current().hasMemoryCredentials() && looksLikePhone(caller)) {
                try {
                    String closed = closeOutboundNote(caller);
                    if (closed != null) out.put("outboundReason", closed);
                    out.set("memory", objectMapper.readTree(getMemory(null, caller, null, null, null, null, null, null)));
                } catch (Exception e) {
                    out.put("memory", "not available: " + e.getMessage());
                }
            }
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_voicebot_call_customer",
          description = "Has a VoiceBot call someone for a reason it opens with (an offer, a delivery, a follow-up). With Exotel Memory set up "
              + "and memory on for the bot, it first saves the reason as an 'Outbound call:' note, so the bot knows it placed this call and "
              + "opens with the reason instead of asking how it can help; exotel_voicebot_call_result marks the note done afterwards. "
              + "Billed and rings a real phone. callerId must be an ExoPhone on this account (default: the saved caller ID).")
    public String callCustomer(
            @ToolParam(description = "VoiceBot ID") String voiceBotId,
            @ToolParam(description = "Number to call, E.164 (e.g. +919876543210)") String toNumber,
            @ToolParam(description = "Why the bot is calling, one plain sentence, e.g. 'Your replacement debit card ending 9993 was dispatched today.'", required = false) String reason,
            @ToolParam(description = "ExoPhone to call from (default: saved caller ID)", required = false) String callerId,
            @ToolParam(description = "true to record the call", required = false) Boolean record) {
        try {
            String botId = checkBotId(voiceBotId);
            String to = normalizePhone(toNumber);
            boolean noted = false;
            if (notBlank(reason) && AuthContext.current().hasMemoryCredentials()) {
                String text = OUTBOUND_PREFIX + reason.trim();
                if (text.length() > 1000) return "Error: reason is too long (max about 980 characters)";
                String n = addNote(to, text, true);
                if (failed(n)) return "Error: the reason could not be saved, so no call was placed. Write it as a plain fact, not an instruction. " + n;
                noted = true;
            }
            String res = voiceBotService.makeOutboundBotCall(to, botId, callerId, "", record, "");
            if (failed(res)) {
                if (noted) closeOutboundNote(to);   // otherwise the next incoming call would be treated as this outbound call
                return res;
            }
            JsonNode r;
            try { r = objectMapper.readTree(res); } catch (Exception e) { return res; }
            String sid = r.path("Call").path("Sid").asText(r.path("call").path("sid").asText(""));
            ObjectNode out = objectMapper.createObjectNode();
            out.put("ok", true);
            out.put("callSid", sid);
            out.put("to", to);
            out.put("status", r.path("Call").path("Status").asText(""));
            out.put("reasonNote", noted ? "saved: the bot opens with the reason" : (notBlank(reason) ? "not saved (Memory is not set up)" : "none"));
            out.put("next", "When the call ends: exotel_voicebot_call_result with this callSid (also marks the reason note done).");
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * No bot session: the call is still running, or ended without being answered. If it ended, close an open outbound
     * reason note for the called number, so the next incoming call is not treated as this outbound call.
     */
    private String noSessionResult(String callSid, JsonNode notFound) throws Exception {
        ObjectNode out = ((ObjectNode) notFound.deepCopy());
        JsonNode call;
        try { call = objectMapper.readTree(voiceBotService.getBotCallDetails(callSid)).path("Call"); } catch (Exception e) { return objectMapper.writeValueAsString(out); }
        String status = call.path("Status").asText("");
        out.put("callStatus", status);
        String customer = call.path("From").asText("");   // Connect API: From is the number that was called
        if (TERMINAL.contains(status) && AuthContext.current().hasMemoryCredentials() && looksLikePhone(customer)) {
            String closed = closeOutboundNote(customer);
            if (closed != null) out.put("outboundReason", closed);
        }
        return objectMapper.writeValueAsString(out);
    }

    private static final Set<String> TERMINAL = Set.of("completed", "busy", "failed", "no-answer", "canceled");

    /** Tool methods return their errors as text starting with "Error". */
    static boolean failed(String result) {
        return result == null || result.startsWith("Error");
    }

    /** The newest note is an open "Outbound call:" note: mark it done so later inbound calls are not treated as this outbound call. */
    private String closeOutboundNote(String phone) {
        try {
            JsonNode m = objectMapper.readTree(getMemory(null, phone, null, null, null, null, "customer", null));
            String brief = m.path("memory").asText("");
            String newest = newestNote(brief);
            if (newest != null && newest.startsWith(OUTBOUND_PREFIX)) {
                addNote(phone, OUTBOUND_DONE_PREFIX + newest.substring(OUTBOUND_PREFIX.length()), true);
                return "marked done";
            }
        } catch (Exception ignored) { /* best effort */ }
        return null;
    }

    /** The first "- note: …" line of a Memory brief (Memory lists notes newest first). */
    static String newestNote(String brief) {
        if (brief == null) return null;
        for (String line : brief.split("\\n")) {
            String t = line.trim();
            if (t.startsWith("- note: ")) return t.substring("- note: ".length()).trim();
        }
        return null;
    }

    /** Adds (on=true) or removes the Exotel Memory section in every prompt location; publishes only when something changes. */
    private ObjectNode updatePrompt(JsonNode bot, boolean on) throws Exception {
        String assistantId = bot.path("assistant_config").path("id").asText("");
        ObjectNode r = objectMapper.createObjectNode();
        if (assistantId.isBlank()) return r.put("changed", false).put("note", "bot has no assistant; prompt not changed");
        JsonNode a = voiceBotAdminTools.getAssistantData(assistantId);
        boolean needs = false;
        java.util.List<String> texts = new java.util.ArrayList<>();
        texts.add(a.path("instruction").asText(""));
        for (JsonNode ag : a.path("agents")) texts.add(ag.path("instruction").asText(""));
        for (String t : texts) {
            if (t.isEmpty()) continue;
            boolean has = t.contains(MEMORY_BLOCK);
            if (on ? !has : t.contains(MEMORY_BLOCK_PREFIX)) { needs = true; break; }
        }
        if (!needs) return r.put("changed", false).put("note", on ? "memory section already in the prompt" : "no memory section in the prompt");
        JsonNode v = voiceBotAdminTools.publishInstructions(assistantId,
            (current, oldTop, n) -> current.isEmpty() ? current : (on ? withMemoryBlock(current) : withoutMemoryBlock(current)),
            on ? "Exotel Memory on" : "Exotel Memory off");
        r.put("changed", true);
        r.put("version", v.path("version").asText(""));
        r.put("promptLocations", v.path("promptLocations").asInt());
        r.put("promptLocationsChanged", v.path("promptLocationsChanged").asInt());
        return r;
    }

    static String withMemoryBlock(String prompt) {
        return withoutMemoryBlock(prompt).stripTrailing() + "\n\n" + MEMORY_BLOCK;
    }

    static String withoutMemoryBlock(String prompt) {
        int s = prompt.indexOf(MEMORY_BLOCK_PREFIX);
        if (s < 0) return prompt;
        int lineEnd = prompt.indexOf('\n', s);
        int startLen = (lineEnd < 0 ? prompt.length() : lineEnd) - s;
        int e = prompt.indexOf(MEMORY_BLOCK_END, s);
        // Someone edited the section and removed its end line: remove only the start line, never the text after it.
        if (e < 0) return (prompt.substring(0, s) + prompt.substring(s + startLen)).stripTrailing();
        return (prompt.substring(0, s).stripTrailing() + prompt.substring(e + MEMORY_BLOCK_END.length())).stripTrailing();
    }

    private void collectTurns(JsonNode n, ArrayNode out, int depth) {
        if (n == null || depth > 10 || out.size() >= 200) return;
        if (n.isObject() && n.path("speaker").isTextual() && n.path("text").isTextual()) {
            String who = "assistant".equalsIgnoreCase(n.path("speaker").asText()) ? "bot" : "caller";
            out.add(who + ": " + n.path("text").asText());
            return;
        }
        if (n.isContainerNode()) for (JsonNode c : n) collectTurns(c, out, depth + 1);
    }

    private String checkBotId(String id) {
        if (!notBlank(id) || !SAFE_ID.matcher(id.trim()).matches()) throw new IllegalArgumentException("voiceBotId is invalid");
        return id.trim();
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
        String requestId = r.headers().firstValue("X-Request-Id").orElse("-");
        logger.info("Memory {} {} -> {} requestId={}", method, url.replaceAll("\\?.*", ""), r.statusCode(), requestId);
        if (r.statusCode() >= 400 && r.statusCode() != 404) {
            throw new IllegalStateException("Memory API " + r.statusCode() + " (request " + requestId + "): " + truncate(r.body(), 500)
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
