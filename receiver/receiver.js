// The Journal's Cast receiver (docs/cast.md). The phone talks to it over one custom namespace with JSON messages:
//
// Phone to TV
//   show     {item, frame?, autoplay?, muted?, position?}  show this photo or video now
//   preload  {items}                                     fetch and decode these ahead (the next few)
//   frame    {id, x, y, w, h}                            the part of the photo to fill the screen with, in fractions of
//                                                        the upright photo (it may reach past its edges: black there)
//   video    {action: "play" | "pause" | "seek", position?}
//   mute     {muted}
//   chunk    {id, index, count, data}                    the original's bytes in base64 pieces, when this page asked
//   stop     {}                                          back to the idle screen
//   hi       {}                                          answered with hello
// TV to phone
//   hello    {width, height}                             the screen's size in pixels (the frame's shape)
//   shown    {id}                                        the photo is on the screen
//   need     {id}                                        couldn't fetch the original's URL: send it in chunks instead
//   video    {id, position, duration, playing, ended}
//   error    {id, message}
//
// An item is {id, kind: "image" | "video", url, mime?, name?, width?, height?}: url is the original on the phone's own
// little web server on the Wi-Fi.

import { decode } from './decode.js';

export const NAMESPACE = 'urn:x-cast:com.chencohensolution.journal';

/** Decoded photos kept: the one shown, the next few the phone sent ahead, and one behind. */
const KEPT = 5;

/** How quickly the screen catches up with the phone's frame (0..1 a display frame): smooth, yet close behind. */
const FOLLOW = 0.35;

const canvases = [document.getElementById('a'), document.getElementById('b')];
const video = document.getElementById('video');
const idle = document.getElementById('idle');
const noteBox = document.getElementById('note');

let send = () => {};

/** Photos by id: {item, bitmap?, promise?, error?}, in the order they were last wanted. */
const photos = new Map();
/** Pieces of an original the phone is sending in chunks, by id: {count, parts[], resolve, reject}. */
const incoming = new Map();

let front = 0;
let shown = null; // {id, kind, bitmap?}
let target = null; // {x, y, w, h}: where the phone's frame is
let drawn = null; // where the screen is now, on its way to target

function screenSize() {
  const ratio = window.devicePixelRatio || 1;
  return { width: Math.round(window.innerWidth * ratio), height: Math.round(window.innerHeight * ratio) };
}

function sizeCanvases() {
  const { width, height } = screenSize();
  for (const c of canvases) {
    if (c.width !== width || c.height !== height) {
      c.width = width;
      c.height = height;
    }
  }
}

/** The frame that shows all of a [w] × [h] photo on the screen, black beside or above it. */
export function wholeFrame(w, h, screen = screenSize()) {
  const photo = w / h;
  const tv = screen.width / screen.height;
  if (photo >= tv) {
    const fh = photo / tv;
    return { x: 0, y: (1 - fh) / 2, w: 1, h: fh };
  }
  const fw = tv / photo;
  return { x: (1 - fw) / 2, y: 0, w: fw, h: 1 };
}

function draw(canvas, bitmap, frame) {
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#000';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  if (!bitmap) return;
  const W = bitmap.width;
  const H = bitmap.height;
  // The frame's part of the photo, clipped to the photo, and where that clipped part lands on the screen.
  const sx0 = Math.max(0, frame.x * W);
  const sy0 = Math.max(0, frame.y * H);
  const sx1 = Math.min(W, (frame.x + frame.w) * W);
  const sy1 = Math.min(H, (frame.y + frame.h) * H);
  if (sx1 <= sx0 || sy1 <= sy0) return;
  const kx = canvas.width / (frame.w * W);
  const ky = canvas.height / (frame.h * H);
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(bitmap, sx0, sy0, sx1 - sx0, sy1 - sy0,
    (sx0 - frame.x * W) * kx, (sy0 - frame.y * H) * ky, (sx1 - sx0) * kx, (sy1 - sy0) * ky);
}

