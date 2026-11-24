#!/usr/bin/env node
// Generates the bundled CC0 placeholder avatar pack: a one-bit geometric orb with a simple face.
//
//   node tools/avatar/make_placeholder_pack.mjs            # writes both bundled copies
//   node tools/avatar/make_placeholder_pack.mjs --frames /tmp/frames   # also writes <state>/NN.png frames
//
// Outputs (byte-identical):
//   android/app/src/main/assets/avatar/default/{manifest.json,atlas.png,LICENSE.txt}
//   simulator/avatar/default/{manifest.json,atlas.png,LICENSE.txt}
//
// No dependencies: drawing is plain arithmetic and the PNG writer uses node:zlib. Everything this
// script draws is original and dedicated to the public domain (CC0 1.0).
import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const CW = 192, CH = 288, COLS = 8;
const CX = 96, CY = 130, R = 70, STROKE = 6;

// ---------- one-bit raster ----------
class Cell {
  constructor() { this.px = new Uint8Array(CW * CH); }
  set(x, y) { x = Math.round(x); y = Math.round(y); if (x >= 0 && y >= 0 && x < CW && y < CH) this.px[y * CW + x] = 1; }
  each(x0, y0, x1, y1, test) {
    for (let y = Math.max(0, Math.floor(y0)); y <= Math.min(CH - 1, Math.ceil(y1)); y++)
      for (let x = Math.max(0, Math.floor(x0)); x <= Math.min(CW - 1, Math.ceil(x1)); x++)
        if (test(x + .5, y + .5)) this.px[y * CW + x] = 1;
  }
  disc(cx, cy, r) { this.each(cx - r, cy - r, cx + r, cy + r, (x, y) => (x - cx) ** 2 + (y - cy) ** 2 <= r * r); }
  ellipse(cx, cy, rx, ry) { this.each(cx - rx, cy - ry, cx + rx, cy + ry, (x, y) => ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1); }
  ring(cx, cy, r, w = STROKE, a0 = 0, a1 = 360) {
    const lo = (r - w / 2) ** 2, hi = (r + w / 2) ** 2;
    this.each(cx - r - w, cy - r - w, cx + r + w, cy + r + w, (x, y) => {
      const d = (x - cx) ** 2 + (y - cy) ** 2;
      if (d < lo || d > hi) return false;
      if (a1 - a0 >= 360) return true;
      let a = Math.atan2(y - cy, x - cx) * 180 / Math.PI; if (a < 0) a += 360;
      return a0 <= a1 ? a >= a0 && a <= a1 : a >= a0 || a <= a1;
    });
  }
  line(x0, y0, x1, y1, w = STROKE) {
    const dx = x1 - x0, dy = y1 - y0, len2 = dx * dx + dy * dy || 1, h = w / 2;
    this.each(Math.min(x0, x1) - h, Math.min(y0, y1) - h, Math.max(x0, x1) + h, Math.max(y0, y1) + h, (x, y) => {
      const t = Math.max(0, Math.min(1, ((x - x0) * dx + (y - y0) * dy) / len2));
      return (x - x0 - t * dx) ** 2 + (y - y0 - t * dy) ** 2 <= h * h;
    });
  }
  path(points, w = STROKE) { for (let i = 1; i < points.length; i++) this.line(...points[i - 1], ...points[i], w); }
  rect(x, y, w, h) { this.each(x, y, x + w - 1, y + h - 1, () => true); }
}

// ---------- the character ----------
// angles: 0 = right, 90 = down (screen coordinates)
function orb(c, { dy = 0, eyes = 'open', look = [0, 0], mouth = 'smile', mouthSize = 1 } = {}) {
  const cy = CY + dy;
  c.ring(CX, cy, R);
  c.line(CX - 44, cy + R + 22, CX + 44, cy + R + 22, 4); // shadow line
  const ex = 26, ey = cy - 12, [lx, ly] = look;
  for (const s of [-1, 1]) {
    const x = CX + s * ex + lx, y = ey + ly;
    if (eyes === 'open') c.disc(x, y, 9);
    else if (eyes === 'closed') c.line(x - 9, y, x + 9, y, 5);
    else if (eyes === 'half') { c.ellipse(x, y + 3, 9, 5); }
    else if (eyes === 'happy') c.ring(x, y + 5, 9, 5, 200, 340);
    else if (eyes === 'x') { c.line(x - 8, y - 8, x + 8, y + 8, 5); c.line(x - 8, y + 8, x + 8, y - 8, 5); }
    else if (eyes === 'narrow') c.line(x - 10, y + 1, x + 10, y - 1, 7);
  }
  const my = cy + 22;
  if (mouth === 'smile') c.ring(CX, my - 12, 22, 5, 30, 150);
  else if (mouth === 'grin') { c.ring(CX, my - 14, 26, 5, 15, 165); c.line(CX - 25, my - 7, CX + 25, my - 7, 5); }
  else if (mouth === 'flat') c.line(CX - 14, my, CX + 14, my, 5);
  else if (mouth === 'open') c.ellipse(CX, my, 10 + 4 * mouthSize, 3 + 7 * mouthSize);
  else if (mouth === 'o') c.ring(CX, my, 7, 4);
  else if (mouth === 'wavy') c.path([[CX - 20, my + 2], [CX - 10, my - 4], [CX, my + 2], [CX + 10, my - 4], [CX + 20, my + 2]], 4);
  else if (mouth === 'frown') c.ring(CX, my + 18, 20, 5, 215, 325);
}
const zee = (c, x, y, s) => c.path([[x, y], [x + s, y], [x, y + s], [x + s, y + s]], 4);
const plus = (c, x, y, s) => { c.line(x - s, y, x + s, y, 4); c.line(x, y - s, x, y + s, 4); };
function gear(c, x, y, r, turn) {
  c.ring(x, y, r, 5);
  for (let i = 0; i < 6; i++) {
    const a = (i * 60 + turn) * Math.PI / 180;
    c.line(x + Math.cos(a) * (r + 2), y + Math.sin(a) * (r + 2), x + Math.cos(a) * (r + 9), y + Math.sin(a) * (r + 9), 6);
  }
}

