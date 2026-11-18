package com.dac.threatdetector.core.model;

/** Serialized error in the IPC payload. */
public record IpcError(String kind, String message) {
}
