package com.dac.threatdetector.app;

import com.dac.threatdetector.app.bridge.Bridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;
import netscape.javascript.JSObject;

/**
 * JavaFX shell: hosts the unchanged front-end in a WebView and wires the
 * bridge. Modes: normal, --smoke-test (bridge + dataset + JSON report) and
 * --compat-audit (WebKit feature report).
 */
public final class App extends Application {

    private static final Logger LOG = Logger.getLogger(App.class.getName());
    private static final String DARK = "#101418";

    private final ObjectMapper mapper = new ObjectMapper();
    private WebHost host;
    private Bridge bridge;
    private Stage stage;
    private WebView webView;
    private WebEngine engine;
    private boolean smoke;
    private boolean compat;
    private boolean screenshot;
    private String pageName = "index.html";
    private String screenshotOut = "";
    private String clickSelector = "";
    private String scrollSelector = "";
    private long autoCloseMs;
    private Object smokeCallback;
    private Object compatCallback;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        List<String> args = getParameters().getRaw();
        smoke = args.contains("--smoke-test");
        compat = args.contains("--compat-audit");
        screenshot = args.contains("--screenshot");
        pageName = parseValue(args, "--page=", "index.html");
        screenshotOut = parseValue(args, "--screenshot-out=", "");
        clickSelector = parseValue(args, "--click=", "");
        scrollSelector = parseValue(args, "--scroll=", "");
        autoCloseMs = parseAutoClose(args);

