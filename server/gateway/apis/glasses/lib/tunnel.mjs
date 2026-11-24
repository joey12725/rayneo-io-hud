// Minimal outbound proxy client for T3 machines that are reachable only through a tailnet sidecar
// (for example Tailscale in userspace-networking mode with its SOCKS5 and HTTP CONNECT proxies). No dependencies: node:net/tls/http.
// proxyFetch() is a fetch-shaped function (returns a WHATWG Response) so lib/gateway.mjs request() keeps its error
// handling; proxyWebSocket() is a browser-shaped WebSocket class over lib/ws.mjs for the T3 RPC socket.
import net from 'node:net';
import tls from 'node:tls';
import http from 'node:http';
import { connectWebSocket } from '../../../lib/ws.mjs';

const MAX_BODY = 64 * 1024 * 1024;
const MAX_HEADER = 16 * 1024;

/** Accepts http://host:port (CONNECT), socks5://host:port or socks5h://host:port. Returns a URL or null. */
export function parseProxy(value) {
  if (!value || typeof value !== 'string' || ['direct', 'none', 'off'].includes(value.trim().toLowerCase())) return null;
  let url; try { url = new URL(value.trim()); } catch { return null; }
  if (!['http:', 'socks5:', 'socks5h:'].includes(url.protocol) || !url.hostname || url.pathname.length > 1 || url.search || url.hash) return null;
  return url;
}
const proxyPort = (p) => Number(p.port) || (p.protocol === 'http:' ? 80 : 1080);
const bare = (host) => host.replace(/^\[|\]$/g, '');

function abortError(signal) { return signal?.reason ?? Object.assign(new Error('aborted'), { name: 'AbortError' }); }

