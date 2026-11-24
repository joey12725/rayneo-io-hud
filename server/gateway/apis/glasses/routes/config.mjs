// GET /glasses/v1/config: the public part of the gateway configuration, so the phone can seed its own settings
// (assistant and owner names, which integrations are on, the delivery targets and the notification policy).
// Never secret names or values, URLs, hosts or file paths.
import { publicConfig } from '../../../lib/config.mjs';
import { policyFor } from '../lib/notify-policy.mjs';

export function register(r, ctx) {
  r.get('/config', () => {
    const { $comment, ...policy } = policyFor(ctx);
    return { ...publicConfig({ ...ctx.gateway, owner: ctx.config.owner ?? ctx.gateway.owner, assistant: ctx.config.assistant ?? ctx.gateway.assistant, routes: ctx.config.routes }, ctx.targets?.list() ?? []), notifyPolicy: policy };
  });
}
