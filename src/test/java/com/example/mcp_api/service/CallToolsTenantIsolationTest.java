package com.example.mcp_api.service;

import com.example.mcp_api.auth.AuthCredentials;
import com.example.mcp_api.dto.AuthData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** VO-6235: call-status tools answer live from the caller's own Exotel account; nothing is stored or called back. */
class CallToolsTenantIsolationTest {

    private static final String HEADER = "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\"}";
    private static final String CALLS = "https://api.exotel.com/v1/Accounts/acme1/Calls";

    /** Stubs Exotel: records every request and answers GETs from a map keyed by URL. */
    static class StubbedExotelService extends ExotelService {
        final List<String> gets = new ArrayList<>();
        final List<Map<String, ?>> posts = new ArrayList<>();
        final Map<String, String> answers = new LinkedHashMap<>();

        @Override
        String makeGetRequest(String url, AuthData authData) {
            gets.add(url);
            String body = answers.get(url);
            if (body == null) throw new IllegalArgumentException("Client error: HTTP 404 Not Found for GET " + url);
            return body;
        }

        @Override
        String makeHttpRequest(String url, Map<String, ?> data, AuthData authData) {
            posts.add(data);
            return "{\"Call\":{\"Sid\":\"CA9\"}}";
        }
    }

    private StubbedExotelService service;

    @BeforeEach
    void bind() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", HEADER);
        request.setAttribute(AuthCredentials.REQUEST_ATTRIBUTE, AuthCredentials.parse(HEADER));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        service = new StubbedExotelService();
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static String call(String sid, String from, String to, String status) {
        return "{\"Sid\":\"" + sid + "\",\"From\":\"" + from + "\",\"To\":\"" + to + "\",\"Status\":\"" + status
                + "\",\"Duration\":\"17\",\"DateUpdated\":\"2026-11-22 10:00:00\",\"RecordingUrl\":\"https://rec/" + sid + "\"}";
    }

    @Test
    void singleCallDetailsReadsTheCallersOwnAccountLive() {
        service.answers.put(CALLS + "/CA123.json", "{\"Call\":" + call("CA123", "09999999999", "08888888888", "completed") + "}");
        String out = service.getCallDetails("CA123");
        assertEquals(List.of(CALLS + "/CA123.json"), service.gets);
        assertTrue(out.contains("Status: completed"), out);
        assertTrue(out.contains("From Number: 09999999999"), out);
        assertTrue(out.contains("To Number: 08888888888"), out);
        assertTrue(out.contains("Duration: 17"), out);
        assertTrue(out.contains("Recording URL: https://rec/CA123"), out);
    }

    @Test
    void singleCallDetailsOfAnotherAccountsCallFindsNothing() {
        String out = service.getCallDetails("CAforeign");
        assertEquals(List.of(CALLS + "/CAforeign.json"), service.gets);
        assertTrue(out.startsWith("No record found for CallSid"), out);
    }

