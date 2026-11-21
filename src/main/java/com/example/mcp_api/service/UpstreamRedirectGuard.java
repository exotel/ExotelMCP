package com.example.mcp_api.service;

import org.apache.hc.core5.http.HttpResponseInterceptor;
import org.springframework.http.client.ClientHttpRequestInterceptor;

import java.io.IOException;

/**
 * Outbound clients never follow redirects (the Authorization header must not leave the allow-listed
 * host). Without this guard a 3xx would come back as an empty "success". This turns any 3xx into an
 * IOException that carries only the status code, never the Location value or any credential.
 */
final class UpstreamRedirectGuard {

    private UpstreamRedirectGuard() {}

    static void requireNotRedirect(int status) throws IOException {
        if (status >= 300 && status < 400) {
            throw new IOException("Exotel API returned an unexpected redirect (HTTP " + status + ")");
        }
    }

    /** For Apache HttpClient 5 builders: {@code HttpClients.custom().addResponseInterceptorFirst(...)}. */
    static HttpResponseInterceptor apacheInterceptor() {
        return (response, entity, context) -> requireNotRedirect(response.getCode());
    }

    /** For RestTemplate on the JDK HttpURLConnection factory. */
    static ClientHttpRequestInterceptor springInterceptor() {
        return (request, body, execution) -> {
            var response = execution.execute(request, body);
            try {
                requireNotRedirect(response.getStatusCode().value());
            } catch (IOException e) {
                response.close();
                throw e;
            }
            return response;
        };
    }
}
