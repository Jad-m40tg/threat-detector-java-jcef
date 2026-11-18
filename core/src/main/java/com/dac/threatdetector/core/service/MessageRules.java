package com.dac.threatdetector.core.service;

import com.dac.threatdetector.core.error.ThreatException;
import com.dac.threatdetector.core.model.RuleHit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local heuristic message rules, translated 1:1 from message_rules.rs. */
public final class MessageRules {

    private static final List<String> URGENCY_PHRASES = List.of(
            "within 24 hours", "immediately", "urgent", "24 hours", "asap", "act now");

    private static final List<String> SUSPENSION_PHRASES = List.of(
            "prevent suspension", "suspend", "locked", "terminated", "restricted",
            "unauthorized activity");

    private static final List<String> ACTION_PHRASES = List.of(
            "review your", "click here", "confirm", "action required", "log in to", "update your");

    private MessageRules() {
    }

    public static List<RuleHit> evaluate(String text) {
        List<RuleHit> hits = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);

        if (URGENCY_PHRASES.stream().anyMatch(lower::contains)) {
            hits.add(new RuleHit("urgency_language",
                    "Urgent time window or pressure tactic detected", 2));
        }

        if (SUSPENSION_PHRASES.stream().anyMatch(lower::contains)) {
            hits.add(new RuleHit("suspension_threat",
                    "Account suspension threat or penalty warning", 3));
        }

        if (ACTION_PHRASES.stream().anyMatch(lower::contains)) {
            hits.add(new RuleHit("action_request", "Vague or unsolicited action request", 1));
        }

        if (lower.contains("account")
                || lower.contains("security settings")
                || lower.contains("credentials")) {
            hits.add(new RuleHit("account_reference",
                    "Message references user account or security credentials", 1));
        }

        for (String word : splitWhitespace(text)) {
            if (word.startsWith("http://") || word.startsWith("https://")) {
                String cleanLink = trimTrailingPunctuation(word);
                try {
                    UrlInspector.ParsedUrl parsed = UrlInspector.parse(cleanLink);
                    for (RuleHit hit : UrlRules.evaluate(parsed)) {
                        hits.add(new RuleHit(
                                "embedded_" + hit.id(),
                                "Embedded URL: " + hit.description(),
                                hit.points()));
                    }
                } catch (ThreatException e) {
                    hits.add(new RuleHit("embedded_malformed_link",
                            "Contains unparseable or obfuscated embedded link", 3));
                }
            }
        }

        return hits;
    }

    private static String trimTrailingPunctuation(String word) {
        int end = word.length();
        while (end > 0) {
            char c = word.charAt(end - 1);
            if (c == '.' || c == ',' || c == ';' || c == '!' || c == '?') {
                end--;
            } else {
                break;
            }
        }
        return word.substring(0, end);
    }

    /** Rust split_whitespace uses Unicode whitespace; Character covers the same intent. */
    private static List<String> splitWhitespace(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (!current.isEmpty()) {
                    words.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            words.add(current.toString());
        }
        return words;
    }
}
