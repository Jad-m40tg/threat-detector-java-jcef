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
import java.time.Instant;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import netscape.javascript.JSObject;

/**
 * JavaFX shell: hosts the unchanged front-end in a WebView and wires the
 * bridge. Modes: normal, --smoke-test (bridge + dataset + JSON report),
 * --compat-audit (WebKit feature report), --theme-debug (appearance dump) and
 * --screenshot. Window policy lives in WindowState: 90% of the visual bounds
 * capped at 1280x800, saved geometry only when it still fits a screen, and
 * --reset-window support. Diagnostics: --window-debug writes window-debug.log.
 */
public final class App extends Application {

    private static final Logger LOG = Logger.getLogger(App.class.getName());
    private static final String DARK = "#101418";

    /** Process start, for time-to-page-ready numbers in perf/debug output. */
    public static final long START_NANOS = System.nanoTime();

    private final ObjectMapper mapper = new ObjectMapper();
    private WebHost host;
    private Bridge bridge;
    private Stage stage;
    private WebView webView;
    private WebEngine engine;
    private boolean smoke;
    private boolean compat;
    private boolean screenshot;
    private boolean themeDebug;
    private boolean windowDebug;
    private boolean resetWindow;
    private String pageName = "index.html";
    private String screenshotOut = "";
    private String clickSelector = "";
    private String scrollSelector = "";
    private long autoCloseMs;
    private Object smokeCallback;
    private Object compatCallback;
    private Object themeDebugCallback;

    /* ---- window state ---- */
    private Path windowStateFile;
    private WindowState.Bounds chosenBounds;
    private String boundsSource = "default (none)";
    private WindowState.Bounds savedCandidate;
    private Map<String, Object> windowReport;

