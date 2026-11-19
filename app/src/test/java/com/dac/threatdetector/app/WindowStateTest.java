package com.dac.threatdetector.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dac.threatdetector.app.WindowState.Area;
import com.dac.threatdetector.app.WindowState.Bounds;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowStateTest {

    private static final Area SCREEN_1080 =
            new Area(0, 0, 1920, 1040);
    private static final Area SCREEN_720 =
            new Area(0, 0, 1280, 720);
    private static final Area SCREEN_SMALL =
            new Area(0, 0, 800, 600);

    @Test
    void firstLaunchUsesNinetyPercentCappedAt1280x800() {
        Bounds bounds = WindowState.defaultFor(SCREEN_1080);
        assertEquals(1280.0, bounds.width(), 0.001);
        assertEquals(800.0, bounds.height(), 0.001);
        assertEquals(320.0, bounds.x(), 0.001);
        assertEquals(120.0, bounds.y(), 0.001);
        assertTrue(bounds.fullyInside(SCREEN_1080));
    }

    @Test
    void firstLaunchShrinksToFitSmallVisualBounds() {
        Bounds bounds = WindowState.defaultFor(SCREEN_720);
        assertEquals(1152.0, bounds.width(), 0.001);
        assertEquals(648.0, bounds.height(), 0.001);
        assertEquals(64.0, bounds.x(), 0.001);
        assertEquals(36.0, bounds.y(), 0.001);
        assertTrue(bounds.fullyInside(SCREEN_720));
    }

    @Test
    void minimumIsCappedByASmallerScreen() {
        Bounds bounds = WindowState.defaultFor(SCREEN_SMALL);
        assertEquals(800.0, bounds.width(), 0.001);
        assertEquals(600.0, bounds.height(), 0.001);
        assertEquals(0.0, bounds.x(), 0.001);
        assertEquals(0.0, bounds.y(), 0.001);
        assertTrue(bounds.fullyInside(SCREEN_SMALL));
    }

    @Test
    void savedGeometryIsAcceptedWhenFullyInside() {
        Bounds saved = new Bounds(100, 80, 1100, 700);
        Optional<Bounds> result = WindowState.validate(saved, List.of(SCREEN_1080));
        assertTrue(result.isPresent());
        assertEquals(saved, result.orElseThrow());
    }

    @Test
    void savedGeometryOffScreenOrCorruptIsRejected() {
        assertTrue(WindowState.validate(
                new Bounds(1700, 80, 1100, 700), List.of(SCREEN_1080)).isEmpty());
        assertTrue(WindowState.validate(
                new Bounds(-900, 80, 1100, 700), List.of(SCREEN_1080)).isEmpty());
        assertTrue(WindowState.validate(
                new Bounds(Double.NaN, 0, 1000, 700), List.of(SCREEN_1080)).isEmpty());
    }

    @Test
    void tinySavedWindowIsGrownToTheMinimumWhenItStillFits() {
        Optional<Bounds> result = WindowState.validate(
                new Bounds(10, 10, 400, 300), List.of(SCREEN_1080));
        assertTrue(result.isPresent());
        assertEquals(900.0, result.orElseThrow().width(), 0.001);
        assertEquals(600.0, result.orElseThrow().height(), 0.001);
    }

    @Test
    void boundsOnASecondScreenAreValid() {
        Area secondary = new Area(1920, 0, 1920, 1040);
        assertTrue(WindowState.validate(
                new Bounds(2100, 100, 1100, 700), List.of(SCREEN_1080, secondary)).isPresent());
    }

    @Test
    void saveAndLoadRoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("window.state");
        Bounds bounds = new Bounds(12.5, 34.25, 1024.0, 768.0);
        WindowState.save(file, bounds);
        assertEquals(Optional.of(bounds), WindowState.load(file));
    }

    @Test
    void corruptStateLoadsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("window.state");
        Files.writeString(file, "x=oops\nwidth=-1\n");
        assertTrue(WindowState.load(file).isEmpty());
        assertFalse(WindowState.load(dir.resolve("missing.state")).isPresent());
    }
}
