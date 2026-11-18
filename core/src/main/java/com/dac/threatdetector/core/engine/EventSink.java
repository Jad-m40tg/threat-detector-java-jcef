package com.dac.threatdetector.core.engine;

import com.dac.threatdetector.core.model.IpcAnalysisResult;
import java.util.List;

/**
 * Decoupled progress sink. The engine knows nothing about JavaFX or WebView;
 * the app supplies an implementation that forwards events to JavaScript.
 */
public interface EventSink {
    void onItemStart(String itemId, String content);

    void onItemDone(IpcAnalysisResult result);

    void onProgress(int done, int total);

    void onBatchDone(int count, List<IpcAnalysisResult> results);
}
