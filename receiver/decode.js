// Turns an original's bytes into a picture the page can draw. The TV's browser reads JPEG, PNG, WebP, GIF and AVIF
// itself; HEIC/HEIF and JPEG XL go through the decoders in vendor/, loaded the first time one comes (HEIF in a worker,
// heif-worker.js). The kind is told from the bytes, not the name or type the phone sent, as the phone's guess may be
// wrong.

/** The longest side a photo is kept at: sharp when zoomed in on a 4K TV, and about 150 MB at most in memory. */
export const MAX_SIDE = 6144;

/** "heif", "jxl", or "native" (anything the browser should read), from the file's first bytes. */
export function kindOf(bytes) {
  const b = bytes;
  if (b.length >= 2 && b[0] === 0xff && b[1] === 0x0a) return 'jxl';
  // JPEG XL in its ISOBMFF container: a 12-byte "JXL " box first.
  if (b.length >= 12 && b[0] === 0 && b[1] === 0 && b[2] === 0 && b[3] === 0x0c &&
      b[4] === 0x4a && b[5] === 0x58 && b[6] === 0x4c && b[7] === 0x20) return 'jxl';
  if (b.length >= 12 && ascii(b, 4, 4) === 'ftyp') {
    const brand = ascii(b, 8, 4);
    if (brand === 'avif' || brand === 'avis') return 'native';
    // heic, heix, hevc, heim, heis, hevm, hevs, mif1, msf1: HEIF of any kind. A "mif1" that is really AVIF lists
    // "avif" among its compatible brands.
    const size = (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3];
    const brands = [];
    for (let i = 16; i + 4 <= Math.min(size, b.length); i += 4) brands.push(ascii(b, i, 4));
    if (brands.includes('avif') && !brands.includes('heic')) return 'native';
    return 'heif';
  }
  return 'native';
}

function ascii(b, start, length) {
  let s = '';
  for (let i = start; i < start + length; i++) s += String.fromCharCode(b[i]);
  return s;
}

/** Width and height that fit [w] × [h] inside [maxSide], keeping the shape; the same when already inside. */
export function fitInside(w, h, maxSide = MAX_SIDE) {
  const longer = Math.max(w, h);
  if (longer <= maxSide) return { width: w, height: h };
  const scale = maxSide / longer;
  return { width: Math.max(1, Math.round(w * scale)), height: Math.max(1, Math.round(h * scale)) };
}

/** An ImageBitmap of the photo in [bytes] (a Uint8Array), upright, at most [MAX_SIDE] on its longer side. */
export async function decode(bytes) {
  switch (kindOf(bytes)) {
    case 'heif': return decodeHeif(bytes);
    case 'jxl': return decodeJxl(bytes);
    default: return decodeNative(new Blob([bytes]));
  }
}

async function decodeNative(blob) {
  // EXIF orientation is applied ("from-image" is the default); the size is read first to scale while decoding.
  const full = await createImageBitmap(blob);
  const fit = fitInside(full.width, full.height);
  if (fit.width === full.width) return full;
  const scaled = await createImageBitmap(full, { resizeWidth: fit.width, resizeHeight: fit.height, resizeQuality: 'high' });
  full.close();
  return scaled;
}

// HEIC/HEIF is decoded in a worker (heif-worker.js): the TV's browser won't compile libheif on the page's thread.
let heifWorker = null;
let heifAsked = 0;
const heifWaiting = new Map();

function heifDecoder() {
  if (!heifWorker) {
    heifWorker = new Worker(new URL('./heif-worker.js', import.meta.url), { type: 'module' });
    heifWorker.onmessage = ({ data }) => {
      const waiting = heifWaiting.get(data.id);
      if (!waiting) return;
      heifWaiting.delete(data.id);
      if (data.error) waiting.reject(new Error(data.error));
      else waiting.resolve(data);
    };
    heifWorker.onerror = (event) => {
      const error = new Error(event.message || 'the HEIF decoder could not start');
      for (const waiting of heifWaiting.values()) waiting.reject(error);
      heifWaiting.clear();
      heifWorker = null;
    };
  }
  return heifWorker;
}

async function decodeHeif(bytes) {
  const id = ++heifAsked;
  const { width, height, data } = await new Promise((resolve, reject) => {
    heifWaiting.set(id, { resolve, reject });
    // A copy goes to the worker, so the bytes the page keeps stay usable.
    heifDecoder().postMessage({ id, bytes });
  });
  const fit = fitInside(width, height);
  return createImageBitmap(new ImageData(data, width, height),
    { resizeWidth: fit.width, resizeHeight: fit.height, resizeQuality: 'high' });
}

let jxl = null;

async function jxlLibrary() {
  if (!jxl) {
    jxl = (async () => {
      const module = await import('./vendor/jxl-oxide/jxl_oxide_wasm.js');
      await module.default();
      return module;
    })();
  }
  return jxl;
}

async function decodeJxl(bytes) {
  const { JxlImage } = await jxlLibrary();
  const image = new JxlImage();
  try {
    image.feedBytes(bytes);
    if (!image.tryInit()) throw new Error('JPEG XL photo is cut short');
    // Rendered in sRGB (turned upright by jxl-oxide) and handed over as PNG, which the browser decodes quickly.
    image.forceSrgb = true;
    // encodeToPng frees the render itself.
    const png = image.render().encodeToPng();
    return await decodeNative(new Blob([png], { type: 'image/png' }));
  } finally {
    image.free();
  }
}