        try {
            host = WebHost.start();
            webView = new WebView();
            engine = webView.getEngine();
            engine.setJavaScriptEnabled(true);
            engine.setUserAgent(engine.getUserAgent() + " ThreatDetectorJavaFX");

            Path userData = Path.of(System.getenv("APPDATA"), "ThreatDetector", "webdata");
            Files.createDirectories(userData);
            engine.setUserDataDirectory(userData.toFile());

            bridge = new Bridge(engine, primaryStage);

            StackPane root = new StackPane(webView);
            Scene scene = new Scene(root, 1280, 800, Color.web(DARK));
            primaryStage.setScene(scene);
            primaryStage.setTitle("Threat Detector");
            primaryStage.setMinWidth(900);
            primaryStage.setMinHeight(600);
            primaryStage.getIcons().add(new javafx.scene.image.Image(
                    App.class.getResourceAsStream("/icons/icon.png")));

            engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                if (newState == Worker.State.SCHEDULED) {
                    bridge.setPageReady(false);
                } else if (newState == Worker.State.SUCCEEDED) {
                    onPageReady();
                } else if (newState == Worker.State.FAILED) {
                    Throwable error = engine.getLoadWorker().getException();
                    LOG.log(Level.SEVERE, "Page load failed", error);
                    if (smoke || compat) {
                        writeFailureReport(error == null ? "page load failed" : error.toString());
                        exit(1);
                    }
                }
            });

            engine.load(host.baseUrl() + pageName);
        } catch (Throwable error) {
            LOG.log(Level.SEVERE, "Startup failed", error);
            if (smoke || compat) {
                writeFailureReport(error.toString());
                exit(1);
            } else {
                Platform.exit();
            }
        }
    }

    private void onPageReady() {
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember("__tdJavaBridge", bridge);
            bridge.setPageReady(true);
            LOG.info("READY page loaded, bridge attached");
            stage.show();

            if (smoke) {
                runSmokeTest(window);
            } else if (compat) {
                runCompatAudit(window);
            } else if (screenshot) {
                PauseTransition prepare = new PauseTransition(Duration.millis(700));
                prepare.setOnFinished(e -> runScreenshotHooks());
                prepare.play();
                PauseTransition pause = new PauseTransition(Duration.millis(2200));
                pause.setOnFinished(e -> {
                    Path out = screenshotPath();
                    captureScreenshot(out);
                    LOG.info("SCREENSHOT " + out.toAbsolutePath());
                    exit(0);
                });
                pause.play();
            } else if (autoCloseMs > 0) {
                PauseTransition pause = new PauseTransition(Duration.millis(autoCloseMs));
                pause.setOnFinished(e -> exit(0));
                pause.play();
            }
        } catch (Throwable error) {
            LOG.log(Level.SEVERE, "Could not attach bridge", error);
            if (smoke || compat) {
                writeFailureReport(error.toString());
                exit(1);
            }
        }
    }

    private void runSmokeTest(JSObject window) {
        try {
            Path dataset = extractResource("/testdata/project_test_dataset.csv", "project_test_dataset.csv");
            String pathJson = mapper.writeValueAsString(dataset.toAbsolutePath().toString());
            SmokeCallback callback = new SmokeCallback(dataset, screenshotPath());
            smokeCallback = callback;
            window.setMember("__tdSmokeBridge", callback);

            // Goes through the real front-end adapter (TD.services.analysis), not
            // a direct __TAURI__.core.invoke, so shim + adapter + event republish
            // are all exercised the same way the page uses them.
            String js = """
                    (function () {
                      var results = [];
                      var finished = false;
                      var backend = (window.TD && TD.services && TD.services.analysis)
                        ? TD.services.analysis.backend : "unavailable";
                      function finish(obj) {
                        if (finished) return;
                        finished = true;
                        window.__tdSmokeBridge.done(JSON.stringify(obj));
                      }
                      TD.events.on('analysis:item-done', function (e) {
                        results.push(e.result);
                      });
                      TD.events.on('analysis:batch-done', function (e) {
                        finish({ ok: true, count: e.count, batchId: e.batchId,
                                 results: results, backend: backend });
                      });
                      TD.services.analysis.analyzeCsvFile(%s, {
                        minItemDurationMs: 0, concurrency: 4
                      }).then(function (info) {
                        window.__smokeBatch = info;
                      }).catch(function (err) {
                        finish({ ok: false, error: String(err), results: results, backend: backend });
                      });
                      setTimeout(function () {
                        finish({ ok: false, error: 'timeout', results: results, backend: backend });
                      }, 25000);
                    })();
                    """.formatted(pathJson);
            engine.executeScript(js);
        } catch (Throwable error) {
            LOG.log(Level.SEVERE, "Smoke test could not start", error);
            writeFailureReport(error.toString());
            exit(1);
        }
    }

    private void runCompatAudit(JSObject window) {
        try {
            CompatCallback callback = new CompatCallback();
            compatCallback = callback;
            window.setMember("__tdCompatBridge", callback);
            engine.executeScript(COMPAT_JS);
        } catch (Throwable error) {
            LOG.log(Level.SEVERE, "Compat audit could not start", error);
            writeFailureReport(error.toString());
            exit(1);
        }
    }

    private Path extractResource(String resource, String fileName) throws Exception {
        Path dir = Path.of(System.getenv("APPDATA"), "ThreatDetector", "testdata");
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);
        try (InputStream in = App.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private void writeFailureReport(String message) {
        try {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("ok", false);
            report.put("error", message);
            report.put("timestamp", System.currentTimeMillis());
            mapper.writerWithDefaultPrettyPrinter().writeValue(
                    Path.of(System.getProperty("user.dir"), "smoke-test-report.json").toFile(), report);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not write failure report", e);
        }
    }

    private void runScreenshotHooks() {
        try {
            if (!clickSelector.isBlank()) {
                engine.executeScript("(function(){var el=document.querySelector("
                        + jsLiteral(clickSelector) + ");if(el)el.click();})()");
            }
            if (!scrollSelector.isBlank()) {
                engine.executeScript("(function(){var el=document.querySelector("
                        + jsLiteral(scrollSelector) + ");if(el)el.scrollIntoView({block:'center'});})()");
            }
        } catch (Throwable error) {
            LOG.log(Level.WARNING, "Screenshot hook failed", error);
        }
    }

    private String jsLiteral(String value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private Path screenshotPath() {
        String page = pageName == null || pageName.isBlank() ? "index" : pageName.replace(".html", "");
        page = page.replaceAll("[^A-Za-z0-9._-]", "_");
        String file = screenshotOut == null || screenshotOut.isBlank()
                ? "screenshot-" + page + ".png"
                : screenshotOut;
        return Path.of(file).toAbsolutePath();
    }

    private void captureScreenshot(Path out) {
        try {
            WritableImage image = stage.getScene().snapshot(null);
            BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            ImageIO.write(buffered, "png", out.toFile());
        } catch (Throwable error) {
            LOG.log(Level.WARNING, "Could not capture screenshot", error);
        }
    }

    private static String parseValue(List<String> args, String prefix, String fallback) {
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                String value = arg.substring(prefix.length()).trim();
                return value.isEmpty() ? fallback : value;
            }
        }
        return fallback;
    }

    private static long parseAutoClose(List<String> args) {
        for (String arg : args) {
            if (arg.startsWith("--auto-close-ms=")) {
                try {
                    return Long.parseLong(arg.substring("--auto-close-ms=".length()));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static void exit(int code) {
        Platform.exit();
        System.exit(code);
    }

    @Override
    public void stop() {
        if (bridge != null) {
            bridge.close();
        }
        if (host != null) {
            host.close();
        }
    }

    /** JavaScript calls this once the batch-done event arrives. */
    public final class SmokeCallback {
        private final Path dataset;
        private final Path screenshotPath;
        private final long startedAt = System.currentTimeMillis();

        SmokeCallback(Path dataset, Path screenshotPath) {
            this.dataset = dataset;
            this.screenshotPath = screenshotPath;
        }

        public void done(String json) {
            try {
                JsonNode payload = mapper.readTree(json);
                boolean ok = payload.path("ok").asBoolean(false);
                String backend = payload.path("backend").asText("");
                List<JsonNode> results = new ArrayList<>();
                payload.path("results").forEach(results::add);

                Map<String, Integer> levels = new LinkedHashMap<>();
                levels.put("Low", 0);
                levels.put("Medium", 0);
                levels.put("High", 0);
                levels.put("NotVerified", 0);
                for (JsonNode result : results) {
                    String level = result.path("riskLevel").asText("unknown");
                    levels.merge(level, 1, Integer::sum);
                }

                boolean expected = results.size() == 10
                        && levels.getOrDefault("Low", 0) == 2
                        && levels.getOrDefault("Medium", 0) == 2
                        && levels.getOrDefault("High", 0) == 4
                        && levels.getOrDefault("NotVerified", 0) == 2;
                boolean pass = ok && expected && "tauri".equals(backend);

                captureScreenshot(screenshotPath);

                Map<String, Object> report = new LinkedHashMap<>();
                report.put("ok", pass);
                report.put("timestamp", System.currentTimeMillis());
                report.put("durationMs", System.currentTimeMillis() - startedAt);
                report.put("dataset", dataset.toAbsolutePath().toString());
                report.put("backend", backend);
                report.put("count", results.size());
                report.put("levels", levels);
                report.put("batchId", payload.path("batchId").asText(""));
                report.put("error", payload.path("error").asText(""));
                report.put("userAgent", engine.getUserAgent());
                report.put("screenshot", screenshotPath.toAbsolutePath().toString());
                report.put("results", results);
                mapper.writerWithDefaultPrettyPrinter().writeValue(
                        Path.of(System.getProperty("user.dir"), "smoke-test-report.json").toFile(), report);

                LOG.info((pass ? "SMOKE PASS" : "SMOKE FAIL") + " count=" + results.size()
                        + " levels=" + levels + " backend=" + backend);
                exit(pass ? 0 : 1);
            } catch (Exception error) {
                LOG.log(Level.SEVERE, "Smoke report failed", error);
                writeFailureReport(error.toString());
                exit(1);
            }
        }
    }

    /** JavaScript calls this with the compatibility JSON. */
    public final class CompatCallback {
        public void done(String json) {
            try {
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("timestamp", System.currentTimeMillis());
                report.put("userAgent", engine.getUserAgent());
                JsonNode payload = mapper.readTree(json);
                boolean storage = payload.path("localStorage").asBoolean(false);
                // History and settings persist through localStorage. A missing
                // storage backend is a functional failure, unlike the cosmetic
                // CSS gaps recorded below.
                report.put("ok", storage);
                report.put("checks", mapper.convertValue(payload.path("checks"), Map.class));
                report.put("localStorage", storage);
                report.put("frameTiming", mapper.convertValue(payload.path("frameTiming"), Map.class));
                mapper.writerWithDefaultPrettyPrinter().writeValue(
                        Path.of(System.getProperty("user.dir"), "compat-report.json").toFile(), report);
                LOG.info((storage ? "COMPAT PASS" : "COMPAT FAIL: localStorage unavailable")
                        + " webkit=" + engine.getUserAgent());
                exit(storage ? 0 : 1);
            } catch (Exception error) {
                LOG.log(Level.SEVERE, "Compat report failed", error);
                writeFailureReport(error.toString());
                exit(1);
            }
        }
    }

    private static final String COMPAT_JS = """
            (function () {
              var checks = {};
              function css(prop, value) {
                try { return !!(window.CSS && CSS.supports(prop, value)); } catch (e) { return false; }
              }
              checks['css-color-mix'] = css('color', 'color-mix(in srgb, red 50%, blue)');
              checks['css-has'] = css('selector(:has(*))', '');
              checks['css-container'] = css('container-type', 'inline-size');
              checks['css-subgrid'] = css('grid-template-columns', 'subgrid');
              checks['css-content-visibility'] = css('content-visibility', 'auto');
              checks['css-aspect-ratio'] = css('aspect-ratio', '16 / 9');
              checks['css-backdrop-filter'] = css('backdrop-filter', 'blur(2px)');
              checks['css-dvh'] = css('height', '100dvh');
              checks['css-svh'] = css('height', '100svh');
              function evalCheck(source) {
                try { return eval(source) === true; } catch (e) { return false; }
              }
              checks['js-optional-chaining'] = evalCheck('(function(){return ({a:{b:1}}).a?.b===1})()');
              checks['js-nullish'] = evalCheck('(function(){var x=null;return (x ?? 2)===2})()');
              checks['js-class-fields'] = evalCheck('(function(){class A{x=1}return new A().x===1})()');
              checks['js-structured-clone'] = typeof structuredClone === 'function';
              checks['js-intersection-observer'] = typeof IntersectionObserver === 'function';
              checks['js-resize-observer'] = typeof ResizeObserver === 'function';
              checks['js-pointer-events'] = typeof PointerEvent === 'function';
              checks['js-random-uuid'] = !!(window.crypto && typeof crypto.randomUUID === 'function');
              var storage = false;
              try {
                localStorage.setItem('__td_probe', '1');
                storage = localStorage.getItem('__td_probe') === '1';
                localStorage.removeItem('__td_probe');
              } catch (e) { storage = false; }
              var frames = [];
              var previous = 0;
              var count = 0;
              function tick(t) {
                if (previous) frames.push(t - previous);
                previous = t;
                count++;
                if (count < 60) { requestAnimationFrame(tick); return; }
                var total = 0, max = 0;
                for (var i = 0; i < frames.length; i++) {
                  total += frames[i];
                  if (frames[i] > max) max = frames[i];
                }
                var avg = frames.length ? total / frames.length : 0;
                window.__tdCompatBridge.done(JSON.stringify({
                  checks: checks,
                  localStorage: storage,
                  frameTiming: { frames: frames.length, avgMs: avg, maxMs: max }
                }));
              }
              requestAnimationFrame(tick);
            })();
            """;
}
