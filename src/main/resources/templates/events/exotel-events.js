// Exotel real-time call event endpoint (HTTPS).
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
const AUTH = 'Basic ' + Buffer.from(`${process.env.EXOTEL_API_KEY}:${process.env.EXOTEL_API_TOKEN}`).toString('base64');
const seen = new Set(); // event_sid de-duplication (Exotel retries up to 3 times)

function authorized(h) {
  if (!BASIC_USER) return true;
  const want = Buffer.from(`${BASIC_USER}:${BASIC_PASS}`);
  const got = Buffer.from(Buffer.from(String(h || '').replace(/^Basic /, ''), 'base64').toString());
  return got.length === want.length && crypto.timingSafeEqual(got, want);
}

// Send ExoML to a live leg, e.g. legAction(legSid, '<Flow><Say>Hello</Say></Flow>')
async function legAction(legSid, exoml, param = '') {
  const url = `${CPAAS}/v2/accounts/${process.env.EXOTEL_ACCOUNT_SID}/legs/${encodeURIComponent(legSid)}/actions`;
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
      // Inbound call on a DID that Exotel support attached to this endpoint: answer it.
      await legAction(leg, '<Flow><Answer/></Flow>', 'answer');
      break;
    case 'leg_answered':
      // Greet, then hand the call to your bot. Act only after leg_answered: actions sent while ringing are lost.
      await legAction(leg, '<Flow><Say>Hello from your Exotel event endpoint.</Say></Flow>', 'greeting');
      if (process.env.BOT_WSS) await legAction(leg, `<Flow><StartStream streamType="bidirectional" streamUrl="${process.env.BOT_WSS.replace(/&/g, '&amp;')}"/></Flow>`, 'stream');
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
}).listen(PORT, () => console.log(`Exotel events on :${PORT}`));
