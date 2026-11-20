package com.dac.threatdetector.app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingNode;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefInitializationException;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import javax.swing.SwingUtilities;

/**
 * Experimental Chromium (JCEF) backend. Loads the same WebHost-served UI but
 * renders it with Chromium (Blink) instead of the JavaFX WebView (WebKit).
 * The Java IPC bridge is NOT wired under JCEF yet (no WebEngine/JSObject),
 * so analysis falls back to the front-end mock backend. UI/visuals only.
 */
public final class JcefApp extends Application {

    private static final String DARK = "#101418";

    @Override
    public void start(Stage stage) {
        try {
            WebHost host = WebHost.start();
            String url = host.baseUrl() + "index.html?jcef=1";

            SwingNode node = new SwingNode();
            Scene scene = new Scene(new StackPane(node), 1100, 700, Color.web(DARK));
            stage.setTitle("Threat Detector (Chromium/JCEF)");
            stage.setScene(scene);
            stage.show();

            SwingUtilities.invokeLater(() -> {
                try {
                    CefAppBuilder builder = new CefAppBuilder();
                    builder.setInstallDir(new java.io.File(System.getProperty("user.home"), ".jcef"));
                    try { builder.getCefSettings().user_agent = "Mozilla/5.0 ... ThreatDetectorJCEF TDSystemDark"; } catch (Throwable t) {}
                    // Software rendering: this machine has no usable GPU adapter for CEF.
                    builder.addJcefArgs("--disable-gpu", "--disable-gpu-compositing", "--use-gl=swiftshader", "--disable-features=Vulkan");
                    CefApp app = builder.build();
                    CefClient client = app.createClient();
                    CefBrowser browser = client.createBrowser(url, false, false);
                    // tag the UA so the shim can detect Chromium
                    SwingUtilities.invokeLater(() -> node.setContent((javax.swing.JComponent) browser.getUIComponent()));
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        javafx.scene.control.Label l = new javafx.scene.control.Label("JCEF init failed: " + e);
                        ((StackPane) scene.getRoot()).getChildren().setAll(l);
                    });
                }
            });
        } catch (Throwable t) {
            javafx.scene.control.Label l = new javafx.scene.control.Label("Startup failed: " + t);
            stage.setScene(new Scene(new StackPane(l), 600, 200));
            stage.show();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
