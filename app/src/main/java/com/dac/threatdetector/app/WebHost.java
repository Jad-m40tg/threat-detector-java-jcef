package com.dac.threatdetector.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serves the copied front-end over a loopback HTTP server with a stable port.
 *
 * <p>A stable http://127.0.0.1:PORT origin gives WebView a durable localStorage
 * origin across restarts; file:// origins are opaque in WebKit and would lose
 * history and settings between launches.
 */
public final class WebHost implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(WebHost.class.getName());
    private static final int PREFERRED_PORT = 47821;

    private final HttpServer server;
    private final Path root;
    private final int port;

    private WebHost(HttpServer server, Path root, int port) {
        this.server = server;
        this.root = root;
        this.port = port;
    }

    public static WebHost start() throws Exception {
        Path root = Path.of(System.getenv("APPDATA"), "ThreatDetector", "web");
        extractWeb(root);

        HttpServer server;
        int port;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PREFERRED_PORT), 0);
            port = PREFERRED_PORT;
        } catch (IOException bindFailure) {
            LOG.warning("Port " + PREFERRED_PORT + " busy, using an ephemeral port: " + bindFailure.getMessage());
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = server.getAddress().getPort();
        }
        WebHost host = new WebHost(server, root, port);
        server.createContext("/", host::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        LOG.info("Web host ready at " + host.baseUrl());
        return host;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port + "/";
    }

    public Path root() {
        return root;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String rawPath = exchange.getRequestURI().getPath();
            String decoded = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
            if (decoded.isEmpty() || decoded.equals("/")) {
                decoded = "/index.html";
            }
            if (decoded.endsWith("/")) {
                decoded = decoded + "index.html";
            }
            String relative = decoded.startsWith("/") ? decoded.substring(1) : decoded;
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                byte[] body = "Not found".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(404, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            byte[] body = Files.readAllBytes(file);
            exchange.getResponseHeaders().add("Content-Type", mimeType(file.getFileName().toString()));
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Request failed", e);
        }
    }

    private static String mimeType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        return "application/octet-stream";
    }

    private static void extractWeb(Path target) throws Exception {
        URL index = WebHost.class.getResource("/web/index.html");
        if (index == null) {
            throw new IllegalStateException("Front-end resource /web/index.html is missing");
        }
        if (Files.exists(target)) {
            try (var walk = Files.walk(target)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        Files.createDirectories(target);

        if ("file".equals(index.getProtocol())) {
            Path source = Path.of(index.toURI()).getParent();
            try (var walk = Files.walk(source)) {
                walk.forEach(path -> {
                    try {
                        Path destination = target.resolve(source.relativize(path).toString());
                        if (Files.isDirectory(path)) {
                            Files.createDirectories(destination);
                        } else {
                            Files.createDirectories(destination.getParent());
                            Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } else {
            JarURLConnection connection = (JarURLConnection) index.openConnection();
            try (JarFile jar = connection.getJarFile()) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.getName().startsWith("web/") || entry.isDirectory()) {
                        continue;
                    }
                    String relative = entry.getName().substring("web/".length());
                    Path destination = target.resolve(relative);
                    Files.createDirectories(destination.getParent());
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        LOG.info("Extracted front-end to " + target);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