function close(a, b) {
  return Math.abs(a.x - b.x) < 1e-4 && Math.abs(a.y - b.y) < 1e-4 && Math.abs(a.w - b.w) < 1e-4 && Math.abs(a.h - b.h) < 1e-4;
}

function tick() {
  if (shown && shown.bitmap && target && drawn && !close(drawn, target)) {
    drawn = {
      x: drawn.x + (target.x - drawn.x) * FOLLOW,
      y: drawn.y + (target.y - drawn.y) * FOLLOW,
      w: drawn.w + (target.w - drawn.w) * FOLLOW,
      h: drawn.h + (target.h - drawn.h) * FOLLOW,
    };
    if (close(drawn, target)) drawn = { ...target };
    draw(canvases[front], shown.bitmap, drawn);
  }
  requestAnimationFrame(tick);
}

let noteTimer = 0;
function note(text, millis = 4000) {
  noteBox.textContent = text;
  noteBox.classList.add('shown');
  clearTimeout(noteTimer);
  if (millis > 0) noteTimer = setTimeout(() => noteBox.classList.remove('shown'), millis);
}
function clearNote() {
  clearTimeout(noteTimer);
  noteBox.classList.remove('shown');
}

// Fetching and decoding

/** The original's bytes: from its URL on the phone, else (blocked or unreachable) sent by the phone in chunks. */
async function bytesOf(item) {
  try {
    // The phone's Wi-Fi may doze and miss a request: tried three times before the slow way.
    for (let attempt = 1; ; attempt++) {
      try {
        return await fetchWatched(item.url);
      } catch (e) {
        if (attempt >= 3) throw e;
        console.warn('fetch failed, trying again', item.id, e);
      }
    }
  } catch (e) {
    console.warn('fetch failed, asking for chunks', item.id, e);
    return new Promise((resolve, reject) => {
      incoming.set(item.id, { count: -1, parts: [], got: 0, resolve, reject });
      send({ type: 'need', id: item.id });
      setTimeout(() => {
        const waiting = incoming.get(item.id);
        if (waiting && waiting.resolve === resolve) {
          incoming.delete(item.id);
          reject(new Error('the phone did not send the photo'));
        }
      }, 120_000);
    });
  }
}

/** How long the phone may stay silent before the fetch is given up: an unreachable phone isn't waited on. */
const FETCH_SILENCE_MS = 10_000;

/** The bytes at [url], given up (and so sent in chunks instead) when no answer or no new bytes come for a while. */
async function fetchWatched(url) {
  const abort = new AbortController();
  let timer = setTimeout(() => abort.abort(), FETCH_SILENCE_MS);
  const heard = () => {
    clearTimeout(timer);
    timer = setTimeout(() => abort.abort(), FETCH_SILENCE_MS);
  };
  try {
    const response = await fetch(url, { cache: 'no-store', signal: abort.signal });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    heard();
    const reader = response.body.getReader();
    const parts = [];
    let total = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      heard();
      parts.push(value);
      total += value.length;
    }
    const bytes = new Uint8Array(total);
    let at = 0;
    for (const p of parts) {
      bytes.set(p, at);
      at += p.length;
    }
    return bytes;
  } finally {
    clearTimeout(timer);
  }
}

function onChunk({ id, index, count, data }) {
  const waiting = incoming.get(id);
  if (!waiting) return;
  waiting.count = count;
  if (!waiting.parts[index]) {
    const binary = atob(data);
    const part = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) part[i] = binary.charCodeAt(i);
    waiting.parts[index] = part;
    waiting.got++;
  }
  if (waiting.got === count) {
    incoming.delete(id);
    const total = waiting.parts.reduce((n, p) => n + p.length, 0);
    const bytes = new Uint8Array(total);
    let at = 0;
    for (const p of waiting.parts) {
      bytes.set(p, at);
      at += p.length;
    }
    waiting.resolve(bytes);
  }
}

