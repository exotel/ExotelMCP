'use strict';
// Calls a developer's Dynamic URL the way an Exotel flow applet would, and checks the response
// against the documented contract (docs.exotel.com/voice-apis: Connect / Gather / Dial-from-URL /
// Passthru / Stream applets). Exotel uses the Fallback URL when these checks fail on a live call.

const TYPES = {
  connect: { timeoutMs: 5000, expects: 'application/json' },
  gather: { timeoutMs: 5000, expects: 'application/json' },
  dial: { timeoutMs: 10000, expects: 'text/plain' },
  passthru: { timeoutMs: 5000, expects: 'status' },
  stream: { timeoutMs: 5000, expects: 'application/json' },
};

const E164 = /^\+[1-9]\d{6,14}$/;

function sampleParams(overrides = {}) {
  const now = new Date();
  const ts = now.toISOString().replace('T', ' ').slice(0, 19);
  return {
    CallSid: 'test' + Math.random().toString(16).slice(2, 14).padEnd(12, '0') + 'exotelclitest',
    CallFrom: '09999999999',
    CallTo: '08000000000',
    From: '09999999999',
    To: '08000000000',
    Direction: 'incoming',
    CallType: 'call-attempt',
    CallStatus: 'in-progress',
    Created: ts,
    StartTime: ts,
    CurrentTime: ts,
    flow_id: '0',
    CustomField: 'exotel-cli-test',
    ...overrides,
  };
}

function checkConnect(body, problems, notes) {
  const d = body.destination;
  if (!d || typeof d !== 'object') problems.push('"destination" is required (object with "numbers" array or "trunk")');
  else if (d.numbers !== undefined) {
    if (!Array.isArray(d.numbers) || !d.numbers.length) problems.push('destination.numbers must be a non-empty array');
    else d.numbers.filter((n) => !E164.test(String(n))).forEach((n) => problems.push(`destination.numbers: "${n}" is not E.164 (+<country><number>)`));
  } else if (d.trunk === undefined) problems.push('destination needs "numbers" or "trunk"');
  else if (typeof d.trunk !== 'string' || !d.trunk.trim()) problems.push('destination.trunk must be the trunk SID string');
  if (body.fetch_after_attempt !== undefined && typeof body.fetch_after_attempt !== 'boolean') problems.push('"fetch_after_attempt" must be true/false');
  if (body.outgoing_phone_number !== undefined && !E164.test(String(body.outgoing_phone_number))) problems.push('"outgoing_phone_number" must be an E.164 ExoPhone');
  if (body.dial_passthru_event_url !== undefined && !/^https?:\/\/\S+$/.test(String(body.dial_passthru_event_url))) problems.push('"dial_passthru_event_url" must be an http(s) URL');
  if (body.custom_params !== undefined) checkCustomParams(body, problems, notes);
  const playback = (name, p, keys) => {
    if (p === undefined) return;
    if (!p || typeof p !== 'object') return problems.push(`"${name}" must be an object`);
    if (!['audio_url', 'text'].includes(p.type)) problems.push(`${name}.type must be audio_url or text`);
    if (!p.value) problems.push(`${name}.value is required`);
    if (p.type === 'audio_url' && p.value && !/^https?:\/\/\S+$/.test(p.value)) problems.push(`${name}.value must be an http(s) URL`);
    for (const k of keys) if (p[k] !== undefined && typeof p[k] !== (k.startsWith('play_to') ? 'boolean' : 'number')) problems.push(`${name}.${k} has the wrong type`);
  };
  playback('in_call_playback', body.in_call_playback, ['start_delay', 'repeat_frequency', 'play_to_callee', 'play_to_caller']);
  playback('end_call_playback', body.end_call_playback, []);
  if ([body.start_call_playback, body.in_call_playback, body.end_call_playback].some((p) => p && p.type === 'audio_url')) {
    notes.push('Playback audio must be 8 kHz, 16-bit, mono WAV; Exotel caches by file name, so change the name when the audio changes');
  }
  if (body.record !== undefined && typeof body.record !== 'boolean') problems.push('"record" must be true/false');
  if (body.recording_channels !== undefined && !['single', 'dual'].includes(body.recording_channels)) problems.push('"recording_channels" must be single or dual');
  const range = (k, lo, hi) => {
    if (body[k] !== undefined && !(Number.isInteger(body[k]) && body[k] >= lo && body[k] <= hi)) problems.push(`"${k}" must be an integer ${lo}–${hi}`);
  };
  range('max_ringing_duration', 1, 60);
  range('max_conversation_duration', 1, 4500);
  if (body.parallel_ringing) {
    if (typeof body.parallel_ringing.activate !== 'boolean') problems.push('parallel_ringing.activate must be true/false');
    if (body.parallel_ringing.max_parallel_attempts !== undefined && !(body.parallel_ringing.max_parallel_attempts >= 1 && body.parallel_ringing.max_parallel_attempts <= 10)) problems.push('parallel_ringing.max_parallel_attempts must be 1–10');
    if (body.parallel_ringing.activate) notes.push('parallel_ringing is chargeable');
  }
  if (body.music_on_hold && !['default_tone', 'operator_tone', 'custom_tone'].includes(body.music_on_hold.type || body.music_on_hold)) problems.push('music_on_hold type must be default_tone, operator_tone or custom_tone');
  if (body.start_call_playback) {
    const p = body.start_call_playback;
    if (p.playback_to && !['both', 'callee'].includes(p.playback_to)) problems.push('start_call_playback.playback_to must be both or callee');
    if (!['audio_url', 'text'].includes(p.type)) problems.push('start_call_playback.type must be audio_url or text');
    if (!p.value) problems.push('start_call_playback.value is required');
  }
  if (body.outgoing_phone_number) notes.push('outgoing_phone_number needs Exotel support to enable it on the account');
}

