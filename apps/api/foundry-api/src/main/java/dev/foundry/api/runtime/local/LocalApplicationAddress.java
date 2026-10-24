package dev.foundry.api.runtime.local;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.UUID;

/**
 * Creates local-only per-deployment hostnames for the development ingress.
 * This is not a public hosting URL; the .localhost suffix resolves to loopback.
 */
public final class LocalApplicationAddress {

    private static final String LOCALHOST_SUFFIX = ".localhost";

    private LocalApplicationAddress() {
    }

    public static URI parseBaseUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String scheme = uri.getScheme();
            boolean supportedScheme = "http".equalsIgnoreCase(scheme);
            boolean rootPath = uri.getPath() == null
                    || uri.getPath().isEmpty()
                    || "/".equals(uri.getPath());

            if (!supportedScheme
                    || !"localhost".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !rootPath
                    || uri.getPort() == 0
                    || uri.getPort() > 65535) {
                throw new IllegalArgumentException(
                        "Local application base URL must be an HTTP localhost URL without a path.");
            }
            return new URI(
                    scheme.toLowerCase(Locale.ROOT),
                    null,
                    "localhost",
                    uri.getPort(),
                    null,
                    null,
                    null);
        } catch (URISyntaxException | IllegalArgumentException invalid) {
            if (invalid instanceof IllegalArgumentException argument
                    && argument.getMessage() != null
                    && argument.getMessage().startsWith("Local application base URL")) {
                throw argument;
            }
            throw new IllegalArgumentException(
                    "Invalid local application base URL. Use a URL such as http://localhost:8080.", invalid);
        }
    }

    public static URI applicationUrl(UUID deploymentId, URI baseUrl) {
        if (deploymentId == null || baseUrl == null) {
            throw new IllegalArgumentException("Deployment ID and local base URL are required.");
        }
        try {
            return new URI(
                    baseUrl.getScheme(),
                    null,
                    deploymentId + LOCALHOST_SUFFIX,
                    baseUrl.getPort(),
                    null,
                    null,
                    null);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("Could not create a local application URL.", invalid);
        }
    }

    public static UUID deploymentIdFromHost(String host) {
        if (host == null) {
            return null;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (!normalized.endsWith(LOCALHOST_SUFFIX)) {
            return null;
        }
        String label = normalized.substring(0, normalized.length() - LOCALHOST_SUFFIX.length());
        if (label.isBlank() || label.contains(".")) {
            return null;
        }
        try {
            return UUID.fromString(label);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
