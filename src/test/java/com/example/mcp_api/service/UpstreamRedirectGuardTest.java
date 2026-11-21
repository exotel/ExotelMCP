package com.example.mcp_api.service;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class UpstreamRedirectGuardTest {

    @Test
    void redirectStatusesThrowWithoutLeakingAnythingButTheCode() {
        for (int status : new int[] {300, 301, 302, 307, 308, 399}) {
            IOException e = assertThrows(IOException.class, () -> UpstreamRedirectGuard.requireNotRedirect(status));
            assertEquals("Exotel API returned an unexpected redirect (HTTP " + status + ")", e.getMessage());
        }
    }

    @Test
    void nonRedirectStatusesPass() throws IOException {
        for (int status : new int[] {200, 201, 204, 299, 400, 401, 500}) {
            UpstreamRedirectGuard.requireNotRedirect(status);
        }
    }

    @Test
    void apacheClientSurfacesRedirectAsIoExceptionWithoutLocation() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", ex -> { ex.sendResponseHeaders(200, -1); ex.close(); });
        server.createContext("/moved", ex -> {
            ex.getResponseHeaders().add("Location", "https://elsewhere.invalid/x?token=SECRET");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        try (CloseableHttpClient client = HttpClients.custom().disableRedirectHandling()
                .addResponseInterceptorFirst(UpstreamRedirectGuard.apacheInterceptor()).build()) {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            IOException e = assertThrows(IOException.class, () -> client.execute(new HttpGet(base + "/moved"), r -> null));
            assertTrue(e.getMessage().contains("HTTP 302"));
            assertFalse(e.getMessage().contains("SECRET"));
            int ok = client.execute(new HttpGet(base + "/ok"), r -> r.getCode());
            assertEquals(200, ok);
        } finally {
            server.stop(0);
        }
    }
}
