#!/usr/bin/env node
// Authors the 27 Hex Next dashboard glyphs as geometry, writes tools/glyphs/glyphs.svg (one 24x24
// symbol each), renders tools/glyphs/contact-sheet.png with Playwright for visual review, and
// emits android/app/src/main/res/drawable/hx_glyph_<row>.xml VectorDrawables with the SAME pathData.
//
// Every glyph is a list of filled sub-paths (no strokes): "line art" is built from capsules,
// rings (even-odd) and thick arcs, so the VectorDrawable needs only fillColor.
//
//   node tools/glyphs/build.js            # svg + contact sheet + xml
//   node tools/glyphs/build.js --no-xml   # svg + contact sheet only
'use strict';
const fs = require('fs');
const path = require('path');

const W = 2; // default line weight (dp in a 24 dp viewport)
const f = (n) => (Math.round(n * 100) / 100).toString();
const pt = (x, y) => `${f(x)},${f(y)}`;
const rad = (deg) => (deg * Math.PI) / 180;
const polar = (cx, cy, r, deg) => [cx + r * Math.cos(rad(deg)), cy + r * Math.sin(rad(deg))];

/** Capsule from (x1,y1) to (x2,y2), width w (round caps). */
function line(x1, y1, x2, y2, w = W) {
  const dx = x2 - x1, dy = y2 - y1;
  const len = Math.hypot(dx, dy) || 1;
  const h = w / 2;
  const nx = (-dy / len) * h, ny = (dx / len) * h;
  return {
    d: `M${pt(x1 + nx, y1 + ny)} L${pt(x2 + nx, y2 + ny)} A${f(h)},${f(h)} 0 0 0 ${pt(x2 - nx, y2 - ny)} ` +
       `L${pt(x1 - nx, y1 - ny)} A${f(h)},${f(h)} 0 0 0 ${pt(x1 + nx, y1 + ny)} Z`,
    rule: 'nonZero',
  };
}
function circlePath(cx, cy, r) {
  return `M${pt(cx - r, cy)} A${f(r)},${f(r)} 0 1 1 ${pt(cx + r, cy)} A${f(r)},${f(r)} 0 1 1 ${pt(cx - r, cy)} Z`;
}
function disc(cx, cy, r) { return { d: circlePath(cx, cy, r), rule: 'nonZero' }; }
/** Circle outline of radius r (centre line) and weight w. */
function ring(cx, cy, r, w = W) {
  return { d: circlePath(cx, cy, r + w / 2) + ' ' + circlePath(cx, cy, r - w / 2), rule: 'evenOdd' };
}
function ellipsePath(cx, cy, rx, ry) {
  return `M${pt(cx - rx, cy)} A${f(rx)},${f(ry)} 0 1 1 ${pt(cx + rx, cy)} A${f(rx)},${f(ry)} 0 1 1 ${pt(cx - rx, cy)} Z`;
}
function ellipseRing(cx, cy, rx, ry, w = W) {
  return { d: ellipsePath(cx, cy, rx + w / 2, ry + w / 2) + ' ' + ellipsePath(cx, cy, rx - w / 2, ry - w / 2), rule: 'evenOdd' };
}
function rrectPath(x, y, w, h, r) {
  r = Math.min(r, w / 2, h / 2);
  return `M${pt(x + r, y)} H${f(x + w - r)} A${f(r)},${f(r)} 0 0 1 ${pt(x + w, y + r)} V${f(y + h - r)} ` +
         `A${f(r)},${f(r)} 0 0 1 ${pt(x + w - r, y + h)} H${f(x + r)} A${f(r)},${f(r)} 0 0 1 ${pt(x, y + h - r)} ` +
         `V${f(y + r)} A${f(r)},${f(r)} 0 0 1 ${pt(x + r, y)} Z`;
}
function rrect(x, y, w, h, r) { return { d: rrectPath(x, y, w, h, r), rule: 'nonZero' }; }
/** Rounded-rectangle outline; (x,y,w,h) is the centre line of the stroke, sw its weight. */
function rrectRing(x, y, w, h, r, sw = W) {
  const o = sw / 2;
  return { d: rrectPath(x - o, y - o, w + sw, h + sw, r + o) + ' ' + rrectPath(x + o, y + o, w - sw, h - sw, Math.max(0.01, r - o)), rule: 'evenOdd' };
}
/** Thick arc of radius r from angle a0 to a1 (degrees, clockwise on screen, a1 > a0), round caps. */
function arc(cx, cy, r, a0, a1, w = W) {
  const h = w / 2, ro = r + h, ri = r - h;
  const large = a1 - a0 > 180 ? 1 : 0;
  const [ox0, oy0] = polar(cx, cy, ro, a0), [ox1, oy1] = polar(cx, cy, ro, a1);
  const [ix0, iy0] = polar(cx, cy, ri, a0), [ix1, iy1] = polar(cx, cy, ri, a1);
  return {
    d: `M${pt(ox0, oy0)} A${f(ro)},${f(ro)} 0 ${large} 1 ${pt(ox1, oy1)} A${f(h)},${f(h)} 0 0 1 ${pt(ix1, iy1)} ` +
       `A${f(ri)},${f(ri)} 0 ${large} 0 ${pt(ix0, iy0)} A${f(h)},${f(h)} 0 0 1 ${pt(ox0, oy0)} Z`,
    rule: 'nonZero',
  };
}
function poly(points) { return { d: 'M' + points.map(([x, y]) => pt(x, y)).join(' L') + ' Z', rule: 'nonZero' }; }
function raw(d, rule = 'nonZero') { return { d, rule }; }
function polyline(points, w = W) {
  const out = [];
  for (let i = 0; i < points.length - 1; i++) out.push(line(points[i][0], points[i][1], points[i + 1][0], points[i + 1][1], w));
  return out;
}
/** Four-point sparkle centred (cx,cy) with half-size s and waist k (0..1). */
function sparkle(cx, cy, s, k = 0.18) {
  const q = s * k;
  return raw(`M${pt(cx, cy - s)} Q${pt(cx + q, cy - q)} ${pt(cx + s, cy)} Q${pt(cx + q, cy + q)} ${pt(cx, cy + s)} ` +
             `Q${pt(cx - q, cy + q)} ${pt(cx - s, cy)} Q${pt(cx - q, cy - q)} ${pt(cx, cy - s)} Z`);
}

