package io.signoz.springboot.errors;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility for generating deterministic fingerprints from {@link Throwable} instances.
 *
 * <p>A fingerprint is a short hex string derived from the exception class name and the
 * top N stack-trace frames. Two identical exceptions thrown from the same call site
 * produce the same fingerprint, making it useful for grouping duplicate errors.</p>
 */
public final class ErrorFingerprint {

    private static final String UNKNOWN = "unknown";

    private ErrorFingerprint() {
        // utility class — no instances
    }

    /**
     * Generate a 12-character hex fingerprint for the given throwable.
     *
     * @param t     the throwable to fingerprint; may be {@code null}
     * @param depth the maximum number of top stack-trace frames to include
     * @return a 12-character hex string, or {@code "unknown"} if {@code t} is {@code null}
     */
    public static String generate(Throwable t, int depth) {
        if (t == null) {
            return UNKNOWN;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getName());

        StackTraceElement[] frames = t.getStackTrace();
        if (frames != null) {
            int limit = Math.min(depth, frames.length);
            for (int i = 0; i < limit; i++) {
                sb.append(frames[i].toString());
            }
        }

        return sha256Hex(sb.toString(), 12);
    }

    /**
     * Compute the SHA-256 hash of the input and return the first {@code length} hex characters.
     */
    private static String sha256Hex(String input, int length) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
                if (hex.length() >= length) {
                    break;
                }
            }
            return hex.substring(0, Math.min(length, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM specification
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
