package com.hms.components.crawler;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reduces the many spellings of a URL to one, so the crawler recognises a page it has already
 * seen: resolves relative links, lower-cases scheme and host, drops the fragment and tracking
 * parameters, sorts the remaining query parameters and removes a trailing slash.
 */
public final class UrlNormalizer {

    private static final Set<String> TRACKING_PARAMS = Set.of("ref", "sessionid", "fbclid", "gclid");

    private UrlNormalizer() {
    }

    /** @return the canonical absolute URL, or {@code null} if the link is not a crawlable http(s) URL */
    public static String normalize(String baseUrl, String href) {
        if (href == null) {
            return null;
        }
        String trimmed = href.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("mailto:")
                || trimmed.startsWith("javascript:") || trimmed.startsWith("tel:")) {
            return null;
        }
        URI uri;
        try {
            uri = (baseUrl == null ? URI.create(trimmed) : URI.create(baseUrl).resolve(trimmed)).normalize();
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(scheme).append("://").append(uri.getHost().toLowerCase(Locale.ROOT));
        int port = uri.getPort();
        if (port != -1 && !(scheme.equals("http") && port == 80) && !(scheme.equals("https") && port == 443)) {
            sb.append(':').append(port);
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        sb.append(path);
        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) {
            TreeSet<String> kept = new TreeSet<>();
            for (String pair : query.split("&")) {
                String name = (pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair).toLowerCase(Locale.ROOT);
                if (!pair.isEmpty() && !TRACKING_PARAMS.contains(name) && !name.startsWith("utm_")) {
                    kept.add(pair);
                }
            }
            if (!kept.isEmpty()) {
                sb.append('?').append(String.join("&", kept));
            }
        }
        return sb.toString();
    }
}
