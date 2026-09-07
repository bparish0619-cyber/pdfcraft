import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  handOffToAndroid,
  androidExportEndpoint,
  installAndroidDownloadInterception,
  resetAndroidBridgeForTests,
} from '@/lib/android-bridge';

const bridgeResponse = () =>
  new Response(JSON.stringify({ export: '/_export/tok3n' }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });

describe('android export bridge', () => {
  beforeEach(() => resetAndroidBridgeForTests());
  afterEach(() => vi.unstubAllGlobals());

  it('hands the bytes to the endpoint the shell advertises', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      void init;
      if (String(input) === '/_bridge.json') return bridgeResponse();
      return new Response(null, { status: 204 });
    });
    vi.stubGlobal('fetch', fetchMock);

    const file = new File([new Uint8Array([0x25, 0x50, 0x44, 0x46])], 'out.pdf', { type: 'application/pdf' });
    await expect(handOffToAndroid(file, 'out.pdf')).resolves.toBe(true);

    const [url, init] = fetchMock.mock.calls[1];
    expect(url).toBe('/_export/tok3n');
    expect(init?.method).toBe('POST');
    expect(init?.body).toBe(file);
    expect(init?.headers).toMatchObject({
      'Content-Type': 'application/pdf',
      'X-PDFCraft-Filename': 'out.pdf',
    });
  });

  it('reports no shell when the bridge is absent, so the caller downloads normally', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('not found', { status: 404 })));
    await expect(androidExportEndpoint()).resolves.toBeNull();
    await expect(handOffToAndroid(new Blob(['x']), 'x.pdf')).resolves.toBe(false);
  });

  it('reports no shell when discovery answers with something else', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ export: 'https://elsewhere.example/steal' }), { status: 200 })));
    await expect(androidExportEndpoint()).resolves.toBeNull();
  });

  it('surfaces a refusal rather than silently failing to save', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/_bridge.json') return bridgeResponse();
      return new Response('too large', { status: 413 });
    }));
    await expect(handOffToAndroid(new Blob(['x']), 'x.pdf')).rejects.toThrow(/HTTP 413/);
  });

  it('discovers once and reuses the answer', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/_bridge.json') return bridgeResponse();
      return new Response(null, { status: 204 });
    });
    vi.stubGlobal('fetch', fetchMock);
    await handOffToAndroid(new Blob(['a']), 'a.pdf');
    await handOffToAndroid(new Blob(['b']), 'b.pdf');
    expect(fetchMock.mock.calls.filter(([url]) => String(url) === '/_bridge.json')).toHaveLength(1);
  });

  describe('download click interception', () => {
    const anchorFor = (href: string, name: string | null) => {
      const anchor = document.createElement('a');
      anchor.href = href;
      if (name !== null) anchor.setAttribute('download', name);
      document.body.appendChild(anchor);
      return anchor;
    };

    afterEach(() => { document.body.innerHTML = ''; });

    it('takes over a download anchor and hands the bytes to the shell', async () => {
      const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url === '/_bridge.json') return bridgeResponse();
        if (url.startsWith('blob:')) return new Response(new Blob(['%PDF-x'], { type: 'application/pdf' }));
        return new Response(null, { status: 204 });
      });
      vi.stubGlobal('fetch', fetchMock);
      installAndroidDownloadInterception();
      await androidExportEndpoint();

      const anchor = anchorFor('blob:http://127.0.0.1:1234/abc', 'merged.pdf');
      const event = new MouseEvent('click', { bubbles: true, cancelable: true });
      anchor.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
      await vi.waitFor(() =>
        expect(fetchMock.mock.calls.some(([url]) => String(url) === '/_export/tok3n')).toBe(true));
    });

    it('leaves ordinary links and non-download anchors alone', async () => {
      const fetchMock = vi.fn(async (input: RequestInfo | URL) =>
        String(input) === '/_bridge.json' ? bridgeResponse() : new Response(null, { status: 204 }));
      vi.stubGlobal('fetch', fetchMock);
      installAndroidDownloadInterception();
      await androidExportEndpoint();

      for (const anchor of [anchorFor('/tools/merge-pdf', null), anchorFor('https://example.com/a.pdf', 'a.pdf')]) {
        const event = new MouseEvent('click', { bubbles: true, cancelable: true });
        anchor.dispatchEvent(event);
        expect(event.defaultPrevented).toBe(false);
      }
    });

    it('does not swallow a click when there is no shell', async () => {
      vi.stubGlobal('fetch', vi.fn(async () => new Response('nope', { status: 404 })));
      installAndroidDownloadInterception();
      await androidExportEndpoint();

      const anchor = anchorFor('blob:http://localhost:3000/abc', 'merged.pdf');
      const event = new MouseEvent('click', { bubbles: true, cancelable: true });
      anchor.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
    });
  });
});
