import { PDFDocument } from "pdf-lib";
import { MergePDFProcessor } from "../../src/lib/pdf/processors/merge";
import { SplitPDFProcessor } from "../../src/lib/pdf/processors/split";
import { LibreOfficeConverter } from "../../src/lib/libreoffice/converter";
import { loadPyMuPDF } from "../../src/lib/pdf/pymupdf-loader";
import { loadPdfjs } from "../../src/lib/pdf/loader";
const check = (value, message) => {
  if (!value) throw new Error(message);
};
const report = (message) => {
  document.body.innerText += message + "\n";
  document.title = message;
};
async function run() {
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
  const merged = await new MergePDFProcessor().process({ files: [await make("first"), await make("second")], options: {} });
  check(merged.success && merged.result, "PDF merge failed");
  const mergedBlob = merged.result;
  const mergedPdf = await PDFDocument.load(await mergedBlob.arrayBuffer());
  check(mergedPdf.getPageCount() === 2, "Merged document must have two pages");
  const split = await new SplitPDFProcessor().process({ files: [new File([mergedBlob], "merged.pdf")], options: { ranges: [{ start: 2, end: 2 }], outputFormat: "multiple" } });
  check(split.success && split.result, "PDF split failed");
  const splitBlob = Array.isArray(split.result) ? split.result[0] : split.result;
  check((await PDFDocument.load(await splitBlob.arrayBuffer())).getPageCount() === 1, "Split must have one page");
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
  const pymupdf = await loadPyMuPDF();
  const py = pymupdf.pyodide;
  py.FS.writeFile("/smoke.pdf", new Uint8Array(await mergedBlob.arrayBuffer()));
  check(await py.runPythonAsync("import pymupdf\nd=pymupdf.open('/smoke.pdf')\nlen(d)") === 2, "PyMuPDF failed");
  for (const wheel of ['python_docx-1.2.0-py3-none-any.whl','et_xmlfile-2.0.0-py3-none-any.whl','openpyxl-3.1.5-py2.py3-none-any.whl','pillow-11.2.1-cp313-cp313-pyodide_2025_0_wasm32.whl','python_pptx-1.0.2-py3-none-any.whl']) await py.loadPackage('/pymupdf-wasm/'+wheel);
  await py.runPythonAsync(`
from docx import Document
from openpyxl import Workbook
from pptx import Presentation
from pptx.util import Inches
doc=Document(); doc.add_paragraph('PDFCraft Android Word test'); doc.save('/smoke.docx')
book=Workbook(); book.active['A1']='PDFCraft Android Excel test'; book.save('/smoke.xlsx')
slides=Presentation(); slide=slides.slides.add_slide(slides.slide_layouts[5]); slide.shapes.title.text='PDFCraft Android PowerPoint test'; slides.save('/smoke.pptx')
`);
  const office = new LibreOfficeConverter();
  await office.initialize();
  try {
    for (const extension of ["docx", "xlsx", "pptx"]) {
      const input = new File([py.FS.readFile("/smoke." + extension)], "smoke." + extension);
      const pdf = await office.convertToPdf(input);
      check((await PDFDocument.load(await pdf.arrayBuffer())).getPageCount() >= 1, extension + " conversion must produce a readable PDF");
      report("PROGRESS: " + extension + " conversion passed");
    }
  } finally {
    await office.destroy();
  }
  const link = document.createElement("a");
  link.href = URL.createObjectURL(mergedBlob);
  link.download = "android-smoke.pdf";
  document.body.appendChild(link);
  link.click();
  report("PASS: Android engines and PDF outputs");
}
run().catch((error) => {
  console.error(error);
  report("FAIL: " + String(error?.stack || error));
});
