'use strict';
// Real-time call events (docs.exotel.com/voice-apis/implementing-your-https-event-endpoint-for-real-time-call-events):
// Exotel POSTs JSON to your HTTPS endpoint, waits ~1 s for any 2xx, retries up to 3 attempts on 408/429/5xx/timeouts.
// Optional Basic Auth (configured by Exotel support). Source IPs below.
const http = require('http');
const fs = require('fs');
const path = require('path');

const EXOTEL_SOURCE_IPS = ['35.154.174.161', '13.233.217.51', '98.130.5.99'];

function checkBasic(header, user, pass) {
  if (!user) return true;
  if (!header || !header.startsWith('Basic ')) return false;
  const want = Buffer.from(`${user}:${pass}`);
  const got = Buffer.from(Buffer.from(header.slice(6), 'base64').toString('utf8'));
  return got.length === want.length && require('crypto').timingSafeEqual(got, want);
}

/** Local receiver: acks immediately (200), then prints/forwards the event. */
/** Basic auth for the event receiver (EXOTEL_EVENTS_USER / EXOTEL_EVENTS_PASS); warns when it is off. */
function authFromEnv() {
  const basicUser = process.env.EXOTEL_EVENTS_USER;
  const basicPass = process.env.EXOTEL_EVENTS_PASS;
  if (!basicUser || !basicPass) {
    require('./io').info('Note: the event receiver accepts unauthenticated POSTs. Anyone with your tunnel URL could send fake events.\n'
      + '  Set EXOTEL_EVENTS_USER and EXOTEL_EVENTS_PASS, and ask Exotel support to send them as Basic auth on your event endpoint.');
    return {};
  }
  return { basicUser, basicPass };
}

function listen({ port = 8080, host = '127.0.0.1', basicUser, basicPass, onEvent, logFile, wsProxyPort }) {
  const seen = new Set(); // event_sid de-duplication; bounded below
  const server = http.createServer((req, res) => {
    if (req.method !== 'POST') {
      res.writeHead(405, { Allow: 'POST' });
      return res.end();
    }
    if (!checkBasic(req.headers.authorization, basicUser, basicPass)) {
      res.writeHead(401);
      return res.end();
    }
    let body = '';
    req.setEncoding('utf8');
    req.on('data', (c) => {
      body += c;
      if (body.length > 1e6) req.destroy();
    });
    req.on('end', () => {
      // Ack first: Exotel waits only ~1 s.
      res.writeHead(200);
      res.end();
      let event;
      try {
        event = JSON.parse(body);
      } catch {
        event = null;
      }
      // Anything that isn't a JSON object (junk, null, an array) is acknowledged and ignored, never crashes.
      if (!event || typeof event !== 'object' || Array.isArray(event)) event = { unparsed: String(body).slice(0, 200) };
      const sid = req.headers['x-exotel-event-sid'] || event.event_sid;
      const duplicate = sid && seen.has(sid);
      if (sid) {
        seen.add(sid);
        if (seen.size > 10000) seen.delete(seen.values().next().value);
      }
      const record = {
        received_at: new Date().toISOString(),
        request_id: req.headers['x-exotel-request-id'] || null,
        duplicate: !!duplicate,
        event,
      };
      if (logFile) fs.appendFileSync(logFile, JSON.stringify(record) + '\n');
      if (onEvent) Promise.resolve().then(() => onEvent(record)).catch((e) => process.stderr.write(`event handler error: ${e.message}\n`));
    });
  });
  if (wsProxyPort) {
    // One public URL for both: WebSocket upgrades (Exotel's stream) are piped to the local bot on wsProxyPort.
    server.on('upgrade', (req, sock, head) => {
      const up = require('net').connect(wsProxyPort, '127.0.0.1', () => {
        up.write(`${req.method} ${req.url} HTTP/1.1\r\n${Object.entries(req.headers).map(([k, v]) => `${k}: ${v}`).join('\r\n')}\r\n\r\n`);
        if (head && head.length) up.write(head);
        sock.pipe(up).pipe(sock);
      });
      up.on('error', () => sock.destroy());
      sock.on('error', () => up.destroy());
    });
  }
  return new Promise((resolve) => server.listen(port, host, () => resolve(server)));
}

