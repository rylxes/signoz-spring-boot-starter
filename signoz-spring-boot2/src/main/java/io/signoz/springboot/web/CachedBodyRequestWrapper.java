package io.signoz.springboot.web;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * {@link HttpServletRequestWrapper} that captures the request body as the
 * downstream handler reads it. Only the configured number of bytes is retained
 * for logging; the handler still receives the full request body.
 *
 * <p>Spring Boot 2.x / {@code javax.servlet} version.
 */
public class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

    private final int maxBytes;
    private final ByteArrayOutputStream cachedBody;
    private ServletInputStream inputStream;
    private BufferedReader reader;

    public CachedBodyRequestWrapper(HttpServletRequest request, int maxBytes) throws IOException {
        super(request);
        this.maxBytes = Math.max(0, maxBytes);
        this.cachedBody = new ByteArrayOutputStream(Math.min(this.maxBytes, 256));
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (inputStream == null) {
            inputStream = new CachedBodyServletInputStream(
                    super.getInputStream(), cachedBody, maxBytes);
        }
        return inputStream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (reader == null) {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null
                    ? Charset.forName(encoding)
                    : StandardCharsets.UTF_8;
            reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
        return reader;
    }

    public byte[] getCachedBody() {
        return cachedBody.toByteArray();
    }

    // --- Inner InputStream adapter ---

    private static class CachedBodyServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final ByteArrayOutputStream cachedBody;
        private final int maxBytes;
        private int capturedBytes;

        CachedBodyServletInputStream(ServletInputStream delegate,
                                     ByteArrayOutputStream cachedBody,
                                     int maxBytes) {
            this.delegate = delegate;
            this.cachedBody = cachedBody;
            this.maxBytes = maxBytes;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1 && capturedBytes < maxBytes) {
                cachedBody.write(value);
                capturedBytes++;
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = delegate.read(b, off, len);
            if (read > 0) {
                capture(b, off, read);
            }
            return read;
        }

        private void capture(byte[] b, int off, int len) {
            int remaining = maxBytes - capturedBytes;
            if (remaining <= 0) {
                return;
            }
            int toCapture = Math.min(len, remaining);
            cachedBody.write(b, off, toCapture);
            capturedBytes += toCapture;
        }
    }
}
