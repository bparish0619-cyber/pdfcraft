package com.pdfcraft.android;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class AssetServerTest {
    @Test public void servesRoutesWithIsolationAndCorrectMime() throws Exception {
        Map<String, byte[]> files = Map.of("en/index.html", "<html>PDFCraft</html>".getBytes(),
                "engine.wasm", new byte[]{0,97,115,109}, "viewer.mjs", "export{}".getBytes());
        AssetServer server = new AssetServer(0, path -> {
            if (!files.containsKey(path)) throw new FileNotFoundException(path);
            return new ByteArrayInputStream(files.get(path));
        });
        server.start();
        try {
            HttpURLConnection page = (HttpURLConnection) new URL(server.origin() + "/en/").openConnection();
            assertEquals(200, page.getResponseCode());
            assertEquals("same-origin", page.getHeaderField("Cross-Origin-Opener-Policy"));
            assertEquals("require-corp", page.getHeaderField("Cross-Origin-Embedder-Policy"));
            assertTrue(new String(page.getInputStream().readAllBytes(), StandardCharsets.UTF_8).contains("PDFCraft"));
            HttpURLConnection wasm = (HttpURLConnection) new URL(server.origin() + "/engine.wasm").openConnection();
            assertEquals("application/wasm", wasm.getContentType());
            assertArrayEquals(files.get("engine.wasm"), wasm.getInputStream().readAllBytes());
            HttpURLConnection missing = (HttpURLConnection) new URL(server.origin() + "/missing.js").openConnection();
            assertEquals(404, missing.getResponseCode());
        } finally { server.stop(); }
    }
    /** A chunked response answers HEAD with the whole body and Content-Length: -1,
     *  leaving the reply unframed so the next request on the same keep-alive
     *  connection reads that body as its status line. LibreOffice's environment
     *  check HEADs five assets at once, so that corrupted one of them at random. */
    @Test public void headReportsSizeWithoutABodyAndLeavesTheConnectionUsable() throws Exception {
        byte[] payload = new byte[64 * 1024];
        java.util.Arrays.fill(payload, (byte) 'x');
        Map<String, byte[]> files = Map.of("engine.wasm", payload, "engine.js", "export{}".getBytes());
        AssetServer server = new AssetServer(0, path -> {
            if (!files.containsKey(path)) throw new FileNotFoundException(path);
            return new ByteArrayInputStream(files.get(path));
        });
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.getListeningPort())) {
            socket.setSoTimeout(5000);
            String host = "127.0.0.1:" + server.getListeningPort();
            OutputStream out = socket.getOutputStream();
            out.write(("HEAD /engine.wasm HTTP/1.1\r\nHost: " + host + "\r\nConnection: keep-alive\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            String head = readHeaders(in);
            assertTrue(head, head.startsWith("HTTP/1.1 200"));
            assertTrue("HEAD must report the asset size: " + head,
                    head.contains("Content-Length: " + payload.length));
            assertFalse("HEAD must not declare an unknown length: " + head, head.contains("Content-Length: -1"));
            assertEquals("HEAD must not send a body", 0, in.available());

            out.write(("GET /engine.js HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String next = readHeaders(in);
            assertTrue("the connection must still be usable after a HEAD: " + next,
                    next.startsWith("HTTP/1.1 200"));
            assertTrue(next, next.contains("text/javascript"));
        } finally { server.stop(); }
    }


    @Test public void handsAnExportToTheSinkOnlyOnTheTokenEndpoint() throws Exception {
        byte[] pdf = "%PDF-1.7 body".getBytes(StandardCharsets.US_ASCII);
        java.util.List<String> seen = new java.util.ArrayList<>();
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        AssetServer server = new AssetServer(0, path -> { throw new FileNotFoundException(path); },
                (body, length, name, mime) -> {
                    seen.add(name + "|" + mime + "|" + length);
                    drain(body, received);
                }, "tok3n");
        server.start();
        try {
            // The page discovers the endpoint here; on the web this path does not exist.
            HttpURLConnection bridge = (HttpURLConnection) new URL(server.origin() + "/_bridge.json").openConnection();
            assertEquals(200, bridge.getResponseCode());
            assertEquals("{\"export\":\"/_export/tok3n\"}",
                    new String(bridge.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

            assertEquals(204, post(server, "/_export/tok3n", pdf, "application/pdf", "report.pdf"));
            assertEquals("[report.pdf|application/pdf|" + pdf.length + "]", seen.toString());
            assertArrayEquals(pdf, received.toByteArray());

            // A guessed or absent token must not reach the sink.
            assertEquals(403, post(server, "/_export/wrong", pdf, "application/pdf", "report.pdf"));
            assertEquals(403, post(server, "/_export/", pdf, "application/pdf", "report.pdf"));
            assertEquals(1, seen.size());
        } finally { server.stop(); }
    }

    @Test public void rejectsAnOversizedOrUnmeasuredExport() throws Exception {
        AssetServer server = new AssetServer(0, path -> { throw new FileNotFoundException(path); },
                (body, length, name, mime) -> fail("must not reach the sink"), "tok3n");
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.getListeningPort())) {
            socket.setSoTimeout(5000);
            String host = "127.0.0.1:" + server.getListeningPort();
            socket.getOutputStream().write(("POST /_export/tok3n HTTP/1.1\r\nHost: " + host
                    + "\r\nContent-Length: " + (AssetServer.MAX_EXPORT_BYTES + 1)
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String head = readHeaders(new BufferedInputStream(socket.getInputStream()));
            assertTrue(head, head.startsWith("HTTP/1.1 413"));
        } finally { server.stop(); }
    }

    @Test public void keepsTheConnectionUsableWhenASinkIgnoresTheBody() throws Exception {
        Map<String, byte[]> files = Map.of("after.js", "export{}".getBytes());
        AssetServer server = new AssetServer(0, path -> {
            if (!files.containsKey(path)) throw new FileNotFoundException(path);
            return new ByteArrayInputStream(files.get(path));
        }, (body, length, name, mime) -> { /* deliberately reads nothing */ }, "tok3n");
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.getListeningPort())) {
            socket.setSoTimeout(5000);
            String host = "127.0.0.1:" + server.getListeningPort();
            byte[] payload = new byte[4096];
            java.util.Arrays.fill(payload, (byte) 'p');
            OutputStream out = socket.getOutputStream();
            out.write(("POST /_export/tok3n HTTP/1.1\r\nHost: " + host + "\r\nContent-Length: "
                    + payload.length + "\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            String accepted = readHeaders(in);
            assertTrue(accepted, accepted.startsWith("HTTP/1.1 204"));
            consumeBody(in, accepted);
            out.write(("GET /after.js HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String next = readHeaders(in);
            assertTrue("an unread body must not desync the connection: " + next,
                    next.startsWith("HTTP/1.1 200"));
        } finally { server.stop(); }
    }

    private static int post(AssetServer server, String path, byte[] body, String mime, String name) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server.origin() + path).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(body.length);
        c.setRequestProperty("Content-Type", mime);
        c.setRequestProperty("X-PDFCraft-Filename", name);
        c.getOutputStream().write(body);
        c.getOutputStream().flush();
        return c.getResponseCode();
    }

    @Test public void sanitisesTheNameThePageAsksFor() {
        assertEquals("download", AssetServer.exportName(null));
        assertEquals("download", AssetServer.exportName("   "));
        assertEquals("_etc_passwd", AssetServer.exportName("/etc/passwd"));
        assertEquals("a_b.pdf", AssetServer.exportName("a\\b.pdf"));
        assertEquals("_.bashrc", AssetServer.exportName(".bashrc"));
        assertEquals("ok.pdf", AssetServer.exportName("ok.pdf"));
    }

    /** Leaves the stream positioned at the next response rather than inside this one. */
    private static void consumeBody(InputStream in, String headers) throws IOException {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?im)^Content-Length: (\\d+)").matcher(headers);
        if (!m.find()) return;
        long remaining = Long.parseLong(m.group(1));
        while (remaining-- > 0 && in.read() != -1) { /* discard */ }
    }

    private static void drain(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
    }

    private static String readHeaders(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            sb.append((char) c);
            int n = sb.length();
            if (n >= 4 && sb.charAt(n - 1) == '\n' && sb.charAt(n - 2) == '\r'
                    && sb.charAt(n - 3) == '\n' && sb.charAt(n - 4) == '\r') return sb.toString();
        }
        return sb + " [connection closed before the headers ended]";
    }

    @Test public void rejectsTraversalAndRecognizesCompressedWasm() {
        assertNull(AssetServer.assetPath("/../secret"));
        assertNull(AssetServer.assetPath("/a/../../secret"));
        assertNull(AssetServer.assetPath("/a\\secret"));
        assertEquals("en/index.html", AssetServer.assetPath("/en/"));
        assertEquals("application/wasm", AssetServer.mimeType("soffice.wasm.bin.gz"));
        assertEquals("text/javascript", AssetServer.mimeType("worker.mjs"));
    }
}
