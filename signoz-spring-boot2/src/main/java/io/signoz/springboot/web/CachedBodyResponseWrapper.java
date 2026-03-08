package io.signoz.springboot.web;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * {@link HttpServletResponseWrapper} that captures the response body so it can
 * be included in the HTTP log entry.
 *
 * <p>Spring Boot 2.x / {@code javax.servlet} version.
 */
public class CachedBodyResponseWrapper extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream capture;
    private ServletOutputStream outputStream;
    private PrintWriter writer;

    public CachedBodyResponseWrapper(HttpServletResponse response) {
        super(response);
        this.capture = new ByteArrayOutputStream(256);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (outputStream == null) {
            outputStream = new CachingServletOutputStream(
                    super.getOutputStream(), capture);
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            writer = new PrintWriter(getOutputStream(), true);
        }
        return writer;
    }

    public byte[] getCapturedBody() {
        return capture.toByteArray();
    }

    /** Flushes buffered writes to the underlying response. */
    public void copyBodyToResponse() throws IOException {
        if (capture.size() > 0) {
            super.getOutputStream().write(capture.toByteArray());
            super.getOutputStream().flush();
        }
    }

    // --- Tee output stream ---

    private static class CachingServletOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;
        private final ByteArrayOutputStream capture;

        CachingServletOutputStream(ServletOutputStream delegate, ByteArrayOutputStream capture) {
            this.delegate = delegate;
            this.capture = capture;
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            delegate.setWriteListener(writeListener);
        }

        @Override
        public void write(int b) throws IOException {
            capture.write(b);
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            capture.write(b, off, len);
            delegate.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }
    }
}
