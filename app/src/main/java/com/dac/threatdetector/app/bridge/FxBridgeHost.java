package com.dac.threatdetector.app.bridge;

import java.io.File;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

/**
 * JavaFX WebView host: FX-thread scheduling, direct JSObject delivery (one
 * {@code __deliver} call per chunk) and the native JavaFX file chooser.
 */
public final class FxBridgeHost implements BridgeHost {

    private final Stage stage;
    private volatile JSObject shim;

    public FxBridgeHost(Stage stage) {
        this.stage = stage;
    }

    /** Keeps the page's shim object; call on the FX thread after each load. */
    public void attach(JSObject window) {
        try {
            Object candidate = window == null ? null : window.getMember("__tdShim");
            shim = candidate instanceof JSObject object ? object : null;
        } catch (Throwable error) {
            shim = null;
        }
    }

    /** Current subscription set, or null when the shim is unavailable. */
    public String subscriptions() {
        JSObject target = shim;
        if (target == null) {
            return null;
        }
        try {
            return String.valueOf(target.call("__subs"));
        } catch (Throwable error) {
            return null;
        }
    }

    @Override
    public void runOnUiThread(Runnable task) {
        Platform.runLater(task);
    }

    @Override
    public void deliver(String messagesJson) {
        JSObject target = shim;
        if (target == null) {
            throw new IllegalStateException("JS shim is not attached");
        }
        target.call("__deliver", messagesJson);
    }

    @Override
    public void pickCsv(Consumer<String> onPath, Consumer<Throwable> onError) {
        Platform.runLater(() -> {
            try {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Select a CSV file to analyze");
                chooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("CSV files", "*.csv"));
                File file = chooser.showOpenDialog(stage);
                onPath.accept(file == null ? null : file.getAbsolutePath());
            } catch (Throwable error) {
                onError.accept(error);
            }
        });
    }
}
