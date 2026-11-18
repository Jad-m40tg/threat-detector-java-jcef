package com.dac.threatdetector.core.model;

/**
 * Risk level assessed from local heuristic rules.
 *
 * <p>Deliberately has no NOT_VERIFIED or UNKNOWN constant. A RiskLevel can only
 * exist when rules were evaluated. Java's enum is the direct equivalent of the
 * Rust enum and the compiler refuses any missing case in a switch expression.
 */
public enum RiskLevel {
    LOW("Low", "low"),
    MEDIUM("Medium", "medium"),
    HIGH("High", "high");

    private final String label;
    private final String wireValue;

    RiskLevel(String label, String wireValue) {
        this.label = label;
        this.wireValue = wireValue;
    }

    public String label() {
        return label;
    }

    public String wireValue() {
        return wireValue;
    }
}
