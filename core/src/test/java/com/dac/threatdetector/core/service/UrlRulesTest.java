package com.dac.threatdetector.core.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class UrlRulesTest {

    private static List<String> hitIds(String url) throws Exception {
        UrlInspector.ParsedUrl parsed = UrlInspector.parse(url);
        return UrlRules.evaluate(parsed).stream().map(h -> h.id()).toList();
    }

    @Test
    void httpFlaggedHttpsNot() throws Exception {
        assertTrue(hitIds("http://example.com/page").contains("http_protocol"));
        assertFalse(hitIds("https://example.com/page").contains("http_protocol"));
    }

    @Test
    void ipHostFlaggedAndNotNested() throws Exception {
        List<String> ids = hitIds("http://192.168.1.50/portal/update");
        assertTrue(ids.contains("ip_host"));
        assertFalse(ids.contains("nested_subdomains"));
    }

    @Test
    void nestedSubdomainsOnDomainsOnly() throws Exception {
        assertTrue(hitIds("http://brand.account-security.example-portal.net/login")
                .contains("nested_subdomains"));
    }

    @Test
    void userinfoAtFlagged() throws Exception {
        assertTrue(hitIds("http://user:token@secure-portal.net/auth").contains("authority_userinfo"));
    }

    @Test
    void punycodeFlagged() throws Exception {
        assertTrue(hitIds("https://xn--80ak6aa92e.com/").contains("punycode_host"));
    }

    @Test
    void longUrlFlagged() throws Exception {
        assertTrue(hitIds("https://example.com/" + "a".repeat(150)).contains("long_url"));
    }

    @Test
    void shortenerFlaggedExactAndSubdomainNotLookalike() throws Exception {
        assertTrue(hitIds("https://bit.ly/abc").contains("shortener_host"));
        assertTrue(hitIds("https://www.bit.ly/abc").contains("shortener_host"));
        assertFalse(hitIds("https://xbit.ly/abc").contains("shortener_host"));
    }

    @Test
    void nonstandardPortFlaggedDefaultPortsNot() throws Exception {
        assertTrue(hitIds("http://example.com:8080/").contains("nonstandard_port"));
        assertFalse(hitIds("http://example.com:80/").contains("nonstandard_port"));
        assertFalse(hitIds("https://example.com:443/").contains("nonstandard_port"));
    }
}
