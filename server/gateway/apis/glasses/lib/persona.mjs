// Who the gateway works for and what its assistant is called (config owner/assistant; lib/config.mjs).
// Every prompt and user-visible label takes these instead of hard-coded names.
const GENERIC = new Set(['the user', 'user', 'me', 'owner', 'the owner']);
const escape = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export const ownerName = ctx => ctx?.config?.owner?.name || 'the user';
export const assistantName = ctx => ctx?.config?.assistant?.name || 'Hermes';
export const workerName = ctx => ctx?.config?.assistant?.workerName || 'Triage';
/** The owner's name and aliases that can be matched in text (generic placeholders such as "the user" are left out). */
export function ownerAliases(ctx) {
  const names = [ownerName(ctx), ...(ctx?.config?.owner?.aliases ?? [])].map(x => String(x).trim()).filter(x => x && !GENERIC.has(x.toLowerCase()));
  return [...new Set(names.map(x => x.toLowerCase()))];
}
/** /\b(name|alias)\b/i for the owner, or null when no name is configured. */
export function ownerPattern(ctx) {
  const names = ownerAliases(ctx);
  return names.length ? new RegExp(`(?<![\\p{L}\\p{N}_])(?:${names.map(escape).join('|')})(?![\\p{L}\\p{N}_])`, 'iu') : null;
}
export const mentionsOwnerName = (ctx, text) => ownerPattern(ctx)?.test(String(text ?? '')) ?? false;
/** Fills {{owner}}, {{assistant}} and {{worker}} in a prompt or label. */
export function persona(text, ctx, extra = {}) {
  const vars = { owner: ownerName(ctx), assistant: assistantName(ctx), worker: workerName(ctx), ...extra };
  return String(text).replace(/\{\{\s*(\w+)\s*\}\}/g, (m, k) => k in vars ? vars[k] : m);
}
