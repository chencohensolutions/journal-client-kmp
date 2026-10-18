// Shared helpers for the screen mockups: theme, icons, sample data and the photo tile.
// Each page loads vendor/petite-vue.iife.js, then this file, then calls mount({...its state}).

(function () {
  const theme = new URLSearchParams(location.search).get('theme');
  if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
})();

// Keeps ?theme= when moving between screens.
function go(page) {
  const theme = new URLSearchParams(location.search).get('theme');
  location.href = page + (theme ? (page.includes('?') ? '&' : '?') + 'theme=' + theme : '');
}
function param(name) { return new URLSearchParams(location.search).get(name); }

const ICONS = {
  back: '<path d="M19 12H5"/><path d="m12 19-7-7 7-7"/>',
  more: '<circle cx="12" cy="5" r="1.6" fill="currentColor"/><circle cx="12" cy="12" r="1.6" fill="currentColor"/><circle cx="12" cy="19" r="1.6" fill="currentColor"/>',
  tag: '<path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8z"/><circle cx="7.5" cy="7.5" r="1.5"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5"/><path d="M12 8h.01"/>',
  close: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  offline: '<path d="M17.5 19H7a5 5 0 0 1-2.7-9.2"/><path d="M8.6 5.6A6 6 0 0 1 19.5 11a4 4 0 0 1 1.6 6.3"/><path d="m2 2 20 20"/>',
  upload: '<path d="M17.5 19H7a5 5 0 1 1 1.1-9.9A6 6 0 0 1 19.5 11a4 4 0 0 1-2 8z"/><path d="M12 16v-5"/><path d="M9.5 13.5 12 11l2.5 2.5"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
  stack: '<rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/>',
  check: '<path d="m5 12 5 5 9-10"/>',
  pencil: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/>',
  play: '<path d="M7 4v16l13-8z" fill="currentColor"/>',
  photos: '<rect x="3" y="5" width="18" height="14" rx="2"/><circle cx="9" cy="10" r="2"/><path d="m21 16-5-5-8 8"/>',
  cloud: '<path d="M17.5 19H7a5 5 0 1 1 1.1-9.9A6 6 0 0 1 19.5 11a4 4 0 0 1-2 8z"/>',
};
function icon(name) {
  return '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + ICONS[name] + '</svg>';
}

// Placeholder photos: a stable color per number, with an optional aspect ratio (width / height).
const TONES = ['#8FB3A6', '#E3CDB4', '#C9D6CF', '#B9A58E', '#6F8F9B', '#D8B7A6', '#A7B98F', '#9AA7C4', '#5E8A7C', '#E8D9B8', '#7E9C8C', '#CDB8A0'];
function tone(n) { return TONES[(n * 5) % TONES.length]; }

// status: local | queued | uploading | done | failed
function photo(n, extra) {
  return Object.assign({ n: n, color: tone(n), status: 'local', video: '', ratio: 1, pct: 0 }, extra || {});
}
function photos(start, count, extra) {
  return Array.from({ length: count }, (_, i) => photo(start + i, typeof extra === 'function' ? extra(i) : extra));
}

const SAMPLE = {
  stories: [
    { id: 'japan', title: 'Japan trip', month: 'OCT', day: 8, meta: 'Oct 8 – 16 · 9 days · 142 photos', pending: 98, cover: [4, 5, 6, 7], mosaic: 'big' },
    { id: 'noa', title: 'Noa’s birthday', month: 'OCT', day: 3, meta: 'Oct 3 · 38 photos', pending: 0, cover: [11, 12], mosaic: 'two' },
    { id: 'north', title: 'Weekend in the north', month: 'SEP', day: 26, meta: 'Sep 26 – 27 · 2 days · 54 photos', pending: 0, cover: [15], mosaic: 'one' },
    { id: 'dog', title: 'Our dog', month: 'MAR', day: 2, meta: 'Mar 2, 2024 – Oct 12 · 211 photos', pending: 3, cover: [19, 20, 21, 22], mosaic: 'big' },
  ],
  tags: [
    { name: 'Receipts', count: 23, n: 30 },
    { name: 'Concert', count: 4, n: 31 },
    { name: 'Recipes', count: 12, n: 32 },
    { name: 'Dog', count: 57, n: 33 },
    { name: 'Car', count: 6, n: 34 },
    { name: 'Garden', count: 18, n: 35 },
  ],
};

// Long-press: call press(start) on pointerdown and press(cancel) on pointerup/leave; returns true if it fired.
function longPress(onFire) {
  let timer = null, fired = false;
  return {
    down() { fired = false; timer = setTimeout(() => { fired = true; onFire(); }, 450); },
    up() { clearTimeout(timer); },
    consumed() { const f = fired; fired = false; return f; },
  };
}

// The photo tile, shared by every screen.
const PHOTO_TEMPLATE = `
<template id="photo-tpl">
  <div class="photo" :style="{ background: p.color, aspectRatio: fill ? 'auto' : p.ratio, height: fill ? '100%' : 'auto' }">
    <div class="ph-sun"></div><div class="ph-hill"></div>
    <div v-if="p.video" class="video-chip"><svg viewBox="0 0 24 24"><path d="M7 4v16l13-8z"/></svg>{{ p.video }}</div>
    <div v-if="badges !== false" class="badge" :class="p.status" :aria-label="statusName(p)">
      <span v-if="p.status === 'local'" v-html="icon('offline')"></span>
      <svg v-if="p.status === 'queued'" viewBox="0 0 24 24" style="width:16px;height:16px"><circle cx="12" cy="12" r="8" stroke-dasharray="3 3.3"/></svg>
      <svg v-if="p.status === 'uploading'" viewBox="0 0 24 24" style="width:18px;height:18px"><circle cx="12" cy="12" r="8" stroke="rgba(255,255,255,.35)" stroke-width="3"/><circle cx="12" cy="12" r="8" stroke-width="3" :stroke-dasharray="(50.3 * p.pct / 100).toFixed(1) + ' 50.3'" transform="rotate(-90 12 12)"/></svg>
      <span v-if="p.status === 'done'" v-html="icon('check')"></span>
      <span v-if="p.status === 'failed'">!</span>
    </div>
  </div>
</template>`;
function statusName(p) {
  return { local: 'Only on this device', queued: 'Waiting to upload', uploading: 'Uploading ' + p.pct + '%', done: 'Backed up', failed: 'Upload failed' }[p.status];
}
function Photo(props) { return Object.assign({ $template: '#photo-tpl', fill: false, badges: true }, props); }

function mount(state) {
  document.body.insertAdjacentHTML('beforeend', PHOTO_TEMPLATE);
  PetiteVue.createApp(Object.assign({ icon, go, param, Photo, statusName, SAMPLE }, state)).mount();
}
