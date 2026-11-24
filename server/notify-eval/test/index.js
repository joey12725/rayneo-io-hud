// Node 24 on Windows resolves an explicit test directory as an entry module.
// Keep `node --test server/notify-eval/test/` working and discover both workers' suites.
import fs from 'node:fs/promises';
for (const file of (await fs.readdir(new URL('.', import.meta.url))).filter(name => name.endsWith('.test.mjs')).sort()) {
  await import(new URL(file, import.meta.url));
}
