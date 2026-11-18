package com.dac.threatdetector.core.engine;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation signal, equivalent to tokio_util's CancellationToken. */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
