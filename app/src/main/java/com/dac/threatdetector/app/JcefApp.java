package com.dac.threatdetector.app;

import com.dac.threatdetector.app.bridge.Bridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.network.CefRequest;

/**
 * Experimental Chromium (JCEF) backend. Loads the same WebHost-served UI but
 * renders it with Chromium (Blink) instead of the JavaFX WebView (WebKit).
 *
 * <p>Runs as a plain Swing window on purpose: JCEF's windowed browser
 * component is a heavyweight AWT {@code Canvas}, and JavaFX's {@code SwingNode}
 * cannot composite heavyweight components, which is what produced the black
 * window in the first attempt.
 *
 * <p>Analysis uses the same {@link Bridge} and core engine as the JavaFX build.
 * JavaScript calls arrive through {@code CefMessageRouter} and replies/events
 * are injected back into {@code window.__tdShim.__deliver}.
 */
public final class JcefApp {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 "
            + "ThreatDetectorJCEF";

    private JcefApp() {
    }

    /** Entry point used by {@link Launcher} when {@code --jcef} is passed. */
    public static void start(String[] args) {
        boolean devtools = java.util.Arrays.asList(args).contains("--devtools");
        // Building the CEF runtime can extract/download ~100 MB on first run.
        // Do it off the EDT so the JVM never looks hung.
        Thread bootstrap = new Thread(() -> {
            try {
                CefApp app = buildCefApp(devtools);
                SwingUtilities.invokeLater(() -> showWindow(app));
            } catch (Throwable error) {
                error.printStackTrace();
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        null,
                        "Chromium (JCEF) failed to start:\n" + error,
                        "Threat Detector",
                        JOptionPane.ERROR_MESSAGE));
            }
        }, "jcef-bootstrap");
        bootstrap.setDaemon(false);
        bootstrap.start();
    }

    private static CefApp buildCefApp(boolean devtools) throws Exception {
        CefAppBuilder builder = new CefAppBuilder();

        // Reuse the CEF bundle already unpacked by the first JCEF run; keep the
        // browser profile in the app's own writable dir. The packaged installer
        // points td.jcef.dir at the bundle shipped next to the launcher.
        File installDir = new File(System.getProperty("td.jcef.dir",
                new File(System.getProperty("user.home"), ".jcef").getPath()));
        File dataDir = new File(System.getenv("LOCALAPPDATA"), "ThreatDetector/jcef/profile");
        installDir.mkdirs();
        dataDir.mkdirs();
        builder.setInstallDir(installDir);
        if (System.getProperty("td.jcef.dir") != null) {
            // Packaged installs ship the extracted CEF bundle; never download.
            builder.setSkipInstallation(true);
        }

        CefSettings settings = builder.getCefSettings();
        String themeMarker = WindowsTheme.isDark()
                .map(dark -> dark ? "TDSystemDark" : "TDSystemLight")
                .orElse("TDSystemUnknown");
        settings.user_agent = USER_AGENT + " " + themeMarker;
        settings.cache_path = dataDir.getAbsolutePath();
        settings.root_cache_path = dataDir.getAbsolutePath();
        settings.log_file = new File(dataDir, "cef.log").getAbsolutePath();
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_INFO;
        settings.windowless_rendering_enabled = false;
        if (devtools) {
            // Opt-in only: exposes a localhost CDP endpoint for debugging.
            settings.remote_debugging_port = 9222;
        }

        // This machine exposes no usable GPU adapter to CEF, so pin Chromium
        // to software rendering. The HKCU\Software\Chromium "usagestats"
        // warning is benign (chromiumembedded/java-cef#505) and unrelated.
        builder.addJcefArgs(
                "--disable-gpu",
                "--disable-gpu-compositing",
                "--use-angle=swiftshader",
                "--disable-features=Vulkan",
                "--no-sandbox",
                "--disable-background-networking",
                "--disable-component-update",
                "--no-default-browser-check",
                "--disable-sync",
                "--disable-restore-session-state");

        return builder.build();
    }

    private static void showWindow(CefApp app) {
        try {
            WebHost host = WebHost.start();
            String url = host.baseUrl() + "index.html?jcef=1";

            JFrame frame = new JFrame("Threat Detector (Chromium/JCEF)");
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setMinimumSize(new Dimension(900, 600));
            frame.setSize(1152, 700);
            frame.setLocationRelativeTo(null);

            JcefBridgeHost bridgeHost = new JcefBridgeHost(frame);
            Bridge bridge = new Bridge(bridgeHost);
            ObjectMapper mapper = new ObjectMapper();

            CefClient client = app.createClient();
            CefMessageRouter router = CefMessageRouter.create(
                    new QueryHandler(bridge, mapper));
            client.addMessageRouter(router);
            client.addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadStart(CefBrowser browser, CefFrame frame,
                        CefRequest.TransitionType type) {
                    if (frame != null && frame.isMain()) {
                        bridge.setPageReady(false);
                    }
                }

                @Override
                public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                    if (frame == null || !frame.isMain()) {
                        return;
                    }
                    bridgeHost.attach(browser);
                    bridge.attachPage();
                    bridge.setPageReady(true);
                    // The shim has been live since <head>; tell Java which
                    // analysis events the page actually listens to.
                    browser.executeJavaScript(
                            "(function(){try{var s=window.__tdShim;"
                            + "if(s&&window.__tdJavaBridge&&window.__tdJavaBridge.sync)"
                            + "window.__tdJavaBridge.sync(s.__subs());}catch(e){}})()",
                            browser.getURL(),
                            0);
                }
            });

            CefBrowser browser = client.createBrowser(url, false, false);
            bridgeHost.attach(browser);

            JPanel content = new JPanel(new BorderLayout());
            content.add(browser.getUIComponent(), BorderLayout.CENTER);
            frame.setContentPane(content);

            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    bridge.setPageReady(false);
                    bridgeHost.detach();
                    browser.close(true);
                    frame.dispose();
                    client.dispose();
                    app.dispose();
                    host.close();
                }
            });
            frame.setVisible(true);
        } catch (Throwable error) {
            error.printStackTrace();
            JOptionPane.showMessageDialog(
                    null,
                    "Chromium (JCEF) window failed:\n" + error,
                    "Threat Detector",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Receives the same call/subscribe/sync envelopes the JavaFX bridge exposes
     * as JSObject members, and acknowledges them immediately. Actual replies are
     * delivered asynchronously by {@link Bridge} through the page shim.
     */
    private static final class QueryHandler extends CefMessageRouterHandlerAdapter {

        private final Bridge bridge;
        private final ObjectMapper mapper;

        QueryHandler(Bridge bridge, ObjectMapper mapper) {
            this.bridge = bridge;
            this.mapper = mapper;
        }

        @Override
        public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                String request, boolean persistent, CefQueryCallback callback) {
            try {
                JsonNode message = mapper.readTree(request == null ? "{}" : request);
                if (message.hasNonNull("r")) {
                    bridge.call(
                            message.path("r").asText(),
                            message.path("c").asText(),
                            message.path("a").asText("{}"));
                } else if (message.hasNonNull("s")) {
                    bridge.subscribe(
                            message.path("s").asText(),
                            message.path("on").asBoolean(false));
                } else if (message.path("subs").isArray()) {
                    bridge.setSubscriptions(message.path("subs").toString());
                }
                callback.success("{}");
            } catch (Throwable error) {
                String text = error.getMessage();
                callback.failure(0, text == null || text.isEmpty()
                        ? error.getClass().getSimpleName() : text);
            }
            return true;
        }
    }
}
