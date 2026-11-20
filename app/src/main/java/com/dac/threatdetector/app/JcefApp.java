package com.dac.threatdetector.app;

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

/**
 * Experimental Chromium (JCEF) backend. Loads the same WebHost-served UI but
 * renders it with Chromium (Blink) instead of the JavaFX WebView (WebKit).
 *
 * <p>Runs as a plain Swing window on purpose: JCEF's windowed browser
 * component is a heavyweight AWT {@code Canvas}, and JavaFX's {@code SwingNode}
 * cannot composite heavyweight components, which is what produced the black
 * window in the first attempt.
 *
 * <p>The Java IPC bridge is NOT wired under JCEF yet (no WebEngine/JSObject),
 * so analysis falls back to the front-end mock backend. UI/visuals only.
 */
public final class JcefApp {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 "
            + "ThreatDetectorJCEF TDSystemDark";

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
        // browser profile in the app's own writable dir.
        File installDir = new File(System.getProperty("user.home"), ".jcef");
        File dataDir = new File(System.getenv("LOCALAPPDATA"), "ThreatDetector/jcef/profile");
        installDir.mkdirs();
        dataDir.mkdirs();
        builder.setInstallDir(installDir);

        CefSettings settings = builder.getCefSettings();
        settings.user_agent = USER_AGENT;
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

            CefClient client = app.createClient();
            CefBrowser browser = client.createBrowser(url, false, false);

            JPanel content = new JPanel(new BorderLayout());
            content.add(browser.getUIComponent(), BorderLayout.CENTER);

            JFrame frame = new JFrame("Threat Detector (Chromium/JCEF)");
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setContentPane(content);
            frame.setMinimumSize(new Dimension(900, 600));
            frame.setSize(1152, 700);
            frame.setLocationRelativeTo(null);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    browser.close(true);
                    frame.dispose();
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
}
