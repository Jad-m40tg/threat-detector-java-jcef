package com.dac.threatdetector.app.bridge;

import com.dac.threatdetector.core.engine.BatchRegistry;
import com.dac.threatdetector.core.engine.CancellationToken;
import com.dac.threatdetector.core.engine.Engine;
import com.dac.threatdetector.core.error.ThreatException;
import com.dac.threatdetector.core.model.AnalysisRequest;
import com.dac.threatdetector.core.model.InputType;
import com.dac.threatdetector.core.model.IpcAnalysisResult;
import com.dac.threatdetector.core.service.CsvParser;
import com.dac.threatdetector.core.service.CsvParser.CsvRowItem;
import com.dac.threatdetector.app.WindowsTheme;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Public bridge object exposed to JavaScript as window.__tdJavaBridge.
 *
 * <p>call() runs on the host UI thread, parses the arguments, and hands all
 * real work to virtual threads. Replies and events go back through a bounded,
 * coalescing outbox that is drained on the UI thread with one
 * {@link BridgeHost#deliver(String)} call per chunk.
 */
public final class Bridge implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Bridge.class.getName());

    private final BridgeHost host;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final BatchRegistry registry = new BatchRegistry();
    private final AtomicBoolean pageReady = new AtomicBoolean(false);

    /* --perf diagnostics: FX-thread time spent inside the JS delivery call. */
    private volatile long scriptNanos;
    private volatile long scriptCount;
    private volatile long droppedProgress;
    private volatile long deliveredEvents;

    /* --delivery-- */
    private static final int OUTBOX_CAP = 2000;
    private static final int MAX_CHUNK_EVENTS = 400;
    private static final double CHUNK_TARGET_MS = 8.0;
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    private final Object outboxLock = new Object();
    private final LinkedHashMap<Object, String> outbox = new LinkedHashMap<>();
    private final AtomicLong deliverySeq = new AtomicLong();
    private volatile boolean deliveryReady;
    private boolean deliveryScheduled;
    private int chunkEvents = 64;

    /** Number of Java -> JavaScript delivery calls so far. */
    public long scriptCount() {
        return scriptCount;
    }

    /** Total nanoseconds spent inside the host's delivery call. */
    public long scriptNanos() {
        return scriptNanos;
    }

    /** Coalesced progress events discarded because the outbox hit its cap. */
    public long droppedProgress() {
        return droppedProgress;
    }

    /** Events actually handed to the page. */
    public long deliveredEvents() {
        return deliveredEvents;
    }

    public Bridge(BridgeHost host) {
        this.host = host;
    }

    public void setPageReady(boolean ready) {
        pageReady.set(ready);
        if (!ready) {
            detach();
        }
    }

    /**
     * Marks the page as reachable for delivery. Called once per load, after the
     * host has bound its JavaScript surface and (for JavaFX) the subscription
     * set has been read from the shim.
     */
    public void attachPage() {
        deliveryReady = true;
    }

    private void detach() {
        synchronized (outboxLock) {
            deliveryReady = false;
            outbox.clear();
            deliveryScheduled = false;
        }
        LOG.fine("Page detached; pending deliveries cleared");
    }

    /** Called from the shim whenever an event gains or loses its last listener. */
    public void subscribe(String name, boolean on) {
        if (name == null || name.isEmpty()) {
            return;
        }
        if (on) {
            subscribed.add(name);
        } else {
            subscribed.remove(name);
        }
    }

    /** Replaces the whole subscription set (JSON array, from __tdShim.__subs). */
    public void setSubscriptions(String jsonArray) {
        try {
            JsonNode names = mapper.readTree(jsonArray == null || jsonArray.isBlank() ? "[]" : jsonArray);
            subscribed.clear();
            if (names != null && names.isArray()) {
                for (JsonNode name : names) {
                    if (name.isTextual()) {
                        subscribed.add(name.asText());
                    }
                }
            }
        } catch (Exception error) {
            LOG.log(Level.WARNING, "Could not read event subscriptions", error);
        }
    }

    /** Entry point called from js/core/java-shim.js. */
    public void call(String callId, String command, String jsonArgs) {
        try {
            JsonNode args = mapper.readTree(jsonArgs == null || jsonArgs.isBlank() ? "{}" : jsonArgs);
            switch (command) {
                case "analyze_item" -> analyzeItem(callId, args);
                case "analyze_csv" -> analyzeCsv(callId, args);
                case "analyze_csv_content" -> analyzeCsvContent(callId, args);
                case "cancel_batch" -> cancelBatch(callId, args);
                case "pick_csv" -> pickCsv(callId);
                case "get_system_theme" -> systemTheme(callId);
                default -> reject(callId, "Unknown command: " + command);
            }
        } catch (Throwable error) {
            LOG.log(Level.WARNING, "Bridge command failed: " + command, error);
            reject(callId, message(error));
        }
    }

    private void analyzeItem(String callId, JsonNode args) {
        workers.submit(() -> {
            try {
                JsonNode request = args.path("request");
                if (!request.hasNonNull("content")) {
                    reject(callId, "missing field `content`");
                    return;
                }
                String id = request.hasNonNull("id") ? request.get("id").asText() : null;
                String type = request.hasNonNull("inputType") ? request.get("inputType").asText() : null;
                String content = request.get("content").asText();
                IpcAnalysisResult result = Engine.analyzeItem(
                        new AnalysisRequest(id, InputType.fromWire(type), content)).toIpcShape(nowMs());
                resolve(callId, result);
            } catch (Throwable error) {
                reject(callId, message(error));
            }
        });
    }

    private void analyzeCsv(String callId, JsonNode args) {
        workers.submit(() -> {
            try {
                String path = requireText(args, "path");
                List<CsvRowItem> items = CsvParser.parseCsvFile(Path.of(path));
                startBatch(callId, items, args.path("options"));
            } catch (Throwable error) {
                reject(callId, message(error));
            }
        });
    }

    private void analyzeCsvContent(String callId, JsonNode args) {
        workers.submit(() -> {
            try {
                String name = requireText(args, "name");
                String content = requireText(args, "content");
                List<CsvRowItem> items = CsvParser.parseCsvText(name, content);
                startBatch(callId, items, args.path("options"));
            } catch (Throwable error) {
                reject(callId, message(error));
            }
        });
    }

    private void startBatch(String callId, List<CsvRowItem> items, JsonNode options) {
        String batchId = "batch-" + nowMs();
        CancellationToken token = new CancellationToken();
        registry.insert(batchId, token);

        long minItemDurationMs = options.path("minItemDurationMs").asLong(0);
        int concurrency = options.path("concurrency").asInt(8);
        if (concurrency < 1) {
            concurrency = 8;
        }
        concurrency = Math.min(concurrency, 32);

        BridgeSink sink = new BridgeSink(this, batchId);
        int total = items.size();

        // Queue the start reply first so the page knows the batch id before
        // any item event is delivered.
        resolve(callId, new BatchStartInfo(batchId, total));

        int limit = concurrency;
        long duration = Math.max(0, minItemDurationMs);
        workers.submit(() -> {
            try {
                Engine.runBatch(items, sink, token, limit, duration);
            } catch (Throwable error) {
                LOG.log(Level.WARNING, "Batch " + batchId + " failed", error);
            } finally {
                registry.remove(batchId);
            }
        });
    }

    private void cancelBatch(String callId, JsonNode args) {
        String batchId = requireText(args, "batchId");
        resolve(callId, registry.cancel(batchId));
    }

    private void pickCsv(String callId) {
        try {
            host.pickCsv(
                    path -> resolve(callId, path),
                    error -> reject(callId, message(error)));
        } catch (Throwable error) {
            reject(callId, message(error));
        }
    }

    /**
     * Windows app-mode theme for the page's matchMedia. Answered off the FX
     * thread: dark=true/false, or dark=null when unknown (unsupported OS,
     * registry read failed). The shim keeps its previous value when null.
     */
    private void systemTheme(String callId) {
        workers.submit(() -> {
            try {
                Optional<Boolean> dark = WindowsTheme.isDark();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("dark", dark.orElse(null));
                payload.put("source", dark.isPresent() ? "registry" : "unavailable");
                resolve(callId, payload);
            } catch (Throwable error) {
                reject(callId, message(error));
            }
        });
    }

    /**
     * Emits one analysis:* event to the page. Safe from any thread.
     *
     * <p>Events with no listener are not serialized and never leave Java. That
     * matters for `analysis:item-start`, which is emitted per item but has no
     * consumer in the dashboard.
     */
    public void emit(String name, Object payload) {
        if (!pageReady.get() || !subscribed.contains(name)) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not serialize event " + name, e);
            return;
        }
        enqueue("{\"n\":" + jsString(name) + ",\"p\":" + json + "}",
                coalesceKey(name, payload));
    }

    /**
     * Progress is a snapshot of the batch counters, so only the latest value is
     * useful. Results (item-done, batch-done) are never coalesced or dropped.
     */
    private static String coalesceKey(String name, Object payload) {
        if ("analysis:progress".equals(name)) {
            return "progress";
        }
        if ("analysis:item-start".equals(name)) {
            String id = payload instanceof BridgeSink.ItemStartEvent start && start.id() != null
                    ? start.id() : "";
            return "item-start:" + id;
        }
        return null;
    }

    private void resolve(String callId, Object payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            reject(callId, "Could not serialize reply: " + message(e));
            return;
        }
        enqueue("{\"r\":" + jsString(callId) + ",\"v\":" + json + "}", null);
    }

    private void reject(String callId, String message) {
        enqueue("{\"r\":" + jsString(callId) + ",\"e\":" + jsString(message == null ? "" : message) + "}", null);
    }

    /**
     * Adds one pre-serialized message to the outbox and schedules a drain.
     *
     * <p>The outbox is bounded. Above the cap only coalescable progress entries
     * are discarded; replies and results keep queuing even if the page is slow,
     * because losing one would corrupt the batch. The number dropped is logged.
     */
    private void enqueue(String messageJson, String coalesceKey) {
        boolean schedule;
        synchronized (outboxLock) {
            Object key = coalesceKey != null ? coalesceKey : "m" + deliverySeq.incrementAndGet();
            outbox.put(key, messageJson);
            if (outbox.size() > OUTBOX_CAP) {
                int before = outbox.size();
                outbox.entrySet().removeIf(entry -> entry.getKey() instanceof String keyName
                        && (keyName.equals("progress") || keyName.startsWith("item-start:")));
                int removed = before - outbox.size();
                if (removed > 0) {
                    droppedProgress += removed;
                }
            }
            schedule = !deliveryScheduled && deliveryReady && pageReady.get();
            if (schedule) {
                deliveryScheduled = true;
            }
        }
        if (schedule) {
            host.runOnUiThread(this::deliverChunk);
        }
    }

    /**
     * Sends at most {@code chunkEvents} messages in one host delivery call. The next
     * chunk size follows the measured duration of this call, so a single
     * FX-thread call stays near {@link #CHUNK_TARGET_MS} ms instead of blocking
     * a whole pulse with a 500-event payload.
     */
    private void deliverChunk() {
        List<String> batch = new ArrayList<>(Math.min(chunkEvents, 128));
        synchronized (outboxLock) {
            if (!deliveryReady || !pageReady.get() || outbox.isEmpty()) {
                deliveryScheduled = false;
                return;
            }
            var iterator = outbox.values().iterator();
            while (iterator.hasNext() && batch.size() < chunkEvents) {
                batch.add(iterator.next());
                iterator.remove();
            }
            deliveryScheduled = false;
        }
        if (batch.isEmpty()) {
            return;
        }
        StringBuilder json = new StringBuilder(batch.size() * 96 + 16).append('[');
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(batch.get(i));
        }
        json.append(']');

        long started = System.nanoTime();
        boolean ok = true;
        try {
            host.deliver(json.toString());
        } catch (Throwable error) {
            ok = false;
            LOG.log(Level.FINE, "JS delivery failed", error);
        }
        long elapsed = System.nanoTime() - started;
        scriptCount++;
        scriptNanos += elapsed;
        deliveredEvents += batch.size();
        adaptChunk(batch.size(), elapsed);

        boolean more;
        synchronized (outboxLock) {
            more = !outbox.isEmpty() && deliveryReady;
            if (more) {
                deliveryScheduled = true;
            }
        }
        if (!ok) {
            // The page went away between attach and delivery; drop what is left
            // rather than retrying against a dead document.
            detach();
            return;
        }
        if (more) {
            host.runOnUiThread(this::deliverChunk);
        }
    }

    private void adaptChunk(int sent, long nanos) {
        double ms = nanos / 1_000_000.0;
        if (ms > CHUNK_TARGET_MS) {
            chunkEvents = Math.max(8, sent / 2);
        } else if (ms < 2.0) {
            chunkEvents = Math.min(MAX_CHUNK_EVENTS, Math.max(16, sent * 2));
        }
    }

    private String jsString(String value) {
        try {
            return mapper.writeValueAsString(value == null ? "" : value);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private static String requireText(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("missing field `" + field + "`");
        }
        return node.asText();
    }

    private static String message(Throwable error) {
        if (error instanceof ThreatException threat) {
            return threat.getMessage();
        }
        String text = error.getMessage();
        return text == null || text.isEmpty() ? error.getClass().getSimpleName() : text;
    }

    private static long nowMs() {
        return System.currentTimeMillis();
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }

    public record BatchStartInfo(String batchId, int total) {
    }
}
