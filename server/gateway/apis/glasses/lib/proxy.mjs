// OpenAI-compatible chat completions and audio transcriptions (llm.baseUrl, key from the secret llm.apiKeySecret).
import { request } from './gateway.mjs';
import { HttpError } from '../../../lib/http.mjs';

const base = ctx => String(ctx.config.proxyUrl ?? ctx.config.llm?.baseUrl ?? '').replace(/\/$/, '');
const key = async ctx => {
  const value = await ctx.secret(ctx.config.llmKeySecret ?? ctx.config.llm?.apiKeySecret ?? 'LLM_API_KEY', { optional: true });
  return value ? { Authorization: 'Bearer ' + value } : {};
};
const EXTENSION = { 'audio/ogg': 'ogg', 'audio/wav': 'wav', 'audio/mp4': 'm4a', 'audio/webm': 'webm', 'audio/mpeg': 'mp3', 'audio/aac': 'aac' };

export async function chat(ctx, model, messages, { json = false, timeoutMs = 15000, temperature, onRaw } = {}) {
  const response = await request(ctx, base(ctx) + '/chat/completions', {
    method: 'POST', timeoutMs,
    headers: await key(ctx),
    body: { model, messages, ...(json ? { response_format: { type: 'json_object' } } : {}), ...(typeof temperature === 'number' ? { temperature } : {}) },
  });
  const content = response?.choices?.[0]?.message?.content;
  if (typeof content === 'string') onRaw?.(content);
  if (typeof content !== 'string') throw new HttpError(502, 'Model returned no text');
  if (!json) return content.trim();
  try { return JSON.parse(content); } catch { throw new HttpError(502, 'Model returned invalid JSON'); }
}

export async function transcribe(ctx, audio, contentType) {
  return (await transcribeDetailed(ctx, audio, contentType, { segments: false })).text;
}

/** {text, segments?: [{start, end, text}]}; segments when the endpoint supports verbose_json. */
export async function transcribeDetailed(ctx, audio, contentType, { segments = true, timeoutMs = 300000 } = {}) {
  const form = new FormData();
  form.append('file', new Blob([audio], { type: contentType }), 'audio.' + (EXTENSION[contentType] ?? 'bin'));
  form.append('model', ctx.config.models.transcribe);
  if (segments) form.append('response_format', 'verbose_json');
  const result = await request(ctx, base(ctx) + '/audio/transcriptions', { method: 'POST', body: form, timeoutMs: segments ? timeoutMs : 90000, headers: await key(ctx) });
  if (typeof result?.text !== 'string') throw new HttpError(502, 'Transcription returned no text');
  const parts = Array.isArray(result.segments) ? result.segments.filter(x => x && typeof x.text === 'string').map(x => ({ start: Number(x.start), end: Number(x.end), text: x.text.trim() })) : null;
  return { text: result.text.trim(), ...(parts?.length ? { segments: parts } : {}) };
}
