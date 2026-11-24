// Small outbound HTTP helpers. Never include credentials in error messages.
export class HttpError extends Error {
  constructor(status, message, detail) { super(message); this.status = status; this.detail = detail; }
}

export const bearer = (t) => ({ Authorization: 'Bearer ' + t });
