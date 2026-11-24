#!/usr/bin/env node
// Checks every relative link in the repository's Markdown files: the target file or folder must exist, and a
// #fragment pointing into a Markdown file must match one of its headings (GitHub's slug rules).
// Usage: node tools/docs/check-links.mjs   (exit 1 and a list of broken links when any are found)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const SKIP = new Set(['.git', 'node_modules', 'build', '.gradle', '.kotlin', '.data', 'private', 'dist', '__pycache__']);

function* markdownFiles(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (SKIP.has(entry.name)) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* markdownFiles(full);
    else if (entry.name.endsWith('.md')) yield full;
  }
}

const slug = text => text.trim().toLowerCase()
  .replace(/<[^>]+>/g, '')
  .replace(/[`*_~]/g, '')
  .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
  .replace(/[^\p{L}\p{N}\s-]/gu, '')
  .replace(/\s/g, '-');

const anchorCache = new Map();
function anchors(file) {
  if (anchorCache.has(file)) return anchorCache.get(file);
  const seen = new Map(), out = new Set();
  let fenced = false;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    if (/^\s*(```|~~~)/.test(line)) fenced = !fenced;
    if (fenced) continue;
    const m = /^#{1,6}\s+(.*?)\s*#*\s*$/.exec(line);
    if (!m) continue;
    const base = slug(m[1]); const n = seen.get(base) ?? 0;
    seen.set(base, n + 1); out.add(n ? `${base}-${n}` : base);
  }
  for (const m of fs.readFileSync(file, 'utf8').matchAll(/<a\s+(?:name|id)="([^"]+)"/g)) out.add(m[1]);
  anchorCache.set(file, out);
  return out;
}

function links(text) {
  const out = [];
  let fenced = false, lineNo = 0;
  for (const line of text.split('\n')) {
    lineNo++;
    if (/^\s*(```|~~~)/.test(line)) { fenced = !fenced; continue; }
    if (fenced) continue;
    const stripped = line.replace(/`[^`]*`/g, '');
    for (const m of stripped.matchAll(/!?\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/g)) out.push({ target: m[1], line: lineNo });
    for (const m of stripped.matchAll(/^\s*\[[^\]]+\]:\s*(\S+)/g)) out.push({ target: m[1], line: lineNo });
    for (const m of stripped.matchAll(/<(?:img|a)\s[^>]*(?:src|href)="([^"]+)"/g)) out.push({ target: m[1], line: lineNo });
  }
  return out;
}

const problems = [];
let checked = 0;
for (const file of markdownFiles(ROOT)) {
  const rel = path.relative(ROOT, file);
  for (const { target, line } of links(fs.readFileSync(file, 'utf8'))) {
    if (/^[a-z][a-z0-9+.-]*:/i.test(target) || target.startsWith('//')) continue; // http:, https:, mailto:, ...
    checked++;
    const [rawPath, fragment] = target.split('#');
    const decoded = decodeURIComponent(rawPath);
    const dest = decoded ? path.resolve(path.dirname(file), decoded) : file;
    if (decoded.startsWith('/')) { problems.push(`${rel}:${line}: absolute link ${target} (use a relative path)`); continue; }
    if (!fs.existsSync(dest)) { problems.push(`${rel}:${line}: ${target} does not exist`); continue; }
    if (fragment && dest.endsWith('.md') && !anchors(dest).has(fragment.toLowerCase()))
      problems.push(`${rel}:${line}: ${target} has no heading #${fragment}`);
  }
}

if (problems.length) {
  console.error(`Broken documentation links (${problems.length}):\n` + problems.map(p => '  ' + p).join('\n'));
  process.exitCode = 1;
} else console.log(`All ${checked} relative documentation links resolve.`);
