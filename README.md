# Threat Detector (Java + JCEF / Chromium)

Offline desktop threat inspection for suspicious URLs, messages and CSV batches.
Same Java backend and HTML front-end as the JavaFX WebKit edition, but the UI is
rendered by **Chromium (CEF 135)** through
[JCEFMaven](https://github.com/jcefmaven/jcefmaven) instead of the JavaFX WebView.
Nothing analyzed is ever fetched or opened; the local HTTP server only serves the
app's own extracted assets.

Sibling editions: `threat-detector-tauri` (Rust) and
`threat-detector-java-webkit` (JavaFX WebView).

## Download

Windows x64 installer (bundled Java runtime and Chromium natives, fully offline):
[ThreatDetector-JavaJCEF-x64-Setup.exe](https://github.com/Jad-m40tg/threat-detector-java-jcef/releases/latest/download/ThreatDetector-JavaJCEF-x64-Setup.exe)

The installer is unsigned, so Windows SmartScreen shows "Unknown publisher".
Click **More info**, then **Run anyway**.

## Requirements (source build)

- JDK 22 and Maven 3.9+
- Inno Setup 6 (`iscc`) for the installer step
- The CEF native bundle: JCEFMaven downloads it into `~/.jcef` on the first
  `mvn exec` run in development; the installer ships it pre-extracted.

## Build and run from source

```bat
mvn -q -DskipTests package
java -jar app\target\threat-detector-java-1.0.0-all.jar --jcef
```

Without `--jcef` the same jar runs the JavaFX WebKit backend.

## Build the standalone installer

```bat
mvn -q -DskipTests package
jlink --add-modules ALL-MODULE-PATH --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output ..\runtime-jcef
jpackage --type app-image --name ThreatDetector-JavaJCEF --dest ..\image --input app\target --main-jar threat-detector-java-1.0.0-all.jar --main-class com.dac.threatdetector.app.Launcher --runtime-image ..\runtime-jcef --icon app\src\main\resources\icons\icon.ico --app-version 1.0.0 --java-options "-Dtd.jcef.dir=$APPDIR\jcef"
xcopy /e /i /y "%USERPROFILE%\.jcef" "..\image\ThreatDetector-JavaJCEF\jcef"
iscc tools\installer.iss
```

The single-file installer is written to `..\release`.

## Tests

```bat
mvn -q clean verify
```

Runs the JUnit suite, including the 562-input differential test against the Rust
goldens.

## License

MIT, see `LICENSE`.
