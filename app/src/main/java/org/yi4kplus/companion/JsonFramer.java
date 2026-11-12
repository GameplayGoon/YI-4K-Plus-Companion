package org.yi4kplus.companion;

import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;
import java.net.SocketTimeoutException;

/**
 * Splits consecutive camera JSON objects without assuming a line delimiter.
 * Safely handles strings containing braces and escaped quote characters.
 */
public final class JsonFramer {
    private JsonFramer() {}

    public static String readObject(Reader reader, int limit) throws IOException {
        return readObject(reader, limit, 0L);
    }

    /** Deadline uses System.nanoTime(); zero disables it for finite local readers. */
    public static String readObject(Reader reader, int limit, long deadlineNanos) throws IOException {
        if (limit < 2) throw new IllegalArgumentException("limit");
        StringBuilder result = new StringBuilder();
        char[] nesting = new char[128];
        int depth = 0;
        int preamble = 0;
        boolean started = false;
        boolean insideString = false;
        boolean escaped = false;
        while (result.length() < limit) {
            if (deadlineNanos != 0L && System.nanoTime() >= deadlineNanos)
                throw new SocketTimeoutException("Camera protocol reply timed out");
            int code = reader.read();
            if (code == -1) throw new EOFException("The camera closed its connection");
            char ch = (char) code;
            if (!started) {
                if (ch != '{') {
                    if (++preamble > Math.min(limit, 4096)) throw new IOException("Camera protocol reply contains excessive leading data");
                    continue;
                }
                started = true;
            }
            result.append(ch);
            if (insideString) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') insideString = false;
            } else if (ch == '"') insideString = true;
            else if (ch == '{' || ch == '[') {
                if (depth == nesting.length) throw new IOException("Camera protocol reply nesting exceeds safety limit");
                nesting[depth++] = ch;
            } else if (ch == '}' || ch == ']') {
                if (depth == 0 || nesting[depth - 1] != (ch == '}' ? '{' : '['))
                    throw new IOException("Malformed camera protocol reply");
                if (--depth == 0) return result.toString();
            }
        }
        throw new IOException("Camera message exceeds safety limit");
    }
}
