package com.pdfcraft.android;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.view.ViewGroup;
import org.mozilla.geckoview.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class EngineSmokeTest {
    @Test public void currentAppAndBundledEnginesProduceValidDocuments() throws Exception {
        CountDownLatch home = new CountDownLatch(1);
        CountDownLatch engines = new CountDownLatch(1);
        CountDownLatch exported = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("No result");
        AtomicReference<String> download = new AtomicReference<>("No download");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                GeckoView view = (GeckoView)((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
                GeckoSession session = view.getSession();
                session.setContentDelegate(new GeckoSession.ContentDelegate() {
                    @Override public void onTitleChange(GeckoSession s, String title) {
                        if (title != null && title.contains("PDFCraft") && !title.startsWith("PASS:")) home.countDown();
                        if (title != null && (title.startsWith("PASS:") || title.startsWith("FAIL:"))) {
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
                    @Override public void onCrash(GeckoSession s) { result.set("Gecko content process crashed"); engines.countDown(); }
                });
                try { session.loadUri(((PdfCraftApplication)activity.getApplication()).origin()+"/en/"); }
                catch(IOException e) { throw new RuntimeException(e); }
            });
            assertTrue("Current application homepage did not load",home.await(90,TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                GeckoView view=(GeckoView)((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
                try { view.getSession().loadUri(((PdfCraftApplication)activity.getApplication()).origin()+"/android-smoke.html"); }
                catch(IOException e) { throw new RuntimeException(e); }
            });
            assertTrue("Engine smoke test timed out: "+result.get(),engines.await(12,TimeUnit.MINUTES));
            assertTrue(result.get(),result.get().startsWith("PASS:"));
            assertTrue("Native Blob download was not delivered",exported.await(30,TimeUnit.SECONDS));
            assertEquals("%PDF-",download.get());
        }
    }
}
