package com.dac.threatdetector.core.model;

/** Input classification, mirroring the Rust InputType enum. */
public enum InputType {
    URL("url"),
    MESSAGE("message");

    private final String wireValue;

    InputType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    /**
     * Exact wire values only. Rust's parse_input_type treats every other value
     * (including "URL" or " url ") as absent and falls back to auto-detection.
     */
    public static InputType fromWire(String raw) {
        if ("url".equals(raw)) {
            return URL;
        }
        if ("message".equals(raw)) {
            return MESSAGE;
        }
        return null;
    }

    @Override
    public String toString() {
        return wireValue;
    }
}
