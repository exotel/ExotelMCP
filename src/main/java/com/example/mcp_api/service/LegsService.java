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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Legs API + ExoML (programmable call control without an App Bazaar flow). Mumbai only: cpaas-api.in.exotel.com.
 * Needs an API key with Legs access and a Legs-enabled number (Exotel support enables both).
 *
 * The next call step is never in the event reply: it is ExoML sent to POST /legs/{leg_sid}/actions, and only after
 * leg_answered (actions sent while ringing are lost). exotel_legs_call_bot does that for the caller by watching the
 * leg's event history (GET /legs/{sid}/external_events), so no event server is needed for an outbound bot call.
 */
@Service
public class LegsService {

    private static final Logger logger = LoggerFactory.getLogger(LegsService.class);
    static final String DEFAULT_CPAAS = "https://cpaas-api.in.exotel.com";
    private static final Pattern NUMBER = Pattern.compile("^\\+?\\d{6,15}$");
    private static final Pattern CONTACT = Pattern.compile("^(\\+?\\d{6,15}|sips?:[^\\s<>\"']{3,200})$");
    private static final Pattern SID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Pattern WSS = Pattern.compile("^wss://[^\\s<>\"']{4,1500}$");
    private static final Pattern EXOTEL_HOST = Pattern.compile("^([a-z0-9-]+\\.)*(exotel\\.com|exotel\\.in)$");
    private static final int MAX_EXOML = 20_000;
    /** ExoML verbs from the Legs API reference v2.4 (top-level children of Flow). */
    static final Set<String> LEG_ACTIONS = Set.of("Answer", "Hangup", "Dial", "Gather", "Hold", "UnHold", "JoinBridge",
        "LeaveBridge", "Mute", "UnMute", "PlaySequence", "Refer", "Say", "StopSay", "SendDigits", "StartMonitoring",
        "StopMonitoring", "StartPlay", "StopPlay", "StartRecording", "StopRecording", "StartStream", "StopStream");
    static final Set<String> BRIDGE_ACTIONS = Set.of("Refer", "StartPlay", "StopPlay", "Say", "StopSay", "StartRecording",
        "StopRecording", "PlaySequence");
    static final Set<String> ENDED = Set.of("leg_terminated", "leg_failed_to_create", "leg_failed", "leg_disconnected", "leg_completed");

    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${exotel.legs.public-base-url:https://mcp.exotel.com}")
    private String publicBaseUrl;

    @Value("${exotel.legs.cpaas-base-url:}")
    private String cpaasOverride;

    /** Exotel posts leg events to this server, which acts at once (outbound bot calls, hosted inbound). Off until approved. */
    @Value("${exotel.legs.webhooks.enabled:false}")
    private boolean webhooks;

    @Autowired
    private VoiceBotService voiceBotService;

    @Autowired
    private LegsInboundRegistry inboundRegistry;

    // ======================== TOOLS ========================

    @Tool(name = "exotel_legs_call_bot",
          description = "Outbound call over the Legs API + ExoML that puts a voice bot on the call, end to end, with no event server: "
              + "dials the number, waits for the answer, then sends StartStream (bidirectional) to the bot. Give botId (an Exotel "
              + "VoiceBot) or streamUrl (any wss:// bot). Returns when the bot is connected (usually 1-3 s after answer) or the call "
              + "ends. Billed: it rings a real phone, so get the user's yes first. Needs an API key with Legs access and a Legs-enabled "
              + "number as exophone (Exotel support). For an Exotel VoiceBot without Legs access use exotel_voicebot_place_call.")
    public String callBot(
            @ToolParam(description = "Number to call, e.g. +919876543210") String to,
            @ToolParam(description = "Your Legs-enabled number (ExoPhone) to call from") String exophone,
            @ToolParam(description = "Exotel VoiceBot ID (use this or streamUrl)", required = false) String botId,
            @ToolParam(description = "Your own bot's wss:// URL (use this or botId)", required = false) String streamUrl,
            @ToolParam(description = "Optional correlation id sent back in events (custom_param)", required = false) String customParam,
            @ToolParam(description = "Seconds to ring before giving up (10-45, default 30)", required = false) Integer ringTimeoutSec) {
        try {
            Cpaas c = cpaas();
            String url = resolveStream(botId, streamUrl);
            int ring = clamp(ringTimeoutSec, 10, 45, 30);
            ObjectNode body = legBody(to, exophone, null, customParam);
            body.put("ring_timeout", ring);
            logger.info("Legs bot call: to={}, from={}, bot={}", mask(to), mask(exophone), botId != null ? botId : "custom");
            Api api = api(c);
            if (!webhooks) return om.writeValueAsString(runBotCall(api, body, streamXml(url), (ring + 15) * 1000L, 100, null));
            // Webhook mode: leg_answered reaches this server and StartStream goes out at once (no polling delay).
            LegsInboundRegistry.Registration reg = inboundRegistry.register(api, c.accountSid(), to.trim(), streamXml(url), Duration.ofMinutes(15));
            try {
                body.put("leg_event_endpoint", trimSlash(publicBaseUrl) + "/legs/inbound/" + reg.id());
                return om.writeValueAsString(runBotCall(api, body, streamXml(url), (ring + 15) * 1000L, 100, reg));
            } finally {
                inboundRegistry.stop(reg.id(), c.accountSid());
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            logger.warn("exotel_legs_call_bot failed: {}", e.toString());
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_legs_dial",
          description = "Creates a call leg (dials a phone number or SIP address) with the Legs API. The leg only rings: send ExoML with "
              + "exotel_legs_action after it is answered (check with exotel_legs_get). Billed: get the user's yes first. eventsUrl is "
              + "your HTTPS event endpoint; leave it empty if you will follow the call with exotel_legs_get instead.")
    public String dial(
            @ToolParam(description = "Number (+919876543210) or sip:user@host to call") String to,
            @ToolParam(description = "Your Legs-enabled number (ExoPhone) to call from") String exophone,
            @ToolParam(description = "Your public https:// event endpoint (optional)", required = false) String eventsUrl,
            @ToolParam(description = "Optional correlation id sent back in events", required = false) String customParam,
            @ToolParam(description = "Seconds to ring (10-60, default 30)", required = false) Integer ringTimeoutSec,
            @ToolParam(description = "Maximum call length in seconds (default: account limit)", required = false) Integer timeLimitSec,
            @ToolParam(description = "true: detect answering machines (amd_enable)", required = false) Boolean amd) {
        try {
            Cpaas c = cpaas();
            ObjectNode body = legBody(to, exophone, eventsUrl, customParam);
            body.put("ring_timeout", clamp(ringTimeoutSec, 10, 60, 30));
            if (timeLimitSec != null) body.put("time_limit", clamp(timeLimitSec, 1, 14400, 14400));
            if (Boolean.TRUE.equals(amd)) body.put("amd_enable", true);
            Resp r = api(c).call("POST", "/legs", om.writeValueAsString(body));
            if (!r.ok()) return failure("Dial", r);
            String leg = data(r).path("leg_sid").asText("");
            ObjectNode out = om.createObjectNode();
            out.put("legSid", leg);
            out.put("eventsUrl", body.path("leg_event_endpoint").asText());
            out.put("next", "exotel_legs_get legSid=" + leg + " to see it ring and answer; then exotel_legs_action with ExoML.");
            return om.writeValueAsString(out);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_legs_action",
          description = "Sends ExoML to a live leg (legSid) or bridge (bridgeSid): e.g. <Flow><Say>Hi</Say></Flow>, <Flow><StartStream "
              + "streamType=\"bidirectional\" streamUrl=\"wss://...\"/></Flow>, <Flow><Dial contactUri=\"+91...\" exophone=\"...\"/></Flow>, "
              + "<Flow><Hangup/></Flow>. Checked before sending (well-formed, root Flow, known actions). Send actions only after "
              + "leg_answered. Dial and Refer place billed calls: get the user's yes first.")
    public String action(
            @ToolParam(description = "Leg SID (use this or bridgeSid)", required = false) String legSid,
            @ToolParam(description = "Bridge SID (use this or legSid)", required = false) String bridgeSid,
            @ToolParam(description = "ExoML, root element Flow") String exoml,
            @ToolParam(description = "Optional tag sent back in action events (action_custom_param)", required = false) String customParam) {
        try {
            boolean bridge = notBlank(bridgeSid);
            if (bridge == notBlank(legSid)) return "Error: give legSid or bridgeSid (one of them)";
            String sid = sid(bridge ? bridgeSid : legSid, bridge ? "bridgeSid" : "legSid");
            List<String> actions = validateExoml(exoml, bridge);
            Cpaas c = cpaas();
            Resp r = api(c).call("POST", (bridge ? "/bridges/" : "/legs/") + sid + "/actions", actionBody(exoml.trim(), customParam));
            if (!r.ok()) return failure("Action", r);
            ObjectNode out = om.createObjectNode();
            out.put("sent", String.join(", ", actions));
            out.put("actionSid", data(r).path("action_sid").asText(""));
            return om.writeValueAsString(out);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_legs_get",
          description = "Gets a call leg: its details and the events so far (leg_ringing, leg_answered, stream_started, leg_terminated...), "
              + "each with its time and whether your event endpoint accepted it. Use it to follow a leg without an event server.")
    public String get(@ToolParam(description = "Leg SID") String legSid) {
        try {
            String sid = sid(legSid, "legSid");
            Api api = api(cpaas());
            Resp leg = api.call("GET", "/legs/" + sid, null);
            if (!leg.ok()) return failure("Get leg", leg);
            ObjectNode out = om.createObjectNode();
            out.set("leg", data(leg));
            Resp ev = api.call("GET", "/legs/" + sid + "/external_events", null);
            ArrayNode list = out.putArray("events");
            String state = "";
            for (JsonNode e : events(ev)) {
                ObjectNode row = list.addObject();
                row.put("event", e.path("event_info").path("event_name").asText());
                row.put("at", e.path("date_created").asText());
                String err = e.path("error").asText("");
                if (!err.isBlank()) row.put("deliveryError", err);
                String st = e.path("event_info").path("data").path("state").asText("");
                if (!st.isBlank()) state = st;
            }
            out.put("state", state);
            return om.writeValueAsString(out);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_legs_bridge_create",
          description = "Joins two or more answered legs into one conversation (a bridge). Play or record for everyone with "
              + "exotel_legs_action bridgeSid=...; a bridge has no hold music of its own (use Hold or StartPlay on a leg before joining).")
    public String bridgeCreate(
            @ToolParam(description = "Answered leg SIDs, comma-separated (2 or more)") String legSids,
            @ToolParam(description = "Your public https:// event endpoint for bridge events (optional)", required = false) String eventsUrl,
            @ToolParam(description = "true: record the bridge", required = false) Boolean record,
            @ToolParam(description = "Maximum participants (2-100)", required = false) Integer maxParticipants) {
        try {
            if (!notBlank(legSids)) return "Error: legSids is required";
            ObjectNode body = om.createObjectNode();
            ArrayNode legs = body.putArray("leg_sids");
            for (String s : legSids.split(",")) if (!s.isBlank()) legs.add(sid(s.trim(), "legSids"));
            if (legs.size() < 2) return "Error: a bridge needs at least two leg SIDs";
            body.put("bridge_event_endpoint", eventsUrl(eventsUrl));
            if (Boolean.TRUE.equals(record)) body.put("recording", true);
            if (maxParticipants != null) body.put("max_participants", clamp(maxParticipants, 2, 100, 10));
            Resp r = api(cpaas()).call("POST", "/bridges", om.writeValueAsString(body));
            if (!r.ok()) return failure("Create bridge", r);
            ObjectNode out = om.createObjectNode();
            out.put("bridgeSid", data(r).path("bridge_sid").asText(data(r).path("sid").asText("")));
            out.set("response", data(r));
            return om.writeValueAsString(out);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_legs_inbound_bot",
          description = "Puts a voice bot on incoming calls to a Legs-enabled number, with ExoML instead of an App Bazaar flow. "
              + "mode=guide (default): the exact steps and ExoML for the user's own event server, plus the no-support alternative "
              + "(route the number to a flow with a VoiceBot applet). mode=hosted: this MCP server answers the calls itself "
              + "(Answer, then StartStream to the bot) at a private URL that Exotel support attaches to the number; only when the "
              + "server operator has turned hosted answering on. Calls ring the bot only after support has attached the number.")
    public String inboundBot(
            @ToolParam(description = "Your Legs-enabled number (DID) that callers dial") String number,
            @ToolParam(description = "Exotel VoiceBot ID (use this or streamUrl)", required = false) String botId,
            @ToolParam(description = "Your own bot's wss:// URL (use this or botId)", required = false) String streamUrl,
            @ToolParam(description = "guide (default) or hosted", required = false) String mode,
            @ToolParam(description = "hosted only: hours to keep answering (1-168, default 24)", required = false) Integer hours) {
        try {
            if (number == null || !NUMBER.matcher(number.trim()).matches()) return "Error: number must be a phone number, e.g. 02048566332";
            String url = resolveStream(botId, streamUrl);
            ObjectNode out = om.createObjectNode();
            out.put("number", number.trim());
            boolean hosted = "hosted".equalsIgnoreCase(mode);
            if (hosted && !webhooks) {
                out.put("hosted", "off");
                out.put("note", "Hosted answering is turned off on this MCP server (exotel.legs.webhooks.enabled). Use the steps below.");
            }
            if (hosted && webhooks) {
                Cpaas c = cpaas();
                LegsInboundRegistry.Registration reg = inboundRegistry.register(api(c), c.accountSid(), number.trim(), streamXml(url),
                    Duration.ofHours(clamp(hours, 1, 168, 24)));
                String endpoint = trimSlash(publicBaseUrl) + "/legs/inbound/" + reg.id();
                out.put("hosted", "on");
                out.put("registrationId", reg.id());
                out.put("eventEndpoint", endpoint);
                out.put("until", reg.expiresAt().toString());
                ArrayNode steps = out.putArray("steps");
                steps.add("1. Ask Exotel support to attach " + number.trim() + " (Legs inbound) to this event endpoint: " + endpoint
                    + " . Share it only with support: the URL is the key.");
                steps.add("2. Call the number. This server sends Answer on leg_incoming and StartStream on leg_answered.");
                steps.add("3. Stop answering any time with exotel_legs_inbound_stop registrationId=" + reg.id()
                    + ". A server restart also stops it; register again after one.");
                return om.writeValueAsString(out);
            }
            ArrayNode a = out.putArray("withoutSupport");
            a.add("Route the number to an App Bazaar flow with a VoiceBot applet (no ExoML, works on any ExoPhone): "
                + "exotel voicebot attach --number " + number.trim() + " --app-id <flow id>, or Dashboard > ExoPhones.");
            ArrayNode b = out.putArray("exomlSteps");
            b.add("1. Ask Exotel support to attach " + number.trim() + " (Legs inbound) to your public HTTPS event endpoint "
                + "(there is no public API for this step). Ask for Basic auth on it.");
            b.add("2. Your endpoint acks every POST with 200 within 1 s, then acts on the event (next step goes to POST "
                + DEFAULT_CPAAS + "/v2/accounts/{account_sid}/legs/{leg_sid}/actions):");
            b.add("   on leg_incoming: " + "<Flow><Answer/></Flow>");
            b.add("   on leg_answered: " + streamXml(url));
            b.add("3. Ready-made endpoint code: exotel_events_endpoint_guide, or run it from a laptop with the CLI: "
                + "exotel voice-ai inbound --number " + number.trim() + " --stream-url <bot wss> --events https://<public>/ --listen 8080");
            return om.writeValueAsString(out);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "exotel_legs_inbound_stop",
          description = "Stops this MCP server answering incoming calls for a registration made with exotel_legs_inbound_bot mode=hosted. "
              + "Ask Exotel support to detach the number too, or calls to it will not be answered.")
    public String inboundStop(@ToolParam(description = "registrationId from exotel_legs_inbound_bot") String registrationId) {
        try {
            Cpaas c = cpaas();
            boolean removed = inboundRegistry.stop(registrationId, c.accountSid());
            return removed ? "{\"stopped\":true}" : "{\"stopped\":false,\"note\":\"No active registration with that id on this account.\"}";
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ======================== OUTBOUND BOT CALL ========================

    /**
     * Dial, wait for leg_answered, send StartStream at once (no greeting: the bot speaks first), wait for stream_started. With a webhook
     * registration the registry sends the actions the moment leg_answered arrives; the history is only read for the report.
     */
    ObjectNode runBotCall(Api api, ObjectNode createBody, String streamXml, long budgetMs, long pollMs,
                          LegsInboundRegistry.Registration webhook) throws Exception {
        long start = System.currentTimeMillis();
        ObjectNode out = om.createObjectNode();
        Resp created = api.call("POST", "/legs", om.writeValueAsString(createBody));
        if (!created.ok()) {
            out.put("ok", false);
            out.put("error", failure("Dial", created));
            return out;
        }
        String leg = data(created).path("leg_sid").asText("");
        out.put("legSid", leg);
        ArrayNode timeline = out.putArray("timeline");
        Set<String> seen = new LinkedHashSet<>();
        Instant answeredAt = null;
        Instant streamAt = null;
        boolean sent = false;
        Instant startStreamAt = null;
        long streamDeadline = Long.MAX_VALUE;
        while (System.currentTimeMillis() - start < budgetMs && System.currentTimeMillis() < streamDeadline) {
            Resp ev = api.call("GET", "/legs/" + leg + "/external_events", null);
            for (JsonNode e : events(ev)) {
                String name = e.path("event_info").path("event_name").asText("");
                if (name.isEmpty() || !seen.add(name)) continue;
                ObjectNode row = timeline.addObject();
                row.put("event", name);
                row.put("at", e.path("date_created").asText(""));
                if ("leg_answered".equals(name)) answeredAt = instant(e);
                if ("stream_started".equals(name)) streamAt = instant(e);
            }
            if (seen.contains("leg_answered") && !sent && webhook != null) {
                sent = true;
                timeline.addObject().put("action", "StartStream sent by this server on the leg_answered webhook");
                streamDeadline = System.currentTimeMillis() + 12_000;
            }
            if (seen.contains("leg_answered") && !sent) {
                sent = true;
                Resp s = api.call("POST", "/legs/" + leg + "/actions", actionBody(streamXml, "start-stream"));
                timeline.addObject().put("action", "StartStream " + status(s));
                startStreamAt = exotelTime(data(s).path("created_at").asText(""));
                if (!s.ok()) {
                    out.put("error", failure("StartStream", s));
                    break;
                }
                streamDeadline = System.currentTimeMillis() + 12_000;
            }
            if (seen.contains("stream_started") || ENDED.stream().anyMatch(seen::contains)) break;
            if (pollMs > 0) Thread.sleep(pollMs);
        }
        boolean answered = seen.contains("leg_answered");
        boolean streaming = seen.contains("stream_started");
        out.put("answered", answered);
        out.put("botConnected", streaming);
        if (webhook != null && webhook.startStreamAt().get(leg) != null) startStreamAt = webhook.startStreamAt().get(leg);
        out.put("mode", webhook != null ? "webhook" : "polling");
        if (answeredAt != null && startStreamAt != null) {
            long ms = Duration.between(answeredAt, startStreamAt).toMillis();
            out.put("answerToStartStreamMs", ms);
            if (ms > 700 && webhook == null)
                out.put("latencyNote", "Polling sees leg_answered 1-5 s late (the event history lags). For StartStream within 700 ms of "
                    + "the answer, the MCP server must receive the event itself: exotel.legs.webhooks.enabled=true on a public server.");
        }
        if (answeredAt != null && streamAt != null) out.put("answerToBotMs", Duration.between(answeredAt, streamAt).toMillis());
        out.put("ok", streaming);
        if (!answered && ENDED.stream().noneMatch(seen::contains))
            out.put("note", "Still not answered; the leg rings until its ring timeout. Follow it with exotel_legs_get legSid=" + leg + ".");
        else if (!answered) out.put("note", "The call ended without an answer.");
        else if (!streaming && !out.has("error")) out.put("note", "Answered, but the bot did not connect. Check the bot URL; see exotel_legs_get legSid=" + leg + ".");
        else if (streaming) out.put("next", "The bot is on the call. Follow it with exotel_legs_get legSid=" + leg + "; end it with exotel_legs_action legSid=" + leg + " exoml=<Flow><Hangup/></Flow>.");
        return out;
    }

    // ======================== HELPERS ========================

    record Cpaas(String base, String accountSid, String basic) {}

    record Resp(int status, String body, String requestId) {
        boolean ok() { return status >= 200 && status < 300; }
    }

    /** One HTTP call to the Legs API under /v2/accounts/{sid}. Swappable in tests. */
    @FunctionalInterface
    interface Api {
        Resp call(String method, String path, String json) throws Exception;
    }

    Api api(Cpaas c) {
        return (method, path, json) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(c.base() + "/v2/accounts/" + c.accountSid() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + c.basic())
                .header("Accept", "application/json");
            if (json != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
            else b.method(method, HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String body = r.body() == null ? "" : r.body();
            String rid = "";
            try { rid = om.readTree(body).path("request_id").asText(""); } catch (Exception ignored) { }
            if (r.statusCode() >= 400) logger.info("Legs API {} {} -> {} (request_id {})", method, path.replaceAll("/[A-Za-z0-9_-]{20,}", "/…"), r.statusCode(), rid);
            return new Resp(r.statusCode(), body, rid);
        };
    }

    /** Legs credentials: the CPaaS token + account SID, or the Calls API key/token. Mumbai only. */
    Cpaas cpaas() {
        AuthCredentials c = AuthContext.current();
        String basic;
        String sid;
        if (notBlank(c.getToken()) && notBlank(c.getAccountSid())) {
            basic = c.getToken().trim();
            sid = c.getAccountSid().trim();
        } else if (c.hasCallsCredentials()) {
            basic = Base64.getEncoder().encodeToString((c.getCallsApiKey() + ":" + c.getCallsApiToken()).getBytes(StandardCharsets.UTF_8));
            sid = c.getCallsAccountId().trim();
        } else {
            throw new IllegalStateException("Legs needs your Exotel API credentials (token and account_sid in the Authorization header). "
                + "Run exotel_setup_guide.");
        }
        if (!sid.matches("^[A-Za-z0-9_-]{3,64}$")) throw new IllegalArgumentException("account_sid has invalid characters");
        String domain = notBlank(c.getApiDomain()) ? c.getApiDomain() : c.getCallsBaseUrl();
        return new Cpaas(cpaasBase(domain, cpaasOverride), sid, basic);
    }

    /** cpaas-api.in.exotel.com for Mumbai accounts; Legs does not run in Singapore. An override must be an Exotel https host. */
    static String cpaasBase(String apiDomain, String override) {
        if (notBlank(override)) {
            URI u = URI.create(override.trim());
            if (!"https".equals(u.getScheme()) || u.getHost() == null || !EXOTEL_HOST.matcher(u.getHost()).matches())
                throw new IllegalArgumentException("exotel.legs.cpaas-base-url must be an https://*.exotel.com URL");
            return trimSlash(override.trim());
        }
        if (apiDomain == null || apiDomain.isBlank() || apiDomain.contains("api.in.exotel.com")) return DEFAULT_CPAAS;
        throw new IllegalStateException("Legs, Bridges and ExoML run only on Mumbai (India) accounts (api.in.exotel.com). "
            + "This login is for " + apiDomain + ". Use Connect Voice AI (exotel_voicebot_place_call) instead.");
    }

    /** Checks ExoML before it is sent: well-formed XML, no DTD, root Flow, only known actions. Returns the action names. */
    static List<String> validateExoml(String xml, boolean bridge) {
        if (xml == null || xml.isBlank()) throw new IllegalArgumentException("exoml is required, e.g. <Flow><Say>Hello</Say></Flow>");
        if (xml.length() > MAX_EXOML) throw new IllegalArgumentException("exoml is too long (max " + MAX_EXOML + " characters)");
        Element root;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            var builder = f.newDocumentBuilder();
            builder.setErrorHandler(null);
            root = builder.parse(new ByteArrayInputStream(xml.trim().getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        } catch (Exception e) {
            throw new IllegalArgumentException("ExoML is not valid XML (" + e.getMessage() + "). Nothing was sent.");
        }
        if (!"Flow".equals(root.getTagName())) throw new IllegalArgumentException("ExoML must start with <Flow>. Nothing was sent.");
        List<String> actions = new ArrayList<>();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE && n.getTextContent().isBlank()) continue;
            if (n.getNodeType() == Node.COMMENT_NODE) continue;
            if (n.getNodeType() != Node.ELEMENT_NODE) throw new IllegalArgumentException("Put text inside an action, e.g. <Say>…</Say>. Nothing was sent.");
            String name = n.getNodeName();
            Set<String> allowed = bridge ? BRIDGE_ACTIONS : LEG_ACTIONS;
            if (!allowed.contains(name))
                throw new IllegalArgumentException("<" + name + "> is not a " + (bridge ? "bridge" : "leg") + " action. Use one of: "
                    + String.join(", ", new java.util.TreeSet<>(allowed)) + ". Nothing was sent.");
            if ("StartStream".equals(name) && !((Element) n).getAttribute("streamUrl").startsWith("wss://"))
                throw new IllegalArgumentException("StartStream needs streamUrl=\"wss://…\". Nothing was sent.");
            actions.add(name);
        }
        if (actions.isEmpty()) throw new IllegalArgumentException("<Flow> has no actions. Nothing was sent.");
        return actions;
    }

    static String streamXml(String wss) {
        return "<Flow><StartStream streamType=\"bidirectional\" streamUrl=\"" + xmlEscape(wss) + "\"/></Flow>";
    }


    static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private String resolveStream(String botId, String streamUrl) {
        if (notBlank(botId) == notBlank(streamUrl)) throw new IllegalArgumentException("give botId (an Exotel VoiceBot) or streamUrl (wss://…), one of them");
        if (notBlank(streamUrl)) {
            if (!WSS.matcher(streamUrl.trim()).matches()) throw new IllegalArgumentException("streamUrl must be a wss:// URL (call audio must be encrypted)");
            return streamUrl.trim();
        }
        return voiceBotService.botStreamUrl(botId.trim());
    }

    private ObjectNode legBody(String to, String exophone, String eventsUrl, String customParam) {
        if (to == null || !CONTACT.matcher(to.trim()).matches()) throw new IllegalArgumentException("to must be a phone number (+919876543210) or sip:user@host");
        if (exophone == null || !NUMBER.matcher(exophone.trim()).matches()) throw new IllegalArgumentException("exophone must be your Legs-enabled number, e.g. 02048566332");
        ObjectNode b = om.createObjectNode();
        b.put("contact_uri", to.trim());
        b.put("exophone", exophone.trim());
        b.put("leg_event_endpoint", eventsUrl(eventsUrl));
        b.put("network_type", to.trim().startsWith("sip") ? "voip" : "pstn");
        b.put("custom_param", notBlank(customParam) ? customParam.trim().substring(0, Math.min(customParam.trim().length(), 256)) : "exotel-mcp");
        return b;
    }

    /** The caller's endpoint, or this server's ack-only endpoint when the caller follows the leg by polling. */
    String eventsUrl(String eventsUrl) {
        if (!notBlank(eventsUrl)) return trimSlash(publicBaseUrl) + "/legs/events";
        String u = eventsUrl.trim();
        if (!u.matches("^https://[^\\s<>\"']{4,1000}$")) throw new IllegalArgumentException("eventsUrl must be a public https:// URL");
        return u;
    }

    private String actionBody(String exoml, String tag) throws Exception {
        ObjectNode b = om.createObjectNode();
        b.put("action_custom_param", notBlank(tag) ? tag.trim().substring(0, Math.min(tag.trim().length(), 256)) : "exotel-mcp");
        b.put("exoml", exoml);
        return om.writeValueAsString(b);
    }

    private JsonNode data(Resp r) {
        try {
            JsonNode root = om.readTree(r.body());
            JsonNode d = root.path("response").path("data");
            return d.isMissingNode() ? root : d;
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }

    private Iterable<JsonNode> events(Resp r) {
        if (r == null || !r.ok()) return List.of(); // 404 for about a second after the dial: not there yet
        JsonNode list = data(r).path("external_events");
        return list.isArray() ? list : List.of();
    }

    /** Exotel action timestamps look like "2026-11-11 05:04:24.292345218 +0000 UTC". */
    static Instant exotelTime(String t) {
        try {
            return Instant.parse(t.trim().replaceFirst("^(\\S+) (\\S+) \\+0000 UTC$", "$1T$2Z"));
        } catch (Exception x) {
            return null;
        }
    }

    private static Instant instant(JsonNode e) {
        try { return Instant.parse(e.path("date_created").asText()); } catch (Exception x) { return null; }
    }

    private String failure(String what, Resp r) {
        String msg = "";
        try {
            JsonNode ed = om.readTree(r.body()).path("response").path("error_data");
            msg = (ed.path("message").asText("") + " " + ed.path("description").asText("")).trim();
        } catch (Exception ignored) { }
        String hint = r.status() == 401 || r.status() == 403
            ? " This API key has no Legs access, or the number is not Legs-enabled: ask Exotel support for both."
            : r.status() == 404 ? " Not found: check the SID (legs and bridges are kept for a limited time)." : "";
        return "Error: " + what + " failed (HTTP " + r.status() + (msg.isBlank() ? "" : ", " + msg) + ")." + hint
            + (r.requestId().isBlank() ? "" : " request_id " + r.requestId());
    }

    private static String status(Resp r) { return r.ok() ? "sent" : "failed (HTTP " + r.status() + ")"; }

    private static String sid(String s, String name) {
        if (s == null || !SID.matcher(s.trim()).matches()) throw new IllegalArgumentException(name + " is missing or has invalid characters");
        return s.trim();
    }

    static int clamp(Integer v, int min, int max, int dflt) {
        if (v == null) return dflt;
        return Math.max(min, Math.min(max, v));
    }

    static String mask(String n) {
        if (n == null) return "";
        String d = n.replaceAll("\\D", "");
        return d.length() <= 4 ? "****" : "****" + d.substring(d.length() - 4);
    }

    private static String trimSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }

    static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
