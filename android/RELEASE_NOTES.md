# Android application addition

PDFCraft now includes an Android application and an independent **Android APK**
GitHub Actions workflow. Existing web/desktop sources and build workflows are
preserved.

## Added

- Universal Android APK for phones/tablets running Android 8+.
- Packaged current PDFCraft interface and tool processors, all generated locales,
  PDF.js, qpdf, Pyodide/PyMuPDF, Python wheels and LibreOffice WASM assets.
- Bundled GeckoView and an isolated, read-only loopback asset server to support
  the shared-memory Office conversion engine.
- Native single/multiple document selection, streamed file export with Android's
  save picker, Back navigation, screen/keyboard insets and responsive layout.
- Automatic rebuilds after normal fork-sync pushes to main/master, manual builds,
  PR checks and a rolling prerelease only after build and emulator checks pass.
- Installable no-secrets QA signing; optional private signing for distribution.
- Native unit tests, APK asset/signature checks and phone/tablet emulator checks
  covering core PDF engines, Office conversion and native download delivery.

## Compatibility and QA scope

The app packages the upstream capabilities; broad tool-by-tool physical-device
parity is still a QA requirement. Complex files depend on available device memory.
Optional remote fonts, OCR/DjVu downloads and tools using external URLs retain
upstream network requirements. Default **PDFCraft QA** builds use a public test
certificate and are not intended for secure public distribution. The APK is large
because the processing engines are included. Updates are downloaded and installed
manually; building is automatic.

For setup, signing, sync behavior and the device QA checklist, see
[android/README.md](README.md). The APK contains `assets/web/android-build.json`
with the exact source commit; Actions artifacts are also tied to their build SHA.
