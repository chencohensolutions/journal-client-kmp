// Decodes HEIC/HEIF photos off the page's thread (decode.js). libheif-js compiles its WebAssembly synchronously,
// which the TV's Cast browser (Chromium 92, seen Nov 9, 2026) refuses on the page's thread for anything over 4 KB, but
// allows in a worker. Gets {warm} once at start (compile now), then {id, bytes}; answers {id, width, height, data} (RGBA, transferred) or {id, error}.

import factory from './vendor/libheif/libheif-bundle.mjs';

let lib = null;

async function library() {
  if (!lib) {
    lib = (async () => {
      const made = factory();
      if (made && typeof made.then === 'function') return await made;
      if (made && made.ready && typeof made.ready.then === 'function') await made.ready;
      return made;
    })();
  }
  return lib;
}

self.onmessage = async ({ data: { id, bytes, warm } }) => {
  // Sent once the page loads: libheif compiled now, before the first photo waits on it.
  if (warm) {
    library().catch((e) => console.warn('libheif could not start', e));
    return;
  }
  try {
    const heif = await library();
    const decoder = new heif.HeifDecoder();
    const images = decoder.decode(bytes);
    try {
      if (!images || images.length === 0) throw new Error('not a HEIF photo');
      // The primary image comes first; libheif turns it upright (its irot and imir boxes).
      const image = images[0];
      const width = image.get_width();
      const height = image.get_height();
      const shown = await new Promise((resolve, reject) => {
        image.display({ data: new Uint8ClampedArray(width * height * 4), width, height }, (done) => {
          if (done) resolve(done); else reject(new Error('HEIF decoding failed'));
        });
      });
      self.postMessage({ id, width, height, data: shown.data }, [shown.data.buffer]);
    } finally {
      for (const each of images || []) each.free?.();
      decoder.decoder && heif.heif_context_free?.(decoder.decoder);
    }
  } catch (e) {
    self.postMessage({ id, error: String(e && e.message || e) });
  }
};
