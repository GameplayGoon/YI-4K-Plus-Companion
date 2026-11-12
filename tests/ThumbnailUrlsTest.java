package org.yi4kplus.companion;

import java.net.URI;
import java.util.List;

public final class ThumbnailUrlsTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] arguments) {
        URI original = URI.create("http://192.168.42.1/DCIM/100MEDIA/A%20B%26C.JPG");
        List<URI> candidates = ThumbnailUrls.photoCandidates(original);
        require(candidates.size() == 3, "Two reduced images must precede original fallback");
        require(candidates.get(0).getRawQuery().equals("type=thumb")
            && candidates.get(1).getRawQuery().equals("type=screen")
            && candidates.get(2).equals(original), "Candidate order must prefer camera-side thumbnails");
        for (URI candidate : candidates) {
            require(candidate.getRawPath().equals(original.getRawPath())
                && candidate.getHost().equals(original.getHost()), "Encoded media path and camera host must not change");
        }
        URI query = URI.create(original + "?time=2026-11-12+00%3A00%3A00&sn=A%26B%2BC");
        require(ThumbnailUrls.photoCandidates(query).get(0).getRawQuery()
            .equals(query.getRawQuery() + "&type=thumb"), "Encoded timestamp and serial query must survive unchanged");
        URI existing = URI.create(original + "?type=original&time=hello%20world");
        require(ThumbnailUrls.photoCandidates(existing).get(0).getRawQuery()
            .equals("type=thumb&time=hello%20world"), "Existing first type parameter must be replaced");
        URI middle = URI.create(original + "?prototype=keep&TYPE=screen&time=later");
        require(ThumbnailUrls.photoCandidates(middle).get(0).getRawQuery()
            .equals("prototype=keep&TYPE=thumb&time=later"), "Only the exact type query key may change");
        URI empty = URI.create(original + "?");
        require(ThumbnailUrls.photoCandidates(empty).get(0).toString().endsWith("?type=thumb"), "Empty query must not gain an extra separator");
        System.out.println("PASS: thumbnail preference, encoded paths, query preservation and original fallback");
    }
}
