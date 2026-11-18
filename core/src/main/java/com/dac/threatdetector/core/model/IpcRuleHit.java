package com.dac.threatdetector.core.model;

/** Serialized rule hit in the IPC payload. */
public record IpcRuleHit(String id, String description, int points) {
}
