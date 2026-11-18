package com.dac.threatdetector.core.model;

/** Input request. id and inputType are nullable, exactly like the Rust Options. */
public record AnalysisRequest(String id, InputType inputType, String content) {
    public AnalysisRequest {
        if (content == null) {
            content = "";
        }
    }
}