const glyphs = {
  cloud_and_account: [
    arc(7.5, 14, 3.5, 90, 270), arc(12.5, 11, 5, 180, 360), arc(16.5, 14, 3.5, 270, 450), line(7.5, 17.5, 16.5, 17.5),
    line(12, 15.5, 12, 10.5, 1.8), line(12, 10.5, 9.8, 12.7, 1.8), line(12, 10.5, 14.2, 12.7, 1.8),
  ],
  google: [ring(12, 12, 8), line(4, 12, 20, 12), ellipseRing(12, 12, 3.6, 8)],
  connections: [arc(12, 17, 3.2, 225, 315), arc(12, 17, 7, 225, 315), arc(12, 17, 10.8, 225, 315), disc(12, 17.5, 1.5)],
  devicecare: [arc(8, 9.5, 4, 180, 360), arc(16, 9.5, 4, 180, 360), line(4, 9.5, 12, 19.5), line(20, 9.5, 12, 19.5)],
  applications: [6, 12, 18].flatMap((y) => [6, 12, 18].map((x) => disc(x, y, 1.7))),
  display: [ring(12, 12, 4), ...[0, 45, 90, 135, 180, 225, 270, 315].map((a) => {
    const [x1, y1] = polar(12, 12, 6.8, a), [x2, y2] = polar(12, 12, 9, a); return line(x1, y1, x2, y2, 1.8); })],
  advanced_feature: [line(5.5, 18.5, 14.5, 9.5, 2.4), sparkle(17.5, 6.5, 3, 0.2), disc(20.5, 12, 1.1), disc(12, 4.5, 1.1)],
  wallpaper: [rrectRing(4, 5, 16, 14, 2), ...polyline([[7.5, 15.5], [11, 11.5], [13.5, 14.3], [15.5, 12.3], [17.5, 15.5]], 1.6), disc(15.5, 8.5, 1.3)],
  general_device_management: [
    line(5, 7, 5.8, 7), line(12.2, 7, 19, 7), ring(9, 7, 2.2, 1.8),
    line(5, 12, 11.8, 12), line(18.2, 12, 19, 12), ring(15, 12, 2.2, 1.8),
    line(5, 17, 7.8, 17), line(14.2, 17, 19, 17), ring(11, 17, 2.2, 1.8),
  ],
  battery: [rrectRing(4, 8, 15, 9, 2), rrect(20, 10.5, 1.8, 4, 0.8), poly([[12.6, 9.4], [9.2, 13.3], [11.4, 13.3], [10.6, 16.3], [14.2, 11.8], [12, 11.8]])],
  galaxy_ai: [sparkle(10.5, 13.5, 7.5, 0.16), sparkle(18.5, 5.5, 2.6, 0.2)],
  sound: [poly([[4.5, 9.5], [8, 9.5], [12.5, 5.5], [12.5, 18.5], [8, 14.5], [4.5, 14.5]]), arc(12, 12, 4.5, 315, 405), arc(12, 12, 7.8, 318, 402)],
  notifications: [arc(12, 11.5, 5.5, 180, 360), line(6.5, 11.5, 6.5, 16), line(17.5, 11.5, 17.5, 16), line(5, 16.5, 19, 16.5), disc(12, 19.6, 1.5), line(12, 4.5, 12, 6, 1.8)],
  themes: [line(17.8, 5.2, 11.6, 11.4, 2.6), line(10.6, 12.4, 8.8, 14.2, 3.2),
    raw('M4.5,19.5 C4.5,16 6,14.3 8.6,13.4 L10.6,15.4 C9.7,18 8,19.5 4.5,19.5 Z')],
  biometrics_and_security: [rrectRing(6, 11, 12, 9, 2), arc(12, 11, 4, 180, 360), disc(12, 15.5, 1.4)],
  lockscreen: [rrectRing(6.5, 3, 11, 18, 2), rrectRing(9.6, 11.4, 4.8, 3.8, 0.8, 1.4), arc(12, 11.4, 1.5, 180, 360, 1.3)],
  accessibility: [disc(12, 5.3, 1.9), line(6, 9.5, 18, 9.5), line(12, 9.5, 12, 14), line(12, 14, 8.5, 19.5), line(12, 14, 15.5, 19.5)],
  location: [raw('M12,21 C12,21 5,13.8 5,9.5 A7,7 0 0 1 19,9.5 C19,13.8 12,21 12,21 Z ' +
    'M12,17.9 C12,17.9 7,13 7,9.5 A5,5 0 0 1 17,9.5 C17,13 12,17.9 12,17.9 Z ' + circlePath(12, 9.5, 2), 'evenOdd')],
  multi_devices: [rrectRing(3.5, 5, 7.5, 14, 1.5), rrectRing(13.5, 8.5, 8, 7.5, 2.2), line(17.5, 5, 17.5, 7.5, 3), line(17.5, 17, 17.5, 19.5, 3)],
  emergency: [raw('M12,3.5 L19.5,6.5 V12 C19.5,16.5 16.5,19.6 12,21 C7.5,19.6 4.5,16.5 4.5,12 V6.5 Z ' +
    'M12,5.7 L17.5,7.9 V12 C17.5,15.5 15.3,17.9 12,18.9 C8.7,17.9 6.5,15.5 6.5,12 V7.9 Z', 'evenOdd'),
    line(12, 8.5, 12, 12.8, 2.1), disc(12, 15.6, 1.25)],
  user_manual: [raw('M3.5,6.5 C6.5,5.3 9.5,5.5 12,7 C14.5,5.5 17.5,5.3 20.5,6.5 V17.8 C17.5,16.8 14.5,16.8 12,18.3 C9.5,16.8 6.5,16.8 3.5,17.8 Z ' +
    'M5.5,8.1 C7.5,7.5 9.5,7.7 11,8.7 V15.5 C9.5,14.9 7.5,14.9 5.5,15.4 Z ' +
    'M13,8.7 C14.5,7.7 16.5,7.5 18.5,8.1 V15.4 C16.5,14.9 14.5,14.9 13,15.5 Z', 'evenOdd')],
  about: [ring(12, 12, 8.5), disc(12, 8.2, 1.35), line(12, 11.2, 12, 16.5, 2.2)],
  digitalwellbeing: [raw('M5,19 C5,10 11,5 19,5 C19,13 13,19 5,19 Z M7.3,16.7 C7.6,11 11.5,7.6 16.7,7.3 C16.4,12.5 12.8,16.4 7.3,16.7 Z', 'evenOdd'),
    line(5.8, 18.2, 14.5, 9.5, 1.6)],
  homescreen: [rrectRing(4, 4, 7, 7, 1.8, 1.8), rrectRing(13, 4, 7, 7, 1.8, 1.8), rrectRing(4, 13, 7, 7, 1.8, 1.8), rrectRing(13, 13, 7, 7, 1.8, 1.8)],
  modes: [ring(12, 12, 7), raw(`M${pt(12, 5)} A7,7 0 0 1 ${pt(12, 19)} Z`),
    ...[0, 45, 90, 135, 180, 225, 270, 315].map((a) => { const [x1, y1] = polar(12, 12, 9.6, a), [x2, y2] = polar(12, 12, 10.8, a); return line(x1, y1, x2, y2, 1.5); })],
  software_update: [line(12, 4, 12, 14, 2.2), line(7.6, 10, 12, 14.4, 2.2), line(16.4, 10, 12, 14.4, 2.2), line(5, 16, 5, 19.5), line(5, 19.5, 19, 19.5), line(19, 19.5, 19, 16)],
  tips: [arc(12, 10, 6, 130, 410), line(8.14, 14.6, 9.5, 17.3), line(15.86, 14.6, 14.5, 17.3), line(9.5, 17.5, 14.5, 17.5), line(10, 20.3, 14, 20.3)],
};