/** The photo's decoded picture, fetched and decoded once; kept for the next [KEPT] photos wanted. */
function photo(item) {
  let entry = photos.get(item.id);
  if (entry) {
    photos.delete(item.id);
    photos.set(item.id, entry);
    // One that failed is tried again when wanted again.
    if (!entry.error) return entry.promise;
  }
  entry = { item };
  entry.promise = bytesOf(item).then(decode).then(
    (bitmap) => {
      entry.bitmap = bitmap;
      return bitmap;
    },
    (error) => {
      entry.error = error;
      throw error;
    },
  );
  photos.set(item.id, entry);
  while (photos.size > KEPT) {
    const [oldest, old] = photos.entries().next().value;
    if (shown && oldest === shown.id) {
      photos.delete(oldest);
      photos.set(oldest, old);
      continue;
    }
    photos.delete(oldest);
    old.promise.then((b) => b.close?.(), () => {});
  }
  return entry.promise;
}

// Showing

async function show({ item, frame, autoplay = true, muted = true, position = 0 }) {
  idle.classList.add('gone');
  if (item.kind === 'video') return showVideo(item, autoplay, muted, position);
  stopVideo();
  shown = { id: item.id, kind: 'image', bitmap: null };
  const wanted = shown;
  const slow = setTimeout(() => note('Loading…', 0), 700);
  let bitmap;
  try {
    bitmap = await photo(item);
  } catch (e) {
    clearTimeout(slow);
    if (shown === wanted) {
      const why = String(e && e.message || e);
      console.warn('photo not shown', item.id, e);
      note(`This photo couldn’t be shown (${why})`, 8000);
      send({ type: 'error', id: item.id, message: why });
    }
    return;
  }
  clearTimeout(slow);
  if (shown !== wanted) return;
  clearNote();
  wanted.bitmap = bitmap;
  // The frame the phone sent last for this photo, if it came while this one was loading.
  const start = target && target.id === item.id ? target : (frame ? { id: item.id, ...frame } : { id: item.id, ...wholeFrame(bitmap.width, bitmap.height) });
  target = start;
  drawn = { ...start };
  sizeCanvases();
  const next = 1 - front;
  draw(canvases[next], bitmap, drawn);
  canvases[next].classList.add('front');
  canvases[front].classList.remove('front');
  front = next;
  send({ type: 'shown', id: item.id });
}

function onFrame({ id, x, y, w, h }) {
  // Followed by tick(), a display frame at a time; one for a photo still loading is where it starts.
  target = { id, x, y, w, h };
}

let videoId = null;
let lastStatus = 0;
/** The video stopped for data at its very end ('waiting' or 'stalled' there): it counts as ended. */
let stuckAtEnd = false;

function showVideo(item, autoplay, muted, position) {
  shown = { id: item.id, kind: 'video' };
  target = null;
  for (const c of canvases) c.classList.remove('front');
  clearNote();
  videoId = item.id;
  stuckAtEnd = false;
  video.muted = muted;
  video.src = item.url;
  video.currentTime = Math.max(0, position / 1000);
  video.classList.add('front');
  if (autoplay) playVideo();
  else videoStatus(true);
}

/** Plays the video; if the TV refuses (not because another item came), the phone hears why, so a slideshow goes on. */
function playVideo() {
  const id = videoId;
  video.play().catch((e) => {
    videoStatus(true);
    if (videoId === id && e && e.name !== 'AbortError') send({ type: 'error', id, message: `play refused: ${e.name}` });
  });
}

/** Within half a second of its end: a stream a little shorter than its stated duration stops there without 'ended'. */
function atEnd() {
  return Number.isFinite(video.duration) && video.duration > 0 && video.duration - video.currentTime < 0.5;
}

function stopVideo() {
  if (!videoId) return;
  videoId = null;
  video.pause();
  video.removeAttribute('src');
  video.load();
  video.classList.remove('front');
}

function videoStatus(force = false) {
  if (!videoId) return;
  const now = performance.now();
  if (!force && now - lastStatus < 1000) return;
  lastStatus = now;
  send({
    type: 'video',
    id: videoId,
    position: Math.round(video.currentTime * 1000),
    duration: Number.isFinite(video.duration) ? Math.round(video.duration * 1000) : 0,
    playing: !video.paused && !video.ended && !stuckAtEnd,
    ended: video.ended || stuckAtEnd,
  });
}

