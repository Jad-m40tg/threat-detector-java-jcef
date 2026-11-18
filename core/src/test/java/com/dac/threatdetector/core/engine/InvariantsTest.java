package com.dac.threatdetector.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.dac.threatdetector.core.model.AnalysisRequest;
import com.dac.threatdetector.core.model.InputType;
import com.dac.threatdetector.core.model.IpcAnalysisResult;
import com.dac.threatdetector.core.model.ItemResult;
import com.dac.threatdetector.core.model.Verdict;
import org.junit.jupiter.api.Test;

class InvariantsTest {

    @Test
    void notVerifiedInvariantsSweep() {
        String[][] cases = {
                {"", "Empty string"},
                {"   ", "Whitespace only"},
                {"\t\r\n", "Newlines and tabs"},
                {"ht!tp://invalid-url-with-bang.com", "Malformed scheme"},
                {"https://", "Missing host"},
                {"http://", "Missing host 2"}
        };

        for (String[] testCase : cases) {
            String input = testCase[0];
            String label = testCase[1];
            ItemResult result = Engine.analyzeItem(new AnalysisRequest(null, null, input));

            if (result.verdict() instanceof Verdict.NotVerified) {
                IpcAnalysisResult ipc = result.toIpcShape(1000);
                assertEquals("NotVerified", ipc.riskLevel(), label + ": risk_level must be NotVerified");
                assertEquals("unverified", ipc.risk(), label + ": risk must be unverified");
                assertEquals("NOT_VERIFIED", ipc.state(), label + ": state must be NOT_VERIFIED");
                assertNull(ipc.score(), label + ": score must be null for NotVerified");
                assertNotEquals("Low", ipc.riskLevel(), label + ": Invariant violated! Claimed Low");
                assertNotEquals("SAFE", ipc.state(), label + ": Invariant violated! Claimed SAFE");
            } else {
                fail("Invariant violated! Input '" + input + "' (" + label + ") was assessed");
            }
        }
    }

    @Test
    void oversizedInputInvariant() {
        String hugeUrl = "https://example.com/" + "a".repeat(3000);
        ItemResult result = Engine.analyzeItem(
                new AnalysisRequest(null, InputType.URL, hugeUrl));
        assertTrue(result.verdict() instanceof Verdict.NotVerified,
                "Oversized URL must be NotVerified");
        IpcAnalysisResult ipc = result.toIpcShape(1000);
        assertEquals("NotVerified", ipc.riskLevel());
        assertEquals("NOT_VERIFIED", ipc.state());
    }
}
