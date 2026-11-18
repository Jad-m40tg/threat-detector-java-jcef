package com.dac.threatdetector.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dac.threatdetector.core.model.InputType;
import com.dac.threatdetector.core.model.IpcAnalysisResult;
import com.dac.threatdetector.core.model.ItemResult;
import com.dac.threatdetector.core.model.NotVerifiedReason;
import com.dac.threatdetector.core.model.Verdict;
import com.dac.threatdetector.core.service.CsvParser.CsvRowItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EngineTest {

    static final class RecordingSink implements EventSink {
        final List<String> starts = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<IpcAnalysisResult> results = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<int[]> progress = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<Integer> batches = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onItemStart(String itemId, String content) {
            starts.add(itemId);
        }

        @Override
        public void onItemDone(IpcAnalysisResult result) {
            results.add(result);
        }

        @Override
        public void onProgress(int done, int total) {
            progress.add(new int[]{done, total});
        }

        @Override
        public void onBatchDone(int count, List<IpcAnalysisResult> results) {
            batches.add(count);
        }
    }

    static List<CsvRowItem> makeItems(int n) {
        List<CsvRowItem> items = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            items.add(new CsvRowItem(String.valueOf(i), InputType.URL,
                    "https://example.com/page/" + i, i + 1, null));
        }
        return items;
    }

    @Test
    void batchDeliversEveryItemAndFinalProgress() throws Exception {
        RecordingSink sink = new RecordingSink();
        List<CsvRowItem> items = makeItems(200);
        List<ItemResult> results = Engine.runBatch(items, sink, new CancellationToken(), 8, 0);

        assertEquals(200, results.size());
        assertEquals(200, sink.results.size());
        Set<String> ids = new HashSet<>();
        for (ItemResult r : results) {
            ids.add(r.id());
        }
        assertEquals(200, ids.size());
        assertEquals(200, sink.starts.size());
        assertEquals(List.of(200), sink.batches);

        int lastDone = 0;
        for (int[] p : sink.progress) {
            assertEquals(200, p[1]);
            assertTrue(p[0] >= lastDone, "progress done must be non-decreasing");
            lastDone = p[0];
        }
        int[] finalProgress = sink.progress.get(sink.progress.size() - 1);
        assertEquals(200, finalProgress[0]);
        assertEquals(200, finalProgress[1]);
    }

    @Test
    void preCancelledBatchStartsNothing() throws Exception {
        RecordingSink sink = new RecordingSink();
        CancellationToken token = new CancellationToken();
        token.cancel();
        List<ItemResult> results = Engine.runBatch(makeItems(50), sink, token, 8, 0);

        assertTrue(results.isEmpty());
        assertTrue(sink.results.isEmpty());
        assertEquals(List.of(0), sink.batches);
    }

    @Test
    void cancelMidBatchStopsEarlyAndClosesCleanly() throws Exception {
        RecordingSink sink = new RecordingSink();
        CancellationToken token = new CancellationToken();
        List<CsvRowItem> items = makeItems(100);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<ItemResult>> future = executor.submit(
                    () -> Engine.runBatch(items, sink, token, 8, 50));
            Thread.sleep(100);
            token.cancel();
            List<ItemResult> results = future.get(20, TimeUnit.SECONDS);

            assertTrue(results.size() < 100, "cancelled batch must finish early");
            assertEquals(List.of(results.size()), sink.batches,
                    "batch-done count must equal delivered results");
            for (ItemResult r : results) {
                assertTrue(r.verdict() instanceof Verdict.Assessed
                                || (r.verdict() instanceof Verdict.NotVerified nv
                                && nv.reason() instanceof NotVerifiedReason.BatchCancelled),
                        "unexpected verdict " + r.verdict());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rapidCancelThenFreshBatchIsIndependent() throws Exception {
        RecordingSink sink1 = new RecordingSink();
        CancellationToken token1 = new CancellationToken();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<ItemResult>> h1 = executor.submit(
                    () -> Engine.runBatch(makeItems(100), sink1, token1, 8, 50));
            Thread.sleep(60);
            token1.cancel();

            RecordingSink sink2 = new RecordingSink();
            List<ItemResult> results2 = Engine.runBatch(
                    makeItems(20), sink2, new CancellationToken(), 8, 0);

            List<ItemResult> r1 = h1.get(20, TimeUnit.SECONDS);
            assertTrue(r1.size() < 100);
            assertEquals(List.of(r1.size()), sink1.batches);
            assertEquals(20, results2.size());
            assertEquals(List.of(20), sink2.batches);
        } finally {
            executor.shutdownNow();
        }
    }
}
