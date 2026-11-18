package com.dac.threatdetector.core.model;

import java.util.List;

/**
 * Primary analysis verdict. Only Assessed carries a score and a RiskLevel;
 * NotVerified is a separate branch, so an unverified item cannot accidentally
 * be read as Low or safe.
 */
public sealed interface Verdict permits Verdict.Assessed, Verdict.NotVerified {

    record Assessed(RiskLevel level, int score, List<RuleHit> hits) implements Verdict {
        public Assessed {
            if (level == null) {
                throw new IllegalArgumentException("Assessed verdict requires a risk level");
            }
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    record NotVerified(NotVerifiedReason reason) implements Verdict {
        public NotVerified {
            if (reason == null) {
                throw new IllegalArgumentException("NotVerified verdict requires a reason");
            }
        }
    }
}
