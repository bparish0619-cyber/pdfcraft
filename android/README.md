# PDFCraft for Android

The Android application packages the current PDFCraft web build and its existing
PDF engines. It is an addition to the repository: the web application and Tauri
desktop targets are retained. Android 8.0/API 26 or newer is required. Phones and
tablets use the existing responsive interface, with Android system-bar/keyboard
insets and Back navigation.

## Install and update

After **Android APK** passes on the default branch, download `PDFCraft-Android.apk`
from the repository's **android-latest** prerelease. Allow installation from the
browser/file manager when Android asks, then open the APK. The APK contains ARM64,
ARMv7 and x86_64 engine libraries; there is no separate runtime installation.
New successful builds replace the rolling APK. Download and install a later APK
to update; the app does not silently install updates itself.

Without signing secrets, builds are labeled **PDFCraft QA**, use package
`com.pdfcraft.android.qa`, and share an intentionally public QA certificate.
Install them only from this fork's trusted release/Actions page. They are for
review, not secure public distribution. See [signing details](signing/README.md).
The APK is large because it bundles a browser engine, all locales, and the same
LibreOffice/Pyodide/PDF assets as the web application. Allow several GB of free
space for installation and large document processing.

## Capabilities

- Existing PDFCraft tools and workflows remain available through the packaged UI.
- PDF merge/split, page editing, rendering, annotations, forms, signatures,
  compression, extraction and conversion use the upstream JavaScript/WASM code.
- LibreOffice assets are included for Word, Excel, PowerPoint and other Office
  conversions. Bundled GeckoView with site isolation and COOP/COEP headers supports
  the shared-memory workers that standard Android WebView cannot provide.
- Pyodide, PyMuPDF, bundled Python wheels, qpdf, PDF.js workers and local fonts are
  included. Android does not substitute reduced-function PDF processors.
- Android's document picker opens single or multiple local/cloud-provider files.
  Exported PDFs, archives, images and other results are streamed to temporary
  private storage, then saved to the location chosen in Android's save dialog.
- No broad storage permission is requested. Documents remain on the device unless
  the user selects an external destination or an upstream network-dependent tool.

Bundling the same source is not proof that every tool or document format is
identical on every device. Very large/complex documents remain subject to device
RAM. Upstream features that fetch remote OCR language data, optional fonts,
DjVu resources or external URLs still need internet; those dependencies are not
misrepresented as offline. External links open in the user's browser. Android
file dialogs replace desktop drag/drop and filesystem paths.

## Automatic builds after fork sync

`.github/workflows/android.yml` runs on pushes to `main`/`master`, Android feature
branches, pull requests to `main`/`master`, and **Run workflow**. GitHub's normal
**Sync fork → Update branch** merge produces a push and rebuilds the APK. Keep the
Android commits when syncing: discarding/resetting the fork to upstream removes
fork-only additions. If Actions is disabled in the fork, enable it once from the
Actions tab. If a sync tool uses `GITHUB_TOKEN`, GitHub suppresses recursive push
workflows; that tool must explicitly dispatch **Android APK** afterward or use
an authorized token whose push can trigger Actions.

The Android workflow is separate from existing desktop/web workflows. Dependency
or code changes in future upstream commits can still require maintenance; no
workflow can guarantee compatibility with unknown future changes.

## Build locally

Install Node 22, JDK 17 and Android SDK platform 37/build-tools 36.0.0, and set
`ANDROID_HOME` (or `android/local.properties` with `sdk.dir=...`). From repo root:

```sh
npm ci
npx --no-install next build
node android/scripts/prepare-assets.mjs
node android/scripts/prepare-tests.mjs
./android/gradlew -p android :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

Windows uses `android\gradlew.bat`. The APK is at
`android/app/build/outputs/apk/release/app-release.apk`.
Use direct `next build` here: the existing `npm run build` postbuild chunks large
assets for Cloudflare; Android requires the complete engines. Preparation fails
if essential assets are missing. Original assets are not modified or removed.

Pinned native toolchain: AGP 9.3.2, Gradle 9.5.0 (checksum verified), SDK/build-tools
37/36.0.0, GeckoView 155.0.20260903215306, AndroidX Activity 1.10.1, NanoHTTPD 2.3.1.
The repository's npm lockfile governs web dependencies. GeckoView must be updated
periodically for browser security fixes; it does not update via System WebView.
GeckoView is MPL-2.0 and NanoHTTPD BSD-3-Clause; the existing project license still
applies. See the dependency publishers for license text and corresponding source.

## Private distribution signing

Set all four repository Actions secrets: `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
Keep a secure backup of your private keystore. The workflow decodes it into the
runner's temporary directory, masks credentials via GitHub Secrets, and removes
it afterward. Private signing is never activated for pull requests.

Private release builds use `com.pdfcraft.android` and the label **PDFCraft**;
QA builds remain separately installable. `github.run_number` supplies a monotonically
increasing versionCode within this workflow. When adopting the workflow in another
repository, set the next versionCode above any previously distributed release.

## Verification and device QA

CI gates: production web build and TypeScript checks; native server unit tests;
Android lint; native APK build; signature and embedded-asset checks; Android API 28
phone and API 35 tablet emulator tests. Engine tests verify secure context,
cross-origin isolation, SharedArrayBuffer across a worker, real PDF merge/split,
PDF.js rendering/text extraction, PyMuPDF loading, DOCX/XLSX/PPTX → readable PDF,
and delivery of an exported Blob to the native download callback.

These are representative regression tests, not an exhaustive certification of
all PDFCraft tools. Before upstream submission, also QA on physical phone and
tablet: select multiple files, cancel/reopen pickers, save and reopen exports,
annotate, fill/sign forms, compress a scan, OCR, ZIP export, rotate while working,
Back navigation, keyboard visibility, cloud providers, offline core tools,
large files, and installation of a second APK over the first. Record exact device,
Android version, source SHA and results; do not equate APK compilation with parity.
