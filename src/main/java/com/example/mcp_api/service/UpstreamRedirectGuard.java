package com.example.mcp_api.service;

import org.apache.hc.core5.http.HttpResponseInterceptor;
import org.apache.hc.core5.http.ProtocolException;
import org.springframework.http.client.ClientHttpRequestInterceptor;

import java.io.IOException;

/**
 * Outbound clients never follow redirects (the Authorization header must not leave the allow-listed
 * host). Without this guard a 3xx would come back as an empty "success". This turns any 3xx into an
 * error that carries only the status code, never the Location value or any credential.
 *
 * The Apache interceptor throws a ProtocolException rather than an IOException: HttpClient 5's retry
 * strategy re-sends idempotent requests (GET) after an IOException, which would hit the upstream twice.
 * HttpClient rethrows it to the caller as a ClientProtocolException (an IOException) with the same text.
 */
final class UpstreamRedirectGuard {

    private UpstreamRedirectGuard() {}

    static void requireNotRedirect(int status) throws IOException {
        if (isRedirect(status)) {
            throw new IOException(redirectMessage(status));
        }
    }

    private static boolean isRedirect(int status) {
        return status >= 300 && status < 400;
    }

    private static String redirectMessage(int status) {
        return "Exotel API returned an unexpected redirect (HTTP " + status + ")";
    }

    /** For Apache HttpClient 5 builders: {@code HttpClients.custom().addResponseInterceptorFirst(...)}. */
    static HttpResponseInterceptor apacheInterceptor() {
        return (response, entity, context) -> {
            if (isRedirect(response.getCode())) {
                throw new ProtocolException(redirectMessage(response.getCode()));
            }
        };
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
