// Unauthenticated liveness for the glasses module (no data).
export function register(r) {
  r.get('/ping', () => ({ ok: true, api: 'glasses', version: 1 }), { public: true });
}
