// Stateless chat completions against any OpenAI-compatible endpoint (the same kind the gateway uses).
// The key comes from env LLM_API_KEY and is only ever put in a header.

/** Returns a `complete(messages, {model, json})` function for an OpenAI-compatible base URL. */
export function completer({ proxy, apiKey = process.env.LLM_API_KEY } = {}) {
  return async function complete(messages, { model, json = false, temperature } = {}) {
    if (!proxy) throw new Error('Pick an endpoint: --proxy <OpenAI-compatible base URL>');
    const body = JSON.stringify({ model, messages, ...(json ? { response_format: { type: 'json_object' } } : {}), ...(temperature !== undefined ? { temperature } : {}) });
    const raw = await (await fetch(proxy.replace(/\/$/, '') + '/chat/completions', { method: 'POST', body, headers: { 'Content-Type': 'application/json', ...(apiKey ? { Authorization: 'Bearer ' + apiKey } : {}) } })).text();
    let parsed; try { parsed = JSON.parse(raw); } catch { throw new Error('endpoint returned non-JSON: ' + raw.slice(0, 120)); }
    const content = parsed?.choices?.[0]?.message?.content;
    if (typeof content !== 'string') throw new Error('no content: ' + raw.slice(0, 160));
    return content.trim();
  };
}

/** Extracts the first JSON object from a model answer (tolerates code fences). */
export function parseJsonAnswer(text) {
  const s = String(text ?? '').replace(/^```(?:json)?\s*/i, '').replace(/```\s*$/, '');
  try { return JSON.parse(s); } catch {}
  const a = s.indexOf('{'), b = s.lastIndexOf('}');
  if (a >= 0 && b > a) { try { return JSON.parse(s.slice(a, b + 1)); } catch {} }
  return null;
}
