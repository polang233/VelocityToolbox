package io.github.polang233.velocitytoolbox.pack.http;

import com.sun.net.httpserver.HttpExchange;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** 只解析可信反代追加的地址链；从右向左找到第一个非可信节点。 */
final class ClientAddress {
    private record Network(byte[] address, int bits) {
        boolean contains(InetAddress candidate) {
            byte[] bytes = candidate.getAddress();
            if (bytes.length != address.length) return false;
            for (int i = 0; i < bits; i++)
                if ((bytes[i / 8] & (1 << (7 - i % 8))) != (address[i / 8] & (1 << (7 - i % 8)))) return false;
            return true;
        }
    }
    private final List<Network> networks = new ArrayList<>();

    ClientAddress(List<String> entries) {
        for (String entry : entries) {
            try {
                String[] parts = entry.split("/", -1);
                if (parts.length > 2) throw new IllegalArgumentException();
                InetAddress address = numeric(parts[0]);
                if (address == null) throw new IllegalArgumentException();
                int bits = parts.length == 1 ? address.getAddress().length * 8 : Integer.parseInt(parts[1]);
                if (bits < 0 || bits > address.getAddress().length * 8) throw new IllegalArgumentException();
                networks.add(new Network(address.getAddress(), bits));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("pack-host.security.trusted-proxies: invalid IP/CIDR: " + entry, failure);
            }
        }
    }

    String resolve(HttpExchange exchange) {
        return resolve(exchange.getRemoteAddress().getAddress(), exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
    }

    String resolve(InetAddress peer, String forwarded) {
        InetAddress current = peer;
        if (!trusted(peer) || forwarded == null) return peer.getHostAddress();
        String[] chain = forwarded.split(",", -1);
        if (chain.length > 16) return peer.getHostAddress();
        for (int i = chain.length - 1; i >= 0 && trusted(current); i--) {
            InetAddress candidate = numeric(chain[i].trim());
            if (candidate == null) return peer.getHostAddress();
            current = candidate;
        }
        return current.getHostAddress();
    }

    private boolean trusted(InetAddress address) { return networks.stream().anyMatch(n -> n.contains(address)); }

    private static InetAddress numeric(String value) {
        if (value.isEmpty() || !value.matches("[0-9a-fA-F:.]+")) return null;
        if (!value.contains(":")) {
            String[] parts = value.split("[.]", -1);
            if (parts.length != 4) return null;
            for (String part : parts)
                if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return null;
        }
        try { return InetAddress.getByName(value); }
        catch (UnknownHostException failure) { return null; }
    }
}
