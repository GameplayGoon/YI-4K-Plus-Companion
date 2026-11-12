package org.yi4kplus.companion;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Camera-side resized images first; retain originals as a firmware fallback. */
final class ThumbnailUrls {
    private ThumbnailUrls() { }

    static List<URI> photoCandidates(URI original) {
        ArrayList<URI> result = new ArrayList<>();
        result.add(withType(original, "thumb"));
        result.add(withType(original, "screen"));
        result.add(original);
        return result;
    }

    private static URI withType(URI original, String type) {
        // Work on the encoded form: decoding and rebuilding a camera filename
        // or timestamp can turn escaped '&', '+' or '%' into another URL.
        String encoded = original.toASCIIString();
        int fragment = encoded.indexOf('#');
        if (fragment >= 0) encoded = encoded.substring(0, fragment);
        String query = original.getRawQuery();
        if (query != null && query.matches("(?is).*(?:^|&)type=[^&]*.*")) {
            encoded = encoded.replaceAll("(?i)([?&]type=)[^&]*", "$1" + type);
        } else {
            encoded += query == null ? "?" : query.isEmpty() ? "" : "&";
            encoded += "type=" + type;
        }
        return URI.create(encoded);
    }
}