function summarize(record) {
  const e = record.event || {};
  const d = e.data || {};
  const err = e.event_data && e.event_data.error;
  return [
    record.received_at.slice(11, 23),
    (e.event_name || '?').padEnd(22),
    d.leg_sid ? `leg=${d.leg_sid}` : d.bridge_sid ? `bridge=${d.bridge_sid}` : '',
    d.state ? `state=${d.state}` : '',
    d.terminal_status ? `status=${d.terminal_status}` : '',
    d.answered_by ? `answered_by=${d.answered_by}` : '',
    e.action_custom_parameter ? `param=${e.action_custom_parameter}` : '',
    err ? `error=${err.error_code} ${err.message || ''}` : '',
    record.duplicate ? '(retry duplicate)' : '',
  ].filter(Boolean).join('  ');
}

// ------------------------------------------------------------------ scaffolds

const NODE_TEMPLATE = `// Exotel real-time call event endpoint (HTTPS), Node.js 20+, no dependencies.
// Contract: POST application/json, reply 2xx within ~1 s, then act via the ExoML APIs.
// Docs: https://docs.exotel.com/voice-apis/implementing-your-https-event-endpoint-for-real-time-call-events
//
// Run:  EXOTEL_API_KEY=... EXOTEL_API_TOKEN=... EXOTEL_ACCOUNT_SID=... EXOTEL_EVENTS_TOKEN=<long random> node exotel-events.js
// Then give Exotel this event URL (behind HTTPS):  https://<your-host>/exotel/events/<EXOTEL_EVENTS_TOKEN>
// If Exotel support configured Basic auth for your endpoint, set EXOTEL_EVENTS_USER / EXOTEL_EVENTS_PASS instead.
const http = require('http');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8080);
const TOKEN = process.env.EXOTEL_EVENTS_TOKEN || '';
const BASIC_USER = process.env.EXOTEL_EVENTS_USER || '';
const BASIC_PASS = process.env.EXOTEL_EVENTS_PASS || '';
const CPAAS = 'https://cpaas-api.in.exotel.com';
const AUTH = 'Basic ' + Buffer.from(\`\${process.env.EXOTEL_API_KEY}:\${process.env.EXOTEL_API_TOKEN}\`).toString('base64');
const MAX_BODY = 256 * 1024;
const seen = new Set(); // event_sid de-duplication (Exotel retries up to 3 times)

if (!TOKEN && !BASIC_USER && process.env.ALLOW_UNAUTHENTICATED !== '1') {
  console.error('Set EXOTEL_EVENTS_TOKEN (secret in the event URL) or EXOTEL_EVENTS_USER/PASS. Anyone could post fake call events otherwise.');
  process.exit(1);
}

const same = (a, b) => { const x = Buffer.from(String(a)); const y = Buffer.from(String(b)); return x.length === y.length && crypto.timingSafeEqual(x, y); };
function authorized(req) {
  if (TOKEN) return same(req.url.split('?')[0], \`/exotel/events/\${TOKEN}\`);
  if (BASIC_USER) return same(req.headers.authorization || '', 'Basic ' + Buffer.from(\`\${BASIC_USER}:\${BASIC_PASS}\`).toString('base64'));
  return true; // ALLOW_UNAUTHENTICATED=1: local testing only
}

// Send ExoML to a live leg, e.g. legAction(legSid, '<Flow><Say>Hello</Say></Flow>')
async function legAction(legSid, exoml, param = '') {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(String(legSid))) return;
  const url = \`\${CPAAS}/v2/accounts/\${process.env.EXOTEL_ACCOUNT_SID}/legs/\${legSid}/actions\`;
  const res = await fetch(url, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json' },
    body: JSON.stringify({ exoml, action_custom_param: param }),
    signal: AbortSignal.timeout(10000),
  });
  if (!res.ok) console.error('action failed', res.status);
}

async function handle(event) {
  const leg = event.data && event.data.leg_sid;
  switch (event.event_name) {
    case 'leg_incoming':
      // Inbound call on a DID that Exotel support attached to this endpoint: answer it.
      await legAction(leg, '<Flow><Answer/></Flow>', 'answer');
      break;
    case 'leg_answered':
      // Hand the call to your bot at once (no greeting: the bot speaks first). Act only after leg_answered: actions sent while ringing are lost.
      if (process.env.BOT_WSS) await legAction(leg, \`<Flow><StartStream streamType="bidirectional" streamUrl="\${process.env.BOT_WSS.replace(/&/g, '&amp;')}"/></Flow>\`, 'stream');
      else await legAction(leg, '<Flow><Say>Hello from your Exotel event endpoint.</Say></Flow>', 'hello');
      break;
    case 'leg_terminated':
    case 'leg_failed_to_create':
      console.log('call ended', leg, event.data && event.data.terminal_status);
      break;
    default:
      break;
  }
}

http.createServer((req, res) => {
  if (req.method !== 'POST') { res.writeHead(405); return res.end(); }
  if (!authorized(req)) { res.writeHead(401); return res.end(); }
  let body = '';
  let size = 0;
  req.on('data', (c) => {
    size += c.length;
    if (size > MAX_BODY) { res.writeHead(413); res.end(); req.destroy(); return; }
    body += c;
  });
  req.on('error', () => {});
  req.on('end', () => {
    if (res.writableEnded) return;
    res.writeHead(200); res.end();            // 1) ack immediately
    let event;
    try { event = JSON.parse(body); } catch { return; }
    if (!event || typeof event !== 'object' || Array.isArray(event) || typeof event.event_name !== 'string') return;
    if (event.event_sid) {
      if (seen.has(event.event_sid)) return;  // retry of an event already handled
      seen.add(event.event_sid);
      if (seen.size > 10000) seen.delete(seen.values().next().value);
    }
    // Log the event type and leg only: event data contains callers' phone numbers.
    console.log(event.event_name, event.data && event.data.leg_sid);
    handle(event).catch((e) => console.error('handler error:', e.message)); // 2) decide  3) act via ExoML APIs
  });
}).listen(PORT, () => console.log(\`Exotel events on :\${PORT}\`));
`;

