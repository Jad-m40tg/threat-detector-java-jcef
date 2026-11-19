package com.dac.threatdetector.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Window geometry policy: 90% of the screen (capped at 1280x800) on first
 * launch, saved geometry only when it still fits a screen, and --reset-window
 * support. Pure data, so it is unit tested without a JavaFX toolkit.
 */
public final class WindowState {

    public static final double MAX_WIDTH = 1280.0;
    public static final double MAX_HEIGHT = 800.0;
    public static final double MIN_WIDTH = 900.0;
    public static final double MIN_HEIGHT = 600.0;

    /** Tolerance for Windows' invisible resize border rounding. */
    private static final double TOLERANCE = 2.0;

    private WindowState() {
    }

    public record Bounds(double x, double y, double width, double height) {

        public boolean finitePositive() {
            return Double.isFinite(x) && Double.isFinite(y)
                    && Double.isFinite(width) && Double.isFinite(height)
                    && width > 0 && height > 0;
        }

        public boolean fullyInside(Area area) {
            return finitePositive()
                    && x >= area.x() - TOLERANCE
                    && y >= area.y() - TOLERANCE
                    && x + width <= area.x() + area.width() + TOLERANCE
                    && y + height <= area.y() + area.height() + TOLERANCE;
        }
    }

    /** A screen's visual bounds in JavaFX logical pixels. */
    public record Area(double x, double y, double width, double height) {
        public boolean usable() {
            return Double.isFinite(x) && Double.isFinite(y)
                    && Double.isFinite(width) && Double.isFinite(height)
                    && width > 0 && height > 0;
        }
    }

    /** 90% of the visual bounds, centered, capped at 1280x800 and at the screen. */
    public static Bounds defaultFor(Area area) {
        if (!area.usable()) {
            return new Bounds(0, 0, MAX_WIDTH, MAX_HEIGHT);
        }
        double minW = Math.min(MIN_WIDTH, area.width());
        double minH = Math.min(MIN_HEIGHT, area.height());
        double width = Math.min(area.width(), Math.max(minW, area.width() * 0.9));
        double height = Math.min(area.height(), Math.max(minH, area.height() * 0.9));
        width = Math.min(width, MAX_WIDTH);
        height = Math.min(height, MAX_HEIGHT);
        return new Bounds(
                area.x() + (area.width() - width) / 2.0,
                area.y() + (area.height() - height) / 2.0,
                width,
                height);
    }

    /**
     * Returns the saved geometry enlarged to the minimum size when it fits a
     * screen. Off-screen, tiny or corrupt state is rejected, never clamped into
     * a confusing half-visible window.
     */
    public static Optional<Bounds> validate(Bounds saved, List<Area> screens) {
        if (saved == null || !saved.finitePositive()) {
            return Optional.empty();
        }
        for (Area area : screens) {
            if (area == null || !area.usable()) {
                continue;
            }
            double minW = Math.min(MIN_WIDTH, area.width());
            double minH = Math.min(MIN_HEIGHT, area.height());
            Bounds fitted = new Bounds(
                    saved.x(), saved.y(),
                    Math.max(saved.width(), minW),
                    Math.max(saved.height(), minH));
            if (fitted.fullyInside(area)) {
                return Optional.of(fitted);
            }
        }
        return Optional.empty();
    }

    /** Reads a corrupt or missing file as empty; never throws. */
    public static Optional<Bounds> load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            double x = Double.NaN;
            double y = Double.NaN;
            double width = Double.NaN;
            double height = Double.NaN;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                int split = text.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                String key = text.substring(0, split).trim();
                String value = text.substring(split + 1).trim();
                switch (key) {
                    case "x" -> x = parse(value);
                    case "y" -> y = parse(value);
                    case "width" -> width = parse(value);
                    case "height" -> height = parse(value);
                    default -> {
                        // Forward-compatible: unknown keys are ignored.
                    }
                }
            }
            Bounds bounds = new Bounds(x, y, width, height);
            return bounds.finitePositive() ? Optional.of(bounds) : Optional.empty();
        } catch (IOException | RuntimeException error) {
            return Optional.empty();
        }
    }

    private static double parse(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException error) {
            return Double.NaN;
        }
    }

    /** Best effort: a failed save must never block closing the window. */
    public static void save(Path file, Bounds bounds) {
        if (file == null || bounds == null || !bounds.finitePositive()) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String text = "# Threat Detector window state\n"
                    + "x=" + bounds.x() + "\n"
                    + "y=" + bounds.y() + "\n"
                    + "width=" + bounds.width() + "\n"
                    + "height=" + bounds.height() + "\n";
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Diagnostics only; the next launch falls back to the default size.
        }
    }
}
