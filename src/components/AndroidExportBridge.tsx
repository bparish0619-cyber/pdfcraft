'use client';

import { useEffect } from 'react';
import { installAndroidDownloadInterception } from '@/lib/android-bridge';

/**
 * Makes exports reach Android. GeckoView never delivers a blob: download to the
 * app, so every tool's download click needs handing to the shell instead. Renders
 * nothing, and does nothing at all on the web.
 */
export function AndroidExportBridge() {
    useEffect(() => { installAndroidDownloadInterception(); }, []);
    return null;
}
