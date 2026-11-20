package com.dac.threatdetector.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javafx.application.Application;

/** Plain main class, so a shaded jar never hits "JavaFX runtime components are missing". */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        // Prefer the hardware (D3D) pipeline; fall back to software only when
        // D3D is unavailable. Overridable with -Dprism.order=... .
        if (System.getProperty("prism.order") == null) {
            System.setProperty("prism.order", "d3d,sw");
        }
        configureLogging();
        if (java.util.Arrays.asList(args).contains("--jcef")) {
            Application.launch(JcefApp.class, args);
        } else {
            Application.launch(App.class, args);
        }
    }

    /** Mirrors console logging into %APPDATA%/ThreatDetector/app.log for support. */
    private static void configureLogging() {
        try {
            Path dir = Path.of(System.getenv("APPDATA"), "ThreatDetector");
            Files.createDirectories(dir);
            FileHandler handler = new FileHandler(
                    dir.resolve("app.log").toString(), 1_000_000, 1, true);
            handler.setFormatter(new SimpleFormatter());
            handler.setLevel(Level.INFO);
            Logger root = Logger.getLogger("");
            root.addHandler(handler);
            root.setLevel(Level.INFO);
        } catch (Exception error) {
            System.err.println("Could not open app.log: " + error.getMessage());
        }
    }
}
