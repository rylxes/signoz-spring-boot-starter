package io.signoz.springboot.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * {@link HttpServletResponseWrapper} that captures the response body so it can
 * be included in the HTTP log entry.
 *
 * <p>Spring Boot 3.x / {@code jakarta.servlet} version.
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
            outputStream = new CachingServletOutputStream(super.getOutputStream(), capture);
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null
                    ? Charset.forName(encoding)
                    : StandardCharsets.UTF_8;
            writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset), true);
        }
        return writer;
    }

    public byte[] getCapturedBody() {
        if (writer != null) {
            writer.flush();
        }
        return capture.toByteArray();
    }

    /** Flushes buffered writes to the underlying response. */
    public void copyBodyToResponse() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        if (outputStream != null) {
            outputStream.flush();
        }
    }

    private static class CachingServletOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;
        private final ByteArrayOutputStream capture;

        CachingServletOutputStream(ServletOutputStream delegate, ByteArrayOutputStream capture) {
            this.delegate = delegate;
            this.capture = capture;
        }

        @Override
        public boolean isReady() { return delegate.isReady(); }

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
