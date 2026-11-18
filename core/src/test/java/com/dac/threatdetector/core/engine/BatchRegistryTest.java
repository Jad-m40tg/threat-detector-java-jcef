package com.dac.threatdetector.core.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BatchRegistryTest {

    @Test
    void cancelSemantics() {
        BatchRegistry registry = new BatchRegistry();
        CancellationToken token = new CancellationToken();

        assertFalse(registry.cancel("unknown"), "unknown batch must report false");

        registry.insert("batch-1", token);
        assertFalse(token.isCancelled(), "insert must not cancel");
        assertTrue(registry.cancel("batch-1"), "known batch must report true");
        assertTrue(token.isCancelled(), "cancel must reach the token");
        assertTrue(registry.cancel("batch-1"), "repeat cancel stays true");

        registry.remove("batch-1");
        assertFalse(registry.cancel("batch-1"), "removed batch must report false");
    }
}
