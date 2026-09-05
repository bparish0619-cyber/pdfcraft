package com.pdfcraft.android;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import org.mozilla.geckoview.*;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity {
    private GeckoSession session;
    private GeckoView view;
    private String origin;
    private boolean canGoBack;
    private FilePrompt filePrompt;
    private GeckoResult<PromptResponse> fileResult;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayDeque<Download> downloads = new ArrayDeque<>();
    private Download saving;
    private record Download(File file, String name, String mime) {}

    private final ActivityResultLauncher<Intent> openFiles = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (filePrompt == null || fileResult == null) return;
                FilePrompt prompt = filePrompt;
                GeckoResult<PromptResponse> pending = fileResult;
                filePrompt = null; fileResult = null;
                Intent data = result.getData();
                if (result.getResultCode() != RESULT_OK || data == null) {
                    pending.complete(prompt.dismiss()); return;
                }
                ArrayList<Uri> uris = new ArrayList<>();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++)
                        uris.add(data.getClipData().getItemAt(i).getUri());
                } else if (data.getData() != null) uris.add(data.getData());
                if (uris.isEmpty()) pending.complete(prompt.dismiss());
                else pending.complete(prompt.confirm(this, uris.toArray(new Uri[0])));
            });
    private final ActivityResultLauncher<Intent> saveFile = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Download download = saving;
                saving = null;
                if (download == null) return;
                Uri uri = result.getData() == null ? null : result.getData().getData();
                io.execute(() -> {
                    try {
                        if (result.getResultCode() == RESULT_OK && uri != null) {
                            try (InputStream in = new FileInputStream(download.file());
                                 OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                                if (out == null) throw new IOException("Cannot open selected destination");
                                copy(in, out);
                            }
                            runOnUiThread(() -> toast("Saved " + download.name()));
                        }
                    } catch (IOException e) { runOnUiThread(() -> error("File could not be saved", e)); }
                    finally { download.file().delete(); runOnUiThread(this::saveNext); }
                });
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        view = new GeckoView(this);
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            androidx.core.graphics.Insets padding = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.ime());
            v.setPadding(padding.left, padding.top, padding.right, padding.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        setContentView(view);
        try { origin = ((PdfCraftApplication) getApplication()).origin(); }
        catch (IOException e) { error("PDFCraft could not start", e); return; }
        // Remove interrupted transfers left by process death, never user-saved documents.
        File[] stale = getCacheDir().listFiles((dir, name) -> name.startsWith("pdfcraft-export-"));
        if (stale != null) for (File f : stale) f.delete();
        session = new GeckoSession();
        session.setPromptDelegate(new Prompts());
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onCanGoBack(GeckoSession s, boolean back) { canGoBack = back; }
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
                if (internal(request.uri)) return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                external(request.uri);
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                if (internal(uri)) s.loadUri(uri); else external(uri);
                return null;
            }
            @Override public GeckoResult<String> onLoadError(GeckoSession s, String uri, WebRequestError e) {
                toast("Unable to open this page. Reopen PDFCraft to retry.");
                return null;
            }
        });
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onExternalResponse(GeckoSession s, WebResponse response) { receive(response); }
            @Override public void onCrash(GeckoSession s) {
                new AlertDialog.Builder(MainActivity.this).setTitle("PDFCraft needs to reopen")
                        .setMessage("The document may exceed available device memory. Try a smaller file.")
                        .setPositiveButton("Reopen", (d,w) -> recreate()).show();
            }
        });
        session.open(((PdfCraftApplication) getApplication()).runtime());
        view.setSession(session);
        session.loadUri(origin + "/");
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (canGoBack) session.goBack(); else finish();
            }
        });
    }
    private boolean internal(String url) {
        return url.equals(origin) || url.startsWith(origin + "/")
                || url.startsWith("blob:" + origin + "/") || url.equals("about:blank");
    }
    private void external(String url) {
        Uri uri = Uri.parse(url);
        if (!Set.of("https", "http", "mailto").contains(String.valueOf(uri.getScheme()))) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (android.content.ActivityNotFoundException e) { toast("No app can open this link."); }
    }
    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024]; int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
    }
    private void receive(WebResponse response) {
        if (response.body == null) { toast("No file data was received."); return; }
        String mime = response.headers.getOrDefault("Content-Type", "application/octet-stream").split(";")[0];
        String name = URLUtil.guessFileName(response.uri, response.headers.get("Content-Disposition"), mime)
                .replaceAll("[\\\\/\\p{Cntrl}]", "_");
        io.execute(() -> {
            File file = null;
            try (InputStream in = response.body) {
                file = File.createTempFile("pdfcraft-export-", ".tmp", getCacheDir());
                try (OutputStream out = new FileOutputStream(file)) { copy(in, out); }
                Download download = new Download(file, name, mime);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) download.file().delete();
                    else { downloads.add(download); saveNext(); }
                });
            } catch (IOException e) {
                if (file != null) file.delete();
                runOnUiThread(() -> error("Download failed", e));
            }
        });
    }
    private void saveNext() {
        if (saving != null || downloads.isEmpty() || isFinishing() || isDestroyed()) return;
        saving = downloads.remove();
        try {
            saveFile.launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(saving.mime()).putExtra(Intent.EXTRA_TITLE, saving.name()));
        } catch (android.content.ActivityNotFoundException e) {
            saving.file().delete(); saving = null; error("No Android file picker is installed", e);
        }
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void error(String title, Exception e) {
        if (!isFinishing() && !isDestroyed()) new AlertDialog.Builder(this).setTitle(title)
                .setMessage(e.getMessage()).setPositiveButton("OK", null).show();
    }
    @Override protected void onDestroy() {
        if (filePrompt != null && fileResult != null) fileResult.complete(filePrompt.dismiss());
        if (session != null) { view.releaseSession(); session.close(); }
        for (Download d : downloads) d.file().delete();
        if (saving != null) saving.file().delete();
        io.shutdown();
        super.onDestroy();
    }

    private final class Prompts implements GeckoSession.PromptDelegate {
        @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s, FilePrompt prompt) {
            if (fileResult != null) return GeckoResult.fromValue(prompt.dismiss());
            filePrompt = prompt; fileResult = new GeckoResult<>();
            GeckoResult<PromptResponse> result = fileResult;
            ArrayList<String> types = new ArrayList<>();
            if (prompt.mimeTypes != null) for (String type : prompt.mimeTypes) {
                if (type.startsWith(".")) type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.substring(1));
                if (type != null && type.contains("/")) types.add(type);
            }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(types.size() == 1 ? types.get(0) : "*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, prompt.type == FilePrompt.Type.MULTIPLE);
            if (types.size() > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
            try { openFiles.launch(intent); }
            catch (android.content.ActivityNotFoundException e) {
                result.complete(prompt.dismiss()); filePrompt = null; fileResult = null;
                error("No Android file picker is installed", e);
            }
            return result;
        }
        @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession s, AlertPrompt p) {
            GeckoResult<PromptResponse> r = new GeckoResult<>();
            new AlertDialog.Builder(MainActivity.this).setTitle(p.title).setMessage(p.message)
                    .setPositiveButton("OK",(d,w)->r.complete(p.dismiss()))
                    .setOnCancelListener(d->r.complete(p.dismiss())).show();
            return r;
        }
        @Override public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession s, ButtonPrompt p) {
            GeckoResult<PromptResponse> r = new GeckoResult<>();
            new AlertDialog.Builder(MainActivity.this).setTitle(p.title).setMessage(p.message)
                    .setPositiveButton("OK",(d,w)->r.complete(p.confirm(ButtonPrompt.Type.POSITIVE)))
                    .setNegativeButton("Cancel",(d,w)->r.complete(p.confirm(ButtonPrompt.Type.NEGATIVE)))
                    .setOnCancelListener(d->r.complete(p.dismiss())).show();
            return r;
        }
        @Override public GeckoResult<PromptResponse> onTextPrompt(GeckoSession s, TextPrompt p) {
            return textPrompt(p, p.message, p.defaultValue, p::confirm);
        }
        @Override public GeckoResult<PromptResponse> onColorPrompt(GeckoSession s, ColorPrompt p) {
            return textPrompt(p, "Color in #RRGGBB format", p.defaultValue, p::confirm);
        }
        @Override public GeckoResult<PromptResponse> onDateTimePrompt(GeckoSession s, DateTimePrompt p) {
            return textPrompt(p, "Enter the date or time in the displayed format", p.defaultValue, p::confirm);
        }
        private GeckoResult<PromptResponse> textPrompt(BasePrompt p, String message, String value,
                java.util.function.Function<String, PromptResponse> confirm) {
            GeckoResult<PromptResponse> r = new GeckoResult<>();
            EditText input = new EditText(MainActivity.this); input.setText(value);
            new AlertDialog.Builder(MainActivity.this).setTitle(p.title).setMessage(message).setView(input)
                    .setPositiveButton("OK",(d,w)->r.complete(confirm.apply(input.getText().toString())))
                    .setNegativeButton("Cancel",(d,w)->r.complete(p.dismiss()))
                    .setOnCancelListener(d->r.complete(p.dismiss())).show();
            return r;
        }
        private void flatten(ChoicePrompt.Choice[] choices, List<ChoicePrompt.Choice> out) {
            for (ChoicePrompt.Choice c : choices) {
                if (c.disabled || c.separator) continue;
                if (c.items != null) flatten(c.items, out); else out.add(c);
            }
        }
        @Override public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession s, ChoicePrompt p) {
            GeckoResult<PromptResponse> r = new GeckoResult<>();
            List<ChoicePrompt.Choice> choices = new ArrayList<>(); flatten(p.choices, choices);
            String[] labels = choices.stream().map(c->c.label).toArray(String[]::new);
            AlertDialog.Builder dialog = new AlertDialog.Builder(MainActivity.this).setTitle(p.title)
                    .setOnCancelListener(d->r.complete(p.dismiss()));
            if (p.type == ChoicePrompt.Type.MULTIPLE) {
                boolean[] selected = new boolean[choices.size()];
                for (int i=0;i<selected.length;i++) selected[i]=choices.get(i).selected;
                dialog.setMultiChoiceItems(labels,selected,(d,i,b)->selected[i]=b)
                    .setPositiveButton("OK",(d,w)->{
                        List<String> ids = new ArrayList<>();
                        for (int i=0;i<selected.length;i++) if(selected[i]) ids.add(choices.get(i).id);
                        r.complete(p.confirm(ids.toArray(new String[0])));
                    }).setNegativeButton("Cancel",(d,w)->r.complete(p.dismiss()));
            } else dialog.setItems(labels,(d,i)->r.complete(p.confirm(choices.get(i).id)));
            dialog.show(); return r;
        }
    }
}