/** custom_params → custom SIP headers on trunk-routed calls. Limits per docs.exotel.com (SIP trunking, Connect applet). */
function checkCustomParams(body, problems, notes) {
  const raw = body.custom_params;
  if (typeof raw !== 'string' || !raw.trim()) return problems.push('"custom_params" must be a "k1=v1&k2=v2" string');
  if (!(body.destination && body.destination.trunk)) notes.push('custom_params are sent as SIP headers only when destination is a trunk');
  const pairs = raw.split('&').filter(Boolean).map((kv) => { const i = kv.indexOf('='); return i > 0 ? [kv.slice(0, i), kv.slice(i + 1)] : [kv, '']; });
  for (const [k, v] of pairs) {
    if (!/^[A-Za-z0-9-]+$/.test(k)) problems.push(`custom_params: "${k}" is not a valid header name (letters, digits, -)`);
    if (/^(exotel|veeno)-/i.test(k)) problems.push(`custom_params: "${k}" uses a reserved prefix (Exotel-, Veeno-)`);
    if (!v) problems.push(`custom_params: "${k}" has no value`);
    if (Buffer.byteLength(`${k}=${v}`) > 800) problems.push(`custom_params: "${k}" is over 800 bytes`);
  }
  if (pairs.length > 10) problems.push(`custom_params: ${pairs.length} headers; the maximum is 10`);
  if (Buffer.byteLength(raw) > 4096) problems.push('custom_params: over 4 KB in total');
  if (pairs.length > 3 || Buffer.byteLength(raw) > 200) notes.push('Older Exotel docs allow only 3 custom SIP headers / 200 bytes; confirm the limit for your account');
}