    /* ---- smoke theme phases: set dark -> reload -> check -> set light ->
       reload -> check -> restore -> batch ---- */
    private String smokePhase = "set-dark";
    private String originalTheme;
    private final Map<String, Object> themeResults = new LinkedHashMap<>();

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        List<String> args = getParameters().getRaw();
        smoke = args.contains("--smoke-test");
        compat = args.contains("--compat-audit");
        screenshot = args.contains("--screenshot");
        themeDebug = args.contains("--theme-debug");
        windowDebug = args.contains("--window-debug");
        resetWindow = args.contains("--reset-window");
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
            Scene scene = new Scene(root, 1, 1, Color.web(DARK));
            scene.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.F11) {
                    stage.setFullScreen(!stage.isFullScreen());
                } else if (event.getCode() == KeyCode.ESCAPE && stage.isFullScreen()) {
                    stage.setFullScreen(false);
                }
            });

            applyWindowGeometry(primaryStage, scene);

            engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                if (newState == Worker.State.SCHEDULED) {
                    bridge.setPageReady(false);
                } else if (newState == Worker.State.SUCCEEDED) {
                    onPageReady();
                } else if (newState == Worker.State.FAILED) {
                    Throwable error = engine.getLoadWorker().getException();
                    LOG.log(Level.SEVERE, "Page load failed", error);
                    if (smoke || compat || themeDebug) {
                        writeFailureReport(error == null ? "page load failed" : error.toString());
                        exit(1);
                    }
                }
            });

            // Show the decorated window before the page loads so the title bar
            // and buttons appear immediately instead of after page-ready.
            primaryStage.show();
            captureWindowReport();
            if (windowDebug) {
                PauseTransition debugDump = new PauseTransition(Duration.millis(800));
                debugDump.setOnFinished(e -> writeWindowDebug());
                debugDump.play();
            }

            engine.load(host.baseUrl() + pageName);
        } catch (Throwable error) {
            LOG.log(Level.SEVERE, "Startup failed", error);
            if (smoke || compat || themeDebug) {
                writeFailureReport(error.toString());
                exit(1);
            } else {
                Platform.exit();
            }
        }
    }

    /**
     * Picks the startup geometry: saved state when it still fits a screen,
     * otherwise 90% of the primary visual bounds capped at 1280x800, centered.
     */
    private void applyWindowGeometry(Stage target, Scene scene) {
        target.initStyle(StageStyle.DECORATED);
        target.setTitle("Threat Detector");
        target.getIcons().add(new javafx.scene.image.Image(
                App.class.getResourceAsStream("/icons/icon.png")));

        List<WindowState.Area> areas = new ArrayList<>();
        for (Screen screen : Screen.getScreens()) {
            Rectangle2D vb = screen.getVisualBounds();
            areas.add(new WindowState.Area(vb.getMinX(), vb.getMinY(), vb.getWidth(), vb.getHeight()));
        }
        WindowState.Area primary = areas.isEmpty()
                ? new WindowState.Area(0, 0, 1280, 800)
                : areas.get(0);

        windowStateFile = Path.of(System.getenv("APPDATA"), "ThreatDetector", "window.state");
        if (!resetWindow) {
            savedCandidate = WindowState.load(windowStateFile).orElse(null);
        }

        Optional<WindowState.Bounds> validated = WindowState.validate(savedCandidate, areas);
        if (resetWindow) {
            boundsSource = savedCandidate == null ? "default (--reset-window, no state)" :
                    "default (--reset-window ignored " + fmt(savedCandidate) + ")";
        } else if (validated.isPresent()) {
            boundsSource = "saved";
        } else if (savedCandidate != null) {
            boundsSource = "default (saved " + fmt(savedCandidate) + " rejected: off-screen)";
        } else {
            boundsSource = "default (no saved state)";
        }

        chosenBounds = validated.orElseGet(() -> WindowState.defaultFor(primary));
        target.setScene(scene);
        target.setResizable(true);
        target.setMinWidth(Math.min(WindowState.MIN_WIDTH, primary.width()));
        target.setMinHeight(Math.min(WindowState.MIN_HEIGHT, primary.height()));
        target.setX(chosenBounds.x());
        target.setY(chosenBounds.y());
        target.setWidth(chosenBounds.width());
        target.setHeight(chosenBounds.height());
        target.setOnCloseRequest(event -> saveWindowState());

        LOG.info("WINDOW " + boundsSource + " -> " + fmt(chosenBounds)
                + " primaryVisual=" + round(primary.width()) + "x" + round(primary.height())
                + " scale=" + Screen.getPrimary().getOutputScaleX());
    }

    private static String fmt(WindowState.Bounds bounds) {
        return bounds == null ? "none"
                : "x=" + round(bounds.x()) + " y=" + round(bounds.y())
                        + " w=" + round(bounds.width()) + " h=" + round(bounds.height());
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private void saveWindowState() {
        if (stage == null || smoke || compat || screenshot || themeDebug || !stage.isShowing()
                || stage.isMaximized() || stage.isFullScreen()) {
            return;
        }
        WindowState.Bounds bounds = new WindowState.Bounds(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        WindowState.save(windowStateFile, bounds);
    }

    /** Snapshot of the shown window for the smoke report and window-debug.log. */
    private void captureWindowReport() {
        if (stage == null) {
            return;
        }
        Screen primary = Screen.getPrimary();
        Rectangle2D vb = primary.getVisualBounds();
        WindowState.Bounds current = new WindowState.Bounds(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        boolean inside = false;
        for (Screen screen : Screen.getScreens()) {
            Rectangle2D other = screen.getVisualBounds();
            if (current.fullyInside(new WindowState.Area(
                    other.getMinX(), other.getMinY(), other.getWidth(), other.getHeight()))) {
                inside = true;
                break;
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("x", stage.getX());
        report.put("y", stage.getY());
        report.put("width", stage.getWidth());
        report.put("height", stage.getHeight());
        report.put("insideVisualBounds", inside);
        report.put("visualBounds", Map.of(
                "minX", vb.getMinX(), "minY", vb.getMinY(),
                "width", vb.getWidth(), "height", vb.getHeight()));
        report.put("outputScaleX", primary.getOutputScaleX());
        report.put("outputScaleY", primary.getOutputScaleY());
        report.put("screens", Screen.getScreens().size());
        report.put("fullScreen", stage.isFullScreen());
        report.put("maximized", stage.isMaximized());
        report.put("resizable", stage.isResizable());
        report.put("stageStyle", stage.getStyle().name());
        report.put("minWidth", stage.getMinWidth());
        report.put("minHeight", stage.getMinHeight());
        report.put("source", boundsSource);
        windowReport = report;
    }

    /** --window-debug: the full diagnosis the brief asked for. */
    private void writeWindowDebug() {
        StringBuilder text = new StringBuilder();
        text.append("Threat Detector window debug\n");
        text.append("generated: ").append(Instant.now()).append('\n');
        text.append("javafx: ").append(System.getProperty("javafx.version"))
                .append("  java: ").append(System.getProperty("java.version")).append('\n');
        text.append("flags: windowDebug=").append(windowDebug)
                .append(" resetWindow=").append(resetWindow).append('\n');
        Screen primary = Screen.getPrimary();
        text.append("primary bounds: ").append(primary.getBounds()).append('\n');
        text.append("primary visualBounds: ").append(primary.getVisualBounds()).append('\n');
        text.append("primary outputScale: ").append(primary.getOutputScaleX())
                .append(" x ").append(primary.getOutputScaleY()).append('\n');
        text.append("screens (").append(Screen.getScreens().size()).append("):\n");
        for (Screen screen : Screen.getScreens()) {
            text.append("  ").append(screen).append(" visual=").append(screen.getVisualBounds())
                    .append(" scale=").append(screen.getOutputScaleX()).append('\n');
        }
        text.append("stage: x=").append(stage.getX()).append(" y=").append(stage.getY())
                .append(" w=").append(stage.getWidth()).append(" h=").append(stage.getHeight())
                .append(" fullScreen=").append(stage.isFullScreen())
                .append(" maximized=").append(stage.isMaximized())
                .append(" resizable=").append(stage.isResizable())
                .append(" style=").append(stage.getStyle())
                .append(" title=").append(stage.getTitle()).append('\n');
        text.append("stage min: ").append(stage.getMinWidth()).append("x").append(stage.getMinHeight())
                .append("  max: ").append(stage.getMaxWidth()).append("x").append(stage.getMaxHeight())
                .append('\n');
        text.append("scene: ").append(stage.getScene().getWidth()).append("x")
                .append(stage.getScene().getHeight())
                .append(" rootPref=").append(round(stage.getScene().getRoot().prefWidth(-1)))
                .append("x").append(round(stage.getScene().getRoot().prefHeight(-1))).append('\n');
        text.append("webView pref: ").append(round(webView.prefWidth(-1))).append("x")
                .append(round(webView.prefHeight(-1)))
                .append(" min=").append(round(webView.minWidth(-1))).append("x")
                .append(round(webView.minHeight(-1))).append('\n');
        text.append("saved state file: ").append(windowStateFile)
                .append(" exists=").append(windowStateFile != null
                        && java.nio.file.Files.exists(windowStateFile)).append('\n');
        text.append("saved candidate: ").append(fmt(savedCandidate)).append('\n');
        text.append("chosen: ").append(fmt(chosenBounds)).append(" source=").append(boundsSource).append('\n');
        if (windowReport != null) {
            text.append("insideVisualBounds: ").append(windowReport.get("insideVisualBounds")).append('\n');
        }
        try {
            Files.writeString(Path.of(System.getProperty("user.dir"), "window-debug.log"),
                    text.toString(), StandardCharsets.UTF_8);
            LOG.info("WINDOW DEBUG written to window-debug.log");
        } catch (Exception error) {
            LOG.log(Level.WARNING, "Could not write window-debug.log", error);
        }
    }

    private void onPageReady() {
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember("__tdJavaBridge", bridge);
            bridge.setPageReady(true);
            LOG.info("READY page loaded, bridge attached ("
                    + (System.nanoTime() - START_NANOS) / 1_000_000 + " ms since start)");

            if (smoke) {
                runSmokePhase(window);
            } else if (compat) {
                runCompatAudit(window);
            } else if (themeDebug) {
                runThemeDebug(window);
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
            if (smoke || compat || themeDebug) {
                writeFailureReport(error.toString());
                exit(1);
            }
        }
    }

    /* ================= smoke test (batch + theme + window) ================= */

    private void runSmokePhase(JSObject window) {
        switch (smokePhase) {
            case "set-dark" -> startSmoke(window);
            case "check-dark" -> {
                attachSmokeBridge(window);
                checkTheme(window, "dark");
            }
            case "check-light" -> {
                attachSmokeBridge(window);
                checkTheme(window, "light");
            }
            default -> {
                // "batch": page reloaded by a previous phase; just wait for done().
            }
        }
    }

    private void attachSmokeBridge(JSObject window) {
        if (smokeCallback == null) {
            LOG.warning("Smoke bridge missing; aborting smoke test");
            writeFailureReport("smoke bridge missing");
            exit(1);
            return;
        }
        window.setMember("__tdSmokeBridge", smokeCallback);
    }

    private void startSmoke(JSObject window) {
        try {
            Path dataset = extractResource("/testdata/project_test_dataset.csv", "project_test_dataset.csv");
            SmokeCallback callback = new SmokeCallback(dataset, screenshotPath());
            smokeCallback = callback;
            window.setMember("__tdSmokeBridge", callback);

            // Preserve the user's appearance choice; restored after the checks.
            try {
                Object stored = engine.executeScript(
                        "window.localStorage ? localStorage.getItem('dac_settings') : null");
                originalTheme = stored == null ? null
                        : mapper.readTree(String.valueOf(stored)).path("theme").asText("system");
            } catch (Exception error) {
                originalTheme = null;
            }

            setTheme("dark");
            PauseTransition settle = new PauseTransition(Duration.millis(600));
            settle.setOnFinished(e -> {
                smokePhase = "check-dark";
                engine.reload();
            });
            settle.play();
        } catch (Throwable error) {
            LOG.log(Level.SEVERE, "Smoke test could not start", error);
            writeFailureReport(error.toString());
            exit(1);
        }
    }

    /** Applies a theme through the page's own settings API (DAC.settings.set). */
    private void setTheme(String theme) {
        engine.executeScript("""
                (function () {
                  try {
                    if (!window.DAC || !DAC.settings) {
                      window.__tdSmokeBridge.themeError('DAC.settings unavailable');
                      return;
                    }
                    DAC.settings.set('theme', %s);
                  } catch (e) {
                    window.__tdSmokeBridge.themeError(String(e && e.message || e));
                  }
                })();
                """.formatted(jsLiteral(theme)));
    }

    /** Reloads the page and then asserts the resolved theme from cold storage. */
    private void checkTheme(JSObject window, String phase) {
        engine.executeScript("""
                (function () {
                  function lum(color) {
                    var m = /rgba?\\(([^)]+)\\)/.exec(color || '');
                    if (!m) return null;
                    var p = m[1].split(',').map(function (s) { return parseFloat(s); });
                    var r = p[0] / 255, g = p[1] / 255, b = p[2] / 255;
                    var f = function (c) { return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
                    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
                  }
                  var root = document.documentElement;
                  var body = document.body;
                  var card = document.querySelector('.card') || document.querySelector('#hero') || body;
                  var out = {
                    phase: %s,
                    attr: root.getAttribute('data-theme'),
                    storedTheme: null,
                    bodyBg: getComputedStyle(body).backgroundColor,
                    bodyLuminance: lum(getComputedStyle(body).backgroundColor),
                    cardBg: card ? getComputedStyle(card).backgroundColor : null,
                    colorScheme: getComputedStyle(root).colorScheme,
                    matchMediaDark: !!(window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches),
                    errors: (window.__tdDiag && window.__tdDiag.errors) ? window.__tdDiag.errors.slice(0, 10) : []
                  };
                  try {
                    var raw = localStorage.getItem('dac_settings');
                    if (raw) { out.storedTheme = (JSON.parse(raw) || {}).theme || null; }
                  } catch (e) {}
                  window.__tdSmokeBridge.themeChecked(JSON.stringify(out));
                })();
                """.formatted(jsLiteral(phase)));
    }

    private void afterThemeCheck(Map<String, Object> result) {
        String phase = String.valueOf(result.get("phase"));
        themeResults.put(phase, result);
        if ("dark".equals(phase)) {
            setTheme("light");
            PauseTransition settle = new PauseTransition(Duration.millis(600));
            settle.setOnFinished(e -> {
                smokePhase = "check-light";
                engine.reload();
            });
            settle.play();
        } else {
            // Restore the user's appearance choice before the batch runs.
            String restore = originalTheme == null ? "system" : originalTheme;
            if (!restore.equals("dark") && !restore.equals("light") && !restore.equals("system")) {
                restore = "system";
            }
            final String value = restore;
            setTheme(value);
            smokePhase = "batch";
            startBatch();
        }
    }

    /** The original batch smoke: real front-end adapter, real events. */
    private void startBatch() {
        try {
            Path dataset = extractResource("/testdata/project_test_dataset.csv", "project_test_dataset.csv");
            String pathJson = mapper.writeValueAsString(dataset.toAbsolutePath().toString());
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
            LOG.log(Level.SEVERE, "Smoke batch could not start", error);
            writeFailureReport(error.toString());
            exit(1);
        }
    }

    /* ================= theme debug dump ================= */

    private void runThemeDebug(JSObject window) {
        try {
            ThemeDebugCallback callback = new ThemeDebugCallback();
            themeDebugCallback = callback;
            window.setMember("__tdThemeDebugBridge", callback);
            engine.executeScript("""
                    (function () {
                      function dump(tag) {
                        function attrs(el) {
                          var out = {};
                          ['data-theme', 'data-bg', 'data-accent', 'data-text', 'data-density', 'data-motion']
                            .forEach(function (name) { out[name] = el.getAttribute(name); });
                          return out;
                        }
                        function lum(color) {
                          var m = /rgba?\\(([^)]+)\\)/.exec(color || '');
                          if (!m) return null;
                          var p = m[1].split(',').map(function (s) { return parseFloat(s); });
                          var f = function (c) { return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
                          return 0.2126 * f(p[0] / 255) + 0.7152 * f(p[1] / 255) + 0.0722 * f(p[2] / 255);
                        }
                        var root = document.documentElement;
                        var card = document.querySelector('.card') || document.body;
                        var keys = {};
                        try {
                          for (var i = 0; i < localStorage.length; i++) {
                            var k = localStorage.key(i);
                            keys[k] = localStorage.getItem(k);
                          }
                        } catch (e) { keys.error = String(e); }
                        var supports = {};
                        try {
                          supports['color-mix'] = CSS.supports('color', 'color-mix(in srgb, red 50%, blue)');
                          supports['container-queries'] = CSS.supports('container-type', 'inline-size');
                          supports[':has'] = CSS.supports('selector(:has(*))', '');
                        } catch (e2) {}
                        return {
                          tag: tag,
                          htmlAttributes: attrs(root),
                          localStorage: keys,
                          storedTheme: (function () {
                            try { return (JSON.parse(localStorage.getItem('dac_settings') || 'null') || {}).theme || null; }
                            catch (e3) { return null; }
                          })(),
                          matchMediaDark: !!(window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches),
                          bodyBg: getComputedStyle(document.body).backgroundColor,
                          bodyLuminance: lum(getComputedStyle(document.body).backgroundColor),
                          cardBg: getComputedStyle(card).backgroundColor,
                          colorScheme: getComputedStyle(root).colorScheme,
                          supports: supports,
                          errors: (window.__tdDiag && window.__tdDiag.errors) ? window.__tdDiag.errors.slice(0, 20) : []
                        };
                      }
                      var steps = [];
                      steps.push(dump('before'));
                      try { DAC.settings.set('theme', 'dark'); } catch (e) { steps.push({ tag: 'dark-error', error: String(e) }); }
                      setTimeout(function () {
                        steps.push(dump('after-dark'));
                        try { DAC.settings.set('theme', 'light'); } catch (e2) { steps.push({ tag: 'light-error', error: String(e2) }); }
                        setTimeout(function () {
                          steps.push(dump('after-light'));
                          window.__tdThemeDebugBridge.done(JSON.stringify({
                            userAgent: navigator.userAgent,
                            prefersColorSchemeSupported: (function () {
                              try { return !!window.matchMedia; } catch (e4) { return false; }
                            })(),
                            steps: steps
                          }));
                        }, 900);
                      }, 900);
                    })();
                    """);
        } catch (Throwable error) {
            LOG.log(Level.SEVERE, "Theme debug could not start", error);
            writeFailureReport(error.toString());
            exit(1);
        }
    }

    /** JavaScript calls this with the theme-debug JSON. */
    public final class ThemeDebugCallback {
        public void done(String json) {
            try {
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("timestamp", System.currentTimeMillis());
                report.put("userAgent", engine.getUserAgent());
                report.putAll(mapper.readValue(json, Map.class));
                mapper.writerWithDefaultPrettyPrinter().writeValue(
                        Path.of(System.getProperty("user.dir"), "theme-debug.json").toFile(), report);
                LOG.info("THEME DEBUG written to theme-debug.json");
                exit(0);
            } catch (Exception error) {
                LOG.log(Level.SEVERE, "Theme debug report failed", error);
                writeFailureReport(error.toString());
                exit(1);
            }
        }
    }

    /* ================= shared helpers ================= */

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
        saveWindowState();
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

        /** Received from the page after a theme phase settled. */
        public void themeChecked(String json) {
            try {
                afterThemeCheck(mapper.readValue(json, Map.class));
            } catch (Exception error) {
                LOG.log(Level.SEVERE, "Theme check failed", error);
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("phase", smokePhase.contains("dark") ? "dark" : "light");
                fallback.put("error", String.valueOf(error));
                afterThemeCheck(fallback);
            }
        }

        /** Received from the page when DAC.settings was unusable. */
        public void themeError(String message) {
            LOG.warning("Theme smoke error: " + message);
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("phase", smokePhase.contains("dark") ? "dark" : "light");
            fallback.put("error", message);
            fallback.put("pass", false);
            themeResults.put(String.valueOf(fallback.get("phase")), fallback);
            if ("dark".equals(fallback.get("phase"))) {
                setTheme("light");
                PauseTransition settle = new PauseTransition(Duration.millis(600));
                settle.setOnFinished(e -> {
                    smokePhase = "check-light";
                    engine.reload();
                });
                settle.play();
            } else {
                smokePhase = "batch";
                startBatch();
            }
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

                captureWindowReport();
                if (windowDebug) {
                    writeWindowDebug();
                }
                boolean windowOk = windowReport != null
                        && Boolean.TRUE.equals(windowReport.get("insideVisualBounds"))
                        && Boolean.TRUE.equals(windowReport.get("resizable"))
                        && "DECORATED".equals(windowReport.get("stageStyle"))
                        && !Boolean.TRUE.equals(windowReport.get("fullScreen"));

                boolean themeOk = themePass("dark") && themePass("light");

                boolean pass = ok && expected && "tauri".equals(backend)
                        && windowOk && themeOk;

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
                report.put("window", windowReport);
                report.put("theme", themeResults);
                report.put("screenshot", screenshotPath.toAbsolutePath().toString());
                report.put("results", results);
                mapper.writerWithDefaultPrettyPrinter().writeValue(
                        Path.of(System.getProperty("user.dir"), "smoke-test-report.json").toFile(), report);

                LOG.info((pass ? "SMOKE PASS" : "SMOKE FAIL") + " count=" + results.size()
                        + " levels=" + levels + " backend=" + backend
                        + " windowOk=" + windowOk + " themeOk=" + themeOk);
                exit(pass ? 0 : 1);
            } catch (Exception error) {
                LOG.log(Level.SEVERE, "Smoke report failed", error);
                writeFailureReport(error.toString());
                exit(1);
            }
        }

        /** Dark must be dark (low luminance), light must be light. */
        private boolean themePass(String phase) {
            Object raw = themeResults.get(phase);
            if (!(raw instanceof Map<?, ?> data)) {
                return false;
            }
            boolean attrOk = phase.equals(String.valueOf(data.get("attr")))
                    && phase.equals(String.valueOf(data.get("storedTheme")));
            Object lum = data.get("bodyLuminance");
            if (!(lum instanceof Number number)) {
                return false;
            }
            double value = number.doubleValue();
            return attrOk && ("dark".equals(phase) ? value < 0.2 : value > 0.55);
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