// Each state: playback policy plus three frame painters. Timing matches the app's historical pacing.
const MS = [350, 350, 450];
const STATES = {
  idle: ['loop', [c => orb(c), c => orb(c, { dy: -2 }), c => orb(c, { eyes: 'closed' })]],
  sleeping: ['loop', [0, 1, 2].map(i => c => { orb(c, { eyes: 'closed', mouth: 'flat', dy: i === 1 ? 2 : 0 }); for (let k = 0; k <= i; k++) zee(c, 132 + k * 14, 44 - k * 16, 10 + k * 3); })],
  waking: ['once', [c => orb(c, { eyes: 'closed', mouth: 'flat' }), c => orb(c, { eyes: 'half', mouth: 'o' }), c => orb(c)]],
  listening: ['loop', [1, 2, 3].map(n => c => { orb(c, { mouth: 'flat' }); for (let k = 0; k < n; k++) for (const s of [-1, 1]) c.ring(CX + s * (R - 14), CY, 24 + k * 7, 4, s < 0 ? 150 : 330, s < 0 ? 210 : 30); })],
  thinking: ['loop', [1, 2, 3].map(n => c => { orb(c, { look: [6, -6], mouth: 'flat' }); for (let k = 0; k < n; k++) c.disc(140 + k * 16, 50 - k * 12, 5 + k * 2); })],
  speaking: ['loop', [.2, 1, .5].map(m => c => orb(c, { mouth: 'open', mouthSize: m }))],
  working: ['loop', [0, 20, 40].map(t => c => { orb(c, { eyes: 'narrow', mouth: 'flat' }); gear(c, 156, 44, 16, t); })],
  searching: ['loop', [-1, 0, 1].map(s => c => { orb(c, { look: [s * 8, 0], mouth: 'flat' }); const x = CX + s * 40; c.ring(x, 250, 16, 5); c.line(x + 12, 262, x + 26, 276, 6); })],
  waiting: ['loop', [0, 1, 2].map(i => c => { orb(c, { mouth: 'flat' }); const x = 156, y = 30; c.path([[x - 14, y], [x + 14, y], [x - 14, y + 36], [x + 14, y + 36], [x - 14, y]], 4); c.rect(x - 8 + i * 2, y + 4 + i * 5, 16 - i * 4, 6); c.rect(x - 2 - i * 3, y + 30 - i * 3, 4 + i * 6, 3 + i * 3); })],
  asking: ['once_then_hold', [0, 1, 2].map(i => c => { orb(c, { mouth: 'o', look: [0, -3] }); if (i > 0) { c.ring(156, 40, 14, 5, 180, 90); c.line(156, 54, 156, 64, 5); } if (i > 1) c.disc(156, 76, 4); })],
  error: ['once', [c => orb(c, { eyes: 'x', mouth: 'wavy' }), c => orb(c, { eyes: 'x', mouth: 'wavy', dy: 3 }), c => { orb(c, { eyes: 'x', mouth: 'wavy' }); c.line(156, 24, 156, 52, 6); c.disc(156, 64, 4); }]],
  happy: ['once', [c => orb(c, { eyes: 'happy', mouth: 'smile' }), c => orb(c, { eyes: 'happy', mouth: 'grin', dy: -4 }), c => orb(c, { eyes: 'happy', mouth: 'smile' })]],
  waving: ['once', [-14, 10, -14].map(a => c => { orb(c, { mouth: 'smile' }); const r = a * Math.PI / 180, x = 174, y = CY + 16; const hx = x + Math.sin(r) * 42, hy = y - Math.cos(r) * 42; c.line(x, y, hx, hy, 6); c.disc(hx, hy, 9); })],
  nodding: ['once', [0, 8, 0].map(d => c => orb(c, { dy: d, eyes: d ? 'closed' : 'open', mouth: 'smile' }))],
  celebrating: ['once', [0, 1, 2].map(i => c => { orb(c, { eyes: 'happy', mouth: 'grin', dy: -2 * i }); [[30, 40], [162, 36], [20, 200], [172, 196]].forEach(([x, y], k) => { if ((k + i) % 2 === 0) plus(c, x, y, 8 + i * 2); else c.disc(x, y, 3); }); })],
};
// Every other state the app can ask for resolves through these (then the app's built-in chain, then idle).
const FALLBACKS = {
  compressing: 'working', delegating: 'working', saving: 'working', nerdy: 'working', creative: 'working',
  remembering: 'thinking', curious: 'thinking', confused: 'thinking', skeptical: 'thinking', philosophical: 'thinking',
  interrupted: 'listening', checking_watch: 'waiting', looking_around: 'idle', coffee: 'idle', stretching: 'idle',
  laughing: 'happy', proud: 'happy', winking: 'happy', dancing: 'celebrating', teaching: 'speaking', debating: 'speaking',
  sad: 'error', concerned: 'error', facepalm: 'error', shaking_head: 'error', surprised: 'asking',
};

