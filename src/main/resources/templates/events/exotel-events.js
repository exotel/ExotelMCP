// Exotel real-time call event endpoint (HTTPS), Node.js 20+, no dependencies.
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
const AUTH = 'Basic ' + Buffer.from(`${process.env.EXOTEL_API_KEY}:${process.env.EXOTEL_API_TOKEN}`).toString('base64');
const MAX_BODY = 256 * 1024;
const seen = new Set(); // event_sid de-duplication (Exotel retries up to 3 times)

if (!TOKEN && !BASIC_USER && process.env.ALLOW_UNAUTHENTICATED !== '1') {
  console.error('Set EXOTEL_EVENTS_TOKEN (secret in the event URL) or EXOTEL_EVENTS_USER/PASS. Anyone could post fake call events otherwise.');
  process.exit(1);
}
if (Boolean(BASIC_USER) !== Boolean(BASIC_PASS)) {
  console.error('Basic auth needs both EXOTEL_EVENTS_USER and EXOTEL_EVENTS_PASS. Set both, or neither.');
  process.exit(1);
}

const same = (a, b) => { const x = Buffer.from(String(a)); const y = Buffer.from(String(b)); return x.length === y.length && crypto.timingSafeEqual(x, y); };
function authorized(req) {
  if (TOKEN) return same(req.url.split('?')[0], `/exotel/events/${TOKEN}`);
  if (BASIC_USER) return same(req.headers.authorization || '', 'Basic ' + Buffer.from(`${BASIC_USER}:${BASIC_PASS}`).toString('base64'));
  return true; // ALLOW_UNAUTHENTICATED=1: local testing only
}

// Send ExoML to a live leg, e.g. legAction(legSid, '<Flow><Say>Hello</Say></Flow>')
async function legAction(legSid, exoml, param = '') {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(String(legSid))) return;
  const url = `${CPAAS}/v2/accounts/${process.env.EXOTEL_ACCOUNT_SID}/legs/${legSid}/actions`;
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
      // Greet, then hand the call to your bot. Act only after leg_answered: actions sent while ringing are lost.
      await legAction(leg, '<Flow><Say>Hello from your Exotel event endpoint.</Say></Flow>', 'greeting');
      if (process.env.BOT_WSS) await legAction(leg, `<Flow><StartStream streamType="bidirectional" streamUrl="${process.env.BOT_WSS.replace(/&/g, '&amp;')}"/></Flow>`, 'stream');
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
}).listen(PORT, () => console.log(`Exotel events on :${PORT}`));
