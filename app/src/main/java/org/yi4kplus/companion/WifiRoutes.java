package org.yi4kplus.companion;

/** IPv4 prefix matching without DNS or assumptions about hotspot subnet masks. */
public final class WifiRoutes {
    private WifiRoutes() { }

    public static boolean contains(String localAddress, int prefixLength, String cameraAddress) {
        if (prefixLength < 1 || prefixLength > 32) return false;
        long local = ipv4(localAddress);
        long camera = ipv4(cameraAddress);
        if (local < 0 || camera < 0) return false;
        long mask = (0xffffffffL << (32 - prefixLength)) & 0xffffffffL;
        return (local & mask) == (camera & mask);
    }

    private static long ipv4(String value) {
        if (value == null) return -1;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return -1;
        long address = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return -1;
            int number = 0;
            for (int i = 0; i < part.length(); i++) {
                char digit = part.charAt(i);
                if (digit < '0' || digit > '9') return -1;
                number = number * 10 + digit - '0';
            }
            if (number > 255) return -1;
            address = (address << 8) | number;
        }
        return address;
    }
}
