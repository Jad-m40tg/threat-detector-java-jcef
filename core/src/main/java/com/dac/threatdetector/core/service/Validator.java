package com.dac.threatdetector.core.service;

import com.dac.threatdetector.core.error.ThreatException;
import com.dac.threatdetector.core.model.InputType;
import java.nio.charset.StandardCharsets;

/** Input validation and type detection, translated from validator.rs. */
public final class Validator {

    public static final int MAX_URL_LENGTH = 2048;
    public static final int MAX_MESSAGE_LENGTH = 4096;

    private Validator() {
    }

    /**
     * URL if it starts with http://, https:// or www.; otherwise URL when it
     * has no whitespace, contains a dot and does not end with a dot. The Rust
     * implementation is the specification and is reproduced exactly.
     */
    public static InputType detectInputType(String raw) {
        String trimmed = raw == null ? "" : raw.strip();
        if (trimmed.startsWith("http://")
                || trimmed.startsWith("https://")
                || trimmed.startsWith("www.")) {
            return InputType.URL;
        }
        if (!containsWhitespace(trimmed) && trimmed.contains(".") && !trimmed.endsWith(".")) {
            return InputType.URL;
        }
        return InputType.MESSAGE;
    }

    /**
     * Trims leading BOMs and surrounding whitespace, then enforces the byte
     * length limit. Rust uses str.len(), which counts UTF-8 bytes; we do the
     * same so unicode inputs have identical limits.
     */
    public static String validateInput(String raw, InputType inputType) throws ThreatException {
        String clean = raw == null ? "" : raw;
        while (clean.startsWith("\uFEFF")) {
            clean = clean.substring(1);
        }
        clean = clean.strip();
        if (clean.isEmpty()) {
            throw ThreatException.emptyInput();
        }
        int maxLen = inputType == InputType.URL ? MAX_URL_LENGTH : MAX_MESSAGE_LENGTH;
        long bytes = clean.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxLen) {
            throw ThreatException.oversized(bytes, maxLen);
        }
        return clean;
    }

    private static boolean containsWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                return true;
            }
        }
        return false;
    }
}
