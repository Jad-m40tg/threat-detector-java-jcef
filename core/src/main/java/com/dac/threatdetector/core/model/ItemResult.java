package com.dac.threatdetector.core.model;

import java.util.List;

/** Core item result plus the single conversion point to the frozen IPC shape. */
public record ItemResult(
        String id,
        InputType inputType,
        String content,
        Verdict verdict,
        long durationMs) {

    /**
     * Canonical conversion to the JSON object the front-end consumes.
     *
     * <p>This is the only place where riskLevel/risk/state/score are built. The
     * NotVerified branch never produces "Low", "SAFE", or a numeric score.
     */
    public IpcAnalysisResult toIpcShape(long currentTs) {
        String typeStr = inputType.toString();
        return switch (verdict) {
            case Verdict.Assessed assessed -> {
                RiskLevel level = assessed.level();
                List<RuleHit> hits = assessed.hits();
                String riskLevel = level.label();
                String risk = level.wireValue();
                String state = level == RiskLevel.LOW ? "SAFE" : "THREAT";
                String status = "verified";

                List<String> reasons = hits.isEmpty()
                        ? List.of("No suspicious heuristic patterns detected.")
                        : hits.stream().map(RuleHit::description).toList();

                List<IpcRuleHit> triggered = hits.stream()
                        .map(h -> new IpcRuleHit(h.id(), h.description(), h.points()))
                        .toList();

                String category = switch (level) {
                    case LOW -> inputType == InputType.URL ? "Legitimate URL" : "Legitimate Message";
                    case MEDIUM -> "Medium Risk Suspicious Pattern";
                    case HIGH -> "High Risk Threat Detected";
                };

                String details = hits.isEmpty()
                        ? "Valid protocol and standard structure; clean inspection."
                        : String.join("; ", reasons);

                String recommendation = switch (level) {
                    case LOW -> "Standard precautions apply. No immediate risk indicators found.";
                    case MEDIUM -> "Exercise caution. Verify source before proceeding or sharing credentials.";
                    case HIGH -> "Do not open, click, or interact with this content. Severe risk indicators identified.";
                };

                yield new IpcAnalysisResult(
                        id, typeStr, content, riskLevel, risk, state, assessed.score(), status,
                        category, details, reasons, triggered, recommendation, null, durationMs, currentTs);
            }
            case Verdict.NotVerified notVerified -> {
                String reasonStr = notVerified.reason().message();
                yield new IpcAnalysisResult(
                        id, typeStr, content, "NotVerified", "unverified", "NOT_VERIFIED",
                        null, "not_verified", "Not Verified", reasonStr, List.of(reasonStr), List.of(),
                        "Unable to complete inspection safely. Treat as untrusted and do not proceed.",
                        new IpcError(notVerified.reason().debugKind(), reasonStr), durationMs, currentTs);
            }
        };
    }
}
