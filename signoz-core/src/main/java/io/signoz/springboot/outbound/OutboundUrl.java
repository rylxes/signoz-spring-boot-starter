package io.signoz.springboot.outbound;

import io.signoz.springboot.masking.MaskingRegistry;

import java.net.URI;

/**
 * Renders an outbound request URL for the {@code SIGNOZ_OUTBOUND} log line without its secrets.
 *
 * <ul>
 *   <li>The query string goes through {@link MaskingRegistry#maskQueryString(String)}, the same
 *       masking the inbound HTTP filters use, so {@code ?access_token=abc} is logged as
 *       {@code ?access_token=***}.</li>
 *   <li>User info ({@code https://user:pass@host}) is replaced with {@code ***}.</li>
 *   <li>The fragment is dropped: it is never sent to the server.</li>
 * </ul>
 *
 * <p>The authority is taken from {@link URI#getRawAuthority()} rather than host and port, because
 * {@link URI#getHost()} is {@code null} for host names the URI grammar rejects, such as container
 * names with underscores, which would otherwise disappear from the log.
 */
final class OutboundUrl {

    private OutboundUrl() {
    }

    /**
     * @param uri      the request URI; may be {@code null}
     * @param registry the masking registry, or {@code null} to leave the query as it is
     * @return the URL to log, or {@code null} when {@code uri} is {@code null}
     */
    static String forLog(URI uri, MaskingRegistry registry) {
        if (uri == null) {
            return null;
        }
        if (uri.isOpaque()) {
            // Not a hierarchical URL (e.g. "mailto:"); nothing here has a query to mask.
            return uri.toString();
        }
        StringBuilder sb = new StringBuilder(uri.toString().length());
        if (uri.getScheme() != null) {
            sb.append(uri.getScheme()).append(':');
        }
        String authority = uri.getRawAuthority();
        if (authority != null) {
            sb.append("//");
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                sb.append("***@").append(authority, at + 1, authority.length());
            } else {
                sb.append(authority);
            }
        }
        if (uri.getRawPath() != null) {
            sb.append(uri.getRawPath());
        }
        String query = uri.getRawQuery();
        if (query != null) {
            sb.append('?').append(registry != null ? registry.maskQueryString(query) : query);
        }
        return sb.toString();
    }

    /**
     * Replaces the raw URL in an exception message with its masked form. Client exceptions often
     * quote the full request URL, which would otherwise undo the masking of the log line.
     *
     * @param message   the exception message; may be {@code null}
     * @param uri       the request URI; may be {@code null}
     * @param maskedUrl the result of {@link #forLog(URI, MaskingRegistry)} for {@code uri}
     * @return the message with every occurrence of the raw URL replaced
     */
    static String maskMessage(String message, URI uri, String maskedUrl) {
        if (message == null || uri == null || maskedUrl == null) {
            return message;
        }
        return message.replace(uri.toString(), maskedUrl);
    }
}
