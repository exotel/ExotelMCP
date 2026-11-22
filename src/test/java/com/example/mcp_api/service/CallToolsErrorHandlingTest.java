package com.example.mcp_api.service;

import com.example.mcp_api.auth.AuthCredentials;
import com.example.mcp_api.dto.AuthData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** VO-6235 review follow-ups: Exotel errors are reported as errors, never as "no calls". */
class CallToolsErrorHandlingTest {

    private static final String HEADER = "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\"}";

    private RuntimeException failure;
    private String body;
    private ExotelService service;

    @BeforeEach
    void bind() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", HEADER);
        request.setAttribute(AuthCredentials.REQUEST_ATTRIBUTE, AuthCredentials.parse(HEADER));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        service = new ExotelService() {
            @Override
            String makeGetRequest(String url, AuthData authData) {
                if (failure != null) throw failure;
                return body;
            }
        };
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void unauthorizedIsAnErrorNotAMissingCall() {
        failure = new IllegalArgumentException("Client error: HTTP 401 Unauthorized for GET https://api.exotel.com/v1/Accounts/acme1/Calls/CA1.json");
        String out = service.getCallDetails("CA1");
        assertTrue(out.startsWith("Error:") && out.contains("401"), out);
    }

    @Test
    void notFoundIsAMissingCall() {
        failure = new IllegalArgumentException("Client error: HTTP 404 Not Found for GET https://api.exotel.com/v1/Accounts/acme1/Calls/CA1.json");
        assertTrue(service.getCallDetails("CA1").startsWith("No record found for CallSid"));
    }

    @Test
    void responseWithoutCallsListIsAnError() {
        for (String b : new String[] {"{}", "{\"RestException\":{\"Status\":403}}", "{\"Calls\":{}}"}) {
            body = b;
            Object data = service.getVoiceCallCallbacks("9999999999").get("status_data");
            assertTrue(String.valueOf(data).startsWith("Not found because"), b + " -> " + data);
            assertTrue(service.searchVoiceCallbacksByNumber("9999999999").startsWith("Search Error"), b);
        }
    }

    @Test
    void serverErrorAndNonJsonAreErrors() {
        failure = new RuntimeException("Server error: HTTP 503 Service Unavailable for GET https://api.exotel.com/v1/Accounts/acme1/Calls.json");
        assertTrue(String.valueOf(service.getVoiceCallCallbacks("9999999999").get("status_data")).startsWith("Not found because"));
        failure = null;
        body = "<html>oops</html>";
        assertTrue(String.valueOf(service.getCallFlowCallbacks("9999999999").get("status_data")).startsWith("Not found because"));
    }

    @Test
    void personalizedBulkSmsKeysAreEncoded() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("Message[0][To]", "+919999999999");
        data.put("Message[0][Body]", "Hi & bye");
        assertEquals("Message%5B0%5D%5BTo%5D=%2B919999999999&Message%5B0%5D%5BBody%5D=Hi+%26+bye", ExotelService.buildFormData(data));
    }
}
