package com.dac.threatdetector.core.model;

/**
 * Why an input could not be verified. Modelled as a sealed interface with one
 * record per Rust enum variant (Rust enum-with-data translates to a tagged
 * union, which Java represents as a sealed hierarchy).
 */
public sealed interface NotVerifiedReason
        permits NotVerifiedReason.EmptyInput,
                NotVerifiedReason.WhitespaceOnly,
                NotVerifiedReason.MalformedUrl,
                NotVerifiedReason.OversizedInput,
                NotVerifiedReason.InvalidCsvRow,
                NotVerifiedReason.IoError,
                NotVerifiedReason.BatchCancelled,
                NotVerifiedReason.ExecutionPanicked,
                NotVerifiedReason.Other {

    /** User-facing message, matching the Rust Display implementation. */
    String message();

    /** Variant name used in the IPC error.kind field. */
    String debugKind();

    record EmptyInput() implements NotVerifiedReason {
        @Override
        public String message() {
            return "Input is empty";
        }

        @Override
        public String debugKind() {
            return "EmptyInput";
        }
    }

    record WhitespaceOnly() implements NotVerifiedReason {
        @Override
        public String message() {
            return "Input contains only whitespace";
        }

        @Override
        public String debugKind() {
            return "WhitespaceOnly";
        }
    }

    record MalformedUrl(String details) implements NotVerifiedReason {
        @Override
        public String message() {
            return "Malformed URL format: " + details;
        }

        @Override
        public String debugKind() {
            return "MalformedUrl(\"" + details + "\")";
        }
    }

    record OversizedInput(long length, int max) implements NotVerifiedReason {
        @Override
        public String message() {
            return "Input size (" + length + " chars) exceeds limit (" + max + " chars)";
        }

        @Override
        public String debugKind() {
            return "OversizedInput { length: " + length + ", max: " + max + " }";
        }
    }

    record InvalidCsvRow(int row, String details) implements NotVerifiedReason {
        @Override
        public String message() {
            return "CSV row " + row + " invalid: " + details;
        }

        @Override
        public String debugKind() {
            return "InvalidCsvRow { row: " + row + ", details: \"" + details + "\" }";
        }
    }

    record IoError(String details) implements NotVerifiedReason {
        @Override
        public String message() {
            return "I/O error: " + details;
        }

        @Override
        public String debugKind() {
            return "IoError(\"" + details + "\")";
        }
    }

    record BatchCancelled() implements NotVerifiedReason {
        @Override
        public String message() {
            return "Batch processing was cancelled";
        }

        @Override
        public String debugKind() {
            return "BatchCancelled";
        }
    }

    record ExecutionPanicked(String details) implements NotVerifiedReason {
        @Override
        public String message() {
            return "Analysis task panicked: " + details;
        }

        @Override
        public String debugKind() {
            return "ExecutionPanicked(\"" + details + "\")";
        }
    }

    record Other(String details) implements NotVerifiedReason {
        @Override
        public String message() {
            return details;
        }

        @Override
        public String debugKind() {
            return "Other(\"" + details + "\")";
        }
    }
}