const names = Object.keys(glyphs);
if (names.length !== 27) throw new Error(`expected 27 glyphs, got ${names.length}`);

const here = __dirname;
const repo = path.resolve(here, '..', '..');

// --- glyphs.svg: one <symbol> per glyph ----------------------------------------------------
const symbols = names.map((n) => `  <symbol id="hx_glyph_${n}" viewBox="0 0 24 24">\n` +
  glyphs[n].map((p) => `    <path d="${p.d}" fill="#FFFFFF" fill-rule="${p.rule === 'evenOdd' ? 'evenodd' : 'nonzero'}"/>`).join('\n') +
  '\n  </symbol>').join('\n');
fs.writeFileSync(path.join(here, 'glyphs.svg'),
  `<?xml version="1.0" encoding="UTF-8"?>\n<!-- Hex Next dashboard glyphs: original 24x24 line art, generated by build.js. -->\n` +
  `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">\n${symbols}\n</svg>\n`);

// --- contact sheet -------------------------------------------------------------------------
const cells = names.map((n) => `<div class="cell"><svg viewBox="0 0 24 24"><use href="#hx_glyph_${n}"/></svg>` +
  `<div class="small"><svg viewBox="0 0 24 24"><use href="#hx_glyph_${n}"/></svg></div><span>${n}</span></div>`).join('\n');
