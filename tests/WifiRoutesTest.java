package org.yi4kplus.companion;

public final class WifiRoutesTest {
    private static void check(boolean expected, String address, int prefix, String remote) {
        if (WifiRoutes.contains(address, prefix, remote) != expected)
            throw new AssertionError(address + "/" + prefix + " -> " + remote);
    }

    public static void main(String[] args) {
        check(true, "192.168.42.2", 24, "192.168.42.1");
        check(false, "192.168.1.2", 24, "192.168.42.1");
        check(true, "192.168.1.2", 16, "192.168.42.1");
        check(true, "10.0.43.2", 23, "10.0.42.1");
        check(false, "10.0.43.2", 24, "10.0.42.1");
        check(true, "172.16.31.2", 20, "172.16.16.1");
        check(false, "172.16.31.2", 20, "172.16.32.1");
        check(true, "192.168.42.1", 32, "192.168.42.1");
        check(false, "192.168.42.2", 32, "192.168.42.1");
        check(false, "192.168.42.2", 0, "192.168.42.1");
        check(false, "192.168.42.2", 33, "192.168.42.1");
        check(false, "2001:db8::1", 64, "192.168.42.1");
        check(false, "192.168.42.999", 24, "192.168.42.1");
        check(false, "192.168.42.2", 24, "192.168.42.1.example.com");
        check(false, null, 24, "192.168.42.1");
        System.out.println("PASS: 15 real-subnet routing checks");
    }
}
