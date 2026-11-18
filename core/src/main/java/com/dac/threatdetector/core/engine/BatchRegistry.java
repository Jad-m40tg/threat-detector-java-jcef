package com.dac.threatdetector.core.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Registry of in-flight batch cancellation tokens, mirroring state.rs. */
public final class BatchRegistry {

    private final Map<String, CancellationToken> tokens = new ConcurrentHashMap<>();

    public void insert(String batchId, CancellationToken token) {
        tokens.put(batchId, token);
    }

    /** Returns false for an unknown batch (already finished or never started). */
    public boolean cancel(String batchId) {
        CancellationToken token = tokens.get(batchId);
        if (token == null) {
            return false;
        }
        token.cancel();
        return true;
    }

    public void remove(String batchId) {
        tokens.remove(batchId);
    }
}
