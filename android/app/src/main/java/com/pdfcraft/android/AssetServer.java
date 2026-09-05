package com.pdfcraft.android;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.util.Locale;

/** Read-only server, bound exclusively to loopback. Never serves user documents. */
public final class AssetServer extends NanoHTTPD {
    public interface Source { InputStream open(String path) throws IOException; }
    private final Source source;
    public AssetServer(int port, Source source) { super("127.0.0.1", port); this.source = source; }
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
        } else if (session.getMethod() != Method.GET && session.getMethod() != Method.HEAD) {
            response = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Read only");
        } else if (path == null) {
            response = newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Invalid path");
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
}
