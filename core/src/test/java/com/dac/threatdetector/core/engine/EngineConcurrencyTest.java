package com.dac.threatdetector.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dac.threatdetector.core.model.AnalysisRequest;
import com.dac.threatdetector.core.model.InputType;
import com.dac.threatdetector.core.model.IpcAnalysisResult;
import com.dac.threatdetector.core.model.ItemResult;
import com.dac.threatdetector.core.model.NotVerifiedReason;
import com.dac.threatdetector.core.model.Verdict;
import com.dac.threatdetector.core.service.CsvParser.CsvRowItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** G4: concurrency bound, failure isolation, and event ordering. */
class EngineConcurrencyTest {

    static final class OrderedSink implements EventSink {
        final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        final Map<String, Long> starts = new ConcurrentHashMap<>();
        final List<Integer> progress = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onItemStart(String itemId, String content) {
            starts.put(itemId, System.nanoTime());
            events.add("start:" + itemId);
        }

        @Override
        public void onItemDone(IpcAnalysisResult result) {
            events.add("done:" + result.id());
        }

        @Override
        public void onProgress(int done, int total) {
            progress.add(done);
        }

        @Override
        public void onBatchDone(int count, List<IpcAnalysisResult> results) {
            events.add("batch-done:" + count);
        }
    }

    private static List<CsvRowItem> items(int count) {
        List<CsvRowItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(new CsvRowItem("i" + i, InputType.MESSAGE,
                    "ordinary message number " + i, i + 1, null));
        }
        return items;
    }

    @Test
    void concurrencyNeverExceedsTheLimitButDoesRunInParallel() throws Exception {
        int limit = 4;
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        List<ItemResult> results = Engine.runBatch(items(40), new EngineTest.RecordingSink(),
                new CancellationToken(), limit, 0, request -> {
                    int now = running.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(15);
                        return Engine.analyzeItem(request);
                    } finally {
                        running.decrementAndGet();
                    }
                });

        assertEquals(40, results.size());
        assertTrue(peak.get() <= limit,
                "peak concurrency " + peak.get() + " must not exceed limit " + limit);
        assertTrue(peak.get() > 1,
                "worker pool must actually overlap items (peak was " + peak.get() + ")");
    }

    @Test
    void oneThrowingItemBecomesNotVerifiedAndTheBatchStillCompletes() throws Exception {
        EngineTest.RecordingSink sink = new EngineTest.RecordingSink();
        List<CsvRowItem> items = items(12);
        items.set(5, new CsvRowItem("boom", InputType.MESSAGE, "explode", 6, null));

        List<ItemResult> results = Engine.runBatch(items, sink, new CancellationToken(),
                4, 0, request -> {
                    if ("boom".equals(request.id())) {
                        throw new IllegalStateException("simulated worker failure");
                    }
                    return Engine.analyzeItem(request);
                });

        assertEquals(12, results.size(), "failure isolation must keep every item");
        assertEquals(List.of(12), sink.batches, "batch-done count must match");

        Map<String, ItemResult> byId = new HashMap<>();
        for (ItemResult result : results) {
            byId.put(result.id(), result);
        }
        ItemResult failed = byId.get("boom");
        assertNotNull(failed);
        Verdict.NotVerified notVerified = (Verdict.NotVerified) failed.verdict();
        assertTrue(notVerified.reason() instanceof NotVerifiedReason.ExecutionPanicked,
                "worker failure must map to ExecutionPanicked, not a risk level");
        assertEquals(11, results.stream()
                .filter(r -> r.verdict() instanceof Verdict.Assessed)
                .count());
    }

    @Test
    void everyStartPrecedesItsDoneAndBatchDoneIsLast() throws Exception {
        OrderedSink sink = new OrderedSink();
        List<CsvRowItem> items = items(60);
        List<ItemResult> results = Engine.runBatch(items, sink, new CancellationToken(), 8, 0);

        assertEquals(60, results.size());
        assertEquals(60, sink.starts.size(), "every item must announce its start");
        assertEquals(results.size() * 2 + 1, sink.events.size(),
                "events must be one start and one done per item, plus batch-done");
        assertTrue(!sink.progress.isEmpty(), "a final progress event must be emitted");
        assertEquals(60, sink.progress.get(sink.progress.size() - 1),
                "final progress must report every item");

        Map<String, Integer> doneAt = new HashMap<>();
        Map<String, Integer> startAt = new HashMap<>();
        for (int i = 0; i < sink.events.size(); i++) {
            String event = sink.events.get(i);
            if (event.startsWith("start:")) {
                startAt.putIfAbsent(event.substring("start:".length()), i);
            } else if (event.startsWith("done:")) {
                doneAt.putIfAbsent(event.substring("done:".length()), i);
            }
        }
        assertEquals(60, doneAt.size(), "every item must report exactly one done");
        for (String id : startAt.keySet()) {
            assertTrue(startAt.get(id) < doneAt.get(id),
                    "start must precede done for item " + id);
        }
        assertTrue(sink.events.get(sink.events.size() - 1).startsWith("batch-done:60"),
                "batch-done must be the final event");
    }
}
