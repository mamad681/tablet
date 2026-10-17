'use strict';
/**
 * Tablet Link server
 *  - Admin (you) logs in with ADMIN_PASSWORD and sees the dashboard.
 *  - Dashboard creates a pairing QR code -> /t/<token>
 *  - The tablet opens that link (web page) and/or the Android app connects with the token.
 *  - Server only relays messages between the admin dashboard and the paired device(s).
 *
 * Tokens are stateless (id + HMAC signature), so they survive redeploys on Railway.
 */
const express = require('express');
const http = require('http');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const QRCode = require('qrcode');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 3000;

let ADMIN_PASSWORD = process.env.ADMIN_PASSWORD;
if (!ADMIN_PASSWORD) {
  ADMIN_PASSWORD = crypto.randomBytes(6).toString('hex');
  console.log('==================================================');
  console.log('ADMIN_PASSWORD is not set. Temporary password for this run:');
  console.log(ADMIN_PASSWORD);
  console.log('Set ADMIN_PASSWORD in Railway > Variables to keep it fixed.');
  console.log('==================================================');
}
// Secret used to sign pairing tokens. Defaults to something derived from the password.
const SECRET =
  process.env.TOKEN_SECRET ||
  crypto.createHash('sha256').update('tablet-link:' + ADMIN_PASSWORD).digest('hex');

/* ------------------------------ helpers ------------------------------ */
const b64url = (buf) => Buffer.from(buf).toString('base64url');

