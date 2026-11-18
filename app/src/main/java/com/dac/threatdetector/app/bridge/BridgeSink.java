package com.dac.threatdetector.app.bridge;

import com.dac.threatdetector.core.engine.CancellationToken;
import com.dac.threatdetector.core.engine.Engine;
import com.dac.threatdetector.core.engine.EventSink;
import com.dac.threatdetector.core.model.IpcAnalysisResult;
import java.util.List;

/** EventSink that forwards engine callbacks to the active batch's web page. */
public final class BridgeSink implements EventSink {

    private final Bridge bridge;
    private final String batchId;

    public BridgeSink(Bridge bridge, String batchId) {
        this.bridge = bridge;
        this.batchId = batchId;
    }

    @Override
    public void onItemStart(String itemId, String content) {
        bridge.emit("analysis:item-start", new ItemStartEvent(itemId, content));
    }

    @Override
    public void onItemDone(IpcAnalysisResult result) {
        bridge.emit("analysis:item-done", new ItemDoneEvent(result.input(), result));
    }

    @Override
    public void onProgress(int done, int total) {
        int pct = total == 0 ? 0 : done * 100 / total;
        bridge.emit("analysis:progress", new ProgressEvent(batchId, done, total, pct));
    }

    @Override
    public void onBatchDone(int count, List<IpcAnalysisResult> results) {
        bridge.emit("analysis:batch-done", new BatchDoneEvent(batchId, count, results));
    }

    public record ItemStartEvent(String id, String input) {
    }

    public record ItemDoneEvent(String input, IpcAnalysisResult result) {
    }

    public record ProgressEvent(String batchId, int done, int total, int pct) {
    }

    public record BatchDoneEvent(String batchId, int count, List<IpcAnalysisResult> results) {
    }
}
