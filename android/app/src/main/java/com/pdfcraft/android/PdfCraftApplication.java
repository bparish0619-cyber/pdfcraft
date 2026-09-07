package com.pdfcraft.android;

import android.app.Application;
import android.content.SharedPreferences;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.StorageController;
import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;

public final class PdfCraftApplication extends Application {
    private GeckoRuntime runtime;
    private AssetServer server;
    /** Set by the window that can show Android's save dialog, cleared when it goes. */
    private volatile AssetServer.Sink exportSink;

    public void setExportSink(AssetServer.Sink sink) { this.exportSink = sink; }

    public synchronized GeckoRuntime runtime() {
        if (runtime == null) {
            runtime = GeckoRuntime.create(this, new GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true).fissionEnabled(true).consoleOutput(BuildConfig.DEBUG)
                    .remoteDebuggingEnabled(BuildConfig.DEBUG).build());
            runtime.getStorageController().clearData(StorageController.ClearFlags.ALL_CACHES);
        }
        return runtime;
    }
    public synchronized String origin() throws IOException {
        if (server == null) {
            SharedPreferences prefs = getSharedPreferences("android-shell", MODE_PRIVATE);
            AssetServer.Source source = path -> getAssets().open("web/" + path);
            // Forwarded rather than passed directly, so the server outliving a window
            // cannot hold a destroyed activity.
            AssetServer.Sink sink = (body, length, name, mime) -> {
                AssetServer.Sink window = exportSink;
                if (window == null) throw new IOException("PDFCraft has no open window to save from");
                window.accept(body, length, name, mime);
            };
            String token = new BigInteger(160, new SecureRandom()).toString(36);
            server = new AssetServer(prefs.getInt("port", 0), source, sink, token);
            try { server.start(); }
            catch (IOException busy) {
                server.stop();
                server = new AssetServer(0, source, sink, token);
                server.start();
            }
            prefs.edit().putInt("port", server.getListeningPort()).apply();
        }
        return server.origin();
    }
}