function signToken(id) {
  const sig = crypto.createHmac('sha256', SECRET).update(id).digest('base64url').slice(0, 22);
  return id + '.' + sig;
}
function verifyToken(token) {
  if (typeof token !== 'string' || token.length > 80) return false;
  const [id, sig] = token.split('.');
  if (!id || !sig) return false;
  const expected = crypto.createHmac('sha256', SECRET).update(id).digest('base64url').slice(0, 22);
  const a = Buffer.from(sig);
  const b = Buffer.from(expected);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
function safeEqual(a, b) {
  const ha = crypto.createHash('sha256').update(String(a)).digest();
  const hb = crypto.createHash('sha256').update(String(b)).digest();
  return crypto.timingSafeEqual(ha, hb);
}
function parseCookies(header) {
  const out = {};
  (header || '').split(';').forEach((p) => {
    const i = p.indexOf('=');
    if (i > 0) out[p.slice(0, i).trim()] = decodeURIComponent(p.slice(i + 1).trim());
  });
  return out;
}
function baseUrl(req) {
  if (process.env.PUBLIC_URL) return process.env.PUBLIC_URL.replace(/\/$/, '');
  const proto = (req.headers['x-forwarded-proto'] || 'http').split(',')[0].trim();
  const host = req.headers['x-forwarded-host'] || req.headers.host;
  return proto + '://' + host;
}

/* ------------------------------ auth state ------------------------------ */
const adminSessions = new Map(); // sid -> expiresAt
const SESSION_TTL = 1000 * 60 * 60 * 12;
function newSession() {
  const sid = crypto.randomBytes(24).toString('base64url');
  adminSessions.set(sid, Date.now() + SESSION_TTL);
  return sid;
}
function isAdminReq(req) {
  const sid = parseCookies(req.headers.cookie).sid;
  if (!sid) return false;
  const exp = adminSessions.get(sid);
  if (!exp) return false;
  if (exp < Date.now()) {
    adminSessions.delete(sid);
    return false;
  }
  return true;
}
// simple brute-force protection: 8 attempts / 10 min / IP
const attempts = new Map();
function tooManyAttempts(ip) {
  const now = Date.now();
  const rec = attempts.get(ip) || [];
  const recent = rec.filter((t) => now - t < 10 * 60 * 1000);
  attempts.set(ip, recent);
  return recent.length >= 8;
}
function noteAttempt(ip) {
  const rec = attempts.get(ip) || [];
  rec.push(Date.now());
  attempts.set(ip, rec);
}

/* ------------------------------ pairings ------------------------------ */
// token -> { token, name, devices:Set<ws>, admins:Set<ws>, lastLocation, lastSeen, info }
const pairings = new Map();
function getPairing(token) {
  let p = pairings.get(token);
  if (!p) {
    p = { token, name: 'Tablet', devices: new Set(), admins: new Set(), lastLocation: null, lastSeen: null, info: null };
    pairings.set(token, p);
  }
  return p;
}
const online = (p) => p.devices.size > 0;
function sendJSON(ws, obj) {
  if (ws.readyState === 1) ws.send(JSON.stringify(obj));
}

/* ------------------------------ express ------------------------------ */
const app = express();
app.set('trust proxy', true);
app.disable('x-powered-by');
app.use(express.json({ limit: '10kb' }));

app.use((req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('Cache-Control', 'no-store');
  next();
});

app.get('/health', (_req, res) => res.json({ ok: true }));

app.post('/api/login', (req, res) => {
  const ip = req.ip;
  if (tooManyAttempts(ip)) return res.status(429).json({ error: 'too_many_attempts' });
  const pw = (req.body && req.body.password) || '';
  if (!safeEqual(pw, ADMIN_PASSWORD)) {
    noteAttempt(ip);
    return res.status(401).json({ error: 'bad_password' });
  }
  const sid = newSession();
  const secure = (req.headers['x-forwarded-proto'] || req.protocol) === 'https';
  res.setHeader(
    'Set-Cookie',
    `sid=${sid}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${SESSION_TTL / 1000}${secure ? '; Secure' : ''}`
  );
  res.json({ ok: true });
});

app.post('/api/logout', (req, res) => {
  const sid = parseCookies(req.headers.cookie).sid;
  if (sid) adminSessions.delete(sid);
  res.setHeader('Set-Cookie', 'sid=; HttpOnly; Path=/; Max-Age=0');
  res.json({ ok: true });
});

app.get('/api/me', (req, res) => res.json({ admin: isAdminReq(req) }));

function requireAdmin(req, res, next) {
  if (!isAdminReq(req)) return res.status(401).json({ error: 'unauthorized' });
  next();
}

// create a new pairing + QR code
app.post('/api/pair', requireAdmin, async (req, res) => {
  const id = b64url(crypto.randomBytes(9));
  const token = signToken(id);
  const url = baseUrl(req) + '/t/' + token;
  const qr = await QRCode.toDataURL(url, { margin: 1, width: 360, errorCorrectionLevel: 'M' });
  const p = getPairing(token);
  const name = ((req.body && req.body.name) || '').toString().slice(0, 40);
  if (name) p.name = name;
  res.json({ token, url, qr });
});

// list known pairings (in memory) with status
app.get('/api/pairings', requireAdmin, (_req, res) => {
  res.json(
    [...pairings.values()].map((p) => ({
      token: p.token,
      name: p.name,
      online: online(p),
      lastSeen: p.lastSeen,
      lastLocation: p.lastLocation,
      info: p.info,
    }))
  );
});

// re-issue QR for an existing token
app.get('/api/qr/:token', requireAdmin, async (req, res) => {
  if (!verifyToken(req.params.token)) return res.status(404).json({ error: 'bad_token' });
  const url = baseUrl(req) + '/t/' + req.params.token;
  const qr = await QRCode.toDataURL(url, { margin: 1, width: 360, errorCorrectionLevel: 'M' });
  res.json({ url, qr });
});

// the page the tablet opens after scanning the QR code
app.get('/t/:token', (req, res) => {
  if (!verifyToken(req.params.token)) return res.status(404).send('Invalid or expired link');
  res.sendFile(path.join(__dirname, 'public', 'tablet.html'));
});

app.get('/app.apk', (_req, res) => {
  const f = path.join(__dirname, 'public', 'app.apk');
  if (!fs.existsSync(f)) return res.status(404).send('APK not uploaded yet');
  res.setHeader('Content-Type', 'application/vnd.android.package-archive');
  res.download(f, 'tablet-link.apk');
});
app.get('/api/has-apk', (_req, res) => res.json({ available: fs.existsSync(path.join(__dirname, 'public', 'app.apk')) }));

app.use(express.static(path.join(__dirname, 'public'), { index: 'index.html' }));

/* ------------------------------ websocket ------------------------------ */
const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true, maxPayload: 8 * 1024 * 1024 });

