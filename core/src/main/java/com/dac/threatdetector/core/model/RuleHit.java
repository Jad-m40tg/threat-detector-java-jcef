package com.dac.threatdetector.core.model;

/** One heuristic rule that fired. Mirrors the Rust RuleHit struct. */
public record RuleHit(String id, String description, int points) {
    public RuleHit {
        if (id == null) {
            id = "";
        }
        if (description == null) {
            description = "";
        }
    }
}
