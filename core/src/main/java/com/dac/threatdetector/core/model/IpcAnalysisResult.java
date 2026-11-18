package com.dac.threatdetector.core.model;

/** Final camelCase JSON payload delivered across the bridge. Field order matches Rust. */
public record IpcAnalysisResult(
        String id,
        String inputType,
        String input,
        String riskLevel,
        String risk,
        String state,
        Integer score,
        String status,
        String category,
        String details,
        java.util.List<String> reasons,
        java.util.List<IpcRuleHit> triggeredRules,
        String recommendation,
        IpcError error,
        long durationMs,
        long ts) {
}
