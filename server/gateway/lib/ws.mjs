// RFC 6455, without extensions. Shared by gateway upgrades and header-authenticated clients.
import http from 'node:http';
import https from 'node:https';
import { createHash, randomBytes } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { HttpError } from './http.mjs';

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
export const MAX_MESSAGE = 1024 * 1024;
export const MAX_BUFFER = 2 * 1024 * 1024;
const acceptKey = key => createHash('sha1').update(key + GUID).digest('base64');
const hasToken = (header, token) => String(header || '').toLowerCase().split(',').some(s => s.trim() === token);
const validCode = code => (code >= 1000 && code <= 1014 && ![1004, 1005, 1006].includes(code)) || (code >= 3000 && code <= 4999);
const utf8 = bytes => { try { return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes); } catch { throw new WebSocketError(1007, 'Invalid UTF-8'); } };

export class WebSocketError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}

export function encodeFrame(data = Buffer.alloc(0), { opcode = 2, fin = true, mask = false } = {}) {
  const payload = Buffer.isBuffer(data) ? data : Buffer.from(data);
  const n = payload.length, ext = n < 126 ? 0 : n <= 65535 ? 2 : 8;
  const out = Buffer.allocUnsafe(2 + ext + (mask ? 4 : 0) + n);
  out[0] = (fin ? 0x80 : 0) | opcode;
  out[1] = (mask ? 0x80 : 0) | (ext === 8 ? 127 : ext === 2 ? 126 : n);
  if (ext === 2) out.writeUInt16BE(n, 2);
  if (ext === 8) out.writeBigUInt64BE(BigInt(n), 2);
  const offset = 2 + ext;
  if (mask) {
    const key = randomBytes(4); key.copy(out, offset);
    for (let i = 0; i < n; i++) out[offset + 4 + i] = payload[i] ^ key[i % 4];
  } else payload.copy(out, offset);
  return out;
}

// Incremental header/payload state avoids repeatedly copying a slowly arriving large frame.
// Lengths and total fragmented-message size are checked before allocating payload memory.
export class FrameParser extends EventEmitter {
  constructor({ masked = true, maxMessageSize = MAX_MESSAGE } = {}) {
    super(); this.masked = masked; this.max = maxMessageSize;
    this.header = Buffer.alloc(14); this.used = 0; this.needed = 2;
    this.frame = null; this.fragments = []; this.size = 0; this.opcode = null; this.ended = false;
  }
  push(chunk) {
    let offset = 0;
    while (offset < chunk.length && !this.ended) {
      if (!this.frame) {
        const n = Math.min(this.needed - this.used, chunk.length - offset);
        chunk.copy(this.header, this.used, offset, offset + n); this.used += n; offset += n;
        if (this.used < this.needed) continue;
        if (this.needed === 2) {
          const [a, b] = this.header, opcode = a & 15, len = b & 127;
          if ((a & 0x70) || ![0, 1, 2, 8, 9, 10].includes(opcode)) throw new WebSocketError(1002, 'Invalid frame');
          if (!!(b & 128) !== this.masked) throw new WebSocketError(1002, 'Invalid frame mask');
          if (opcode >= 8 && (!(a & 128) || len > 125)) throw new WebSocketError(1002, 'Invalid control frame');
          if (opcode === 0 ? this.opcode === null : opcode < 8 && this.opcode !== null) throw new WebSocketError(1002, 'Invalid continuation');
          this.needed = 2 + (len === 126 ? 2 : len === 127 ? 8 : 0) + (this.masked ? 4 : 0);
          if (this.used < this.needed) continue;
        }
        const marker = this.header[1] & 127;
        const length = marker === 127 ? this.header.readBigUInt64BE(2) : BigInt(marker === 126 ? this.header.readUInt16BE(2) : marker);
        if ((marker === 127 && (length >> 63n || length < 65536n)) || (marker === 126 && length < 126n)) throw new WebSocketError(1002, 'Invalid frame length');
        const opcode = this.header[0] & 15;
        if (length > BigInt(this.max) || (opcode < 8 && BigInt(this.size) + length > BigInt(this.max))) throw new WebSocketError(1009, 'Message too large');
        this.frame = { opcode, fin: !!(this.header[0] & 128), payload: Buffer.allocUnsafe(Number(length)), used: 0,
          mask: this.masked ? Buffer.from(this.header.subarray(this.needed - 4, this.needed)) : null };
      }
      const f = this.frame, n = Math.min(f.payload.length - f.used, chunk.length - offset);
      chunk.copy(f.payload, f.used, offset, offset + n); f.used += n; offset += n;
      if (f.used < f.payload.length) continue;
      this.frame = null; this.used = 0; this.needed = 2;
      if (f.mask) for (let i = 0; i < f.payload.length; i++) f.payload[i] ^= f.mask[i % 4];
      this.deliver(f);
    }
  }
  deliver({ opcode, fin, payload }) {
    if (opcode === 8) {
      if (payload.length === 1) throw new WebSocketError(1002, 'Invalid close payload');
      const code = payload.length ? payload.readUInt16BE() : 1005;
      if (payload.length && !validCode(code)) throw new WebSocketError(1002, 'Invalid close code');
      const reason = utf8(payload.subarray(2));
      this.ended = true; this.emit('close', code, reason); return;
    }
    if (opcode >= 8) { this.emit(opcode === 9 ? 'ping' : 'pong', payload); return; }
    if (opcode !== 0) this.opcode = opcode;
    this.size += payload.length;
    if (payload.length) this.fragments.push(payload);
    if (!fin) return;
    const data = Buffer.concat(this.fragments, this.size), binary = this.opcode === 2;
    this.fragments = []; this.size = 0; this.opcode = null;
    if (!binary) utf8(data);
    this.emit('message', data, binary);
  }
}

