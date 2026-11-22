package com.example.mcp_api.service;

import com.example.mcp_api.auth.AuthCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MSG-2807: tool calls read credentials only from the verified request (AuthContext). No HttpSession is
 * created, and no caller's Authorization header outlives its request.
 */
class NoStoredAuthHeaderTest {

    private static final String HEADER_A = "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\"}";
    private static final String HEADER_B = "{\"token\":\"b3RoZXI6dG9r\",\"account_sid\":\"acmeb\"}";
    private static final String CALLS_A = "https://api.exotel.com/v1/Accounts/acme1/Calls";

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static MockHttpServletRequest bind(String rawHeader, String verifiedHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        if (rawHeader != null) request.addHeader("Authorization", rawHeader);
        if (verifiedHeader != null) {
            request.setAttribute(AuthCredentials.REQUEST_ATTRIBUTE, AuthCredentials.parse(verifiedHeader));
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    @Test
    void noProductionCodeCallsGetSession() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<Path> offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            String src = Files.readString(p);
                            return src.contains("getSession(") || src.contains("HttpSession");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }).toList();
            assertEquals(List.of(), offenders);
        }
    }

    @Test
    void exotelServiceKeepsNoAuthHeaderStore() {
        for (Field f : ExotelService.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())) continue;
            String name = f.getName().toLowerCase(Locale.ROOT);
            assertFalse(name.contains("auth") || name.contains("header") || name.contains("session"),
                    "ExotelService must not hold caller credentials in a field: " + f.getName());
        }
        for (Method m : ExotelService.class.getDeclaredMethods()) {
            assertFalse(m.getName().startsWith("setAuthHeader"), "method must not exist: " + m.getName());
        }
    }

    @Test
    void toolCallCreatesNoHttpSession() {
        MockHttpServletRequest request = bind(HEADER_A, HEADER_A);
        CallToolsTenantIsolationTest.StubbedExotelService service = new CallToolsTenantIsolationTest.StubbedExotelService();
        service.answers.put(CALLS_A + "/CA1.json", "{\"Call\":{\"Sid\":\"CA1\",\"Status\":\"completed\"}}");
        service.getCallDetails("CA1");
        assertNull(request.getSession(false), "a tool call must not create an HttpSession");
    }

    @Test
    void credentialsComeFromTheVerifiedRequestNotTheRawHeader() {
        bind(HEADER_B, HEADER_A);
        CallToolsTenantIsolationTest.StubbedExotelService service = new CallToolsTenantIsolationTest.StubbedExotelService();
        service.answers.put(CALLS_A + "/CA1.json", "{\"Call\":{\"Sid\":\"CA1\",\"Status\":\"completed\"}}");
        service.getCallDetails("CA1");
        assertEquals(List.of(CALLS_A + "/CA1.json"), service.gets);
    }

    @Test
    void credentialValuesAreNeverLogged() {
        String secretHeader = "{\"token\":\"a2V5OnRvaw==\",\"account_sid\":\"acme1\","
                + "\"calls_api_key\":\"ck\",\"calls_api_token\":\"SECRET-CALLS-TOKEN\",\"calls_account_id\":\"acct9\","
                + "\"admin_username\":\"adm\",\"admin_password\":\"SECRET-ADMIN-PW\"}";
        ch.qos.logback.classic.Logger log =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ExotelService.class);
        ch.qos.logback.classic.Level before = log.getLevel();
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        log.addAppender(appender);
        log.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            bind(secretHeader, secretHeader);
            CallToolsTenantIsolationTest.StubbedExotelService service = new CallToolsTenantIsolationTest.StubbedExotelService();
            service.answers.put(CALLS_A + "/CA1.json", "{\"Call\":{\"Sid\":\"CA1\",\"Status\":\"completed\"}}");
            service.getCallDetails("CA1");
            assertFalse(appender.list.isEmpty(), "DEBUG logging should have produced events");
            for (var event : appender.list) {
                String line = event.getFormattedMessage();
                assertFalse(line.contains("SECRET-CALLS-TOKEN") || line.contains("SECRET-ADMIN-PW")
                        || line.contains("a2V5OnRvaw=="), "credential value logged: " + line);
            }
        } finally {
            log.detachAppender(appender);
            log.setLevel(before);
        }
    }

    @Test
    void anEarlierCallersHeaderIsNeverReused() {
        CallToolsTenantIsolationTest.StubbedExotelService service = new CallToolsTenantIsolationTest.StubbedExotelService();
        service.answers.put(CALLS_A + "/CA1.json", "{\"Call\":{\"Sid\":\"CA1\",\"Status\":\"completed\"}}");

        bind(HEADER_A, HEADER_A);
        service.getCallDetails("CA1");
        assertEquals(1, service.gets.size());

        // Same thread, no request context: must not fall back to caller A's header.
        RequestContextHolder.resetRequestAttributes();
        String out = service.getCallDetails("CA1");
        assertEquals(1, service.gets.size(), "no Exotel call without the caller's own credentials");
        assertTrue(out.contains("Authorization header is required"), out);

        // A request that was not verified (no credentials attribute) gets nothing either.
        bind(HEADER_A, null);
        out = service.getCallDetails("CA1");
        assertEquals(1, service.gets.size());
        assertTrue(out.contains("Authorization header is required"), out);
    }
}
