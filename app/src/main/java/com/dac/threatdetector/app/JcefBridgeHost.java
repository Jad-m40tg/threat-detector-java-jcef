package com.dac.threatdetector.app;

import com.dac.threatdetector.app.bridge.BridgeHost;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.function.Consumer;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.cef.browser.CefBrowser;

/**
 * JCEF host: schedules on the Swing EDT, injects messages into the page with
 * {@code executeJavaScript}, and uses a Swing file chooser.
 *
 * <p>The browser is a heavyweight AWT component, so all UI work stays on the
 * EDT. Messages use the same JSON envelope as the JavaFX path; the page-side
 * shim is identical.
 */
final class JcefBridgeHost implements BridgeHost {

    private final JFrame frame;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile CefBrowser browser;

    JcefBridgeHost(JFrame frame) {
        this.frame = frame;
    }

    void attach(CefBrowser target) {
        browser = target;
    }

    void detach() {
        browser = null;
    }

    @Override
    public void runOnUiThread(Runnable task) {
        SwingUtilities.invokeLater(task);
    }

    @Override
    public void deliver(String messagesJson) {
        CefBrowser target = browser;
        if (target == null) {
            throw new IllegalStateException("Chromium page is not ready");
        }
        String literal;
        try {
            literal = mapper.writeValueAsString(messagesJson);
        } catch (Exception error) {
            throw new IllegalStateException("Could not encode the JS payload", error);
        }
        target.executeJavaScript(
                "window.__tdShim&&window.__tdShim.__deliver(" + literal + ");",
                target.getURL(),
                0);
    }

    @Override
    public void pickCsv(Consumer<String> onPath, Consumer<Throwable> onError) {
        SwingUtilities.invokeLater(() -> {
            try {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Select a CSV file to analyze");
                chooser.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
                int result = chooser.showOpenDialog(frame);
                if (result != JFileChooser.APPROVE_OPTION) {
                    onPath.accept(null);
                    return;
                }
                File file = chooser.getSelectedFile();
                onPath.accept(file == null ? null : file.getAbsolutePath());
            } catch (Throwable error) {
                onError.accept(error);
            }
        });
    }
}
