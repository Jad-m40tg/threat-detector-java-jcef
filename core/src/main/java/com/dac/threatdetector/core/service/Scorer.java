package com.dac.threatdetector.core.service;

import com.dac.threatdetector.core.model.RiskLevel;
import com.dac.threatdetector.core.model.RuleHit;
import com.dac.threatdetector.core.model.Verdict;
import java.util.List;

/** Maps accumulated rule hits to a risk level and display score, as in scorer.rs. */
public final class Scorer {

    private Scorer() {
    }

    /**
     * Thresholds: 0-1 points Low, 2-4 Medium, 5+ High.
     * Display score: 12 for a clean input, otherwise min(points * 15, 99).
     */
    public static Verdict.Assessed scoreHits(List<RuleHit> hits) {
        int rawScore = hits.stream().mapToInt(RuleHit::points).sum();
        int displayScore = rawScore == 0 ? 12 : Math.min(rawScore * 15, 99);
        RiskLevel level;
        if (rawScore <= 1) {
            level = RiskLevel.LOW;
        } else if (rawScore <= 4) {
            level = RiskLevel.MEDIUM;
        } else {
            level = RiskLevel.HIGH;
        }
        return new Verdict.Assessed(level, displayScore, hits);
    }
}
