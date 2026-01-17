# Mobile Compatibility Guidelines (iOS & Android)

PDFCraft is designed to be highly compatible with both iOS and Android via **Capacitor**. To ensure a smooth transition to native mobile platforms, follow these guidelines.

## 📱 Layout & UI

### Safe Areas
We use `viewport-fit=cover` and CSS `env(safe-area-inset-*)` variables to handle notches, home indicators, and Android status/navigation bars.
- Always use the `--safe-area-*` variables defined in [globals.css](file:///Users/brandonparish/Projects/pdfcraft/src/app/globals.css) for outer-most containers.
- On Android, ensure the status bar color matches the app theme.

### Touch Interactions
- Use `-webkit-tap-highlight-color: transparent` for custom interactive elements.
- Ensure touch targets are at least 44x44px (Apple's Human Interface Guidelines).
- Avoid relying on `hover` states for critical functionality.

## ⚙️ Core Logic

### Hardware Back Button (Android)
Android users expect the hardware back button to navigate back or close modals. 
- Use Capacitor's `App` plugin to listen for the `backButton` event and integrate it with Next.js routing.

### Native APIs
When you need native functionality (File system, Share sheet, Camera):
1. **Detection**: Check if the app is running in a Capacitor bridge.
2. **Graceful Degradation**: Fall back to standard Web APIs when running in a browser.

### WebAssembly
Both iOS and Android WebViews support WASM, but memory limits can be tighter than on desktop, especially on entry-level Android devices.
- Monitor memory usage when processing large PDFs.
- Use `SharedArrayBuffer` with caution (requires specific COOP/COEP headers).

## 🚀 Deployment

When you are ready to export:
1. Install Capacitor: `npm install @capacitor/core @capacitor/cli`
2. Initialize: `npx cap init`
3. Add Platforms:
   - `npx cap add ios`
   - `npx cap add android`
4. Build Web App: `npm run build` (generates `out/` folder)
5. Sync: `npx cap copy`
6. Open IDE:
   - `npx cap open ios` (Xcode)
   - `npx cap open android` (Android Studio)

> [!TIP]
> Use the **Browser** plugin from Capacitor for external links instead of `window.open`.