const PY_TEMPLATE = `"""Exotel real-time call event endpoint (HTTPS), Python 3.9+, standard library only.
Contract: POST application/json, reply 2xx within ~1 s, then act via the ExoML APIs.
Docs: https://docs.exotel.com/voice-apis/implementing-your-https-event-endpoint-for-real-time-call-events

Run:  EXOTEL_API_KEY=... EXOTEL_API_TOKEN=... EXOTEL_ACCOUNT_SID=... EXOTEL_EVENTS_TOKEN=<long random> python3 exotel_events.py
Then give Exotel this event URL (behind HTTPS):  https://<your-host>/exotel/events/<EXOTEL_EVENTS_TOKEN>
If Exotel support configured Basic auth for your endpoint, set EXOTEL_EVENTS_USER / EXOTEL_EVENTS_PASS instead.
"""
import base64, hmac, json, os, re, sys, threading, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8080"))
TOKEN = os.environ.get("EXOTEL_EVENTS_TOKEN", "")
BASIC_USER = os.environ.get("EXOTEL_EVENTS_USER", "")
BASIC_PASS = os.environ.get("EXOTEL_EVENTS_PASS", "")
CPAAS = "https://cpaas-api.in.exotel.com"
AUTH = "Basic " + base64.b64encode(f"{os.environ.get('EXOTEL_API_KEY')}:{os.environ.get('EXOTEL_API_TOKEN')}".encode()).decode()
MAX_BODY = 256 * 1024
seen = set()
lock = threading.Lock()

if not TOKEN and not BASIC_USER and os.environ.get("ALLOW_UNAUTHENTICATED") != "1":
    sys.exit("Set EXOTEL_EVENTS_TOKEN (secret in the event URL) or EXOTEL_EVENTS_USER/PASS. Anyone could post fake call events otherwise.")


def authorized(handler):
    if TOKEN:
        return hmac.compare_digest(handler.path.split("?")[0], f"/exotel/events/{TOKEN}")
    if BASIC_USER:
        want = "Basic " + base64.b64encode(f"{BASIC_USER}:{BASIC_PASS}".encode()).decode()
        return hmac.compare_digest(handler.headers.get("Authorization", ""), want)
    return True  # ALLOW_UNAUTHENTICATED=1: local testing only


def leg_action(leg_sid, exoml, param=""):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", str(leg_sid or "")):
        return
    url = f"{CPAAS}/v2/accounts/{os.environ['EXOTEL_ACCOUNT_SID']}/legs/{leg_sid}/actions"
    req = urllib.request.Request(url, data=json.dumps({"exoml": exoml, "action_custom_param": param}).encode(),
                                 headers={"Authorization": AUTH, "Content-Type": "application/json"}, method="POST")
    try:
        urllib.request.urlopen(req, timeout=10).read()
    except Exception as e:
        print("action failed:", e)


def handle(event):
    data = event.get("data") if isinstance(event.get("data"), dict) else {}
    leg, name = data.get("leg_sid"), event.get("event_name")
    if name == "leg_incoming":  # inbound call on a DID attached to this endpoint: answer it
        leg_action(leg, "<Flow><Answer/></Flow>", "answer")
    elif name == "leg_answered":  # act only after answer; actions sent while ringing are lost
        if os.environ.get("BOT_WSS"):  # hand the call to your bot at once (no greeting: the bot speaks first)
            url = os.environ["BOT_WSS"].replace("&", "&amp;")
            leg_action(leg, f'<Flow><StartStream streamType="bidirectional" streamUrl="{url}"/></Flow>', "stream")
        else:
            leg_action(leg, "<Flow><Say>Hello from your Exotel event endpoint.</Say></Flow>", "hello")
    elif name in ("leg_terminated", "leg_failed_to_create"):
        print("call ended", leg, data.get("terminal_status"))


class Handler(BaseHTTPRequestHandler):
    def reply(self, code):
        self.send_response(code); self.send_header("Content-Length", "0"); self.end_headers()

    def do_POST(self):
        if not authorized(self):
            return self.reply(401)
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.reply(400)
        if length < 0 or length > MAX_BODY:
            return self.reply(413)
        body = self.rfile.read(length)
        self.reply(200)                                      # 1) ack immediately
        try:
            event = json.loads(body)
        except ValueError:
            return
        if not isinstance(event, dict) or not isinstance(event.get("event_name"), str):
            return
        sid = event.get("event_sid")
        with lock:
            if sid and sid in seen:                          # retry of an event already handled
                return
            if sid:
                seen.add(sid)
                if len(seen) > 10000:
                    seen.pop()
        # Log the event type and leg only: event data contains callers' phone numbers.
        print(event["event_name"], (event.get("data") or {}).get("leg_sid") if isinstance(event.get("data"), dict) else None)
        threading.Thread(target=handle, args=(event,), daemon=True).start()  # 2) decide 3) act

    def do_GET(self):
        self.reply(405)

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
`;

const TEMPLATES = {
  node: { file: 'exotel-events.js', content: NODE_TEMPLATE },
  python: { file: 'exotel_events.py', content: PY_TEMPLATE },
};

function scaffold(lang, dir, { force = false } = {}) {
  const t = TEMPLATES[lang];
  if (!t) throw new Error(`Unknown language "${lang}". Use: ${Object.keys(TEMPLATES).join(', ')}`);
  const file = path.resolve(dir || '.', t.file);
  if (fs.existsSync(file) && !force) return { file, status: 'exists' };
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, t.content);
  return { file, status: 'created' };
}

module.exports = { authFromEnv, listen, summarize, scaffold, TEMPLATES, EXOTEL_SOURCE_IPS, checkBasic };