/** Opens a raw TCP tunnel to host:port through the proxy. Resolves with a socket positioned at the first tunnelled byte. */
export function openTunnel(proxy, host, port, { timeoutMs = 8000, signal } = {}) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(abortError(signal)); return; }
    const socket = net.connect(proxyPort(proxy), bare(proxy.hostname));
    let buffer = Buffer.alloc(0), step = 'connect', done = false;
    const finish = (error, value) => {
      if (done) return; done = true; clearTimeout(timer); signal?.removeEventListener('abort', onAbort);
      socket.removeListener('data', onData); socket.removeListener('error', onError); socket.removeListener('close', onClose);
      if (error) { socket.destroy(); reject(error); } else resolve(value);
    };
    const fail = (message) => finish(Object.assign(new Error(message), { proxy: true }));
    const onAbort = () => finish(abortError(signal));
    const onError = () => fail('proxy connection failed');
    const onClose = () => fail('proxy closed the connection');
    const timer = setTimeout(() => fail('proxy connect timed out'), timeoutMs);
    signal?.addEventListener('abort', onAbort, { once: true });
    const target = bare(host);
    // Paused with any early bytes put back, so nothing is emitted before the TLS layer or HTTP client attaches.
    const leftover = (n) => { socket.pause(); const rest = buffer.subarray(n); if (rest.length) socket.unshift(rest); finish(null, socket); };
    function onData(chunk) {
      buffer = Buffer.concat([buffer, chunk]);
      if (proxy.protocol === 'http:') {
        const end = buffer.indexOf('\r\n\r\n');
        if (end < 0) { if (buffer.length > MAX_HEADER) fail('proxy answer too large'); return; }
        const status = Number(/^HTTP\/1\.[01] (\d{3})/.exec(buffer.subarray(0, end).toString('latin1'))?.[1]);
        if (status !== 200) { fail('proxy refused the tunnel' + (status ? ' (HTTP ' + status + ')' : '')); return; }
        leftover(end + 4); return;
      }
      // SOCKS5 (RFC 1928, RFC 1929 for user/password).
      if (step === 'greeting') {
        if (buffer.length < 2) return;
        if (buffer[0] !== 5 || ![0, 2].includes(buffer[1]) || (buffer[1] === 2 && !proxy.username)) { fail('SOCKS proxy refused authentication'); return; }
        const method = buffer[1]; buffer = buffer.subarray(2);
        if (method === 2) {
          const user = Buffer.from(decodeURIComponent(proxy.username)), pass = Buffer.from(decodeURIComponent(proxy.password));
          step = 'auth'; socket.write(Buffer.concat([Buffer.from([1, user.length]), user, Buffer.from([pass.length]), pass])); return;
        }
        step = 'request'; socket.write(request()); return;
      }
      if (step === 'auth') {
        if (buffer.length < 2) return;
        if (buffer[1] !== 0) { fail('SOCKS proxy rejected the credentials'); return; }
        buffer = buffer.subarray(2); step = 'request'; socket.write(request()); return;
      }
      if (step === 'request') {
        if (buffer.length < 5) return;
        if (buffer[0] !== 5 || buffer[1] !== 0) { fail('SOCKS proxy refused the tunnel (code ' + buffer[1] + ')'); return; }
        const type = buffer[3], length = type === 1 ? 4 : type === 4 ? 16 : type === 3 ? 1 + buffer[4] : -1;
        if (length < 0) { fail('SOCKS proxy sent an invalid answer'); return; }
        if (buffer.length < 4 + length + 2) return;
        leftover(4 + length + 2);
      }
    }
    function request() {
      const kind = net.isIPv4(target) ? 1 : net.isIPv6(target) ? 4 : 3;
      const address = kind === 1 ? Buffer.from(target.split('.').map(Number))
        : kind === 4 ? Buffer.from(ipv6Bytes(target))
        : Buffer.concat([Buffer.from([Buffer.byteLength(target)]), Buffer.from(target)]);
      const p = Buffer.alloc(2); p.writeUInt16BE(port);
      return Buffer.concat([Buffer.from([5, 1, 0, kind]), address, p]);
    }
    socket.on('data', onData); socket.on('error', onError); socket.on('close', onClose);
    socket.once('connect', () => {
      if (proxy.protocol === 'http:') {
        const auth = proxy.username ? `Proxy-Authorization: Basic ${Buffer.from(decodeURIComponent(proxy.username) + ':' + decodeURIComponent(proxy.password)).toString('base64')}\r\n` : '';
        const authority = net.isIPv6(target) ? `[${target}]:${port}` : `${target}:${port}`;
        socket.write(`CONNECT ${authority} HTTP/1.1\r\nHost: ${authority}\r\n${auth}\r\n`);
      } else {
        step = 'greeting'; socket.write(Buffer.from(proxy.username ? [5, 2, 0, 2] : [5, 1, 0]));
      }
    });
  });
}
function ipv6Bytes(address) {
  const [head, tail = ''] = address.split('::'), a = head ? head.split(':') : [], b = tail ? tail.split(':') : [];
  const groups = address.includes('::') ? [...a, ...Array(8 - a.length - b.length).fill('0'), ...b] : a;
  return groups.flatMap(g => { const n = parseInt(g, 16) || 0; return [n >> 8, n & 255]; });
}

/** A tunnel to the URL's origin, wrapped in TLS for https:/wss: (SNI and certificate checked against the hostname). */
export async function tunnelFor(proxy, url, { timeoutMs = 8000, signal } = {}) {
  const secure = ['https:', 'wss:'].includes(url.protocol);
  const port = Number(url.port) || (secure ? 443 : 80);
  const raw = await openTunnel(proxy, url.hostname, port, { timeoutMs, signal });
  // Plain HTTP (loopback tests only, see t3.mjs safeUrl): resume once the consumer's listeners are attached.
  if (!secure) { setImmediate(() => raw.resume()); return raw; }
  return new Promise((resolve, reject) => {
    const host = bare(url.hostname);
    const socket = tls.connect({ socket: raw, servername: net.isIP(host) ? undefined : host, ALPNProtocols: ['http/1.1'] });
    const timer = setTimeout(() => { socket.destroy(); reject(new Error('TLS handshake timed out')); }, timeoutMs);
    socket.once('secureConnect', () => { clearTimeout(timer); resolve(socket); });
    socket.once('error', (e) => { clearTimeout(timer); socket.destroy(); reject(e); });
  });
}

