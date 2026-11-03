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
        event = { unparsed: body };
      }
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
      if (onEvent) onEvent(record);
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

const NODE_TEMPLATE = `// Exotel real-time call event endpoint (HTTPS).
// Contract: POST application/json, reply 2xx within ~1 s, then act via the ExoML APIs.
// Docs: https://docs.exotel.com/voice-apis/implementing-your-https-event-endpoint-for-real-time-call-events
// Run:  EXOTEL_API_KEY=... EXOTEL_API_TOKEN=... EXOTEL_ACCOUNT_SID=... node exotel-events.js
// Expose it over HTTPS (load balancer / reverse proxy / tunnel) and pass that URL as leg_event_endpoint.
const http = require('http');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8080);
const BASIC_USER = process.env.EXOTEL_EVENTS_USER || '';   // set if Exotel support configured Basic Auth
const BASIC_PASS = process.env.EXOTEL_EVENTS_PASS || '';
const CPAAS = 'https://cpaas-api.in.exotel.com';
const AUTH = 'Basic ' + Buffer.from(\`\${process.env.EXOTEL_API_KEY}:\${process.env.EXOTEL_API_TOKEN}\`).toString('base64');
const seen = new Set(); // event_sid de-duplication (Exotel retries up to 3 times)

function authorized(h) {
  if (!BASIC_USER) return true;
  const want = Buffer.from(\`\${BASIC_USER}:\${BASIC_PASS}\`);
  const got = Buffer.from(Buffer.from(String(h || '').replace(/^Basic /, ''), 'base64').toString());
  return got.length === want.length && crypto.timingSafeEqual(got, want);
}

// Send ExoML to a live leg, e.g. legAction(legSid, '<Flow><Say>Hello</Say></Flow>')
async function legAction(legSid, exoml, param = '') {
  const url = \`\${CPAAS}/v2/accounts/\${process.env.EXOTEL_ACCOUNT_SID}/legs/\${encodeURIComponent(legSid)}/actions\`;
  const res = await fetch(url, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json' },
    body: JSON.stringify({ exoml, action_custom_param: param }),
  });
  if (!res.ok) console.error('action failed', res.status, await res.text());
}

async function handle(event) {
  const leg = event.data && event.data.leg_sid;
  switch (event.event_name) {
    case 'leg_incoming':
      // Inbound call on a DID registered to this endpoint (POST /v2/accounts/<sid>/callback): answer it.
      await legAction(leg, '<Flow><Answer/></Flow>', 'answer');
      break;
    case 'leg_answered':
      // Greet, then hand the call to your bot. Act only after leg_answered: actions sent while ringing are lost.
      await legAction(leg, '<Flow><Say>Hello from your Exotel event endpoint.</Say></Flow>', 'greeting');
      if (process.env.BOT_WSS) await legAction(leg, \`<Flow><StartStream streamType="bidirectional" streamUrl="\${process.env.BOT_WSS.replace(/&/g, '&amp;')}"/></Flow>\`, 'stream');
      break;
    case 'gather_success':
      console.log('digits', event.event_data);
      break;
    case 'leg_terminated':
    case 'leg_failed_to_create':
      console.log('call ended', event.data && event.data.terminal_status, event.event_data && event.event_data.error);
      break;
    default:
      break;
  }
}

http.createServer((req, res) => {
  if (req.method !== 'POST') { res.writeHead(405); return res.end(); }
  if (!authorized(req.headers.authorization)) { res.writeHead(401); return res.end(); }
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    res.writeHead(200); res.end();            // 1) ack immediately
    let event;
    try { event = JSON.parse(body); } catch { return; }
    if (seen.has(event.event_sid)) return;    // retry of an event already handled
    seen.add(event.event_sid);
    console.log(event.event_name, JSON.stringify(event.data || {}));
    handle(event).catch((e) => console.error(e)); // 2) decide  3) act via ExoML APIs
  });
}).listen(PORT, () => console.log(\`Exotel events on :\${PORT}\`));
`;

const PY_TEMPLATE = `"""Exotel real-time call event endpoint (HTTPS), standard library only.
Contract: POST application/json, reply 2xx within ~1 s, then act via the ExoML APIs.
Docs: https://docs.exotel.com/voice-apis/implementing-your-https-event-endpoint-for-real-time-call-events
Run:  EXOTEL_API_KEY=... EXOTEL_API_TOKEN=... EXOTEL_ACCOUNT_SID=... python3 exotel_events.py
"""
import base64, hmac, json, os, threading, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8080"))
BASIC_USER = os.environ.get("EXOTEL_EVENTS_USER", "")
BASIC_PASS = os.environ.get("EXOTEL_EVENTS_PASS", "")
CPAAS = "https://cpaas-api.in.exotel.com"
AUTH = "Basic " + base64.b64encode(f"{os.environ.get('EXOTEL_API_KEY')}:{os.environ.get('EXOTEL_API_TOKEN')}".encode()).decode()
seen = set()


def leg_action(leg_sid, exoml, param=""):
    url = f"{CPAAS}/v2/accounts/{os.environ['EXOTEL_ACCOUNT_SID']}/legs/{leg_sid}/actions"
    req = urllib.request.Request(url, data=json.dumps({"exoml": exoml, "action_custom_param": param}).encode(),
                                 headers={"Authorization": AUTH, "Content-Type": "application/json"}, method="POST")
    urllib.request.urlopen(req, timeout=10).read()


def handle(event):
    leg = (event.get("data") or {}).get("leg_sid")
    name = event.get("event_name")
    if name == "leg_incoming":  # inbound call on a DID registered to this endpoint: answer it
        leg_action(leg, "<Flow><Answer/></Flow>", "answer")
    elif name == "leg_answered":  # act only after answer; actions sent while ringing are lost
        leg_action(leg, "<Flow><Say>Hello from your Exotel event endpoint.</Say></Flow>", "greeting")
        if os.environ.get("BOT_WSS"):
            url = os.environ["BOT_WSS"].replace("&", "&amp;")
            leg_action(leg, f'<Flow><StartStream streamType="bidirectional" streamUrl="{url}"/></Flow>', "stream")
    elif name in ("leg_terminated", "leg_failed_to_create"):
        print("call ended", (event.get("data") or {}).get("terminal_status"), (event.get("event_data") or {}).get("error"))


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if BASIC_USER:
            want = "Basic " + base64.b64encode(f"{BASIC_USER}:{BASIC_PASS}".encode()).decode()
            if not hmac.compare_digest(self.headers.get("Authorization", ""), want):
                self.send_response(401); self.end_headers(); return
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        self.send_response(200); self.end_headers()          # 1) ack immediately
        try:
            event = json.loads(body)
        except ValueError:
            return
        if event.get("event_sid") in seen:                  # retry of an event already handled
            return
        seen.add(event.get("event_sid"))
        print(event.get("event_name"), json.dumps(event.get("data") or {}))
        threading.Thread(target=handle, args=(event,), daemon=True).start()  # 2) decide 3) act

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
