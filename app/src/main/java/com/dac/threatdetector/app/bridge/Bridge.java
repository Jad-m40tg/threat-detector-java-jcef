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
import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * Public bridge object exposed to JavaScript as window.__tdJavaBridge.
 *
 * <p>call() runs on the FX thread (JavaFX invokes Java members there), parses
 * the arguments, and hands all real work to virtual threads. Replies and
 * events go back through Platform.runLater + executeScript in call order.
 */
public final class Bridge implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Bridge.class.getName());

    private final WebEngine engine;
    private final Stage stage;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final BatchRegistry registry = new BatchRegistry();
    private final AtomicBoolean pageReady = new AtomicBoolean(false);

    public Bridge(WebEngine engine, Stage stage) {
        this.engine = engine;
        this.stage = stage;
    }

    public void setPageReady(boolean ready) {
        pageReady.set(ready);
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
        Platform.runLater(() -> {
            try {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Select a CSV file to analyze");
                chooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("CSV files", "*.csv"));
                File file = chooser.showOpenDialog(stage);
                resolve(callId, file == null ? null : file.getAbsolutePath());
            } catch (Throwable error) {
                reject(callId, message(error));
            }
        });
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

    /** Emits one analysis:* event to the page. Safe from any thread. */
    public void emit(String name, Object payload) {
        if (!pageReady.get()) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not serialize event " + name, e);
            return;
        }
        runScript("window.__tdShim && window.__tdShim.emit(" + jsString(name) + "," + jsString(json) + ")");
    }

    private void resolve(String callId, Object payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            reject(callId, "Could not serialize reply: " + message(e));
            return;
        }
        runScript("window.__tdShim && window.__tdShim.resolve(" + jsString(callId) + "," + jsString(json) + ")");
    }

    private void reject(String callId, String message) {
        runScript("window.__tdShim && window.__tdShim.reject(" + jsString(callId) + "," + jsString(message) + ")");
    }

    private void runScript(String script) {
        Platform.runLater(() -> {
            try {
                engine.executeScript(script);
            } catch (Throwable error) {
                LOG.log(Level.FINE, "executeScript failed", error);
            }
        });
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
