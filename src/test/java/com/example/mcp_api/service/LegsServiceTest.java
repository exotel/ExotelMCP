package com.example.mcp_api.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class LegsServiceTest {

    // ---------------------------------------------------------------- ExoML checks

    @Test
    void validExomlIsAcceptedAndItsActionsListed() {
        assertEquals(List.of("Say", "StartStream"), LegsService.validateExoml(
            "<Flow><Say>Hi</Say><StartStream streamType=\"bidirectional\" streamUrl=\"wss://bot/x?a=1&amp;b=2\"/></Flow>", false));
        assertEquals(List.of("Hold"), LegsService.validateExoml("<Flow><Hold><StartPlay>https://x/a.wav</StartPlay></Hold></Flow>", false));
    }

    @Test
    void badExomlIsRejectedBeforeAnythingIsSent() {
        assertThrows(IllegalArgumentException.class, () -> LegsService.validateExoml("<Response><Say>x</Say></Response>", false));
        assertThrows(IllegalArgumentException.class, () -> LegsService.validateExoml("<Flow><Speak>x</Speak></Flow>", false));
        assertThrows(IllegalArgumentException.class, () -> LegsService.validateExoml("<Flow></Flow>", false));
        assertThrows(IllegalArgumentException.class, () -> LegsService.validateExoml("<Flow><Say>x</Flow>", false));
        assertThrows(IllegalArgumentException.class, () -> LegsService.validateExoml("<Flow><StartStream streamUrl=\"ws://bot\"/></Flow>", false));
        assertThrows(IllegalArgumentException.class, () -> LegsService.validateExoml("<Flow><Dial contactUri=\"+91\" exophone=\"0\"/></Flow>", true), "Dial is not a bridge action");
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE f [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><Flow><Say>&x;</Say></Flow>";
        assertThrows(IllegalArgumentException.class, () -> LegsService.validateExoml(xxe, false), "DTDs and external entities are refused");
    }

    @Test
    void generatedExomlEscapesUrlsAndText() {
        String x = LegsService.streamXml("wss://bot.example/ws?a=1&b=\"2\"");
        assertTrue(x.contains("a=1&amp;b=&quot;2&quot;"));
        assertEquals(List.of("StartStream"), LegsService.validateExoml(x, false));
    }

    // ---------------------------------------------------------------- host

    @Test
    void legsRunOnlyOnTheMumbaiHost() {
        assertEquals("https://cpaas-api.in.exotel.com", LegsService.cpaasBase("https://api.in.exotel.com"));
        assertEquals("https://cpaas-api.in.exotel.com", LegsService.cpaasBase(null));
        assertThrows(IllegalStateException.class, () -> LegsService.cpaasBase("https://api.exotel.com"));
    }

    // ---------------------------------------------------------------- outbound bot call (poll-driven)

    /** Fake Legs API: plays back the event history step by step, records every request. */
    static class FakeLegs implements LegsService.Api {
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<String> history = new ArrayList<>();
        final List<String[]> script; // events that appear, one per poll
        int poll = 0;

        FakeLegs(List<String[]> script) { this.script = script; }

        @Override
        public LegsService.Resp call(String method, String path, String json) {
            requests.add(method + " " + path + (json != null && json.contains("exoml") ? " " + json.replaceAll(".*\"exoml\":\"(<Flow><[A-Za-z]+).*", "$1") : ""));
            if (method.equals("POST") && path.equals("/legs"))
                return new LegsService.Resp(202, "{\"response\":{\"data\":{\"leg_sid\":\"LEG123456789\"}}}", "r1");
            if (method.equals("POST")) return new LegsService.Resp(202, "{\"response\":{\"data\":{\"action_sid\":\"A1\",\"created_at\":\"2026-11-11 05:04:24.292345218 +0000 UTC\"}}}", "r2");
            if (poll == 0) { poll++; return new LegsService.Resp(404, "{}", "r3"); } // history not there yet
            if (poll - 1 < script.size()) for (String ev : script.get(poll - 1)) history.add(ev);
            poll++;
            StringBuilder b = new StringBuilder("{\"response\":{\"data\":{\"external_events\":[");
            for (int i = 0; i < history.size(); i++) {
                String[] p = history.get(i).split("@");
                if (i > 0) b.append(',');
                b.append("{\"date_created\":\"").append(p[1]).append("\",\"event_info\":{\"event_name\":\"").append(p[0]).append("\"}}");
            }
            return new LegsService.Resp(200, b.append("]}}}").toString(), "r4");
        }

        long actionsBefore(String marker) {
            int idx = -1;
            for (int i = 0; i < requests.size(); i++) if (requests.get(i).contains(marker)) { idx = i; break; }
            return requests.subList(0, Math.max(idx, 0)).stream().filter(r -> r.contains("/actions")).count();
        }
    }

    private static ObjectNode body() {
        ObjectNode b = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        b.put("contact_uri", "+919999999999");
        return b;
    }

    @Test
    void botIsStreamedOnlyAfterTheAnswerAndTheCallReportsTheLatency() throws Exception {
        FakeLegs api = new FakeLegs(List.of(
            new String[]{"channel_created@2026-11-11T05:04:17.903Z", "leg_ringing@2026-11-11T05:04:20.598Z"},
            new String[]{},
            new String[]{"leg_answered@2026-11-11T05:04:23.687Z"},
            new String[]{"stream_started@2026-11-11T05:04:25.781Z"}));
        ObjectNode out = new LegsService().runBotCall(api, body(), LegsService.streamXml("wss://bot/x"), 5000, 0);
        assertTrue(out.path("ok").asBoolean(), out.toString());
        assertTrue(out.path("botConnected").asBoolean());
        assertEquals(2094, out.path("answerToBotMs").asLong());
        assertEquals(605, out.path("answerToStartStreamMs").asLong(), "measured with Exotel's own timestamps");
        assertEquals("LEG123456789", out.path("legSid").asText());
        long streams = api.requests.stream().filter(r -> r.contains("<Flow><StartStream")).count();
        assertEquals(1, streams, "StartStream is sent exactly once: " + api.requests);
        int answeredPoll = 4; // 404, ringing, nothing, answered
        int firstAction = 0;
        for (int i = 0, gets = 0; i < api.requests.size(); i++) {
            if (api.requests.get(i).startsWith("GET")) gets++;
            if (api.requests.get(i).contains("/actions")) { firstAction = gets; break; }
        }
        assertEquals(answeredPoll, firstAction, "no action while ringing: " + api.requests);
    }


    @Test
    void exotelActionTimestampsAreParsed() {
        assertEquals(java.time.Instant.parse("2026-11-11T05:04:24.292345218Z"), LegsService.exotelTime("2026-11-11 05:04:24.292345218 +0000 UTC"));
        assertNull(LegsService.exotelTime("garbage"));
    }


    @Test
    void anUnansweredCallSendsNoActions() throws Exception {
        FakeLegs api = new FakeLegs(List.of(
            new String[]{"leg_ringing@2026-11-11T05:00:00Z"},
            new String[]{"leg_terminated@2026-11-11T05:00:30Z"}));
        ObjectNode out = new LegsService().runBotCall(api, body(), LegsService.streamXml("wss://bot/x"), 5000, 0);
        assertFalse(out.path("ok").asBoolean());
        assertFalse(out.path("answered").asBoolean());
        assertEquals("The call ended without an answer.", out.path("note").asText());
        assertEquals(0, api.requests.stream().filter(r -> r.contains("/actions")).count());
    }

    @Test
    void aFailedDialStopsAtOnce() throws Exception {
        LegsService.Api api = (m, p, j) -> new LegsService.Resp(401, "{\"response\":{\"error_data\":{\"message\":\"Unauthorized\"}}}", "rq");
        ObjectNode out = new LegsService().runBotCall(api, body(), LegsService.streamXml("wss://bot/x"), 5000, 0);
        assertFalse(out.path("ok").asBoolean());
        assertTrue(out.path("error").asText().contains("no Legs access"), out.toString());
    }

    // ---------------------------------------------------------------- hosted inbound




    @Test
    void phoneNumbersAreMaskedInLogs() {
        assertEquals("****3210", LegsService.mask("+919876543210"));
    }
}
