# JCEF (Chromium) backend — experimental

Branch: `feature/jcef-chromium`

The standard build renders the UI with the JavaFX WebView (WebKit 617.1).
This branch adds an optional second backend that renders the same front-end
with Chromium (CEF 135) through [JCEFMaven](https://github.com/jcefmaven/jcefmaven).

## Run

```bat
rem JavaFX WebKit (unchanged default)
java -jar app\target\threat-detector-java-1.0.0-all.jar

rem Chromium / JCEF
java -jar app\target\threat-detector-java-1.0.0-all.jar --jcef

rem Chromium with the local DevTools protocol on 127.0.0.1:9222
java -jar app\target\threat-detector-java-1.0.0-all.jar --jcef --devtools
```

The homepage banner reports the active engine:
`Rendering engine: Chromium (JCEF)` or `Rendering engine: JavaFX WebKit`.

## Why a Swing window?

JCEF's windowed browser component is a heavyweight AWT `Canvas`. JavaFX's
`SwingNode` cannot composite heavyweight components, so embedding it there
produced a black window. `JcefApp` therefore opens a plain `JFrame` instead of
a JavaFX `Stage`; JavaFX is not started in `--jcef` mode.

## Current limitations

- **UI only.** The Java IPC bridge (`window.javaBridge`) is not wired under
  JCEF yet, so the front-end falls back to its mock backend. Analysis results
  are placeholders in this mode.
- **Software rendering.** This machine exposes no usable GPU adapter to CEF,
  so the launcher passes `--disable-gpu`, `--disable-gpu-compositing` and
  `--use-angle=swiftshader`.
- **First run downloads ~130 MB** of Chromium binaries into `~/.jcef`.
  The browser profile lives in `%LOCALAPPDATA%\ThreatDetector\jcef\profile`.
- `Failed opening key Software\Chromium to set usagestats; result: 5` is a
  benign CEF warning ([java-cef#505](https://github.com/chromiumembedded/java-cef/issues/505));
  it is not the cause of a blank window.
- No JavaFX FPS overlay in this mode.

## Files

- `app/pom.xml` — `me.friwi:jcefmaven:135.0.20`
- `app/src/main/java/com/dac/threatdetector/app/JcefApp.java` — Swing host
- `app/src/main/java/com/dac/threatdetector/app/Launcher.java` — `--jcef` routing
- `app/src/main/resources/web/index.html` — engine banner