export class WebSocketConnection extends EventEmitter {
  constructor(socket, head = Buffer.alloc(0), { client = false, maxMessageSize = MAX_MESSAGE, maxBufferedAmount = MAX_BUFFER, idleTimeoutMs = 60000, closeTimeoutMs = 2000 } = {}) {
    super(); this.socket = socket; this.client = client; this.maxBuffer = maxBufferedAmount;
    this.state = 'open'; this.closeCode = 1006; this.closeReason = ''; this.closeTimeoutMs = closeTimeoutMs;
    this.parser = new FrameParser({ masked: !client, maxMessageSize });
    this.on('error', () => {}); // Transport errors are also always followed by close.
    const receive = chunk => {
      if (this.state === 'closed') return;
      this.emit('activity');
      try { this.parser.push(chunk); }
      catch (e) {
        if (!(e instanceof WebSocketError)) { this.emit('error', e); this.close(1011, 'WebSocket failure'); return; }
        this.parser.ended = true; this.close(e.code, e.message);
      }
    };
    this.parser.on('message', (data, binary) => { if (this.state === 'open') this.emit('message', data, binary); });
    this.parser.on('ping', data => { if (this.state === 'open') this.write(data, 10); this.emit('ping', data); });
    this.parser.on('pong', data => this.emit('pong', data));
    this.parser.on('close', (code, reason) => {
      this.closeCode = code; this.closeReason = reason;
      if (this.state === 'open') this.close(code, reason);
      this.emit('closing', code, reason);
      socket.end();
    });
    socket.setNoDelay(true);
    socket.on('data', receive);
    socket.on('drain', () => this.emit('drain'));
    socket.on('error', () => { this.emit('error', new Error('WebSocket transport failed')); socket.destroy(); });
    socket.on('end', () => socket.end());
    socket.on('close', () => {
      this.state = 'closed'; clearTimeout(this.closeTimer); socket.setTimeout(0);
      this.parser.fragments = []; this.parser.frame = null;
      this.emit('close', this.closeCode, this.closeReason);
    });
    socket.setTimeout(idleTimeoutMs, () => this.close(1001, 'Idle timeout'));
    // Pause until promise consumers can attach listeners; consume upgrade head before data.
    socket.pause();
    setImmediate(() => { this.started = true; receive(head); if (!this.paused && this.state !== 'closed') socket.resume(); });
  }
  pause() { this.paused = true; this.socket.pause(); }
  resume() { this.paused = false; if (this.started && this.state !== 'closed') this.socket.resume(); }
  get bufferedAmount() { return this.socket.writableLength; }
  write(data, opcode) {
    const bytes = Buffer.isBuffer(data) ? data : Buffer.from(data);
    if (this.bufferedAmount + bytes.length + 14 > this.maxBuffer) {
      this.close(1013, 'Backpressure limit'); return false;
    }
    return this.socket.write(encodeFrame(bytes, { opcode, mask: this.client }));
  }
  // false means wait for drain; overflow closes 1013 instead of discarding queued audio.
  send(data, binary = typeof data !== 'string') {
    if (this.state !== 'open') return false;
    return this.write(data, binary ? 2 : 1);
  }
  ping(data = Buffer.alloc(0)) {
    if (Buffer.byteLength(data) > 125) throw new RangeError('Ping too large');
    return this.state === 'open' && this.write(data, 9);
  }
  close(code = 1000, reason = '') {
    if (this.state !== 'open') return;
    if (code !== 1005 && !validCode(code)) throw new RangeError('Invalid close code');
    const text = Buffer.from(reason);
    if (text.length > 123) throw new RangeError('Close reason too large');
    const payload = code === 1005 ? Buffer.alloc(0) : Buffer.alloc(2 + text.length);
    if (payload.length) { payload.writeUInt16BE(code); text.copy(payload, 2); }
    this.state = 'closing';
    // Close is bounded control data, allowed even when the application buffer is full.
    this.socket.write(encodeFrame(payload, { opcode: 8, mask: this.client }));
    this.closeTimer = setTimeout(() => this.socket.destroy(), this.closeTimeoutMs); this.closeTimer.unref();
    this.emit('closing', code, reason);
  }
  terminate() { this.socket.destroy(); }
}

