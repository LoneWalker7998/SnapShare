package p2p.controller;

import p2p.service.FileSharer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.UUID;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * FileController - handles /upload, /download, /share endpoints using com.sun.net.httpserver
 */
public class FileController {

    private final HttpServer server;
    private final FileSharer fileSharer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public FileController(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.fileSharer = new FileSharer();

        server.createContext("/upload", new UploadHandler());
        server.createContext("/download", new DownloadHandler());
        server.createContext("/share", new ShareHandler());
        server.createContext("/", new CORSHandler());
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        try {
            server.stop(0);
            executor.shutdownNow();
        } catch (Exception ignore) {
        }
    }

    // ---------------- CORS handler for root fallback (and OPTIONS handling) ----------------
    private class CORSHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            String response = "Not Found";
            exchange.sendResponseHeaders(404, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    // ---------------- UPLOAD handler ----------------
    private class UploadHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers respHeaders = exchange.getResponseHeaders();
            respHeaders.add("Access-Control-Allow-Origin", "*");

            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                String response = "Method Not Allowed";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            Headers requestHeaders = exchange.getRequestHeaders();
            String contentType = requestHeaders.getFirst("Content-type");
            if (contentType == null || !contentType.contains("multipart/form-data")) {
                String response = "Bad Request: content-type must be multipart/form-data";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            // Extract boundary
            String boundary = null;
            int idx = contentType.indexOf("boundary=");
            if (idx != -1) {
                boundary = contentType.substring(idx + 9);
                if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() >= 2) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
            }
            if (boundary == null || boundary.isEmpty()) {
                String response = "Bad Request: missing boundary";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            // Ensure upload directory exists
            String uploadDirPath = System.getenv().getOrDefault("UPLOAD_DIR", "uploads");
            File uploadDir = new File(uploadDirPath);
            if (!uploadDir.exists()) uploadDir.mkdirs();

            InputStream reqIn = exchange.getRequestBody();
            byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);

            MultipartStreamReader msr = new MultipartStreamReader(reqIn, boundaryBytes);
            List<File> savedFiles = new ArrayList<>();
            try {
                MultipartPart part;
                while ((part = msr.readNextPart()) != null) {
                    String disposition = part.getHeaders().get("Content-Disposition");
                    if (disposition == null) {
                        // ignore non-disposition parts
                        continue;
                    }

                    // parse filename from Content-Disposition
                    String filename = parseFilenameFromContentDisposition(disposition);
                    if (filename == null || filename.isEmpty()) {
                        // might be a form field (e.g., meta). ignore
                        continue;
                    }

                    String safe = safeFileName(filename);
                    File out = new File(uploadDir, UUID.randomUUID().toString() + "-" + safe);

                    try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(out))) {
                        streamCopy(part.getInputStream(), fos);
                    }

                    savedFiles.add(out);
                }
            } catch (IOException ex) {
                // cleanup partial saved files in case of parse/upload error
                for (File f : savedFiles) {
                    try { f.delete(); } catch (Exception ee) { /* ignore */ }
                }
                String response = "Upload failed: " + ex.getMessage();
                exchange.sendResponseHeaders(500, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            } finally {
                try { reqIn.close(); } catch (Exception ignore) {}
            }

            if (savedFiles.isEmpty()) {
                String response = "Bad Request: No file part found";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }
            // Decide what to offer: single file or a zip bundle
            File fileToOffer = null;
            boolean createdZip = false;

            if (savedFiles.size() == 1) {
                // Serve the original uploaded file directly (no zip)
                fileToOffer = savedFiles.get(0);
                System.out.println("Single file upload detected. Will serve file directly: " + fileToOffer.getName());
            } else {
                // Create zip from savedFiles
                File zipFile = new File(uploadDir, "bundle-" + UUID.randomUUID().toString() + ".zip");
                try (FileOutputStream fos = new FileOutputStream(zipFile);
                     BufferedOutputStream bos = new BufferedOutputStream(fos);
                     ZipOutputStream zos = new ZipOutputStream(bos)) {

                    byte[] buf = new byte[8192];
                    for (File f : savedFiles) {
                        ZipEntry entry = new ZipEntry(f.getName());
                        zos.putNextEntry(entry);
                        try (FileInputStream fis = new FileInputStream(f)) {
                            int len;
                            while ((len = fis.read(buf)) > 0) {
                                zos.write(buf, 0, len);
                            }
                        }
                        zos.closeEntry();
                    }
                    zos.finish();
                } catch (IOException ex) {
                    // cleanup
                    System.err.println("Error creating zip: " + ex.getMessage());
                    if (zipFile.exists()) try { zipFile.delete(); } catch (Exception ignore) {}
                    for (File f : savedFiles) {
                        try { f.delete(); } catch (Exception ignore) {}
                    }
                    String response = "Failed to create zip: " + ex.getMessage();
                    exchange.sendResponseHeaders(500, response.getBytes().length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                    return;
                }

                // verify zip created
                if (!zipFile.exists() || zipFile.length() == 0L) {
                    String response = "Server error: created zip is missing or empty";
                    exchange.sendResponseHeaders(500, response.getBytes().length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                    for (File f : savedFiles) {
                        try { f.delete(); } catch (Exception ignore) {}
                    }
                    return;
                }

                fileToOffer = zipFile;
                createdZip = true;
                System.out.println("Created zip: " + zipFile.getAbsolutePath() + " size=" + zipFile.length());
            }

            // Offer the chosen file (single file or zip) to FileSharer
            int invitePort;
            try {
                invitePort = fileSharer.offerFile(fileToOffer.getAbsolutePath());
            } catch (Exception ex) {
                String response = "Failed to offer file: " + ex.getMessage();
                exchange.sendResponseHeaders(500, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            // Start FileSharer server asynchronously so it begins listening for the downloader.
            final File served = fileToOffer;
            final int startedPort = invitePort;
            new Thread(() -> {
                try {
                    System.out.println("Starting FileSharer server for port " + startedPort + ", serving: " + served.getAbsolutePath());
                    fileSharer.startFileServer(startedPort);
                    System.out.println("FileSharer server finished for port " + startedPort);
                } catch (Exception e) {
                    System.err.println("Failed to start file server for port " + startedPort + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }, "FileSharer-Starter-" + invitePort).start();

            // Respond with JSON
            ObjectNode res = objectMapper.createObjectNode();
            res.put("inviteCode", invitePort);
            res.put("fileCount", savedFiles.size());
            res.put("servedName", fileToOffer.getName());
            res.put("isZip", createdZip);

            byte[] bytes = res.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }

        }

        private String parseFilenameFromContentDisposition(String disposition) {
            // e.g. form-data; name="files"; filename="a.txt"
            String[] parts = disposition.split(";");
            for (String p : parts) {
                p = p.trim();
                if (p.startsWith("filename=")) {
                    String val = p.substring(9).trim();
                    if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                        val = val.substring(1, val.length() - 1);
                    }
                    // decode in case browser encoded it
                    try {
                        return URLDecoder.decode(val, "UTF-8");
                    } catch (UnsupportedEncodingException e) {
                        return val;
                    }
                }
            }
            return null;
        }
    }

    // ---------------- DOWNLOAD handler ----------------
    private class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");

            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                String response = "Method Not Allowed";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            String path = exchange.getRequestURI().getPath();
            // expected: /download/<port>
            String[] parts = path.split("/");
            if (parts.length < 3) {
                String response = "Bad Request: missing port";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            String portStr = parts[2];
            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                String response = "Bad Request: invalid port";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            // This client connects to FileSharer server socket to download file
            try (Socket socket = new Socket()) {
                // set Content-Disposition if we know the original filename
                String regPath = fileSharer.getRegisteredFilePath(port);
                if (regPath != null) {
                    File registered = new File(regPath);
                    String suggested = registered.getName();
                    exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + suggested + "\"");
                } else {
                    exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"downloaded\"");
                }

                socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                // Read from socket input and stream to HTTP response
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                // we can't know length ahead of time; use 0 length to indicate streaming
                exchange.sendResponseHeaders(200, 0);
                try (InputStream in = socket.getInputStream(); OutputStream out = exchange.getResponseBody()) {
                    streamCopy(in, out);
                }
            } catch (IOException e) {
                String response = "Failed to download from peer: " + e.getMessage();
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, respBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBytes);
                }
            }

        }
    }

    // ---------------- SHARE handler (email / copy) ----------------
    private class ShareHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers resp = exchange.getResponseHeaders();
            resp.add("Access-Control-Allow-Origin", "*");
            resp.add("Access-Control-Allow-Methods", "POST, OPTIONS");
            resp.add("Access-Control-Allow-Headers", "Content-Type");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                String response = "Method Not Allowed";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            // parse JSON body (simple)
            InputStream in = exchange.getRequestBody();
            Map<String, Object> jsonMap;
            try {
                jsonMap = objectMapper.readValue(in, Map.class);
            } catch (Exception ex) {
                String response = "Bad Request: invalid JSON";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            } finally {
                try { in.close(); } catch (Exception ignore) {}
            }

            String method = (jsonMap.get("method") instanceof String) ? (String) jsonMap.get("method") : null;
            String target = (jsonMap.get("target") instanceof String) ? (String) jsonMap.get("target") : null;
            Integer port = (jsonMap.get("port") instanceof Number) ? ((Number) jsonMap.get("port")).intValue() : null;

            if (method == null || port == null) {
                String response = "Bad Request: missing fields";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            try {
                if ("email".equalsIgnoreCase(method)) {
                    boolean ok = sendEmailShare(target, port);
                    if (ok) {
                        ObjectNode res = objectMapper.createObjectNode();
                        res.put("status", "sent");
                        byte[] bytes = res.toString().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(bytes);
                        }
                        return;
                    } else {
                        String response = "Failed to send email (check SMTP settings)";
                        exchange.sendResponseHeaders(500, response.getBytes().length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(response.getBytes());
                        }
                        return;
                    }
                } else {
                    // fallback: return share object for copy/paste
                    ObjectNode res = objectMapper.createObjectNode();
                    String base = (System.getenv("APP_BASE_URL") != null) ? System.getenv("APP_BASE_URL") : ("http://localhost:8080");
                    res.put("url", base + "/download/" + port);
                    byte[] bytes = res.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                    return;
                }
            } catch (Exception ex) {
                String response = "Error while sharing: " + ex.getMessage();
                exchange.sendResponseHeaders(500, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            }
        }

        /**
         * Send simple email using SMTP environment variables:
         * SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASS, SMTP_FROM
         */
        private boolean sendEmailShare(String toAddress, int port) {
            String host = System.getenv("SMTP_HOST");
            if (host == null || host.isEmpty()) return false;
            String portStr = System.getenv("SMTP_PORT");
            int smtpPort = (portStr != null && !portStr.isEmpty()) ? Integer.parseInt(portStr) : 587;
            String user = System.getenv("SMTP_USER");
            String pass = System.getenv("SMTP_PASS");
            String from = System.getenv("SMTP_FROM");
            if (from == null || from.isEmpty()) from = user;

            Properties props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            props.put("mail.smtp.auth", (user != null && pass != null) ? "true" : "false");
            props.put("mail.smtp.starttls.enable", "true");

            javax.mail.Session session;
            if (user != null && pass != null) {
                session = javax.mail.Session.getInstance(props, new javax.mail.Authenticator() {
                    protected javax.mail.PasswordAuthentication getPasswordAuthentication() {
                        return new javax.mail.PasswordAuthentication(user, pass);
                    }
                });
            } else {
                session = javax.mail.Session.getInstance(props);
            }

            try {
                javax.mail.Message message = new javax.mail.internet.MimeMessage(session);
                message.setFrom(new javax.mail.internet.InternetAddress(from));
                message.setRecipients(javax.mail.Message.RecipientType.TO, javax.mail.internet.InternetAddress.parse(toAddress));
                message.setSubject("PeerLink file share");
                String base = (System.getenv("APP_BASE_URL") != null) ? System.getenv("APP_BASE_URL") : ("http://localhost:8080");
                String body = "You have a file available. Download using port: " + port + "\n\nDirect URL (if available): " + base + "/download/" + port;
                message.setText(body);
                javax.mail.Transport.send(message);
                return true;
            } catch (Exception ex) {
                ex.printStackTrace();
                return false;
            }
        }
    }

    // ---------------- Helper methods ----------------
    private static String safeFileName(String name) {
        if (name == null) return "file";
        // replace characters that could be problematic in filenames
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (cleaned.length() > 200) cleaned = cleaned.substring(0, 200);
        return cleaned;
    }

    private static void streamCopy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        out.flush();
    }

    // ---------------- Small streaming multipart parser classes ----------------
    // NOTE: This is a minimal streaming parser for typical multipart/form-data uploads.
    // It does not implement all RFCs or handle every malformed input. It is sufficient
    // for common browser uploads where each file part is separated by boundary lines.

    private static class MultipartPart {
        private final Map<String, String> headers;
        private final InputStream in;

        MultipartPart(Map<String, String> headers, InputStream in) {
            this.headers = headers;
            this.in = in;
        }

        Map<String, String> getHeaders() {
            return headers;
        }

        InputStream getInputStream() {
            return in;
        }
    }

    /**
     * MultipartStreamReader - reads parts from an InputStream separated by boundary bytes.
     * It yields MultipartPart objects containing headers and an InputStream that reads the
     * part's body until the boundary.
     */
    private static class MultipartStreamReader {
        private final InputStream in;
        private final byte[] boundary; // e.g. "--boundary"
        private final byte[] boundaryWithCrLf; // CRLF + --boundary
        private final byte[] buffer = new byte[8192];

        MultipartStreamReader(InputStream in, byte[] boundary) {
            this.in = in;
            this.boundary = boundary;
            this.boundaryWithCrLf = ("\r\n" + new String(boundary, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        }

        /**
         * Read the next part. Returns null when no more parts.
         */
        MultipartPart readNextPart() throws IOException {
            // Read until the first boundary
            int b;
            // read until boundary line is found
            if (!skipToFirstBoundary()) return null;

            // Read headers
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null) {
                if (line.isEmpty()) break; // blank line = end headers
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim();
                    String val = line.substring(colon + 1).trim();
                    headers.put(name, val);
                }
            }

            // The part body InputStream needs to read until boundary occurs.
            // We'll create a piped approach: read bytes and buffer until boundary found.
            PipedOutputStream pos = new PipedOutputStream();
            PipedInputStream pis = new PipedInputStream(pos, 8192);

            // Launch a background thread to stream part content into the piped output until boundary
            Thread t = new Thread(() -> {
                try {
                    readPartDataInto(pos);
                } catch (IOException ex) {
                    // close quietly
                } finally {
                    try {
                        pos.close();
                    } catch (IOException ignore) { }
                }
            });
            t.setDaemon(true);
            t.start();

            return new MultipartPart(headers, pis);
        }

        private boolean skipToFirstBoundary() throws IOException {
            // Read until we encounter boundary line starting with "--"
            // First, read lines until a line equals the boundary string or boundary prefixed with --
            String line;
            while ((line = readLine(in)) != null) {
                if (line.equals(new String(boundary, StandardCharsets.UTF_8))) {
                    return true;
                }
                // On some clients the first boundary may be prefixed by '--'
                if (line.equals(new String(boundary, StandardCharsets.UTF_8) + "--")) {
                    return false;
                }
            }
            return false;
        }

        private void readPartDataInto(OutputStream out) throws IOException {
            // read bytes and write to out until we detect boundary sequence preceded by CRLF
            ByteArrayOutputStream matchBuf = new ByteArrayOutputStream();
            int mIndex = 0;

            // We'll use a sliding window buffer to detect the boundary with preceding CRLF.
            // Keep last N bytes equal to boundaryWithCrLf length to check for match.
            int len;
            byte[] small = new byte[4096];
            // We will read the stream byte-by-byte to detect the boundary reliably.
            int prev = -1;
            boolean first = true;
            // Use a ring buffer
            Deque<Byte> ring = new ArrayDeque<>();

            int boundaryLen = boundaryWithCrLf.length;
            int b;
            // We will read bytes and check if the tail equals boundaryWithCrLf or boundary + "--"
            ByteArrayOutputStream tail = new ByteArrayOutputStream();

            while ((b = in.read()) != -1) {
                tail.write(b);
                // keep tail limited
                if (tail.size() > boundaryLen + 4) {
                    // flush earliest bytes to output
                    byte[] tb = tail.toByteArray();
                    out.write(tb[0]); // write oldest
                    byte[] newTail = Arrays.copyOfRange(tb, 1, tb.length);
                    tail.reset();
                    tail.write(newTail);
                }

                byte[] tailArr = tail.toByteArray();
                String tailStr = new String(tailArr, StandardCharsets.UTF_8);

                // Check if tail ends with \r\n--boundary
                if (tailArr.length >= boundaryWithCrLf.length) {
                    int start = tailArr.length - boundaryWithCrLf.length;
                    boolean match = true;
                    for (int i = 0; i < boundaryWithCrLf.length; i++) {
                        if (tailArr[start + i] != boundaryWithCrLf[i]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        // remove the boundary and trailing CRLF from the output (we didn't write it yet)
                        // write remaining bytes before boundary (tailArr[0..start-1]) to out
                        if (start > 0) out.write(tailArr, 0, start);
                        // consume the rest of the line (there may be "--" indicating final boundary)
                        // read the remainder of the line
                        String rest = readLine(in); // read until newline
                        return;
                    }
                }

                // if not matched and tail has exceeded some bytes, flush earliest
                if (tail.size() > boundaryWithCrLf.length) {
                    out.write(tailArr[0]);
                    byte[] newTail = Arrays.copyOfRange(tailArr, 1, tailArr.length);
                    tail.reset();
                    tail.write(newTail);
                }
            }
        }

        private String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int prev = -1;
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    break;
                }
                if (prev == '\r' && b == '\n') {
                    break;
                }
                baos.write(b);
                prev = b;
            }
            if (baos.size() == 0 && b == -1) return null;
            String line = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            // trim CR if present
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            return line;
        }
    }
}
