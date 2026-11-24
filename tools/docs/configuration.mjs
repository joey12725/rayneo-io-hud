#!/usr/bin/env node
// Regenerates the gateway key table in docs/configuration.md from the gateway's config schema
// (server/gateway/lib/config.mjs), so the documentation always lists the real keys, env vars and defaults.
// Usage: node tools/docs/configuration.mjs          rewrite the table
//        node tools/docs/configuration.mjs --check  exit 1 when docs/configuration.md is out of date
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, SCHEMA } from '../../server/gateway/lib/config.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const DOC = path.join(ROOT, 'docs/configuration.md');
const BEGIN = '<!-- BEGIN GENERATED GATEWAY KEYS (node tools/docs/configuration.mjs) -->';
const END = '<!-- END GENERATED GATEWAY KEYS -->';

const cell = v => String(v).replace(/\|/g, '\\|').replace(/\n/g, ' ').replace(/</g, '&lt;');
const shown = leaf => {
  const v = leaf.default;
  if (v == null || v === '' || (Array.isArray(v) && !v.length)) return '';
  return '`' + cell(typeof v === 'object' ? JSON.stringify(v) : String(v)) + '`';
};

export function table() {
  const leaves = describe();
  const groups = new Map();
  for (const leaf of leaves) {
    const top = leaf.path.includes('.') ? leaf.path.split('.')[0] : '';
    if (!groups.has(top)) groups.set(top, []);
    groups.get(top).push(leaf);
  }
  const out = [];
  for (const [top, list] of groups) {
    out.push(`#### ${top ? '`' + top + '`' : 'Server'}`, '');
    const groupDoc = top && SCHEMA[top]?.doc;
    if (groupDoc) out.push(groupDoc, '');
    out.push('| Key | Environment variable | Type | Default | Description |', '| --- | --- | --- | --- | --- |');
    for (const leaf of list) {
      const notes = [leaf.doc, leaf.secret ? '(Names a secret; never its value.)' : '', leaf.public ? '(Sent to the phone by `GET /glasses/v1/config`.)' : '']
        .filter(Boolean).join(' ');
      out.push(`| \`${leaf.path}\` | \`${leaf.env}\` | ${leaf.type} | ${shown(leaf)} | ${cell(notes)} |`);
    }
    out.push('');
  }
  return out.join('\n').trimEnd();
}

export function render(text) {
  const a = text.indexOf(BEGIN), b = text.indexOf(END);
  if (a < 0 || b < a) throw new Error(`docs/configuration.md needs the markers ${BEGIN} and ${END}`);
  return text.slice(0, a + BEGIN.length) + '\n\n' + table() + '\n\n' + text.slice(b);
}

const current = fs.readFileSync(DOC, 'utf8');
const next = render(current);
if (process.argv.includes('--check')) {
  if (next !== current) { console.error('docs/configuration.md is out of date: run node tools/docs/configuration.mjs'); process.exitCode = 1; }
  else console.log('docs/configuration.md matches the gateway config schema.');
} else if (next !== current) { fs.writeFileSync(DOC, next); console.log('updated docs/configuration.md'); }
