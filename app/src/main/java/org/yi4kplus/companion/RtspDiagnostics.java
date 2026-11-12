package org.yi4kplus.companion;

import android.net.Network;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Only sends DESCRIBE (read-only), never SETUP/PLAY or arbitrary scan probes. */
public final class RtspDiagnostics {
    private RtspDiagnostics() {}
    public static String inspect(Network network, String host) throws Exception {
        String uri = "rtsp://" + host + "/live";
        try (Socket socket = network.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(host, 554), 2800);
            socket.setSoTimeout(3500);
            OutputStream out = socket.getOutputStream();
            out.write(("DESCRIBE " + uri + " RTSP/1.0\r\nCSeq: 1\r\nAccept: application/sdp\r\nUser-Agent: YI4KPlusCompanion/0.3\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] body = new byte[20000]; int n=0, ch;
            // Read a bounded response. Most YI SDP replies are <2 KB.
            while(n<body.length && (ch=in.read())!=-1) {
                body[n++]=(byte)ch;
                String temp = new String(body,0,n,StandardCharsets.UTF_8);
                int start=temp.indexOf("\r\n\r\n");
                if (start > 0) {
                    int length=0;
                    for (String line : temp.substring(0,start).split("\r\n")) {
                        if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                            try { length=Integer.parseInt(line.substring(15).trim()); } catch(Exception ignore){}
                        }
                    }
                    if (n >= start+4+length && length>0) break;
                    if (length==0 && n>start+4) break;
                }
            }
            String response = new String(body, 0, n, StandardCharsets.UTF_8);
            StringBuilder result = new StringBuilder();
            String[] lines = response.split("[\r\n]+");
            if (lines.length > 0) result.append("Response: ").append(lines[0]).append('\n');
            for (String line : lines) {
                if (line.startsWith("m=video") || line.startsWith("m=audio") || line.startsWith("a=rtpmap") ||
                    line.startsWith("a=fmtp") || line.startsWith("a=control")) {
                    String safe = line.length() > 250 ? line.substring(0,250) + "…" : line;
                    result.append(safe).append('\n');
                }
            }
            result.append("Video fmtp advertised: ").append(response.contains("a=fmtp:") ? "yes" : "NO (Media3 may reject this)");
            return result.toString();
        }
    }
}
