import org.yi4kplus.companion.JsonFramer;
import org.yi4kplus.companion.HtmlIndex;
import java.io.StringReader;
import java.io.IOException;
import java.net.URI;
import java.util.List;

public final class TestHelpers {
    static void require(boolean expected, String label) {
        if (!expected) throw new AssertionError(label);
        System.out.println("PASS: " + label);
    }
    public static void main(String[] args) throws Exception {
        String payload = "junk {\"msg_id\":7,\"param\":\"a {quoted} \\\"hi\\\"\"}" +
            "{\"rval\":0,\"msg_id\":257,\"param\":35}";
        StringReader r = new StringReader(payload);
        String first = JsonFramer.readObject(r, 1000);
        String second = JsonFramer.readObject(r, 1000);
        require(first.contains("{quoted}") && first.endsWith("}"), "Framing notification with string braces");
        require(second.contains("\"msg_id\":257"), "Framing adjacent response without newline");
        boolean fail = false;
        try { JsonFramer.readObject(new StringReader("{\"key\": true"), 100); }
        catch (IOException expected) { fail = true; }
        require(fail, "Incomplete JSON fails rather than hanging");
        URI base = URI.create("http://192.168.42.1/DCIM/100MEDIA/");
        String html = "<a href='a.mp4'>video</a><a href=\"foo.JPG\">photo</a>" +
            "<a href='../102MEDIA/'>folder</a><a href='https://bad.com/DCIM/w.mp4'>evil</a>" +
            "<a href='../../private.mp4'>traversal</a><a href='?sort=hi'>query</a>";
        List<URI> urls = HtmlIndex.entries(base, html);
        require(urls.size() == 3, "HTTP listing ignores external, query and traversal URLs");
        require(urls.stream().filter(HtmlIndex::isMedia).count() == 2, "Gallery recognizes MP4 and JPG only");
        require(!HtmlIndex.isMedia(URI.create("http://192.168.42.1/other/secret.mp4")), "No links outside DCIM");
        System.out.println("All standalone checks passed.");
    }
}
