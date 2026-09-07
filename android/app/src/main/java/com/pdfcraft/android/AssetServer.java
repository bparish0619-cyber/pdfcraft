package com.pdfcraft.android;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.util.Locale;

/**
 * Read-only server for the bundled web assets, bound exclusively to loopback, plus
 * one write endpoint the page uses to hand an export to Android.
 *
 * The hand-off exists because GeckoView never delivers a blob: download to
 * ContentDelegate.onExternalResponse, so an export clicked in the page reached
 * nothing at all. The page POSTs the bytes here instead and the app offers them to
 * Android's save dialog. The endpoint carries a per-process token and can only ever
 * open that dialog, which the user still has to confirm, so the worst another local
 * process can do with it is prompt for a save the user did not ask for.
 */
public final class AssetServer extends NanoHTTPD {
    public interface Source { InputStream open(String path) throws IOException; }
    /** Receives an export from the page, on a server thread, already length bounded. */
    public interface Sink { void accept(InputStream body, long length, String name, String mime) throws IOException; }

    /** Refuse a hand-off larger than any document this app can produce. */
    static final long MAX_EXPORT_BYTES = 2L * 1024 * 1024 * 1024;
    private static final String EXPORT_PREFIX = "/_export/";
    static final String BRIDGE_PATH = "/_bridge.json";

    private final Source source;
    private final Sink sink;
    private final String token;

    public AssetServer(int port, Source source) { this(port, source, null, null); }
    public AssetServer(int port, Source source, Sink sink, String token) {
        super("127.0.0.1", port);
        this.source = source;
        this.sink = sink;
        this.token = token;
    }
    public String origin() { return "http://127.0.0.1:" + getListeningPort(); }

    static String assetPath(String uri) {
        if (uri == null || uri.contains("\\") || uri.indexOf('\0') >= 0) return null;
        for (String segment : uri.split("/")) if (segment.equals("..") || segment.equals(".")) return null;
        String path = uri.replaceFirst("^/+", "");
        if (path.isEmpty() || path.endsWith("/")) path += "index.html";
        else if (!path.substring(path.lastIndexOf('/') + 1).contains(".")) path += "/index.html";
        return path;
    }
    static String mimeType(String path) {
        String p = path.toLowerCase(Locale.ROOT).replaceFirst("\\.gz$", "");
        if (p.endsWith(".wasm") || p.endsWith(".wasm.bin")) return "application/wasm";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript";
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json") || p.endsWith(".map")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".pdf")) return "application/pdf";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".ttf")) return "font/ttf";
        return getMimeTypeForFile(p);
    }
    @Override public Response serve(IHTTPSession session) {
        Response response;
        String host = session.getHeaders().get("host");
        String path = assetPath(session.getUri());
        if (! ("127.0.0.1:" + getListeningPort()).equals(host)) {
            response = newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Invalid host");
        } else if (session.getMethod() == Method.POST) {
            response = receiveExport(session);
        } else if (session.getMethod() != Method.GET && session.getMethod() != Method.HEAD) {
            response = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Read only");
        } else if (BRIDGE_PATH.equals(session.getUri())) {
            // How the page discovers that it is running inside the Android shell, and
            // where to hand an export. Absent on the web, so the page falls back to a
            // normal browser download there.
            response = token == null
                    ? newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No bridge")
                    : newFixedLengthResponse(Response.Status.OK, "application/json",
                            "{\"export\":\"" + EXPORT_PREFIX + token + "\"}");
        } else if (path == null) {
            response = newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Invalid path");
        } else if (session.getMethod() == Method.HEAD) {
            // A chunked response answers HEAD with the whole body and
            // "Content-Length: -1", so the reply is unframed and the next request
            // on the same keep-alive connection reads this body as its headers.
            // LibreOffice's environment check HEADs five assets at once, so that
            // corruption surfaced as an intermittent failure to fetch one of them.
            // An empty body with the asset's length declared reports the real size
            // in a single Content-Length and writes no bytes, because the send loop
            // stops at end of stream.
            try (InputStream probe = source.open(path)) {
                response = newFixedLengthResponse(Response.Status.OK, mimeType(path),
                        new ByteArrayInputStream(new byte[0]), probe.available());
                if (path.endsWith(".gz")) response.addHeader("Content-Encoding", "gzip");
            } catch (IOException e) {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found");
            }
        } else {
            try {
                response = newChunkedResponse(Response.Status.OK, mimeType(path), source.open(path));
                if (path.endsWith(".gz")) response.addHeader("Content-Encoding", "gzip");
            } catch (IOException e) {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found");
            }
        }
        response.addHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.addHeader("Cross-Origin-Embedder-Policy", "require-corp");
        response.addHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private Response receiveExport(IHTTPSession session) {
        if (sink == null || token == null || !(EXPORT_PREFIX + token).equals(session.getUri())) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Unknown endpoint");
        }
        long length;
        try { length = Long.parseLong(session.getHeaders().getOrDefault("content-length", "-1")); }
        catch (NumberFormatException e) { length = -1; }
        if (length < 0) {
            return newFixedLengthResponse(Response.Status.LENGTH_REQUIRED, "text/plain", "Content-Length required");
        }
        if (length > MAX_EXPORT_BYTES) {
            return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, "text/plain", "Export too large");
        }
        String mime = session.getHeaders().getOrDefault("content-type", "application/octet-stream").split(";")[0];
        String name = exportName(session.getHeaders().get("x-pdfcraft-filename"));
        Bounded body = new Bounded(session.getInputStream(), length);
        try {
            sink.accept(body, length, name, mime);
            // Nothing to return: the bytes are Android's problem from here.
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", null, 0);
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Export failed");
        } finally {
            // A sink that read less than it was given would leave the rest of the body
            // in the socket, where the next request would parse it as a request line.
            try { body.drain(); } catch (IOException ignored) { }
        }
    }

    /** Keeps a page from choosing where the bytes land or what they are called. */
    static String exportName(String header) {
        if (header == null) return "download";
        String name = header.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        if (name.startsWith(".")) name = "_" + name;
        return name.isEmpty() ? "download" : name;
    }

    /** Exposes exactly the declared number of body bytes and no more. */
    private static final class Bounded extends InputStream {
        private final InputStream in;
        private long left;
        Bounded(InputStream in, long length) { this.in = in; this.left = length; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }
        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
            if (left <= 0) return -1;
            int read = in.read(buffer, offset, (int) Math.min(count, left));
            if (read > 0) left -= read;
            return read;
        }
        void drain() throws IOException {
            byte[] scratch = new byte[8192];
            while (left > 0 && read(scratch, 0, scratch.length) > 0) { /* discard */ }
        }
    }
}
