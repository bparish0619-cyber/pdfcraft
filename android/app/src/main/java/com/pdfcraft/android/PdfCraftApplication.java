package com.pdfcraft.android;

import android.app.Application;
import android.content.SharedPreferences;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.StorageController;
import java.io.IOException;

public final class PdfCraftApplication extends Application {
    private GeckoRuntime runtime;
    private AssetServer server;
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
            server = new AssetServer(prefs.getInt("port", 0), path -> getAssets().open("web/" + path));
            try { server.start(); }
            catch (IOException busy) {
                server.stop();
                server = new AssetServer(0, path -> getAssets().open("web/" + path));
                server.start();
            }
            prefs.edit().putInt("port", server.getListeningPort()).apply();
        }
        return server.origin();
    }
}
