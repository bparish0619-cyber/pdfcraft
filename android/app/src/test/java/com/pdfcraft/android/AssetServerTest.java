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
    @Test public void rejectsTraversalAndRecognizesCompressedWasm() {
        assertNull(AssetServer.assetPath("/../secret"));
        assertNull(AssetServer.assetPath("/a/../../secret"));
        assertNull(AssetServer.assetPath("/a\\secret"));
        assertEquals("en/index.html", AssetServer.assetPath("/en/"));
        assertEquals("application/wasm", AssetServer.mimeType("soffice.wasm.bin.gz"));
        assertEquals("text/javascript", AssetServer.mimeType("worker.mjs"));
    }
}
