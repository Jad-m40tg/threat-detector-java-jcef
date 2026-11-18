package com.dac.threatdetector.core.engine;

import com.dac.threatdetector.core.error.ThreatException;
import com.dac.threatdetector.core.model.AnalysisRequest;
import com.dac.threatdetector.core.model.InputType;
import com.dac.threatdetector.core.model.IpcAnalysisResult;
import com.dac.threatdetector.core.model.ItemResult;
import com.dac.threatdetector.core.model.NotVerifiedReason;
import com.dac.threatdetector.core.model.Verdict;
import com.dac.threatdetector.core.service.CsvParser.CsvRowItem;
import com.dac.threatdetector.core.service.MessageRules;
import com.dac.threatdetector.core.service.Scorer;
import com.dac.threatdetector.core.service.UrlInspector;
import com.dac.threatdetector.core.service.UrlRules;
import com.dac.threatdetector.core.service.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Concurrent batch engine. A bounded Semaphore limits the number of items in
 * flight, a completion service delivers results in completion order, and the
 * caller's thread acts as the single aggregator (no mutex on counters).
 */
public final class Engine {

    /** Minimum interval between progress updates sent to the UI. */
    public static final long PROGRESS_FLUSH_MS = 40;

    @FunctionalInterface
    interface ItemAnalyzer {
        ItemResult analyze(AnalysisRequest request) throws Exception;
    }

    private Engine() {
    }

    public static ItemResult analyzeItem(AnalysisRequest request) {
        long start = System.nanoTime();
        String id = request.id() != null ? request.id() : "req-" + uuidSimple();
        InputType inputType = request.inputType() != null
                ? request.inputType()
                : Validator.detectInputType(request.content());

        String clean;
        try {
            clean = Validator.validateInput(request.content(), inputType);
        } catch (ThreatException e) {
            return new ItemResult(id, inputType, request.content(),
                    new Verdict.NotVerified(e.toNotVerifiedReason()), elapsedMs(start));
        }

        Verdict verdict;
        if (inputType == InputType.URL) {
            try {
                UrlInspector.ParsedUrl parsed = UrlInspector.parse(clean);
                verdict = Scorer.scoreHits(UrlRules.evaluate(parsed));
            } catch (ThreatException e) {
                verdict = new Verdict.NotVerified(e.toNotVerifiedReason());
            }
        } else {
            verdict = Scorer.scoreHits(MessageRules.evaluate(clean));
        }

        return new ItemResult(id, inputType, request.content(), verdict, elapsedMs(start));
    }

    public static List<ItemResult> runBatch(
            List<CsvRowItem> items,
            EventSink sink,
            CancellationToken cancelToken,
            int concurrencyLimit,
            long minItemDurationMs) throws ThreatException {
        return runBatch(items, sink, cancelToken, concurrencyLimit, minItemDurationMs, Engine::analyzeItem);
    }

    /**
     * Package-private overload used by concurrency tests to inject an analyzer
     * that can fail or block, while production always uses analyzeItem.
     */
    static List<ItemResult> runBatch(
            List<CsvRowItem> items,
            EventSink sink,
            CancellationToken cancelToken,
            int concurrencyLimit,
            long minItemDurationMs,
            ItemAnalyzer analyzer) throws ThreatException {

        int total = items.size();
        Semaphore semaphore = new Semaphore(Math.max(1, concurrencyLimit), true);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CompletionService<ItemResult> completion = new ExecutorCompletionService<>(executor);
        List<Future<ItemResult>> futures = new ArrayList<>();
        int submitted = 0;

        try {
            for (CsvRowItem item : items) {
                if (cancelToken.isCancelled()) {
                    break;
                }
                Future<ItemResult> future = completion.submit(() -> {
                    boolean acquired = false;
                    try {
                        semaphore.acquire();
                        acquired = true;

                        // Cancellation check 1: the item never started, so it is
                        // not reported at all; the batch ends with fewer results.
                        if (cancelToken.isCancelled()) {
                            return null;
                        }

                        sink.onItemStart(item.id(), item.content());

                        if (minItemDurationMs > 0) {
                            Thread.sleep(minItemDurationMs);
                        }

                        // Cancellation check 2: between stages.
                        if (cancelToken.isCancelled()) {
                            return cancelledResult(item);
                        }

                        if (item.parseError() != null) {
                            return new ItemResult(item.id(), item.inputType(), item.content(),
                                    new Verdict.NotVerified(item.parseError()), 0);
                        }

                        AnalysisRequest request = new AnalysisRequest(
                                item.id(), item.inputType(), item.content());
                        try {
                            return analyzer.analyze(request);
                        } catch (Throwable panic) {
                            // Failure isolation: one bad item becomes a typed
                            // NotVerified result; the rest of the batch survives.
                            return new ItemResult(item.id(), item.inputType(), item.content(),
                                    new Verdict.NotVerified(new NotVerifiedReason.ExecutionPanicked(
                                            panicMessage(panic))), 0);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return cancelledResult(item);
                    } finally {
                        if (acquired) {
                            semaphore.release();
                        }
                    }
                });
                futures.add(future);
                submitted++;
            }

            List<ItemResult> allResults = new ArrayList<>(total);
            long lastFlush = System.nanoTime();
            int doneCount = 0;

            for (int i = 0; i < submitted; i++) {
                ItemResult result;
                try {
                    result = completion.take().get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw ThreatException.cancelled();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    result = new ItemResult("row-?", InputType.MESSAGE, "",
                            new Verdict.NotVerified(new NotVerifiedReason.ExecutionPanicked(
                                    panicMessage(cause))), 0);
                }
                if (result == null) {
                    continue;
                }

                doneCount++;
                long ts = System.currentTimeMillis();
                IpcAnalysisResult ipc = result.toIpcShape(ts);
                sink.onItemDone(ipc);
                allResults.add(result);

                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastFlush);
                if (doneCount == total || elapsedMs >= PROGRESS_FLUSH_MS) {
                    sink.onProgress(doneCount, total);
                    lastFlush = System.nanoTime();
                }
            }

            long finalTs = System.currentTimeMillis();
            List<IpcAnalysisResult> finalIpcs = allResults.stream()
                    .map(r -> r.toIpcShape(finalTs))
                    .toList();
            sink.onBatchDone(allResults.size(), finalIpcs);
            return allResults;
        } finally {
            executor.shutdown();
            for (Future<ItemResult> future : futures) {
                future.cancel(false);
            }
        }
    }

    private static ItemResult cancelledResult(CsvRowItem item) {
        return new ItemResult(item.id(), item.inputType(), item.content(),
                new Verdict.NotVerified(new NotVerifiedReason.BatchCancelled()), 0);
    }

    private static String panicMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? "unknown panic payload" : message;
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static String uuidSimple() {
        return Long.toHexString(System.nanoTime());
    }
}
