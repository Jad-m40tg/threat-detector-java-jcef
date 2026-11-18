package com.dac.threatdetector.core.service;

import com.dac.threatdetector.core.error.ThreatException;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Offline URL parser reproducing the parts of Rust's url::Url that the rules
 * use: scheme, host, userinfo, port and normalized text. No network access.
 */
public final class UrlInspector {

    public record ParsedUrl(
            String scheme,
            String host,
            String username,
            String password,
            Integer port,
            String normalized) {

        public boolean hasUserInfo() {
            return (username != null && !username.isEmpty()) || password != null;
        }

        public int byteLength() {
            return normalized.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private UrlInspector() {
    }

    public static ParsedUrl parse(String raw) throws ThreatException {
        if (raw == null) {
            throw ThreatException.malformedUrl("relative URL without a base");
        }
        String text = raw.strip();
        int colon = schemeColon(text);
        if (colon <= 0) {
            throw ThreatException.malformedUrl("relative URL without a base");
        }
        String scheme = text.substring(0, colon);
        if (!isValidScheme(scheme)) {
            throw ThreatException.malformedUrl("relative URL without a base");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        String rest = text.substring(colon + 1);
        boolean special = isSpecial(scheme);

        if (!rest.startsWith("//")) {
            if (!special) {
                return new ParsedUrl(scheme, null, null, null, null, scheme + ":" + rest);
            }
            // The Rust url crate accepts http:example.com as a special URL.
            while (rest.startsWith("/")) {
                rest = rest.substring(1);
            }
            rest = "//" + rest;
        }

        String afterSlashes = rest.substring(2);
        int end = firstOf(afterSlashes, '/', '?', '#');
        String authority = end < 0 ? afterSlashes : afterSlashes.substring(0, end);
        String tail = end < 0 ? "" : afterSlashes.substring(end);

        if (authority.isEmpty()) {
            if (special && !"file".equals(scheme)) {
                throw ThreatException.malformedUrl("empty host");
            }
            String path = normalizeTail(tail, special);
            return new ParsedUrl(scheme, null, null, null, null, scheme + "://" + path);
        }

        String userinfo = null;
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            userinfo = authority.substring(0, at);
            authority = authority.substring(at + 1);
        }
        String username = null;
        String password = null;
        if (userinfo != null) {
            int c = userinfo.indexOf(':');
            if (c >= 0) {
                username = userinfo.substring(0, c);
                password = userinfo.substring(c + 1);
            } else {
                username = userinfo;
            }
        }

        String host;
        Integer port = null;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw ThreatException.malformedUrl("invalid IPv6 address");
            }
            host = authority.substring(0, close + 1);
            String after = authority.substring(close + 1);
            if (!after.isEmpty()) {
                if (!after.startsWith(":")) {
                    throw ThreatException.malformedUrl("invalid IPv6 address");
                }
                port = parsePort(after.substring(1));
            }
        } else {
            int c = authority.lastIndexOf(':');
            if (c >= 0 && authority.indexOf(':') == c) {
                host = authority.substring(0, c);
                port = parsePort(authority.substring(c + 1));
            } else if (c >= 0) {
                throw ThreatException.malformedUrl("invalid IPv6 address");
            } else {
                host = authority;
            }
        }

        if (host.isEmpty()) {
            if (special && !"file".equals(scheme)) {
                throw ThreatException.malformedUrl("empty host");
            }
            host = null;
        } else {
            host = normalizeHost(host);
            if (host.isEmpty()) {
                throw ThreatException.malformedUrl("empty host");
            }
        }

        if (port != null && port == defaultPort(scheme)) {
            port = null;
        }

        StringBuilder normalized = new StringBuilder();
        normalized.append(scheme).append("://");
        if (userinfo != null) {
            normalized.append(userinfo).append('@');
        }
        if (host != null) {
            normalized.append(host);
        }
        if (port != null) {
            normalized.append(':').append(port);
        }
        normalized.append(normalizeTail(tail, special));
        return new ParsedUrl(scheme, host, username, password, port, normalized.toString());
    }

    private static int schemeColon(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ':') {
                return i;
            }
            if (c == '/' || c == '?' || c == '#') {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isValidScheme(String scheme) {
        if (scheme.isEmpty() || !isAsciiAlpha(scheme.charAt(0))) {
            return false;
        }
        for (int i = 1; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            if (!isAsciiAlpha(c) && !Character.isDigit(c) && c != '+' && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isSpecial(String scheme) {
        return switch (scheme) {
            case "http", "https", "ws", "wss", "ftp", "file" -> true;
            default -> false;
        };
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "http", "ws" -> 80;
            case "https", "wss" -> 443;
            case "ftp" -> 21;
            default -> -1;
        };
    }

    private static int parsePort(String raw) throws ThreatException {
        if (raw.isEmpty()) {
            throw ThreatException.malformedUrl("invalid port number");
        }
        for (int i = 0; i < raw.length(); i++) {
            if (!Character.isDigit(raw.charAt(i))) {
                throw ThreatException.malformedUrl("invalid port number");
            }
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw ThreatException.malformedUrl("invalid port number");
        }
        if (value > 65535) {
            throw ThreatException.malformedUrl("invalid port number");
        }
        return value;
    }

    private static String normalizeHost(String host) throws ThreatException {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == '?' || c == '#' || c == '@') {
                throw ThreatException.malformedUrl("invalid domain character");
            }
        }
        String lower = host.toLowerCase(Locale.ROOT);
        boolean ascii = true;
        for (int i = 0; i < lower.length(); i++) {
            if (lower.charAt(i) > 127) {
                ascii = false;
                break;
            }
        }
        if (!ascii) {
            try {
                lower = IDN.toASCII(lower, IDN.ALLOW_UNASSIGNED);
            } catch (IllegalArgumentException e) {
                throw ThreatException.malformedUrl("invalid international domain name");
            }
        }
        return lower;
    }

    private static String normalizeTail(String tail, boolean special) {
        if (tail.isEmpty()) {
            return special ? "/" : "";
        }
        if (tail.startsWith("/")) {
            return tail;
        }
        return (special ? "/" : "") + tail;
    }

    private static int firstOf(String text, char a, char b, char c) {
        for (int i = 0; i < text.length(); i++) {
            char x = text.charAt(i);
            if (x == a || x == b || x == c) {
                return i;
            }
        }
        return -1;
    }
}
