import { PDFDocument } from "pdf-lib";
import { MergePDFProcessor } from "../../src/lib/pdf/processors/merge";
import { SplitPDFProcessor } from "../../src/lib/pdf/processors/split";
import { LibreOfficeConverter } from "../../src/lib/libreoffice/converter";
import { loadPyMuPDF } from "../../src/lib/pdf/pymupdf-loader";
import { PDFToSlideProcessor } from "../../src/lib/pdf/processors/pdf-to-slide";
import { loadPdfjs } from "../../src/lib/pdf/loader";
const check = (value, message) => {
  if (!value) throw new Error(message);
};
const started = Date.now();
// Titles are the only channel the instrumented test can observe, and logcat is
// the only channel CI can read, so every stage goes to both. Elapsed seconds
// turn a timeout into a report of which stage was still running.
const report = (message) => {
  const line = `${message} [${((Date.now() - started) / 1000).toFixed(1)}s]`;
  document.body.innerText += line + "\n";
  document.title = line;
  console.log("PDFCraftSmoke " + line);
  window.__smokeAlive?.();
};
const stage = (name) => report("PROGRESS: starting " + name);
async function run() {
  stage("runtime capability checks");
  check(window.isSecureContext, "Loopback must be a secure context");
  check(window.crossOriginIsolated, "Cross-origin isolation must be enabled");
  check(typeof SharedArrayBuffer === "function", "SharedArrayBuffer must be available");
  const workerUrl = URL.createObjectURL(new Blob(['onmessage=e=>{Atomics.add(new Int32Array(e.data),0,1);postMessage("ok")}'], { type: "text/javascript" }));
  const worker = new Worker(workerUrl);
  const shared = new SharedArrayBuffer(4);
  await new Promise((resolve, reject) => {
    worker.onmessage = () => resolve();
    worker.onerror = reject;
    worker.postMessage(shared);
  });
  check(new Int32Array(shared)[0] === 1, "Shared worker memory failed");
  worker.terminate();
  URL.revokeObjectURL(workerUrl);
  report("PROGRESS: isolation and shared workers passed");
  const make = async (name) => {
    const pdf = await PDFDocument.create();
    pdf.addPage().drawText(name);
    return new File([await pdf.save()], name + ".pdf", { type: "application/pdf" });
  };
  stage("merge and split");
  const merged = await new MergePDFProcessor().process({ files: [await make("first"), await make("second")], options: {} });
  check(merged.success && merged.result, "PDF merge failed");
  const mergedBlob = merged.result;
  const mergedPdf = await PDFDocument.load(await mergedBlob.arrayBuffer());
  check(mergedPdf.getPageCount() === 2, "Merged document must have two pages");
  const split = await new SplitPDFProcessor().process({ files: [new File([mergedBlob], "merged.pdf")], options: { ranges: [{ start: 2, end: 2 }], outputFormat: "multiple" } });
  check(split.success && split.result, "PDF split failed");
  const splitBlob = Array.isArray(split.result) ? split.result[0] : split.result;
  check((await PDFDocument.load(await splitBlob.arrayBuffer())).getPageCount() === 1, "Split must have one page");
  stage("PDF.js load, text extraction and canvas render");
  const pdfjs = await loadPdfjs();
  const rendered = await pdfjs.getDocument({ data: await mergedBlob.arrayBuffer() }).promise;
  const page = await rendered.getPage(1);
  check((await page.getTextContent()).items.some((i) => i.str === "first"), "PDF.js text extraction failed");
  const viewport = page.getViewport({ scale: 0.5 });
  const canvas = document.createElement("canvas");
  canvas.width = viewport.width;
  canvas.height = viewport.height;
  await page.render({ canvasContext: canvas.getContext("2d"), viewport }).promise;
  report("PROGRESS: merge, split, PDF.js rendering and text passed");
  stage("PyMuPDF/Pyodide runtime (about 62 MB of bundled assets)");
  const pymupdf = await loadPyMuPDF();
  const py = pymupdf.pyodide;
  py.FS.writeFile("/smoke.pdf", new Uint8Array(await mergedBlob.arrayBuffer()));
  check(await py.runPythonAsync("import pymupdf\nd=pymupdf.open('/smoke.pdf')\nlen(d)") === 2, "PyMuPDF failed");
  stage("python-docx and openpyxl wheels");
  for (const wheel of ['python_docx-1.2.0-py3-none-any.whl','et_xmlfile-2.0.0-py3-none-any.whl','openpyxl-3.1.5-py2.py3-none-any.whl']) await py.loadPackage('/pymupdf-wasm/'+wheel);
  await py.runPythonAsync(`
from docx import Document
from openpyxl import Workbook
doc=Document(); doc.add_paragraph('PDFCraft Android Word test'); doc.save('/smoke.docx')
book=Workbook(); book.active['A1']='PDFCraft Android Excel test'; book.save('/smoke.xlsx')
`);
  stage("PDF to PowerPoint");
  const slideResult = await new PDFToSlideProcessor().process({files:[new File([mergedBlob], "slides.pdf")], options:{}});
  check(slideResult.success && slideResult.result instanceof Blob, "PDF to PowerPoint failed");
  py.FS.writeFile("/smoke.pptx", new Uint8Array(await slideResult.result.arrayBuffer()));
  stage("LibreOffice runtime (about 235 MB of bundled assets)");
  const office = new LibreOfficeConverter();
  await office.initialize();
  report("PROGRESS: LibreOffice runtime initialized");
  try {
    for (const extension of ["docx", "xlsx", "pptx"]) {
      stage(extension + " conversion");
      const input = new File([py.FS.readFile("/smoke." + extension)], "smoke." + extension);
      const pdf = await office.convertToPdf(input);
      check((await PDFDocument.load(await pdf.arrayBuffer())).getPageCount() >= 1, extension + " conversion must produce a readable PDF");
      report("PROGRESS: " + extension + " conversion passed");
    }
  } finally {
    await office.destroy();
  }
  stage("native Blob download hand-off");
  const link = document.createElement("a");
  link.href = URL.createObjectURL(mergedBlob);
  link.download = "android-smoke.pdf";
  document.body.appendChild(link);
  link.click();
  report("PASS: Android engines and PDF outputs");
}
run().catch((error) => {
  console.error(error);
  // Firefox stacks start at the throw site and omit the message, so report both:
  // checkEnvironment alone throws for isolation, a bad HTTP status and a failed
  // fetch, and only the message says which.
  const message = error && error.message ? error.message : String(error);
  const stack = error && error.stack ? " | " + String(error.stack).split("\n")[0] : "";
  report("FAIL: " + message.replace(/\s+/g, " ") + stack);
});
