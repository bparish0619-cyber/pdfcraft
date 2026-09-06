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
