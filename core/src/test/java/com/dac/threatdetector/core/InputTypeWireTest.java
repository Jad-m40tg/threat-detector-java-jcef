package com.dac.threatdetector.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dac.threatdetector.core.engine.Engine;
import com.dac.threatdetector.core.model.AnalysisRequest;
import com.dac.threatdetector.core.model.InputType;
import com.dac.threatdetector.core.model.ItemResult;
import com.dac.threatdetector.core.model.RiskLevel;
import com.dac.threatdetector.core.model.Verdict;
import org.junit.jupiter.api.Test;

/** Locks the Rust fallback rule for unknown/oversized-type wire values. */
class InputTypeWireTest {

    @Test
    void onlyExactLowercaseValuesAreAccepted() {
        assertEquals(InputType.URL, InputType.fromWire("url"));
        assertEquals(InputType.MESSAGE, InputType.fromWire("message"));
        assertNull(InputType.fromWire("URL"));
        assertNull(InputType.fromWire(" url "));
        assertNull(InputType.fromWire(""));
        assertNull(InputType.fromWire(null));
    }

    @Test
    void unknownTypeFallsBackToAutoDetectionNotUrlParsing() {
        // With an explicit but malformed "URL", Rust auto-detects a message.
        // Claiming URL here would wrongly turn this into NotVerified.
        ItemResult result = Engine.analyzeItem(
                new AnalysisRequest("x", InputType.fromWire("URL"), "hello there, friend"));
        assertTrue(result.verdict() instanceof Verdict.Assessed,
                "auto-detected message must be assessed, got " + result.verdict());
        assertEquals(RiskLevel.LOW, ((Verdict.Assessed) result.verdict()).level());
    }
}
