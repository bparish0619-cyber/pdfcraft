# QA signing only

`qa.keystore` is intentionally public test material (password `android`, alias
`androiddebugkey`). It lets the no-secrets QA workflow produce APK updates with
the same certificate. These builds use the separate `com.pdfcraft.android.qa`
package and the **PDFCraft QA** label. This key does not establish publisher
identity: install QA updates only from your own trusted Actions/release page.
Do not use it for public distribution or the Play Store.

For distribution, configure the four private signing secrets documented in
`android/README.md`. Private signing uses `com.pdfcraft.android`, so it installs
alongside QA; data does not automatically migrate between those applications.
