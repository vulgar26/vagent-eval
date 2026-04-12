package com.vagent.eval.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Day9：IPv4 CIDR 匹配（P0 仅 IPv4；IPv6 地址返回不匹配）。
 */
public final class EvalIpv4Cidr {

    private EvalIpv4Cidr() {
    }

    /**
     * @param cidrSpec 形如 {@code 10.0.0.0/8}、{@code 127.0.0.1/32}
     * @param ip       主机 IPv4 点分字符串
     * @return 是否落在网段内
     */
    public static boolean matches(String cidrSpec, String ip) {
        if (cidrSpec == null || ip == null || cidrSpec.isBlank() || ip.isBlank()) {
            return false;
        }
        String[] parts = cidrSpec.trim().split("/");
        if (parts.length != 2) {
            return false;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (prefix < 0 || prefix > 32) {
            return false;
        }
        if (prefix == 0) {
            return true;
        }
        byte[] net;
        byte[] host;
        try {
            net = InetAddress.getByName(parts[0].trim()).getAddress();
            host = InetAddress.getByName(ip.trim()).getAddress();
        } catch (UnknownHostException e) {
            return false;
        }
        if (net.length != 4 || host.length != 4) {
            return false;
        }
        long network = toUnsignedIpv4(net);
        long candidate = toUnsignedIpv4(host);
        long mask = prefix == 32 ? 0xFFFF_FFFFL : (0xFFFF_FFFFL << (32 - prefix)) & 0xFFFF_FFFFL;
        return (network & mask) == (candidate & mask);
    }

    private static long toUnsignedIpv4(byte[] a) {
        return ((a[0] & 0xFFL) << 24) | ((a[1] & 0xFFL) << 16) | ((a[2] & 0xFFL) << 8) | (a[3] & 0xFFL);
    }
}
