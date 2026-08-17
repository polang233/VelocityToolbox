package io.github.velocitytoolbox.pack;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;

/**
 * 探测本机局域网 IPv4，仅用于 {@code public-url} 留空时的内网回退。
 */
final class LanIpv4Addresses {

    private LanIpv4Addresses() {
    }

    static List<String> detect() throws IOException {
        List<String> addresses = new ArrayList<>();
        var interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                continue;
            }
            var inetAddresses = networkInterface.getInetAddresses();
            while (inetAddresses.hasMoreElements()) {
                InetAddress address = inetAddresses.nextElement();
                if (address instanceof Inet4Address ipv4
                        && !ipv4.isLoopbackAddress()
                        && !ipv4.isLinkLocalAddress()
                        && !ipv4.isMulticastAddress()) {
                    addresses.add(ipv4.getHostAddress());
                }
            }
        }
        return addresses;
    }
}
