package com.dac.threatdetector.app.bridge;

import java.util.function.Consumer;

/**
 * UI/JavaScript surface the analysis bridge needs from its host.
 *
 * <p>JavaFX WebView and JCEF deliver JavaScript in very different ways, so the
 * bridge only depends on this small interface. Implementations are responsible
 * for running work on their own UI thread and for never blocking it.
 */
public interface BridgeHost {

    /** Runs a task on the host's UI toolkit thread. */
    void runOnUiThread(Runnable task);

    /** Delivers one JSON array of bridge messages to window.__tdShim.__deliver. */
    void deliver(String messagesJson);

    /** Opens the CSV file picker; exactly one of the callbacks is invoked. */
    void pickCsv(Consumer<String> onPath, Consumer<Throwable> onError);
}
