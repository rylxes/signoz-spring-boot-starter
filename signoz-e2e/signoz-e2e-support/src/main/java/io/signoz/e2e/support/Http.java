package io.signoz.e2e.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A minimal HTTP client for driving the demo services; always connects directly, never via a proxy. */
public final class Http {

    private Http() {
    }

    public static Response get(String url) throws IOException {
        return send("GET", url, null, null, new LinkedHashMap<String, String>());
    }

    public static Response get(String url, Map<String, String> headers) throws IOException {
        return send("GET", url, null, null, headers);
    }

    public static Response post(String url, String contentType, String body) throws IOException {
        return send("POST", url, contentType, body, new LinkedHashMap<String, String>());
    }

    public static Response post(String url, String contentType, String body, Map<String, String> headers)
            throws IOException {
        return send("POST", url, contentType, body, headers);
    }

    public static Response send(String method, String url, String contentType, String body,
                                Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(30000);
        for (Map.Entry<String, String> h : headers.entrySet()) {
            c.setRequestProperty(h.getKey(), h.getValue());
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : new String(OtlpReceiver.readAll(in), StandardCharsets.UTF_8);
        Map<String, String> responseHeaders = new LinkedHashMap<String, String>();
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() != null && !e.getValue().isEmpty()) {
                responseHeaders.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
            }
        }
        c.disconnect();
        return new Response(status, text, responseHeaders);
    }

    /** A response's status, body and headers (header names lower-cased). */
    public static final class Response {
        public final int status;
        public final String body;
        public final Map<String, String> headers;

        Response(int status, String body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public String toString() {
            return status + " " + headers + " " + body;
        }
    }
}
