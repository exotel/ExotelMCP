package com.example.mcp_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Hosted inbound answering (routes in StreamableHttpConfig: POST /legs/inbound/{id}) for exotel_legs_inbound_bot mode=hosted and outbound bot calls (off unless exotel.legs.webhooks.enabled=true).
 * Exotel posts the number's leg events to /legs/inbound/{id}; this answers the call and starts the bot stream with the
 * credentials given at registration. Everything is in memory only: nothing is written to disk, registrations expire
 * (at most 7 days) and a restart drops them. The id (256-bit random) in the URL is the secret that protects the endpoint.
 */
@Component
public class LegsInboundRegistry {

    private static final Logger logger = LoggerFactory.getLogger(LegsInboundRegistry.class);
    static final int MAX_REGISTRATIONS = 500;
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    record Registration(String id, String accountSid, String number, LegsService.Api api, String streamXml,
                        Instant expiresAt, Set<String> seen, Map<String, Instant> startStreamAt) {}

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    private final ObjectMapper om = new ObjectMapper();
    /** Actions run off the request thread so Exotel gets its 200 at once; bounded, drops when full. */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 8, 60, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1000), new ThreadPoolExecutor.DiscardPolicy());

    Registration register(LegsService.Api api, String accountSid, String number, String streamXml, Duration ttl) {
        purgeExpired();
        if (registrations.size() >= MAX_REGISTRATIONS) throw new IllegalStateException("Too many active inbound registrations on this server. Stop unused ones first.");
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        Set<String> seen = Collections.newSetFromMap(Collections.synchronizedMap(new LinkedHashMap<>() {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 2000; }
        }));
        Registration r = new Registration(id, accountSid, number, api, streamXml, Instant.now().plus(ttl), seen, new ConcurrentHashMap<>());
        registrations.put(id, r);
        logger.info("Legs webhook registered for account {} until {}", accountSid, r.expiresAt());
        return r;
    }

    boolean stop(String id, String accountSid) {
        if (id == null || !ID.matcher(id).matches()) return false;
        Registration r = registrations.get(id);
        if (r == null || !r.accountSid().equals(accountSid)) return false;
        return registrations.remove(id) != null;
    }

    /** Handles one event POST. Returns the HTTP status for Exotel: 200 (acked), 404 (unknown or expired id). */
    public int handle(String id, String body) {
        if (id == null || !ID.matcher(id).matches()) return 404;
        Registration r = registrations.get(id);
        if (r == null) return 404;
        if (Instant.now().isAfter(r.expiresAt())) {
            registrations.remove(id);
            return 404;
        }
        JsonNode e;
        try {
            e = om.readTree(body);
        } catch (Exception ex) {
            return 200; // ack junk; never retry it
        }
        if (e == null || !e.isObject()) return 200;
        String name = e.path("event_name").asText("");
        JsonNode d = e.path("data");
        String leg = d.path("leg_sid").asText("");
        String account = d.path("account_sid").asText("");
        String eventSid = e.path("event_sid").asText(name + ":" + leg);
        if (leg.isEmpty() || (!account.isEmpty() && !account.equals(r.accountSid()))) return 200;
        if (!r.seen().add(eventSid)) return 200; // duplicate delivery
        if ("leg_incoming".equals(name)) executor.execute(() -> act(r, leg, "<Flow><Answer/></Flow>", "answer"));
        if ("leg_answered".equals(name) && r.seen().add("answered:" + leg)) executor.execute(() -> act(r, leg, r.streamXml(), "start-stream"));
        return 200;
    }

    private void act(Registration r, String leg, String exoml, String tag) {
        try {
            ObjectNode b = om.createObjectNode();
            b.put("action_custom_param", tag);
            b.put("exoml", exoml);
            LegsService.Resp res = r.api().call("POST", "/legs/" + leg + "/actions", om.writeValueAsString(b));
            if (res.ok() && "start-stream".equals(tag)) {
                Instant at = LegsService.exotelTime(om.readTree(res.body()).path("response").path("data").path("created_at").asText(""));
                if (at != null && r.startStreamAt().size() < 1000) r.startStreamAt().put(leg, at);
            }
            if (!res.ok()) logger.warn("Hosted inbound {} failed: HTTP {} (request_id {})", tag, res.status(), res.requestId());
        } catch (Exception ex) {
            logger.warn("Hosted inbound {} failed: {}", tag, ex.toString());
        }
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        registrations.values().removeIf(r -> now.isAfter(r.expiresAt()));
    }

    int size() { return registrations.size(); }
}