const html = `<!doctype html><html><head><meta charset="utf-8"><style>
body{margin:0;background:#1c1b1f;color:#cac4d0;font:13px/1.3 sans-serif}
.grid{display:grid;grid-template-columns:repeat(6,160px);gap:8px;padding:12px}
.cell{background:#2b2930;border-radius:12px;padding:10px;display:flex;flex-direction:column;align-items:center;gap:6px;position:relative}
.cell>svg{width:96px;height:96px;background:#3d7bd9;border-radius:50%;padding:18px;box-sizing:border-box}
.small{position:absolute;right:8px;top:8px;width:28px;height:28px;border-radius:50%;background:#e8def8;display:flex}
.small svg{width:18px;height:18px;margin:auto}
.small svg path{fill:#1d192b}
span{word-break:break-all;text-align:center}
</style></head><body>${fs.readFileSync(path.join(here, 'glyphs.svg'), 'utf8').replace(/^<\?xml[^>]*>\s*/, '').replace('<svg ', '<svg style="display:none" ')}
<div class="grid">${cells}</div></body></html>`;
fs.writeFileSync(path.join(here, 'contact-sheet.html'), html);

// --- VectorDrawables -----------------------------------------------------------------------
if (!process.argv.includes('--no-xml')) {
  const outDir = path.join(repo, 'android', 'app', 'src', 'main', 'res', 'drawable');
  for (const n of names) {
    const paths = glyphs[n].map((p) => `    <path\n        android:fillColor="#FFFFFFFF"` +
      (p.rule === 'evenOdd' ? `\n        android:fillType="evenOdd"` : '') +
      `\n        android:pathData="${p.d}" />`).join('\n');
    fs.writeFileSync(path.join(outDir, `hx_glyph_${n}.xml`),
      `<?xml version="1.0" encoding="utf-8"?>\n<!-- Hex Next glyph "${n}" (original line art, generated by tools/glyphs/build.js). -->\n` +
      `<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="24dp"\n    android:height="24dp"\n` +
      `    android:viewportWidth="24"\n    android:viewportHeight="24">\n${paths}\n</vector>\n`);
  }
  console.log(`wrote ${names.length} VectorDrawables to ${outDir}`);
}

// --- render with Playwright ----------------------------------------------------------------
(async () => {
  const { chromium } = require('playwright');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 6 * 168 + 24, height: 5 * 190 }, deviceScaleFactor: 2 });
  await page.goto('file://' + path.join(here, 'contact-sheet.html'));
  await page.screenshot({ path: path.join(here, 'contact-sheet.png'), fullPage: true });
  await browser.close();
  console.log('contact sheet: ' + path.join(here, 'contact-sheet.png'));
})().catch((e) => { console.error(e); process.exit(1); });
