package com.webhook.admin.config;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

@Component
public class SsrfValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    public void validateUrl(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            throw new IllegalArgumentException("Target URL cannot be empty");
        }

        URI uri;
        try {
            uri = URI.create(urlString).normalize();
            uri.parseServerAuthority();
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed URL structure: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException("Only HTTP and HTTPS protocols are supported");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL host cannot be empty");
        }

        host = normalizeObfuscatedHost(host);
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            
            for (InetAddress address : addresses) {
                if (address.isLoopbackAddress()
                        || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("Target URL resolves to a restricted or internal network address");
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Could not resolve host: " + host);
        }
    }

    private String normalizeObfuscatedHost(String host) {
        try {
            if (host.startsWith("0x") || host.startsWith("0X")) {
                long val = Long.parseLong(host.substring(2), 16);
                return longToIpv4(val);
            }
            if (host.matches("^\\d+$")) {
                long val = Long.parseLong(host);
                if (val >= 0 && val <= 0xFFFFFFFFL) {
                    return longToIpv4(val);
                }
            }
        }
        catch (NumberFormatException ignored) {
        }
        return host;
    }

    private String longToIpv4(long val) {
        return String.format("%d.%d.%d.%d",
                (val >> 24) & 0xFF,
                (val >> 16) & 0xFF,
                (val >> 8) & 0xFF,
                val & 0xFF);
    }
}