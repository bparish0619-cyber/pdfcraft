package com.pdfcraft.android;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.platform.app.InstrumentationRegistry;
import org.mozilla.geckoview.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class EngineSmokeTest {
    private static final String TAG = "PDFCraftSmoke";
    /** Measured end to end on emulators: 46s on an API 35 tablet, 118s on an API 28
     *  phone, where compiling the bundled LibreOffice WebAssembly dominates. This
     *  leaves several times that headroom while keeping a hang cheap to discover. */
    private static final long ENGINE_TIMEOUT_MINUTES = 8;

    @Test public void currentAppAndBundledEnginesProduceValidDocuments() throws Exception {
        CountDownLatch home = new CountDownLatch(1);
        CountDownLatch harness = new CountDownLatch(1);
        CountDownLatch engines = new CountDownLatch(1);
        CountDownLatch exported = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("No result");
        AtomicReference<String> progress = new AtomicReference<>("no progress reported");
        AtomicReference<String> download = new AtomicReference<>("No download");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                GeckoView view = (GeckoView)((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
                GeckoSession session = view.getSession();
                // MainActivity sets no progress delegate, so this adds page load
                // reporting without displacing its navigation handling.
                session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
                    @Override public void onPageStop(GeckoSession s, boolean success) {
                        Log.i(TAG, "page load finished, success=" + success);
                    }
                });
                session.setContentDelegate(new GeckoSession.ContentDelegate() {
                    @Override public void onTitleChange(GeckoSession s, String title) {
                        if (title == null) return;
                        Log.i(TAG, "title: " + title);
                        if (title.contains("PDFCraft") && !title.startsWith("PASS:")) home.countDown();
                        if (title.startsWith("PROGRESS:") || title.startsWith("PASS:") || title.startsWith("FAIL:")) {
                            progress.set(title);
                            harness.countDown();
                        }
                        if (title.startsWith("PASS:") || title.startsWith("FAIL:")) {
                            result.set(title); engines.countDown();
                        }
                    }
                    @Override public void onExternalResponse(GeckoSession s, WebResponse response) {
                        new Thread(() -> {
                            try (InputStream in=response.body; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                                assertNotNull(in); MainActivity.copy(in,out);
                                byte[] bytes=out.toByteArray();
                                download.set(bytes.length > 100 ? new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII) : "too short");
                            } catch (Throwable e) { download.set(e.toString()); }
                            finally { exported.countDown(); }
                        }).start();
                    }
                    @Override public void onCrash(GeckoSession s) {
                        result.set("Gecko content process crashed after: " + progress.get());
                        engines.countDown();
                    }
                });
                try { session.loadUri(((PdfCraftApplication)activity.getApplication()).origin()+"/en/"); }
                catch(IOException e) { throw new RuntimeException(e); }
            });
            assertTrue("Current application homepage did not load",home.await(3,TimeUnit.MINUTES));
            scenario.onActivity(activity -> {
                GeckoView view=(GeckoView)((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
                try { view.getSession().loadUri(((PdfCraftApplication)activity.getApplication()).origin()+"/android-smoke.html"); }
                catch(IOException e) { throw new RuntimeException(e); }
            });
            // The harness reports its first stage within seconds of executing, so a
            // separate short wait separates "never ran" from "still running".
            assertTrue("Engine smoke harness never reported a stage; the page or its module failed to load",
                    harness.await(4,TimeUnit.MINUTES));
            // Await first: Java evaluates arguments before the call, so building
            // the message inline would capture the stage as it was 30 minutes ago.
            boolean finished = engines.await(ENGINE_TIMEOUT_MINUTES,TimeUnit.MINUTES);
            assertTrue("Engine smoke test timed out after "+ENGINE_TIMEOUT_MINUTES
                    +" minutes, last stage: "+progress.get(), finished);
            assertTrue(result.get(),result.get().startsWith("PASS:"));
            // GeckoView hands a download to the app only for one a user asked for,
            // so tap the harness's target rather than letting the script click it.
            boolean delivered = false;
            for (int attempt = 1; attempt <= 3 && !delivered; attempt++) {
                Thread.sleep(1500);
                tapCentreOfWebView(scenario);
                delivered = exported.await(40,TimeUnit.SECONDS);
                if (!delivered) Log.i(TAG, "no download after tap attempt " + attempt);
            }
            assertTrue("Native Blob download was not delivered after three taps",delivered);
            assertEquals("%PDF-",download.get());
        }
    }

    /** Sends a real touch through the input pipeline, which is what gives the page
     *  the user activation a script-driven click cannot supply. */
    private void tapCentreOfWebView(ActivityScenario<MainActivity> scenario) {
        float[] point = new float[2];
        scenario.onActivity(activity -> {
            View view = ((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            point[0] = location[0] + view.getWidth() / 2f;
            point[1] = location[1] + view.getHeight() / 2f;
        });
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, point[0], point[1], 0);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, point[0], point[1], 0);
        try {
            instrumentation.sendPointerSync(down);
            instrumentation.sendPointerSync(up);
        } finally {
            down.recycle();
            up.recycle();
        }
        Log.i(TAG, "tapped the download target at " + point[0] + "," + point[1]);
    }
}