function checkGather(body, problems) {
  const g = body.gather_prompt;
  if (!g || typeof g !== 'object' || (!g.audio_url && !g.text)) problems.push('"gather_prompt" is required: {"text": "..."} or {"audio_url": "https://..."}');
  if (body.max_input_digits !== undefined && !(Number.isInteger(body.max_input_digits) && body.max_input_digits >= 1 && body.max_input_digits <= 255)) problems.push('"max_input_digits" must be an integer 1–255');
  if (body.finish_on_key !== undefined && !/^[0-9*#]?$/.test(String(body.finish_on_key))) problems.push('"finish_on_key" must be 0-9, *, # or ""');
  if (body.input_timeout !== undefined && !(Number.isInteger(body.input_timeout) && body.input_timeout > 0)) problems.push('"input_timeout" must be a positive integer (seconds)');
  if (body.repeat_menu !== undefined && !(Number.isInteger(body.repeat_menu) && body.repeat_menu >= 0)) problems.push('"repeat_menu" must be an integer ≥ 0');
}

function checkStream(body, problems) {
  if (!body.url || !/^wss?:\/\/\S+$/.test(body.url)) problems.push('"url" must be a ws:// or wss:// URL');
}

async function test(type, url, { params = {}, timeoutMs } = {}) {
  const spec = TYPES[type];
  if (!spec) throw new Error(`Unknown applet type "${type}". Use: ${Object.keys(TYPES).join(', ')}`);
  if (!/^https?:\/\//.test(url)) throw new Error('URL must be http(s)');
  const target = new URL(url);
  for (const [k, v] of Object.entries(sampleParams(params))) target.searchParams.set(k, v);

  const problems = [];
  const notes = [];
  const started = Date.now();
  let res;
  try {
    res = await fetch(target, {
      method: 'GET',
      headers: { 'Exotel-Version': '1.0', 'User-Agent': 'exotel-cli-applet-test' },
      redirect: 'manual',
      signal: AbortSignal.timeout(timeoutMs || spec.timeoutMs),
    });
  } catch (e) {
    const ms = Date.now() - started;
    return {
      type, url: target.toString(), ok: false, ms,
      problems: [e.name === 'TimeoutError' ? `No response within ${(timeoutMs || spec.timeoutMs) / 1000}s — Exotel would use the Fallback URL` : `Request failed: ${e.message}`],
      notes,
    };
  }
  const ms = Date.now() - started;
  const ctype = res.headers.get('content-type') || '';
  const text = await res.text();
  const result = { type, url: target.toString(), status: res.status, contentType: ctype, ms };

  if (type === 'passthru') {
    if (res.status === 200) result.route = 'Choice A (200 OK)';
    else if (res.status === 302) result.route = 'Choice B (302 Found)';
    else problems.push(`Status ${res.status}: Passthru only defines 200 (Choice A) and 302 (Choice B)`);
  } else {
    if (res.status !== 200) problems.push(`Status ${res.status}: Exotel needs 200, otherwise it uses the Fallback URL`);
    if (spec.expects === 'application/json') {
      if (!ctype.includes('application/json')) problems.push(`Content-Type is "${ctype || 'missing'}"; must be application/json`);
      let body;
      try {
        body = JSON.parse(text);
      } catch (e) {
        problems.push(`Body is not valid JSON: ${e.message}`);
      }
      if (body && typeof body === 'object') {
        result.body = body;
        if (type === 'connect') checkConnect(body, problems, notes);
        if (type === 'gather') checkGather(body, problems);
        if (type === 'stream') checkStream(body, problems);
      }
    } else if (type === 'dial') {
      if (!ctype.includes('text/plain')) problems.push(`Content-Type is "${ctype || 'missing'}"; must be text/plain`);
      const nums = text.trim().split(',').map((x) => x.trim()).filter(Boolean);
      if (!nums.length) problems.push('Body must contain a number or comma-separated numbers');
      nums.filter((n) => !E164.test(n)).forEach((n) => problems.push(`"${n}" is not E.164 (+<country><number>)`));
      result.body = nums;
    }
  }
  if (ms > (timeoutMs || spec.timeoutMs) * 0.6) notes.push(`Slow: ${ms} ms. Exotel times out at ${(timeoutMs || spec.timeoutMs) / 1000}s.`);
  return { ...result, ok: problems.length === 0, problems, notes };
}

const SCAFFOLDS = {
  connect: {
    node: `// Exotel Connect applet Dynamic URL handler. Exotel GETs this URL with call details; reply within 5 s.
// Test: exotel applet test connect http://localhost:3000/connect --param digits=1
const http = require('http');
// Security: put a secret in the URL you give Exotel, e.g. https://your-host/handler?key=<APPLET_KEY>,
// and start this with APPLET_KEY set. Without it anyone could drive your call routing.
const APPLET_KEY = process.env.APPLET_KEY || '';
if (!APPLET_KEY && process.env.ALLOW_UNAUTHENTICATED !== '1') { console.error('Set APPLET_KEY (and add ?key=<APPLET_KEY> to the URL in App Bazaar).'); process.exit(1); }
const crypto = require('crypto');
const same = (a, b) => { const x = Buffer.from(String(a)); const y = Buffer.from(String(b)); return x.length === y.length && crypto.timingSafeEqual(x, y); };
/** Parsed query, or null after replying 400/401. Never throws on junk input. */
function guard(req, res) {
  let q;
  try { q = new URL(req.url, 'http://x').searchParams; } catch { res.writeHead(400); res.end(); return null; }
  if (APPLET_KEY && !same(q.get('key') || '', APPLET_KEY)) { res.writeHead(401); res.end(); return null; }
  return q;
}
const AGENTS = { '1': ['+919800000001'], '2': ['+919800000002', '+919800000003'] }; // IVR choice -> agents
http.createServer((req, res) => {
  try {
  const q = guard(req, res);
  if (!q) return;
  // q: CallSid, From, To, Direction, digits, CustomField, …
  const numbers = AGENTS[q.get('digits')] || AGENTS['1'];
  const body = {
    destination: { numbers },                                     // or { trunk: '<trunk_sid>' } for a SIP trunk
    // custom_params: 'X-Customer=' + encodeURIComponent(q.get('From') || ''),  // SIP headers, trunk destinations only
    record: true,
    recording_channels: 'dual',
    max_ringing_duration: 30,
    start_call_playback: { playback_to: 'callee', type: 'text', value: 'Incoming customer call' },
  };
  res.writeHead(200, { 'Content-Type': 'application/json' });     // exact content type, or Exotel uses the fallback URL
  res.end(req.method === 'HEAD' ? undefined : JSON.stringify(body));
  } catch (e) {
    console.error('handler error:', e.message);
    if (!res.headersSent) { res.writeHead(500); res.end(); }
  }
}).listen(process.env.PORT || 3000);
`,
    python: `"""Exotel Connect applet Dynamic URL handler (stdlib). Reply within 5 s with application/json."""
import json, os
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs
AGENTS = {"1": ["+919800000001"], "2": ["+919800000002", "+919800000003"]}
import hmac, sys
# Security: put a secret in the URL you give Exotel (…?key=<APPLET_KEY>) and start this with APPLET_KEY set.
APPLET_KEY = os.environ.get("APPLET_KEY", "")
if not APPLET_KEY and os.environ.get("ALLOW_UNAUTHENTICATED") != "1":
    sys.exit("Set APPLET_KEY (and add ?key=<APPLET_KEY> to the URL in App Bazaar).")
class H(BaseHTTPRequestHandler):
    def _reply(self, send_body):
        q = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}  # CallSid, From, To, digits, CustomField…
        if APPLET_KEY and not hmac.compare_digest(q.get("key", ""), APPLET_KEY):
            self.send_response(401); self.send_header("Content-Length", "0"); self.end_headers(); return
        body = {"destination": {"numbers": AGENTS.get(q.get("digits"), AGENTS["1"])},  # or {"trunk": "<trunk_sid>"}
                "record": True, "recording_channels": "dual", "max_ringing_duration": 30}
        data = json.dumps(body).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers()
        if send_body: self.wfile.write(data)
    def do_GET(self): self._reply(True)
    def do_HEAD(self): self._reply(False)
HTTPServer(("0.0.0.0", int(os.environ.get("PORT", 3000))), H).serve_forever()
`,
  },
  gather: {
    node: `// Exotel Gather applet Dynamic URL: return the prompt and input rules. The caller's digits arrive as ?digits= next time.
const http = require('http');
// Security: put a secret in the URL you give Exotel, e.g. https://your-host/handler?key=<APPLET_KEY>,
// and start this with APPLET_KEY set. Without it anyone could drive your call routing.
const APPLET_KEY = process.env.APPLET_KEY || '';
if (!APPLET_KEY && process.env.ALLOW_UNAUTHENTICATED !== '1') { console.error('Set APPLET_KEY (and add ?key=<APPLET_KEY> to the URL in App Bazaar).'); process.exit(1); }
const crypto = require('crypto');
const same = (a, b) => { const x = Buffer.from(String(a)); const y = Buffer.from(String(b)); return x.length === y.length && crypto.timingSafeEqual(x, y); };
/** Parsed query, or null after replying 400/401. Never throws on junk input. */
function guard(req, res) {
  let q;
  try { q = new URL(req.url, 'http://x').searchParams; } catch { res.writeHead(400); res.end(); return null; }
  if (APPLET_KEY && !same(q.get('key') || '', APPLET_KEY)) { res.writeHead(401); res.end(); return null; }
  return q;
}
http.createServer((req, res) => {
  try {
  const q = guard(req, res);
  if (!q) return;
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ gather_prompt: { text: 'Press 1 for sales, 2 for support' }, max_input_digits: 1, finish_on_key: '#', input_timeout: 6, repeat_menu: 1 }));
  } catch (e) {
    console.error('handler error:', e.message);
    if (!res.headersSent) { res.writeHead(500); res.end(); }
  }
}).listen(process.env.PORT || 3000);
`,
  },
  passthru: {
    node: `// Exotel Passthru applet: 200 OK -> Choice A, 302 Found -> Choice B. Decide fast (5 s).
const http = require('http');
// Security: put a secret in the URL you give Exotel, e.g. https://your-host/handler?key=<APPLET_KEY>,
// and start this with APPLET_KEY set. Without it anyone could drive your call routing.
const APPLET_KEY = process.env.APPLET_KEY || '';
if (!APPLET_KEY && process.env.ALLOW_UNAUTHENTICATED !== '1') { console.error('Set APPLET_KEY (and add ?key=<APPLET_KEY> to the URL in App Bazaar).'); process.exit(1); }
const crypto = require('crypto');
const same = (a, b) => { const x = Buffer.from(String(a)); const y = Buffer.from(String(b)); return x.length === y.length && crypto.timingSafeEqual(x, y); };
/** Parsed query, or null after replying 400/401. Never throws on junk input. */
function guard(req, res) {
  let q;
  try { q = new URL(req.url, 'http://x').searchParams; } catch { res.writeHead(400); res.end(); return null; }
  if (APPLET_KEY && !same(q.get('key') || '', APPLET_KEY)) { res.writeHead(401); res.end(); return null; }
  return q;
}
const VIP = new Set(['09999999999']);
http.createServer((req, res) => {
  try {
  const q = guard(req, res);
  if (!q) return;
  // q: CallSid, From, To, CustomField, digits…
  res.writeHead(VIP.has(q.get('From')) ? 200 : 302, VIP.has(q.get('From')) ? {} : { Location: '/' });
  res.end();
  } catch (e) {
    console.error('handler error:', e.message);
    if (!res.headersSent) { res.writeHead(500); res.end(); }
  }
}).listen(process.env.PORT || 3000);
`,
  },
  dial: {
    node: `// Exotel "dial number from URL": reply text/plain with one E.164 number or a comma-separated list (10 s limit).
const http = require('http');
// Security: put a secret in the URL you give Exotel, e.g. https://your-host/handler?key=<APPLET_KEY>,
// and start this with APPLET_KEY set. Without it anyone could drive your call routing.
const APPLET_KEY = process.env.APPLET_KEY || '';
if (!APPLET_KEY && process.env.ALLOW_UNAUTHENTICATED !== '1') { console.error('Set APPLET_KEY (and add ?key=<APPLET_KEY> to the URL in App Bazaar).'); process.exit(1); }
const crypto = require('crypto');
const same = (a, b) => { const x = Buffer.from(String(a)); const y = Buffer.from(String(b)); return x.length === y.length && crypto.timingSafeEqual(x, y); };
/** Parsed query, or null after replying 400/401. Never throws on junk input. */
function guard(req, res) {
  let q;
  try { q = new URL(req.url, 'http://x').searchParams; } catch { res.writeHead(400); res.end(); return null; }
  if (APPLET_KEY && !same(q.get('key') || '', APPLET_KEY)) { res.writeHead(401); res.end(); return null; }
  return q;
}
http.createServer((req, res) => {
  try {
  const q = guard(req, res);
  if (!q) return;
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('+919800000001,+919800000002');
  } catch (e) {
    console.error('handler error:', e.message);
    if (!res.headersSent) { res.writeHead(500); res.end(); }
  }
}).listen(process.env.PORT || 3000);
`,
  },
  stream: {
    node: `// Exotel Stream / Voicebot applet with an https URL: reply JSON with the wss URL to stream this call to.
const http = require('http');
// Security: put a secret in the URL you give Exotel, e.g. https://your-host/handler?key=<APPLET_KEY>,
// and start this with APPLET_KEY set. Without it anyone could drive your call routing.
const APPLET_KEY = process.env.APPLET_KEY || '';
if (!APPLET_KEY && process.env.ALLOW_UNAUTHENTICATED !== '1') { console.error('Set APPLET_KEY (and add ?key=<APPLET_KEY> to the URL in App Bazaar).'); process.exit(1); }
const crypto = require('crypto');
const same = (a, b) => { const x = Buffer.from(String(a)); const y = Buffer.from(String(b)); return x.length === y.length && crypto.timingSafeEqual(x, y); };
/** Parsed query, or null after replying 400/401. Never throws on junk input. */
function guard(req, res) {
  let q;
  try { q = new URL(req.url, 'http://x').searchParams; } catch { res.writeHead(400); res.end(); return null; }
  if (APPLET_KEY && !same(q.get('key') || '', APPLET_KEY)) { res.writeHead(401); res.end(); return null; }
  return q;
}
http.createServer((req, res) => {
  try {
  const q = guard(req, res);
  if (!q) return;
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ url: 'wss://bot.example.com/agent?call=' + encodeURIComponent(q.get('CallSid') || '') }));
  } catch (e) {
    console.error('handler error:', e.message);
    if (!res.headersSent) { res.writeHead(500); res.end(); }
  }
}).listen(process.env.PORT || 3000);
`,
  },
};

function scaffold(type, lang = 'node', dir = '.', { force = false } = {}) {
  const fs = require('fs');
  const path = require('path');
  const t = SCAFFOLDS[type];
  if (!t) throw new Error(`Unknown applet "${type}". Use: ${Object.keys(SCAFFOLDS).join(', ')}`);
  const code = t[lang];
  if (!code) throw new Error(`No ${lang} template for ${type}; available: ${Object.keys(t).join(', ')}`);
  const file = path.resolve(dir, `exotel-${type}-handler.${lang === 'python' ? 'py' : 'js'}`);
  if (fs.existsSync(file) && !force) return { file, status: 'exists' };
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, code);
  return { file, status: 'created' };
}

module.exports = { TYPES, SCAFFOLDS, test, scaffold, sampleParams, checkCustomParams };