export function acceptWebSocket(req, socket, head, options) {
  const key = req.headers['sec-websocket-key'];
  if (req.method !== 'GET' || !hasToken(req.headers.connection, 'upgrade') || !hasToken(req.headers.upgrade, 'websocket') ||
      typeof key !== 'string' || !/^[A-Za-z0-9+/]{22}==$/.test(key) || Buffer.from(key, 'base64').length !== 16) throw new HttpError(400, 'Invalid WebSocket handshake');
  if (req.headers['sec-websocket-version'] !== '13') throw new HttpError(426, 'WebSocket version 13 required');
  socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + acceptKey(key) + '\r\n\r\n');
  return new WebSocketConnection(socket, head, options);
}

export function rejectUpgrade(socket, status, message) {
  if (socket.destroyed) return;
  const body = Buffer.from(JSON.stringify({ error: message }));
  socket.end(`HTTP/1.1 ${status} ${http.STATUS_CODES[status] || 'Error'}\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n${status === 426 ? 'Sec-WebSocket-Version: 13\r\n' : ''}${status === 503 ? 'Retry-After: 30\r\n' : ''}\r\n` + body);
  const timer = setTimeout(() => socket.destroy(), 2000); timer.unref();
  socket.once('close', () => clearTimeout(timer));
}

// Unlike the browser-shaped client, this exposes drain, bounded writes, HTTP rejection
// status and all RFC close codes. No redirects, URL credentials, or subprotocol auth.
export function connectWebSocket(value, { headers = {}, timeoutMs = 8000, signal, createConnection, ...options } = {}) {
  return new Promise((resolve, reject) => {
    const url = new URL(value);
    if (!['ws:', 'wss:'].includes(url.protocol) || url.username || url.password || url.hash) throw new Error('Invalid WebSocket URL');
    const key = randomBytes(16).toString('base64');
    url.protocol = url.protocol === 'wss:' ? 'https:' : 'http:';
    const req = (url.protocol === 'https:' ? https : http).request(url, {
      method: 'GET', headers: { ...headers, Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Key': key, 'Sec-WebSocket-Version': '13' },
      // Optional socket factory (lib/tunnel.mjs: a tunnel through a proxy); the default agent otherwise.
      ...(createConnection ? { createConnection } : {}),
    });
    const timer = setTimeout(() => req.destroy(new Error('WebSocket handshake timeout')), timeoutMs);
    const abort = () => req.destroy(new Error('WebSocket handshake aborted'));
    const cleanup = () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); };
    signal?.addEventListener('abort', abort, { once: true });
    const fail = status => { const e = new Error(status ? `WebSocket upstream HTTP ${status}` : 'WebSocket handshake failed'); if (status) e.status = status; return e; };
    req.on('error', () => { cleanup(); reject(fail()); });
    req.on('response', res => { cleanup(); res.destroy(); req.destroy(); reject(fail(res.statusCode)); });
    req.on('upgrade', (res, socket, head) => {
      cleanup();
      if (res.statusCode !== 101 || !hasToken(res.headers.upgrade, 'websocket') || !hasToken(res.headers.connection, 'upgrade') ||
          res.headers['sec-websocket-accept'] !== acceptKey(key) || res.headers['sec-websocket-extensions'] || res.headers['sec-websocket-protocol']) {
        socket.destroy(); reject(fail()); return;
      }
      resolve(new WebSocketConnection(socket, head, { ...options, client: true }));
    });
    req.end();
    if (signal?.aborted) abort();
  });
}
