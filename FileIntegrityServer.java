import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileIntegrityServer {

    private static final int BUFFER_SIZE = 8192;
    private static final Path REF_DIR = Paths.get("references");

    public static void main(String[] args) throws Exception {
        Files.createDirectories(REF_DIR);
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", FileIntegrityServer::serveStatic);
        server.createContext("/api/hash", ex -> handle(ex, true));
        server.createContext("/api/verify", ex -> handle(ex, false));
        server.createContext("/api/references", FileIntegrityServer::listReferences);
        server.start();
        System.out.println("======================================");
        System.out.println(" FILE INTEGRITY CHECKER - WEB VERSION");
        System.out.println(" Open: http://localhost:8080");
        System.out.println("======================================");
    }

    static String sha256(InputStream in) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buffer)) != -1) {
            digest.update(buffer, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    static void handle(HttpExchange ex, boolean generate) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("POST")) {
                send(ex, 405, json("error", "Use POST"));
                return;
            }
            String header = ex.getRequestHeaders().getFirst("X-Filename");
            if (header == null || header.isBlank()) {
                send(ex, 400, json("error", "File name missing"));
                return;
            }
            String name = URLDecoder.decode(header, StandardCharsets.UTF_8);
            name = Paths.get(name).getFileName().toString();

            String current = sha256(ex.getRequestBody());
            Path ref = REF_DIR.resolve(name + ".sha256");

            if (generate) {
                Files.write(ref, (current + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                send(ex, 200, json("status", "CREATED", "file", name, "hash", current));
                return;
            }

            if (!Files.exists(ref)) {
                send(ex, 404, json("status", "NO_REFERENCE", "file", name, "current", current));
                return;
            }
            String saved = new String(Files.readAllBytes(ref), StandardCharsets.UTF_8).trim();
            boolean ok = MessageDigest.isEqual(
                    saved.getBytes(StandardCharsets.US_ASCII),
                    current.getBytes(StandardCharsets.US_ASCII));
            send(ex, 200, json("status", ok ? "VERIFIED" : "FAILED",
                    "file", name, "saved", saved, "current", current));
        } catch (Exception e) {
            send(ex, 500, json("error", String.valueOf(e.getMessage())));
        }
    }

    static void listReferences(HttpExchange ex) throws IOException {
        try (Stream<Path> files = Files.list(REF_DIR)) {
            List<String> items = files
                    .filter(p -> p.toString().endsWith(".sha256"))
                    .sorted()
                    .map(p -> {
                        try {
                            String fn = p.getFileName().toString();
                            String hash = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
                            return json("file", fn.substring(0, fn.length() - 7), "hash", hash);
                        } catch (IOException e) {
                            return json("file", p.getFileName().toString(), "hash", "unreadable");
                        }
                    })
                    .collect(Collectors.toList());
            send(ex, 200, "[" + String.join(",", items) + "]");
        }
    }

    static void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        String type;
        if (path.equals("/index.html")) type = "text/html";
        else if (path.equals("/style.css")) type = "text/css";
        else if (path.equals("/script.js")) type = "application/javascript";
        else { send(ex, 404, json("error", "Not found")); return; }

        byte[] data = Files.readAllBytes(Paths.get(path.substring(1)));
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String json(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(esc(kv[i])).append("\":\"").append(esc(kv[i + 1])).append("\"");
        }
        return sb.append("}").toString();
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }
}