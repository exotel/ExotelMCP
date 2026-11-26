package com.example.mcp_api.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The builder tools' client must refuse every redirect, like the other upstream clients (PR #27 review, blocker 1). */
class VoiceBotBuilderRedirectTest {

    @Test
    void builderClientNeverFollowsARedirect() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger targetHits = new AtomicInteger();
        AtomicInteger status = new AtomicInteger();
        server.createContext("/target", ex -> { targetHits.incrementAndGet(); ex.sendResponseHeaders(200, -1); ex.close(); });
        server.createContext("/moved", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/target?token=SECRET");
            ex.sendResponseHeaders(status.get(), -1);
            ex.close();
        });
        server.start();
        try {
            RestTemplate client = VoiceBotBuilderTools.createRestTemplate(true);
            URI moved = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/moved");
            for (int code : new int[] {301, 302, 303, 307, 308}) {
                for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.GET}) {
                    status.set(code);
                    HttpEntity<String> body = method == HttpMethod.GET ? new HttpEntity<>((String) null) : new HttpEntity<>("{}");
                    ResourceAccessException e = assertThrows(ResourceAccessException.class,
                            () -> client.exchange(moved, method, body, String.class), method + " " + code);
                    String msg = String.valueOf(e.getMostSpecificCause().getMessage());
                    assertTrue(msg.contains("unexpected redirect (HTTP " + code + ")"), method + " " + code + ": " + msg);
                    assertFalse(msg.contains("SECRET"), "the Location value must not leak");
                }
            }
            assertEquals(0, targetHits.get(), "no redirect target may be reached");
        } finally {
            server.stop(0);
        }
    }
}
