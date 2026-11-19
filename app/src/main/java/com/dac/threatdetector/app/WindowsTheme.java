package com.dac.threatdetector.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Windows "app mode" theme from the registry:
 * HKCU\...\Themes\Personalize\AppsUseLightTheme (0 = dark apps).
 * The WebView does not expose this to the page, so the shell reads it here and
 * the shim uses it for matchMedia('(prefers-color-scheme: dark)').
 *
 * <p>Short-cached and thread-safe: the page polls it every few seconds. Every
 * failure path returns empty; the shim then keeps its previous value.
 */
public final class WindowsTheme {

    private static final String REG_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final String REG_VALUE = "AppsUseLightTheme";
    private static final Pattern DWORD =
            Pattern.compile(Pattern.quote(REG_VALUE) + "\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)");
    private static final long CACHE_MS = 5000;
    private static final long RETRY_MS = 2000;

    private static final Object LOCK = new Object();
    private static volatile Boolean cachedDark;
    private static volatile long cachedAt;

    private WindowsTheme() {
    }

    /** present=true -> dark, present=false -> light, empty -> unknown. */
    public static Optional<Boolean> isDark() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return Optional.empty();
        }
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (cachedDark != null && now - cachedAt < CACHE_MS) {
                return Optional.of(cachedDark);
            }
            if (cachedDark != null || now - cachedAt >= RETRY_MS) {
                Optional<Boolean> fresh = query();
                if (fresh.isPresent()) {
                    cachedDark = fresh.get();
                    cachedAt = now;
                    return fresh;
                }
            }
            // Unknown now: serve a stale value if we have one, else empty.
            Boolean stale = cachedDark;
            if (stale != null) {
                return Optional.of(stale);
            }
            cachedAt = now; // throttle retries
            return Optional.empty();
        }
    }

    private static Optional<Boolean> query() {
        try {
            ProcessBuilder builder = new ProcessBuilder("reg", "query", REG_KEY, "/v", REG_VALUE);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            Matcher matcher = DWORD.matcher(output);
            if (!matcher.find()) {
                return Optional.empty();
            }
            return Optional.of(Integer.parseInt(matcher.group(1), 16) == 0);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception error) {
            return Optional.empty();
        }
    }
}