// ---------- pack ----------
const names = Object.keys(STATES);
const cells = [];
const states = {};
for (const name of names) {
  const [playback, painters] = STATES[name];
  states[name] = { playback, frames: painters.map((paint, i) => { const c = new Cell(); paint(c); cells.push([name, i, c]); return { cell: cells.length - 1, ms: MS[i] }; }) };
}
const rows = Math.ceil(cells.length / COLS);
const AW = COLS * CW, AH = rows * CH;
const atlas = new Uint8Array(AW * AH);
cells.forEach(([, , c], k) => {
  const ox = (k % COLS) * CW, oy = Math.floor(k / COLS) * CH;
  for (let y = 0; y < CH; y++) atlas.set(c.px.subarray(y * CW, (y + 1) * CW), (oy + y) * AW + ox);
});

const manifest = {
  schema: 1,
  id: 'default',
  name: 'Placeholder orb',
  license: 'CC0-1.0',
  author: 'RayNeo iO HUD contributors',
  cell: { w: CW, h: CH },
  atlas: { file: 'atlas.png', cols: COLS },
  portraitCrop: [CX - R - 10, CY - R - 10, 2 * R + 20, 2 * R + 20],
  threshold: 100,
  states,
  fallbacks: FALLBACKS,
};

// ---------- PNG (1-bit palette: black, green) ----------
const CRC = new Uint32Array(256).map((_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
const crc32 = buf => { let c = 0xffffffff; for (const b of buf) c = CRC[(c ^ b) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
function chunk(type, data) {
  const out = Buffer.alloc(12 + data.length), t = Buffer.from(type, 'ascii');
  out.writeUInt32BE(data.length, 0); t.copy(out, 4); data.copy(out, 8);
  out.writeUInt32BE(crc32(Buffer.concat([t, data])), 8 + data.length);
  return out;
}
function png(bits, w, h) {
  const stride = Math.ceil(w / 8), raw = Buffer.alloc((stride + 1) * h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) if (bits[y * w + x]) raw[y * (stride + 1) + 1 + (x >> 3)] |= 0x80 >> (x & 7);
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 1; ihdr[9] = 3;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('PLTE', Buffer.from([0, 0, 0, 0, 255, 0])),
    chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

const LICENSE = `Placeholder orb avatar pack
Generated by tools/avatar/make_placeholder_pack.mjs.

To the extent possible under law, the RayNeo iO HUD contributors have waived all copyright
and related or neighboring rights to this avatar pack (manifest.json and atlas.png).
This work is published from the public domain under CC0 1.0 Universal:
https://creativecommons.org/publicdomain/zero/1.0/
`;
const atlasPng = png(atlas, AW, AH), json = JSON.stringify(manifest, null, 1) + '\n';
for (const dir of ['android/app/src/main/assets/avatar/default', 'simulator/avatar/default']) {
  const out = join(ROOT, dir); mkdirSync(out, { recursive: true });
  writeFileSync(join(out, 'atlas.png'), atlasPng);
  writeFileSync(join(out, 'manifest.json'), json);
  writeFileSync(join(out, 'LICENSE.txt'), LICENSE);
}
const framesAt = process.argv.indexOf('--frames');
if (framesAt > 0) {
  const base = resolve(process.argv[framesAt + 1]);
  for (const [name, i, c] of cells) { mkdirSync(join(base, name), { recursive: true }); writeFileSync(join(base, name, String(i).padStart(2, '0') + '.png'), png(c.px, CW, CH)); }
}
console.log(`placeholder pack: ${names.length} states, ${cells.length} cells, atlas ${AW}x${AH}, ${atlasPng.length} bytes`);
