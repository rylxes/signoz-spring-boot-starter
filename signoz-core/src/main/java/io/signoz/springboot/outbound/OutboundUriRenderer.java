package io.signoz.springboot.outbound;

import io.signoz.springboot.masking.MaskingRegistry;
import io.signoz.springboot.properties.SigNozLoggingProperties;

import java.net.URI;

/**
 * Renders an outbound request URI for the access log, with sensitive query parameters masked.
 *
 * <p>The rendered form is {@code scheme://host[:port]/path[?maskedQuery]}. Userinfo
 * ({@code user:password@}) is dropped and the fragment, which is never sent, is omitted.
 */
final class OutboundUriRenderer {

    private OutboundUriRenderer() {
    }

    /**
     * Returns the given registry, or one built from the default logging properties when none is
     * supplied, so callers that predate masking still log outbound queries masked.
     */
    static MaskingRegistry orDefault(MaskingRegistry maskingRegistry) {
        return maskingRegistry != null
                ? maskingRegistry
                : new MaskingRegistry(new SigNozLoggingProperties());
    }

    static String render(URI uri, MaskingRegistry maskingRegistry) {
        if (uri == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        if (uri.getScheme() != null) {
            sb.append(uri.getScheme()).append(':');
        }
        if (uri.isOpaque()) {
            // Not a hierarchical HTTP URI; its scheme-specific part has no safe structure to keep.
            return sb.toString();
        }
        if (uri.getHost() != null) {
            sb.append("//").append(uri.getHost());
            if (uri.getPort() != -1) {
                sb.append(':').append(uri.getPort());
            }
        } else if (uri.getRawAuthority() != null) {
            // Registry-based authority (for example a host name with an underscore).
            String authority = uri.getRawAuthority();
            sb.append("//").append(authority.substring(authority.lastIndexOf('@') + 1));
        }
        if (uri.getRawPath() != null) {
            sb.append(uri.getRawPath());
        }
        String query = uri.getRawQuery();
        if (query != null) {
            sb.append('?').append(maskingRegistry.maskQueryString(query));
        }
        return sb.toString();
    }
}
