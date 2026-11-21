package com.example.mcp_api.service;

import com.example.mcp_api.auth.AuthCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;

/** SL-3868: tool parameters that are concatenated into URLs are validated before any outbound call. */
class ExotelServiceUrlParamsTest {

    private static final String HEADER = "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\"}";

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void bind(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", header);
        request.setAttribute(AuthCredentials.REQUEST_ATTRIBUTE, AuthCredentials.parse(header));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void metadataRejectsUnsafeNumbersBeforeAnyOutboundCall() {
        bind(HEADER);
        ExotelService service = new ExotelService();
        for (String bad : new String[] {"../../../Calls/connect.json?x=", "9999999999?x=1", "9999999999#", "99999 99999",
                "%2e%2e%2f", "9999999999%3Fx"}) {
            String result = service.getNumberMetadata(bad);
            assertTrue(result.startsWith("Not able to fetch number metadata due to number must be a phone number"), result);
        }
    }

    @Test
    void bulkCallDetailsRejectsUnsafeFromNumbers() {
        bind(HEADER);
        ExotelService service = new ExotelService();
        for (String bad : new String[] {"../x", "9999999999&To=1", "9999999999#", "99999 99999", "%26"}) {
            String result = service.getBulkCallDetails(bad);
            assertTrue(result.contains("Not able to fetch bulk call details due to fromNumber must be a phone number"), result);
        }
    }

    @Test
    void metadataCacheKeyDiffersByApiHost() {
        String a = ExotelService.metadataCacheKey("https://api.exotel.com", "acme1", "+919999999999");
        String b = ExotelService.metadataCacheKey("https://api.in.exotel.com", "acme1", "+919999999999");
        assertNotEquals(a, b);
        assertEquals(a, ExotelService.metadataCacheKey("https://api.exotel.com", "acme1", "+919999999999"));
    }

    @Test
    void tenantSpawnAndTerminateRejectUnsafeIds() {
        String header = "{\"tools_server_api_key\":\"k\",\"tools_server_tenant_id\":\"t1\"}";
        bind(header);
        VoiceBotToolsService tools = new VoiceBotToolsService();
        for (String bad : new String[] {"../x", "t1/../../y", "t1?x=1", "t1#", "t 1", "t1%2fspawn"}) {
            assertTrue(tools.spawnTenant(bad).contains("tenantId contains invalid characters"), bad);
            assertTrue(tools.terminateTenant(bad).contains("tenantId contains invalid characters"), bad);
        }
    }
}
