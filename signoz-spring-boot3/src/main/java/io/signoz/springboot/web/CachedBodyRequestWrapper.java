package io.signoz.springboot.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;

/**
 * {@link HttpServletRequestWrapper} that caches the request body so it can be
 * read multiple times (once by the logging filter, once by the actual handler).
 *
 * <p>Spring Boot 3.x / {@code jakarta.servlet} version.
 */
public class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyRequestWrapper(HttpServletRequest request, int maxBytes) throws IOException {
        super(request);
        InputStream inputStream = request.getInputStream();
        byte[] buffer = new byte[maxBytes];
        int bytesRead = 0;
        int totalRead = 0;
        while (totalRead < maxBytes
                && (bytesRead = inputStream.read(buffer, totalRead, maxBytes - totalRead)) != -1) {
            totalRead += bytesRead;
        }
        cachedBody = new byte[totalRead];
        System.arraycopy(buffer, 0, cachedBody, 0, totalRead);
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedBodyServletInputStream(cachedBody);
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(cachedBody)));
    }

    public byte[] getCachedBody() {
        return cachedBody;
    }

    private static class CachedBodyServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream stream;

        CachedBodyServletInputStream(byte[] body) {
            this.stream = new ByteArrayInputStream(body);
        }

        @Override
        public boolean isFinished() {
            return stream.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // no-op
        }

        @Override
        public int read() throws IOException {
            return stream.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return stream.read(b, off, len);
        }
    }
}
