# Android application design

Add a standalone Java/Gradle Android application under `android/`. Keep every existing source file, desktop target and workflow; append Android instructions to the README. Package the current Next.js static export without Cloudflare asset splitting. Embed pinned GeckoView to support the SharedArrayBuffer and WebAssembly threads required by LibreOffice, with a loopback-only asset server supplying COOP/COEP and correct MIME/encoding headers. Use native Storage Access Framework file prompts and streamed downloads, safe-area insets, responsive layouts and Android back navigation.

Build a universal APK for Android 8+ (API 26), targeting/compiling API 37, with JDK 17, Gradle 9.5.0 and AGP 9.3.2. A public QA-only signing key and distinct QA application ID allow installation and updates without secrets; optional private signing is reserved for distribution. Never describe the public QA key as secure release signing.

GitHub Actions runs on main/master pushes, pull requests and manual dispatch; normal fork sync merges produce pushes. No upstream files are removed or upstream workflows replaced. Publish a rolling QA prerelease and artifact after native tests, lint, build and emulator smoke tests. Preserve source provenance and document dependency versions, functional test scope and remaining device QA.
