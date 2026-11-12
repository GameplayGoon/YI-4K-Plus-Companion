package org.yi4kplus.companion;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative URL extraction from Cherokee/Apache-style directory listings. */
public final class HtmlIndex {
    private HtmlIndex() {}
    private static final Pattern LINKS = Pattern.compile(
        "(?is)<a\\b[^>]*?\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    public static List<URI> entries(URI base, String html) {
        LinkedHashSet<URI> result = new LinkedHashSet<>();
        Matcher matcher = LINKS.matcher(html);
        while (matcher.find() && result.size() < 500) {
            String raw = matcher.group(1) != null ? matcher.group(1) :
                matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            raw = raw.replace("&amp;", "&").replace("&#38;", "&");
            try {
                if (raw.startsWith("#") || raw.startsWith("?") || raw.startsWith("javascript:")) continue;
                URI uri = base.resolve(raw).normalize();
                if (!"http".equalsIgnoreCase(uri.getScheme())) continue;
                if (!base.getHost().equalsIgnoreCase(uri.getHost())) continue;
                if (base.getPort() != uri.getPort()) continue;
                if (uri.getPath() == null || !uri.getPath().startsWith("/DCIM/")) continue;
                if (uri.getQuery() != null || uri.getFragment() != null) continue;
                if (!uri.equals(base)) result.add(uri);
            } catch (IllegalArgumentException ignored) { }
        }
        return new ArrayList<>(result);
    }

    public static boolean isMedia(URI uri) {
        if (uri == null || uri.getPath() == null) return false;
        String p = uri.getPath().toLowerCase(java.util.Locale.ROOT);
        return p.startsWith("/dcim/") && (p.endsWith(".jpg") || p.endsWith(".jpeg") ||
            p.endsWith(".png") || p.endsWith(".mp4") || p.endsWith(".mov"));
    }
}
