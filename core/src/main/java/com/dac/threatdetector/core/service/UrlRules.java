package com.dac.threatdetector.core.service;

import com.dac.threatdetector.core.model.RuleHit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local heuristic URL rules, translated 1:1 from url_rules.rs. */
public final class UrlRules {

    static final int SUSPICIOUS_URL_LENGTH = 100;

    static final List<String> SHORTENER_HOSTS = List.of(
            "bit.ly", "tinyurl.com", "goo.gl", "t.co", "is.gd",
            "cutt.ly", "rb.gy", "ow.ly", "buff.ly", "rebrand.ly");

    private static final List<String> SUSPICIOUS_KEYWORDS = List.of(
            "portal", "login", "account", "verify", "secure", "update", "bank");

    private UrlRules() {
    }

    public static List<RuleHit> evaluate(UrlInspector.ParsedUrl url) {
        List<RuleHit> hits = new ArrayList<>();

        if ("http".equals(url.scheme())) {
            hits.add(new RuleHit("http_protocol", "Uses unencrypted HTTP protocol", 2));
        }

        String host = url.host();
        if (host == null || host.isEmpty()) {
            return hits;
        }
        String hostLower = host.toLowerCase(Locale.ROOT);
        boolean isIpHost = isIpv4(hostLower);

        if (isIpHost) {
            hits.add(new RuleHit("ip_host",
                    "Hostname is a raw IP address instead of a domain name", 4));
        }

        if (url.hasUserInfo()) {
            hits.add(new RuleHit("authority_userinfo",
                    "Contains @ symbol in authority section (user credentials spoofing)", 4));
        }

        if (!isIpHost) {
            String[] parts = host.split("\\.", -1);
            if (parts.length >= 4) {
                hits.add(new RuleHit("nested_subdomains",
                        "Multiple nested subdomains and misleading domain structure", 3));
            }
        }

        int keywordHits = 0;
        for (String keyword : SUSPICIOUS_KEYWORDS) {
            if (hostLower.contains(keyword)) {
                keywordHits++;
            }
        }
        if (keywordHits > 0) {
            hits.add(new RuleHit("suspicious_keyword",
                    "Hostname contains " + keywordHits + " suspicious keyword(s)",
                    Math.min(keywordHits, 3)));
        }

        if (hostLower.contains("xn--")) {
            hits.add(new RuleHit("punycode_host",
                    "Hostname uses punycode encoding (possible lookalike domain)", 4));
        }

        if (url.byteLength() > SUSPICIOUS_URL_LENGTH) {
            hits.add(new RuleHit("long_url",
                    "Unusually long URL (possible padding to hide the real destination)", 1));
        }

        if (isShortenerHost(hostLower)) {
            hits.add(new RuleHit("shortener_host",
                    "Routes through a URL shortener that can hide the real destination", 3));
        }

        Integer port = url.port();
        if (port != null) {
            boolean standard = switch (url.scheme()) {
                case "http", "ws" -> port == 80;
                case "https", "wss" -> port == 443;
                case "ftp" -> port == 21;
                default -> false;
            };
            if (!standard) {
                hits.add(new RuleHit("nonstandard_port", "Uses a non-standard network port", 1));
            }
        }

        return hits;
    }

    private static boolean isShortenerHost(String host) {
        for (String entry : SHORTENER_HOSTS) {
            if (host.equals(entry) || host.endsWith("." + entry)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }
}
