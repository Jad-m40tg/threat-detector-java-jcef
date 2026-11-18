package com.dac.threatdetector.core.error;

import com.dac.threatdetector.core.model.NotVerifiedReason;

/**
 * Typed error used by the pure core. Every instance can be converted to a
 * NotVerifiedReason; there is no constructor that yields "Low" or "safe".
 *
 * <p>This replaces Rust's ThreatError enum and thiserror Display impl. Java
 * exceptions are the closest idiom: they can be thrown, but callers map them
 * to an explicit NotVerified verdict at the boundary.
 */
public final class ThreatException extends Exception {

    public enum Kind {
        EMPTY_INPUT,
        WHITESPACE_ONLY,
        OVERSIZED_INPUT,
        MALFORMED_URL,
        CSV_ROW_ERROR,
        CSV_FILE_ERROR,
        IO,
        CANCELLED,
        TASK_PANICKED,
        OTHER
    }

    private final Kind kind;
    private final long length;
    private final int max;
    private final int row;
    private final String details;

    private ThreatException(Kind kind, String message, long length, int max, int row, String details) {
        super(message);
        this.kind = kind;
        this.length = length;
        this.max = max;
        this.row = row;
        this.details = details;
    }

    public static ThreatException emptyInput() {
        return new ThreatException(Kind.EMPTY_INPUT, "Input text is empty", 0, 0, 0, "");
    }

    public static ThreatException whitespaceOnly() {
        return new ThreatException(Kind.WHITESPACE_ONLY, "Input text contains only whitespace", 0, 0, 0, "");
    }

    public static ThreatException oversized(long length, int max) {
        return new ThreatException(Kind.OVERSIZED_INPUT,
                "Input exceeds maximum allowed length: length=" + length + ", max=" + max,
                length, max, 0, "");
    }

    public static ThreatException malformedUrl(String details) {
        return new ThreatException(Kind.MALFORMED_URL, "Malformed URL: " + details, 0, 0, 0, details);
    }

    public static ThreatException csvRow(int row, String details) {
        return new ThreatException(Kind.CSV_ROW_ERROR,
                "CSV parsing error on row " + row + ": " + details, 0, 0, row, details);
    }

    public static ThreatException csvFile(String details) {
        return new ThreatException(Kind.CSV_FILE_ERROR, "CSV file error: " + details, 0, 0, 0, details);
    }

    public static ThreatException io(String details) {
        return new ThreatException(Kind.IO, "I/O error: " + details, 0, 0, 0, details);
    }

    public static ThreatException cancelled() {
        return new ThreatException(Kind.CANCELLED, "Operation cancelled by user or timeout", 0, 0, 0, "");
    }

    public static ThreatException taskPanicked(String details) {
        return new ThreatException(Kind.TASK_PANICKED, "Task panicked during execution: " + details, 0, 0, 0, details);
    }

    public static ThreatException other(String details) {
        return new ThreatException(Kind.OTHER, "General error: " + details, 0, 0, 0, details);
    }

    public Kind kind() {
        return kind;
    }

    public long length() {
        return length;
    }

    public int max() {
        return max;
    }

    public int row() {
        return row;
    }

    public String details() {
        return details;
    }

    /** Maps every failure to an explicit NotVerified reason. Never a risk level. */
    public NotVerifiedReason toNotVerifiedReason() {
        return switch (kind) {
            case EMPTY_INPUT -> new NotVerifiedReason.EmptyInput();
            case WHITESPACE_ONLY -> new NotVerifiedReason.WhitespaceOnly();
            case OVERSIZED_INPUT -> new NotVerifiedReason.OversizedInput(length, max);
            case MALFORMED_URL -> new NotVerifiedReason.MalformedUrl(details);
            case CSV_ROW_ERROR -> new NotVerifiedReason.InvalidCsvRow(row, details);
            case CSV_FILE_ERROR, IO -> new NotVerifiedReason.IoError(details);
            case CANCELLED -> new NotVerifiedReason.BatchCancelled();
            case TASK_PANICKED -> new NotVerifiedReason.ExecutionPanicked(details);
            case OTHER -> new NotVerifiedReason.Other(details);
        };
    }
}