    @Test
    void singleCallDetailsRefusesUnsafeSidsWithoutARequest() {
        for (String bad : new String[] {"../Calls", "CA1?x=1", "CA1#", "CA 1", "CA1/../x"}) {
            String out = service.getCallDetails(bad);
            assertTrue(out.startsWith("Error"), bad + " -> " + out);
        }
        assertTrue(service.gets.isEmpty(), service.gets.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void statusCallbacksQueryToAndFromLiveAndMergeBySid() {
        service.answers.put(CALLS + ".json?To=09999999999",
                "{\"Calls\":[" + call("CA1", "08888888888", "09999999999", "completed") + "," + call("CA2", "09999999999", "09999999999", "busy") + "]}");
        service.answers.put(CALLS + ".json?From=09999999999",
                "{\"Calls\":[" + call("CA2", "09999999999", "09999999999", "busy") + "," + call("CA3", "09999999999", "07777777777", "no-answer") + "]}");
        Map<String, Object> out = service.getVoiceCallCallbacks("9999999999");
        assertEquals(List.of(CALLS + ".json?To=09999999999", CALLS + ".json?From=09999999999"), service.gets);
        List<Map<String, Object>> calls = (List<Map<String, Object>>) out.get("status_data");
        assertEquals(List.of("CA1", "CA2", "CA3"), calls.stream().map(c -> c.get("Sid")).toList());
        Map<String, Object> info = (Map<String, Object>) out.get("search_info");
        assertFalse(info.containsKey("user_id"), info.toString());
        assertFalse(info.containsKey("sql_query"), info.toString());
        assertEquals(3, info.get("records_found"));
    }

    @Test
    void searchByNumberFormatsTheLiveCalls() {
        service.answers.put(CALLS + ".json?To=09999999999", "{\"Calls\":[" + call("CA1", "08888888888", "09999999999", "completed") + "]}");
        service.answers.put(CALLS + ".json?From=09999999999", "{\"Calls\":[]}");
        String out = service.searchVoiceCallbacksByNumber("9999999999");
        assertEquals(2, service.gets.size());
        assertTrue(out.contains("Found 1 records"), out);
        assertTrue(out.contains("CallSid: CA1"), out);
        assertTrue(out.contains("Status: completed"), out);
        assertTrue(out.contains("From: 08888888888"), out);
    }

    @Test
    void searchLabelsDirectionWhateverNumberFormatExotelReturns() {
        service.answers.put(CALLS + ".json?To=09999999999", "{\"Calls\":["
                + call("CA1", "+918888888888", "+919999999999", "completed") + ","
                + call("CA2", "08888888888", "09999999999", "completed") + "]}");
        service.answers.put(CALLS + ".json?From=09999999999", "{\"Calls\":["
                + call("CA3", "+919999999999", "+917777777777", "completed") + "]}");
        String out = service.searchVoiceCallbacksByNumber("+919999999999");
        assertTrue(out.contains("=== Record 1 (OUTGOING Call) ==="), out);
        assertTrue(out.contains("=== Record 2 (OUTGOING Call) ==="), out);
        assertTrue(out.contains("=== Record 3 (INCOMING Call) ==="), out);
    }

    @Test
    void numberSearchesRefuseUnsafeNumbersWithoutARequest() {
        for (String bad : new String[] {"9999999999&To=1", "../x", "99999 99999", "%26"}) {
            Map<String, Object> out = service.getVoiceCallCallbacks(bad);
            assertTrue(String.valueOf(out.get("status_data")).contains("must be a phone number"), bad + " -> " + out);
            assertTrue(service.searchVoiceCallbacksByNumber(bad).contains("must be a phone number"), bad);
            assertTrue(String.valueOf(service.getCallFlowCallbacks(bad).get("status_data")).contains("must be a phone number"), bad);
        }
        assertTrue(service.gets.isEmpty(), service.gets.toString());
    }

    @Test
    void callFlowCallbacksQueryFromLive() {
        service.answers.put(CALLS + ".json?From=09999999999", "{\"Calls\":[" + call("CA3", "09999999999", "07777777777", "completed") + "]}");
        Map<String, Object> out = service.getCallFlowCallbacks("9999999999");
        assertEquals(List.of(CALLS + ".json?From=09999999999"), service.gets);
        assertEquals(1, ((List<?>) out.get("status_data")).size());
    }

    @Test
    void callPlacingToolsSendNoStatusCallback() {
        service.sendVoiceCallToUser("9999999999");
        service.outgoingCallToConnectNumber("9999999999", "8888888888");
        service.connectNumberToCallFlow("12345", "9999999999");
        assertEquals(3, service.posts.size());
        for (Map<String, ?> data : service.posts) {
            assertFalse(data.containsKey("StatusCallback"), data.toString());
            assertFalse(data.containsKey("StatusCallbackContentType"), data.toString());
        }
    }

    @Test
    void formDataEncodesEveryKeyAndValue() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("Body", "a&StatusCallback=https://evil");
        data.put("To[0]", "+919999999999");
        data.put("Text", "50% off é");
        assertEquals("Body=a%26StatusCallback%3Dhttps%3A%2F%2Fevil&To%5B0%5D=%2B919999999999&Text=50%25+off+%C3%A9",
                ExotelService.buildFormData(data));
    }
}