/** fetch(url, {method, headers, body, signal}) through the proxy. One connection per request; no redirects followed. */
export function proxyFetch(proxyUrl) {
  const proxy = typeof proxyUrl === 'string' ? parseProxy(proxyUrl) : proxyUrl;
  if (!proxy) throw new Error('Invalid proxy URL');
  return (input, { method = 'GET', headers = {}, body, signal } = {}) => new Promise((resolve, reject) => {
    const url = new URL(String(input));
    if (signal?.aborted) { reject(abortError(signal)); return; }
    const payload = body === undefined || body === null ? null : Buffer.from(typeof body === 'string' ? body : body);
    const req = http.request({
      method, host: url.hostname, port: Number(url.port) || (url.protocol === 'https:' ? 443 : 80), path: url.pathname + url.search, setHost: false,
      headers: { Host: url.host, Connection: 'close', ...headers, ...(payload ? { 'Content-Length': payload.length } : {}) },
      createConnection: (_options, callback) => { tunnelFor(proxy, url, { signal }).then(socket => callback(null, socket), callback); },
    });
    const onAbort = () => req.destroy(abortError(signal));
    signal?.addEventListener('abort', onAbort, { once: true });
    const settle = () => signal?.removeEventListener('abort', onAbort);
    req.on('error', (e) => { settle(); reject(signal?.aborted ? abortError(signal) : e); });
    req.on('response', (res) => {
      const chunks = []; let size = 0;
      res.on('data', (c) => { size += c.length; if (size > MAX_BODY) req.destroy(new Error('response too large')); else chunks.push(c); });
      res.on('error', (e) => { settle(); reject(e); });
      res.on('end', () => {
        settle();
        const status = res.statusCode, list = new Headers();
        for (const [k, v] of Object.entries(res.headers)) if (v !== undefined) list.set(k, Array.isArray(v) ? v.join(', ') : String(v));
        resolve(new Response([101, 204, 205, 304].includes(status) ? null : Buffer.concat(chunks), { status, headers: list }));
      });
    });
    req.end(payload ?? undefined);
  });
}

/** A browser-shaped WebSocket class (onopen/onmessage/onerror/onclose, send, close) whose connections use the proxy. */
export function proxyWebSocket(proxyUrl, { connect = connectWebSocket, maxMessageSize = 16 * 1024 * 1024 } = {}) {
  const proxy = typeof proxyUrl === 'string' ? parseProxy(proxyUrl) : proxyUrl;
  if (!proxy) throw new Error('Invalid proxy URL');
  return class ProxiedWebSocket {
    constructor(value) {
      const url = new URL(String(value));
      this.readyState = 0; this.url = url.href;
      connect(url.href, { maxMessageSize, idleTimeoutMs: 120000, createConnection: (_options, callback) => { tunnelFor(proxy, url).then(s => callback(null, s), callback); } }).then(conn => {
        if (this.closing) { conn.terminate(); this.readyState = 3; this.onclose?.({ code: 1006, reason: '' }); return; }
        this.conn = conn; this.readyState = 1;
        conn.on('message', (data, binary) => this.onmessage?.({ data: binary ? data : data.toString('utf8') }));
        conn.on('close', (code, reason) => { this.readyState = 3; this.onclose?.({ code, reason }); });
        this.onopen?.({});
      }, () => { this.readyState = 3; this.onerror?.({}); this.onclose?.({ code: 1006, reason: '' }); });
    }
    send(data) { if (this.readyState !== 1) throw new Error('WebSocket is not open'); this.conn.send(data); }
    close(code = 1000, reason = '') { this.closing = true; if (this.conn && this.readyState === 1) { this.readyState = 2; try { this.conn.close(code, reason); } catch { this.conn.terminate(); } } }
  };
}