for (const event of ['play', 'pause', 'seeked', 'loadedmetadata', 'ended']) video.addEventListener(event, () => videoStatus(true));
video.addEventListener('timeupdate', () => videoStatus());
for (const event of ['waiting', 'stalled']) {
  video.addEventListener(event, () => {
    if (!stuckAtEnd && atEnd()) {
      stuckAtEnd = true;
      videoStatus(true);
    }
  });
}
video.addEventListener('seeking', () => { stuckAtEnd = false; });
video.addEventListener('error', () => {
  if (!videoId) return;
  note('This video couldn’t be played');
  const error = video.error;
  send({ type: 'error', id: videoId, message: error ? `media error ${error.code} ${error.message || ''}`.trim() : 'media error' });
});

function onVideo({ action, position }) {
  if (!videoId) return;
  if (action === 'play') playVideo();
  else if (action === 'pause') video.pause();
  else if (action === 'seek' && typeof position === 'number') video.currentTime = Math.max(0, position / 1000);
}

function stop() {
  stopVideo();
  shown = null;
  target = null;
  for (const c of canvases) c.classList.remove('front');
  idle.classList.remove('gone');
  clearNote();
}

function hello() {
  const { width, height } = screenSize();
  send({ type: 'hello', width, height });
}

/** One message from the phone. */
export function receive(message) {
  switch (message && message.type) {
    case 'hi': return hello();
    case 'show': return show(message);
    case 'preload':
      for (const item of message.items || []) if (item.kind === 'image') photo(item).catch(() => {});
      return;
    case 'frame': return onFrame(message);
    case 'video': return onVideo(message);
    case 'mute': video.muted = !!message.muted; return;
    case 'chunk': return onChunk(message);
    case 'stop': return stop();
    default: console.warn('unknown message', message);
  }
}

// Starting

window.addEventListener('resize', () => {
  sizeCanvases();
  if (shown && shown.bitmap && drawn) draw(canvases[front], shown.bitmap, drawn);
  hello();
});
sizeCanvases();
requestAnimationFrame(tick);

const local = new URLSearchParams(location.search).has('local');
if (!local && window.cast && cast.framework) {
  const context = cast.framework.CastReceiverContext.getInstance();
  const options = new cast.framework.CastReceiverOptions();
  options.customNamespaces = { [NAMESPACE]: cast.framework.system.MessageType.JSON };
  // The phone drives what is shown; the page stays until the phone stops casting.
  options.disableIdleTimeout = true;
  options.skipPlayersLoad = true;
  options.maxInactivity = 3600;
  context.addCustomMessageListener(NAMESPACE, (event) => {
    let data = event.data;
    if (typeof data === 'string') {
      try { data = JSON.parse(data); } catch { return; }
    }
    receive(data);
  });
  // With the idle timeout off, the page must close itself when the phone is gone: at once when it stops casting, and
  // after a minute when it just went away (out of Wi-Fi, the app closed), so a stale page never blocks the next cast.
  let alone = 0;
  context.addEventListener(cast.framework.system.EventType.SENDER_CONNECTED, () => {
    clearTimeout(alone);
    hello();
  });
  context.addEventListener(cast.framework.system.EventType.SENDER_DISCONNECTED, (event) => {
    if (context.getSenders().length > 0) return;
    if (event.reason === cast.framework.system.DisconnectReason.REQUESTED_BY_SENDER) return context.stop();
    clearTimeout(alone);
    alone = setTimeout(() => {
      if (context.getSenders().length === 0) context.stop();
    }, 60_000);
  });
  send = (data) => {
    try {
      context.sendCustomMessage(NAMESPACE, undefined, data);
    } catch (e) {
      console.warn('send failed', e);
    }
  };
  context.start(options);
} else {
  // Tried in a browser (receiver/test/): messages go to window.sent, and window.receive takes the phone's.
  window.sent = [];
  send = (data) => window.sent.push(data);
  window.receive = receive;
  window.wholeFrame = wholeFrame;
}