server.on('upgrade', (req, socket, head) => {
  let url;
  try {
    url = new URL(req.url, 'http://x');
  } catch {
    return socket.destroy();
  }
  if (url.pathname !== '/ws') return socket.destroy();
  const role = url.searchParams.get('role');
  const token = url.searchParams.get('token');

  if (role === 'admin') {
    if (!isAdminReq(req) || !verifyToken(token)) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      return socket.destroy();
    }
  } else if (role === 'device') {
    if (!verifyToken(token)) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      return socket.destroy();
    }
  } else {
    return socket.destroy();
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    ws.role = role;
    ws.token = token;
    ws.isAlive = true;
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', (ws) => {
  const p = getPairing(ws.token);
  ws.on('pong', () => (ws.isAlive = true));

  if (ws.role === 'admin') {
    p.admins.add(ws);
    sendJSON(ws, { type: 'presence', online: online(p), name: p.name, info: p.info });
    if (p.lastLocation) sendJSON(ws, { type: 'location', ...p.lastLocation });

    ws.on('message', (data, isBinary) => {
      if (isBinary) return;
      let msg;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        return;
      }
      if (!msg || typeof msg.type !== 'string') return;
      const allowed = ['tap', 'swipe', 'key', 'startScreen', 'stopScreen', 'locate', 'notify', 'beep', 'rename'];
      if (!allowed.includes(msg.type)) return;
      if (msg.type === 'rename') {
        p.name = String(msg.name || '').slice(0, 40) || p.name;
        return;
      }
      p.devices.forEach((d) => sendJSON(d, msg));
    });
    ws.on('close', () => {
      p.admins.delete(ws);
      // stop streaming when nobody is watching
      if (p.admins.size === 0) p.devices.forEach((d) => sendJSON(d, { type: 'stopScreen' }));
    });
  } else {
    // device
    p.devices.add(ws);
    p.lastSeen = Date.now();
    p.admins.forEach((a) => sendJSON(a, { type: 'presence', online: true, name: p.name, info: p.info }));
    if (p.admins.size > 0) sendJSON(ws, { type: 'adminWatching' });

    ws.on('message', (data, isBinary) => {
      p.lastSeen = Date.now();
      if (isBinary) {
        // screen frame (JPEG) -> relay to admins
        p.admins.forEach((a) => {
          if (a.readyState === 1 && a.bufferedAmount < 2 * 1024 * 1024) a.send(data, { binary: true });
        });
        return;
      }
      let msg;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        return;
      }
      if (!msg || typeof msg.type !== 'string') return;
      if (msg.type === 'location') {
        const loc = {
          lat: Number(msg.lat),
          lng: Number(msg.lng),
          acc: Number(msg.acc) || null,
          ts: Number(msg.ts) || Date.now(),
          src: msg.src === 'app' ? 'app' : 'web',
        };
        if (!isFinite(loc.lat) || !isFinite(loc.lng) || Math.abs(loc.lat) > 90 || Math.abs(loc.lng) > 180) return;
        p.lastLocation = loc;
        p.admins.forEach((a) => sendJSON(a, { type: 'location', ...loc }));
      } else if (msg.type === 'info') {
        p.info = {
          model: String(msg.model || '').slice(0, 60),
          battery: Number.isFinite(Number(msg.battery)) ? Number(msg.battery) : null,
          src: msg.src === 'app' ? 'app' : 'web',
          screen: msg.screen || null,
        };
        p.admins.forEach((a) => sendJSON(a, { type: 'info', info: p.info }));
      } else if (msg.type === 'status') {
        p.admins.forEach((a) => sendJSON(a, { type: 'status', text: String(msg.text || '').slice(0, 200) }));
      }
    });
    ws.on('close', () => {
      p.devices.delete(ws);
      p.lastSeen = Date.now();
      p.admins.forEach((a) => sendJSON(a, { type: 'presence', online: online(p), name: p.name, info: p.info }));
    });
  }
});

setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.isAlive) return ws.terminate();
    ws.isAlive = false;
    try {
      ws.ping();
    } catch {}
  });
  const now = Date.now();
  for (const [sid, exp] of adminSessions) if (exp < now) adminSessions.delete(sid);
}, 30000);

server.listen(PORT, () => console.log('Tablet Link listening on :' + PORT));
