/**
 * Hands an export to the Android shell.
 *
 * GeckoView never delivers a blob: download to the app, so clicking an <a download>
 * inside the Android build reached nothing at all and every export silently failed.
 * There the page posts the bytes to the shell's loopback server, which offers them
 * to Android's save dialog. The endpoint is discovered at runtime and is absent on
 * the web, so callers fall back to an ordinary browser download.
 */
const BRIDGE_URL = '/_bridge.json';
const EXPORT_PREFIX = '/_export/';

let discovery: Promise<string | null> | null = null;
let resolved: string | null = null;
let intercepting = false;

/** The shell always serves the app from loopback, so nothing else needs probing. */
function couldBeTheAndroidShell(): boolean {
    if (typeof window === 'undefined' || typeof fetch === 'undefined') return false;
    const host = window.location.hostname;
    return host === '127.0.0.1' || host === 'localhost';
}

async function discover(): Promise<string | null> {
    if (!couldBeTheAndroidShell()) return null;
    try {
        const response = await fetch(BRIDGE_URL, { cache: 'no-store' });
        if (!response.ok) return null;
        const bridge: unknown = await response.json();
        const endpoint = (bridge as { export?: unknown } | null)?.export;
        return typeof endpoint === 'string' && endpoint.startsWith(EXPORT_PREFIX) ? endpoint : null;
    } catch {
        // Not the Android shell, or the shell is not serving the bridge.
        return null;
    }
}

/** Resolved once per page: the answer cannot change while the page is loaded. */
export function androidExportEndpoint(): Promise<string | null> {
    if (!discovery) discovery = discover();
    return discovery;
}

/**
 * Returns false when there is no shell to hand off to, so the caller should download
 * the file the ordinary way. Throws when a shell is present but refused the bytes,
 * because falling back to a blob download there would do nothing at all.
 */
export async function handOffToAndroid(file: Blob, filename: string): Promise<boolean> {
    const endpoint = await androidExportEndpoint();
    if (!endpoint) return false;
    const response = await fetch(endpoint, {
        method: 'POST',
        body: file,
        headers: {
            'Content-Type': file.type || 'application/octet-stream',
            'X-PDFCraft-Filename': filename,
        },
    });
    if (!response.ok) {
        throw new Error(`The Android shell refused the export with HTTP ${response.status}`);
    }
    return true;
}

/**
 * Takes over every <a download> click while running inside the Android shell,
 * which is every export the app offers: each tool builds such an anchor, and in
 * the shell that click reaches nothing. Does nothing on the web.
 */
export function installAndroidDownloadInterception(): void {
    if (intercepting || typeof document === 'undefined') return;
    intercepting = true;
    // Resolved up front because preventDefault cannot wait for a promise; until it
    // resolves, clicks are left alone rather than swallowed.
    void androidExportEndpoint().then((endpoint) => { resolved = endpoint; });
    document.addEventListener('click', interceptDownloadClick, true);
}

function interceptDownloadClick(event: MouseEvent): void {
    if (!resolved || event.defaultPrevented) return;
    const target = event.target;
    if (!(target instanceof Element)) return;
    const anchor = target.closest('a[download]');
    if (!(anchor instanceof HTMLAnchorElement)) return;
    const href = anchor.getAttribute('href') ?? '';
    if (!href.startsWith('blob:') && !href.startsWith('data:')) return;

    event.preventDefault();
    const filename = anchor.getAttribute('download') || 'download';
    void (async () => {
        const blob = await (await fetch(href)).blob();
        await handOffToAndroid(new File([blob], filename, { type: blob.type }), filename);
    })().catch((error) => {
        console.error('[android-bridge] Could not hand the export to Android', error);
    });
}

/** Test seam: forget the discovered endpoint and stop intercepting. */
export function resetAndroidBridgeForTests(): void {
    discovery = null;
    resolved = null;
    if (intercepting && typeof document !== 'undefined') {
        document.removeEventListener('click', interceptDownloadClick, true);
    }
    intercepting = false;
}
