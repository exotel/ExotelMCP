'use strict';
// `exotel ask "<what you want to do>"`: picks the Exotel API/route for a goal, the CLI command to run,
// the gotchas verified on live calls, and the most relevant pages from developer.exotel.com and docs.exotel.com.
// Deterministic (no AI key). Add new intents here as the platform grows.
const docs = require('./docs');
const { out, info } = require('./io');

const INTENTS = [
  {
    id: 'own-bot-outbound', title: 'Put your own voice bot (WSS) on an outbound call',
    not: [/\b(inbound|incoming|receive|callers|dials? in)\b/],
    match: [/\b(wss|websocket|web socket)\b/, /\b(openai|realtime|gemini|pipecat|livekit|deepgram|sarvam|vapi|retell|llm)\b/, /\bmy (own )?(bot|agent|voice ?ai)\b/, /\bvoice ?ai\b/, /\bconnect .*bot\b/, /\bbot\b.*\b(call|dial|phone)\b/],
    api: 'Connect Voice AI API — POST /v1/Accounts/{sid}/Calls/connect.json with StreamUrl + StreamType=bidirectional',
    steps: ['exotel voice-ai connect --to <E.164> --stream-url "wss://<bot>/path?sample-rate=8000" --wait',
      'Starter bot (echo + OpenAI receptionist/booking/lead): examples/exotel-voice-agent'],
    gotchas: ['Audio is base64 16-bit PCM (SLIN) mono, 8 kHz default; outbound chunks multiple of 320 B, 3.2–100 KB.',
      'Send {"event":"clear"} on barge-in; close the socket to end the bot part of the call.',
      'Exotel must reach the bot: public wss:// (ngrok/cloudflared while developing).'],
    query: 'connect voice ai agentstream websocket',
  },
  {
    id: 'own-bot-inbound', title: 'Callers dialling your number talk to your bot',
    match: [/\b(inbound|incoming|receive|callers?|dial[s]? (in|my))\b.*\b(bot|agent|ai|wss)\b/, /\b(bot|agent|ai)\b.*\b(inbound|incoming|my number|exophone|did)\b/, /\b(inbound|incoming)\b/],
    api: 'App Bazaar flow with a Voicebot applet (wss URL), assigned to the ExoPhone — or, with no flow, Legs: register DID → event endpoint',
    steps: ['Flow: exotel voice-ai setup --stream-url wss://… (prints the applet steps) → exotel voicebot attach --number <ExoPhone> --app-id <app_id>',
      'No flow (LP): exotel voice-ai inbound --number <LP DID> --stream-url wss://… --events https://<public>/events --listen 8787 --ws-proxy <bot port>'],
    gotchas: ['Flow ids come only from a flow you built; never reuse another flow\'s id.',
      'The Voicebot applet must be enabled on the account (Exotel support).',
      'Legs route needs an LP-enabled API key/token and an LP number from Exotel support.'],
    query: 'voicebot applet inbound flow exophone',
  },
  {
    id: 'legs', title: 'Real-time call control without a flow (Legs API + ExoML)',
    match: [/\b(exoml|legs?|bridge|conference|warm transfer|hold|unhold|whisper|barge|monitor|mute)\b/, /\breal[- ]?time (call )?control\b/, /\bwithout (a |any )?(flow|app ?bazaar)\b/, /\bstart ?stream\b/],
    api: 'Legs API (cpaas-api.in.exotel.com): POST /v2/accounts/{sid}/legs, then ExoML via POST /legs/{leg_sid}/actions on events',
    steps: ['exotel voice-ai connect --via legs --exophone <LP number> --to <E.164> --stream-url wss://… --events https://<public>/events --listen 8787 --ws-proxy <bot port>',
      'Single actions: exotel leg say|play|gather|record|stream|hold|mute|dial|hangup --leg-sid <sid> …   (exotel leg --help)',
      'Event endpoint starter: exotel events scaffold --lang node|python'],
    gotchas: ['Needs an LP-enabled API key/token + LP number from Exotel support (401 code 1010 = key without Legs; 503 code 1012 = number without a route).',
      'Send actions only after the leg_answered event; actions while ringing are lost.',
      'All actions are ExoML on /actions; /actions/start_stream style JSON endpoints return 404.'],
    query: 'legs exoml actions bridge start stream',
  },
  {
    id: 'elevenlabs', title: 'ElevenLabs agent on Exotel numbers', weight: 3,
    match: [/eleven ?labs/],
    api: 'Native: Exotel Voicebot applet → wss://api.(in.residency.)elevenlabs.io/v1/convai/conversation/exotel + number imported into ElevenLabs; or SIP trunk',
    steps: ['exotel voice-ai setup --platform elevenlabs   (URL + App Bazaar steps)',
      'exotel integrate elevenlabs setup --agent <id> --number <ExoPhone> --app-id <app_id>',
      'exotel integrate elevenlabs call --agent <id> --to <E.164> --wait'],
    gotchas: ['ElevenLabs rejects streams (403) from numbers not imported into it, so a bare WSS URL is not enough.'],
    query: 'elevenlabs voicebot',
  },
  {
    id: 'bolna', title: 'Bolna agent on Exotel numbers', weight: 3,
    match: [/\bbolna\b/],
    api: 'Bolna ↔ Exotel provider: Voicebot applet → https://api.bolna.ai/exotel_callback (Bolna returns the wss per call)',
    steps: ['exotel voice-ai setup --platform bolna', 'exotel integrate bolna status | call --agent <id> --to <E.164> --wait'],
    gotchas: ['Needs outbound + inbound App Bazaar apps; without them Bolna reports "Exotel app flow never connected it".', 'Trial accounts can only call verified numbers.'],
    query: 'voicebot applet passthru',
  },
  {
    id: 'hosted-voicebot', title: 'Build an Exotel-hosted VoiceBot (no bot code)',
    match: [/\b(exotel )?voice ?bot\b/, /\bcreate (a |an )?bot\b/, /\bno[- ]code\b/, /\breceptionist|appointment|booking|lead qualif/],
    api: 'Exotel VoiceBot (MCP tools exotel_voicebot_* + VoiceBot API)',
    steps: ['exotel voicebot create --prompt "<brief>" --wait', 'exotel voicebot test-call --bot-id <id> --to <E.164> --wait',
      'exotel voicebot transcript --call-sid <sid>   |   exotel voicebot debug --call-sid <sid>'],
    gotchas: ['Inbound needs one App Bazaar step (VoiceBot applet with the bot\'s Voicebot URL): exotel voicebot attach --number … --bot …'],
    query: 'voicebot create bot',
  },
  {
    id: 'click-to-call', title: 'Call a customer / connect two numbers (click-to-call)',
    match: [/\bclick[- ]to[- ]call\b/, /\bconnect two\b/, /\b(call|dial) (a |the )?(customer|agent|number)\b/, /\boutbound call\b/, /\bmake a call\b/],
    api: 'Voice v1: POST /v1/Accounts/{sid}/Calls/connect.json (From → To, CallerId) — or Url=<flow> to run a flow',
    steps: ['exotel mcp call exotel_voice_connect_two_numbers fromNumber=<agent> toNumber=<customer> --yes',
      'Raw: exotel api POST /v1/Accounts/{sid}/Calls/connect.json --form "From=…&To=…&CallerId=<ExoPhone>" --yes'],
    gotchas: ['CallerId must be an ExoPhone on the account (exotel numbers list).'],
    query: 'connect two numbers call',
  },
  {
    id: 'ivr', title: 'IVR / menu / dynamic routing',
    match: [/\bivr\b/, /\bmenu\b/, /\bdtmf\b/, /\bpress (1|one)\b/, /\bgather\b/, /\bdynamic (url|routing)\b/, /\broute calls?\b/],
    api: 'App Bazaar flow with Gather / Connect applets using a Dynamic URL (your webhook) — or Legs <Gather> without a flow',
    steps: ['exotel applet test gather|connect|passthru <your https URL>   (calls it like Exotel does)', 'Legs: exotel leg gather --leg-sid <sid> --digits 1 --prompt "Press 1 for sales"'],
    gotchas: ['Dynamic URL must answer within 5 s with the exact Content-Type or Exotel uses the fallback URL.'],
    query: 'gather applet dynamic url connect applet',
  },
  {
    id: 'connect-applet', title: 'Connect applet: route a call to numbers, agents or a SIP trunk (static or Dynamic URL)',
    match: [/\bconnect applet\b/, /\bdynamic url\b/, /\bhunt\b/, /\bparallel ring/, /\bmusic on hold\b/, /\bwhisper\b.*\bagent\b/, /\broute (the )?call to (an? )?(agent|queue|trunk)/],
    weight: 2,
    api: 'Connect applet Dynamic URL: Exotel GETs your URL (Exotel-Version: 1.0) and your JSON reply sets destination (numbers | trunk), recording, limits, playback, parallel ringing',
    steps: ['exotel applet scaffold connect --lang node|python', 'exotel applet test connect https://<host>/connect --param digits=1,CustomField=…'],
    gotchas: ['Reply within 5 s with Content-Type application/json, or Exotel uses the Fallback URL.', 'parallel_ringing is chargeable; outgoing_phone_number needs support and the same telecom circle.', 'Playback audio: 8 kHz 16-bit mono WAV; Exotel caches by file name.'],
    query: 'connect applet dynamic url',
  },
  {
    id: 'custom-data', title: 'Pass custom data through a call (CustomField, bot params, SIP X- headers, Legs custom_param)',
    match: [/\bcustom ?(param|params|parameters|header|headers|field)\b/, /\bx-[a-z]/, /\bsip headers?\b/, /\bpass (data|context|metadata|an? id)\b/, /\bcorrelat/],
    weight: 2,
    api: 'CustomField (Connect API) · Voicebot applet URL params (≤3, 256 chars) · StartStream URL params (≤25, 800 chars) · Legs custom_param / action_custom_param · Connect custom_params → SIP headers',
    steps: ['exotel voice-ai connect … --custom-field "crm_id=C-88" --stream-url "wss://bot/agent?crm_id=C-88"', 'Connect Dynamic URL reply: {"destination":{"trunk":"<trunk_sid>"},"custom_params":"X-CRM-Id=C-88"}', 'Skill: exotel-custom-data'],
    gotchas: ['SIP custom headers: newer docs 10 headers / 800 B each / 4 KB; older docs 3 / 200 B — stay within the smaller until confirmed.', 'Exotel- and Veeno- header prefixes are reserved.', 'Pass ids, not personal data or secrets.'],
    query: 'custom headers sip connect applet custom params',
  },
  {
    id: 'call-records', title: 'Call logs, reports and recordings', weight: 2,
    match: [/\bcall (logs?|reports?|records?|history|cdrs?)\b/, /\bcdrs?\b/, /\b(download|export|backup|store) (the |my )?(call )?recordings?\b/, /\bhow many calls\b/, /\brecordings?\b.*\b(download|get|list)\b/],
    api: 'Voice v1 Calls API: GET /v1/Accounts/{sid}/Calls.json (filters, paging) and /Calls/{CallSid}.json?details=true; RecordingUrl (time-limited)',
    steps: ['exotel calls list --since 2026-11-01 --until 2026-11-03 --status failed', 'exotel calls get --call-sid <sid> --details', 'exotel calls recording --call-sid <sid> --out call.mp3'],
    gotchas: ['DateCreated needs both ends; max 1-month range, 6 months back (the CLI fills the missing end).', 'Recording URLs expire and retention is plan-based: copy recordings you must keep.'],
    query: 'call details recording',
  },
  {
    id: 'campaigns', title: 'Outbound call campaigns', weight: 2,
    match: [/\bcampaigns?\b/, /\bbulk (call|dial)/, /\bauto ?dial/, /\bdialer\b/, /\breminder calls?\b/, /\bcall (a |my )?list of numbers\b/],
    api: 'Campaigns API: /v2/accounts/{sid}/campaigns (+ /lists, /contacts); body {"campaigns":[{…}]}',
    steps: ['exotel campaigns create --caller-id <ExoPhone> --numbers <a,b> --app-id <flow> --retries 2 --dry-run', 'exotel campaigns list | get | calls | pause | resume', 'exotel campaigns lists'],
    gotchas: ['Billed and calls real people: consent, DND and calling hours apply.', '5,000 numbers per campaign, up to 3 retries.', 'Live API needs the {"campaigns":[…]} wrapper (portal shows a flat body).'],
    query: 'call campaigns create campaign',
  },
  {
    id: 'voice-sdks', title: 'In-app / browser calling with the Voice SDKs (WebRTC)', weight: 2,
    match: [/\bsdk\b/, /\bwebrtc\b/, /\bsoftphone\b/, /\bin-?app call/, /\bbrowser call/, /\b(android|ios|flutter) (app|sdk)\b/, /\bvoip\b/, /\bip-?pstn\b/],
    api: 'Client WebSDK / Android / iOS / Flutter SDKs (subscriber tokens from your backend) or CRM WebSDK (Exotel-managed users)',
    steps: ['exotel docs show https://docs.exotel.com/voice-apis/which-voice-sdk-should-i-use', 'Skill: exotel-voice-sdks'],
    gotchas: ['Never ship the Exotel API key/token in the app; your backend issues tokens.', 'Mobile needs push (FCM / PushKit) for incoming calls.'],
    query: 'webrtc sdk voice sdk',
  },
  {
    id: 'sip', title: 'Connect a PBX / SBC / SIP platform (SIP trunking)',
    match: [/\bsip\b/, /\btrunk/, /\bpbx\b/, /\bsbc\b/, /\b(asterisk|freeswitch|kamailio)\b/],
    api: 'Dynamic SIP Trunking: /v2/accounts/{sid}/trunks (+ phone_numbers, whitelisted_ips, destination_uris, credentials, settings)',
    steps: ['exotel trunk setup --name <n> --number <E.164> --ip <IPv4> --uri <host:port> --transport tls --dry-run', 'exotel trunk --help'],
    gotchas: ['SIP trunking must be enabled + KYC done (Exotel support).', 'Codec G.711 A-law; media UDP 10000–40000; 403 = IP not whitelisted.'],
    query: 'dynamic sip trunking trunk',
  },
  {
    id: 'sms', title: 'Send SMS / OTP',
    match: [/\bsms\b/, /\botp\b/, /\btext message\b/, /\bdlt\b/],
    api: 'SMS API (MCP exotel_sms_* tools); India needs DLT template + entity id',
    steps: ['exotel mcp call exotel_sms_send_single toNumber=<E.164> message="…" dltTemplateId=<id> dltEntityId=<id> --yes'],
    gotchas: ['India: text must match the registered DLT template exactly.'],
    query: 'sms send dlt',
  },
  {
    id: 'whatsapp', title: 'Send WhatsApp messages',
    match: [/whats ?app/],
    api: 'WhatsApp API (MCP exotel_whatsapp_send_message)',
    steps: ['exotel mcp call exotel_whatsapp_send_message toNumber=<E.164> messageType=template templateName=<name> --yes'],
    gotchas: ['Template messages to start a conversation; free text only within 24 h of the user\'s last message.'],
    query: 'whatsapp message template',
  },
  {
    id: 'numbers', title: 'Get, route or release a phone number (ExoPhone)',
    match: [/\b(buy|purchase|get|new|release|provision)\b.*\b(number|exophone|did|toll ?free)\b/, /\bexophone\b/],
    api: 'ExoPhones API: /v2_beta/Accounts/{sid}/IncomingPhoneNumbers',
    steps: ['exotel numbers list | get --exophone-sid <sid>', 'Route to a flow: exotel numbers update --exophone-sid <sid> --voice-url <flow url>'],
    gotchas: ['Buy: exotel numbers available, then exotel numbers buy --number … (billed; Indian numbers need approved KYC). Release: exotel numbers release (permanent).'],
    query: 'exophone numbers',
  },
  {
    id: 'debug', title: 'Why did a call fail / get call details, recording, transcript',
    match: [/\b(fail|failed|why|debug|not connect|dropped|silent|no audio)\b/, /\bcall (details|status|log)\b/, /\brecording\b/, /\btranscript\b/],
    api: 'Calls API GET /v1/Accounts/{sid}/Calls/{CallSid}.json; VoiceBot conversations; Legs external_events',
    steps: ['exotel voicebot debug --call-sid <sid>   (VoiceBot calls: call + session + transcript + likely cause)',
      'exotel api GET /v1/Accounts/{sid}/Calls/<CallSid>.json', 'Legs: exotel leg events --leg-sid <sid>'],
    gotchas: [],
    query: 'call details status callback recording',
  },
  {
    id: 'events', title: 'Get call events / webhooks / status callbacks',
    match: [/\b(event|events|webhook|status ?callback|callback)\b/],
    api: 'Voice v1 StatusCallback (answered/terminal); Legs events to an HTTPS endpoint (2xx within ~1 s, 3 retries)',
    steps: ['exotel events listen --port 8787   (local receiver)', 'exotel events scaffold --lang node|python'],
    gotchas: ['Inbound without a flow: Exotel support attaches the DID to your HTTPS event endpoint (no public API); exotel leg inbound prints the steps.'],
    query: 'status callback events https endpoint',
  },
  {
    id: 'transcription', title: 'Live transcription / agent assist (one-way stream)',
    match: [/\blive transcri/, /\bagent assist\b/, /\bunidirectional\b/, /\bcoaching\b/],
    api: 'Stream applet (unidirectional) or Legs <StartStream streamType="unidirectional">; AI Assist MCP tools',
    steps: ['exotel leg stream --leg-sid <sid> --url wss://… --direction unidirectional', 'exotel mcp tools --filter aiassist'],
    gotchas: [],
    query: 'stream applet unidirectional transcription',
  },
  {
    id: 'quality', title: 'Score call quality (Conversational Intelligence / CQA)',
    match: [/\b(cqa|quality|score calls?|audit|compliance)\b/],
    api: 'Conversational Intelligence (MCP exotel_cqa_* tools)',
    steps: ['exotel mcp tools --filter cqa'],
    gotchas: ['Needs cqa_api_key / cqa_account_id in the MCP credentials.'],
    query: 'conversation quality analysis',
  },
  {
    id: 'balance', title: 'Account balance / usage',
    match: [/\bbalance\b/, /\bcredit\b/, /\busage\b/],
    api: 'GET /v1/Accounts/{sid}/Balance.json',
    steps: ['exotel balance'],
    gotchas: [],
    query: 'balance',
  },
];

function rank(question) {
  const q = String(question).toLowerCase();
  return INTENTS.map((it) => ({ it, score: (it.not || []).some((re) => re.test(q)) ? 0 : it.match.reduce((n, re) => n + (re.test(q) ? 1 : 0), 0) * (it.weight || 1) }))
    .filter((x) => x.score > 0)
    .sort((a, b) => b.score - a.score);
}

async function ask(question, flags = {}) {
  if (!question || !String(question).trim()) {
    return out({ intents: INTENTS.map((i) => ({ id: i.id, title: i.title })) },
      `Ask in plain words, e.g.\n  exotel ask "connect my OpenAI bot to a phone call"\n  exotel ask "inbound calls to my bot without app bazaar"\n  exotel ask "send OTP by SMS"\n\nI know about:\n${INTENTS.map((i) => `  • ${i.title}`).join('\n')}`);
  }
  const ranked = rank(question).slice(0, 2);
  let pages = [];
  try {
    const index = await docs.loadIndex({ offline: !!flags.offline || !!process.env.EXOTEL_DOCS_OFFLINE });
    // The user's words count fully; the recipe's search words help break ties.
    const limit = Number(flags.limit || 6);
    const merged = new Map();
    for (const p of docs.search(index, question, { limit: 30 })) merged.set(p.url, { ...p, score: p.score });
    if (ranked.length) {
      for (const p of docs.search(index, ranked[0].it.query, { limit: 30 })) {
        const m = merged.get(p.url);
        merged.set(p.url, m ? { ...m, score: m.score + p.score * 0.5 } : { ...p, score: p.score * 0.5 });
      }
    }
    // Top results from each portal: developer.exotel.com (API reference) and docs.exotel.com (product docs).
    const all = [...merged.values()].sort((x, y) => y.score - x.score);
    const per = Math.max(1, Math.ceil(limit / 2));
    pages = ['developer.exotel.com', 'docs.exotel.com'].flatMap((src) => all.filter((p) => p.source === src).slice(0, per));
  } catch (e) {
    info(`(docs search unavailable: ${e.message})`);
  }
  // Status / error-code questions: answer from the reference built from both portals' status-code pages.
  let reference = null;
  const codes = (String(question).match(/\b\d{3,5}\b/g) || []).slice(0, 3);
  const aboutStatus = /\b(status(es)?|error codes?|codes?|hang ?up cause|terminal status|states?)\b/i.test(question);
  if (codes.length || aboutStatus) {
    try {
      const ref = require('./reference');
      const rows = await ref.load({ offline: !!flags.offline || !!process.env.EXOTEL_DOCS_OFFLINE });
      if (codes.length) {
        reference = { codes: codes.map((c) => ({ code: c, matches: ref.lookup(rows, c, { limit: 8 }).map((h) => ({ area: h.heading, page: h.page, url: h.url, source: h.source, text: h.cells.join(' · ') })) })) };
      } else {
        reference = { families: ref.families(rows).slice(0, 30).map((f) => ({ family: f.family, values: f.values, url: f.url })) };
      }
    } catch (e) {
      info(`(status reference unavailable: ${e.message})`);
    }
  }
  // Optional: the developer portal's own AI assistant (POST developer.exotel.com/api/chat, Gemini).
  // Opt-in because the portal logs every question and its retrieval can miss pages; shown next to the grounded answer.
  let portalAi = null;
  if (flags.ai) {
    try {
      const r = await fetch(process.env.EXOTEL_PORTAL_AI_URL || 'https://developer.exotel.com/api/chat', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ question: String(question).slice(0, 500) }), signal: AbortSignal.timeout(45000),
      });
      const d = await r.json();
      portalAi = { model: d.model, answer: d.answer, sources: (d.sources || []).map((x) => ({ title: x.title, url: x.url })) };
    } catch (e) {
      portalAi = { error: e.message };
    }
  }
  const answer = {
    question,
    ...(portalAi ? { portalAi } : {}),
    ...(reference ? { reference } : {}),
    recommendations: ranked.map(({ it }) => ({ id: it.id, title: it.title, api: it.api, run: it.steps, gotchas: it.gotchas })),
    docs: pages.map((p) => ({ title: p.title, url: p.url, source: p.source, description: p.description })),
  };
  return out(answer, () => {
    const lines = [];
    if (reference && reference.codes) {
      for (const c of reference.codes) {
        lines.push(`Code ${c.code}:${c.matches.length ? '' : ' not found in the docs reference'}`);
        for (const m of c.matches) lines.push(`  • ${m.text}`, `      — ${m.page}${m.area ? ` › ${m.area}` : ''}${m.url ? `  ${m.url}` : ''}`);
        lines.push('');
      }
    }
    if (reference && reference.families) {
      lines.push('Statuses and codes Exotel documents (exotel explain <value> for details):');
      for (const f of reference.families) lines.push(`  ${f.family}`, `    ${f.values.join(', ')}`);
      lines.push('');
    }
    if (!ranked.length && !reference) lines.push('No built-in recipe matched; closest documentation below. Try different words, or `exotel ask` to see topics.');
    ranked.forEach(({ it }, i) => {
      lines.push(`${i === 0 ? '▶' : 'Also'} ${it.title}`, `  API:  ${it.api}`, '  Run:', ...it.steps.map((s) => `    ${s}`));
      if (it.gotchas.length) lines.push('  Watch out:', ...it.gotchas.map((g) => `    • ${g}`));
      lines.push('');
    });
    if (portalAi) {
      lines.push(`Developer portal AI (${portalAi.model || 'unavailable'}; check against the docs above):`);
      lines.push(...String(portalAi.answer || portalAi.error || '').split('\n').map((l) => `  ${l}`));
      if (portalAi.sources && portalAi.sources.length) lines.push(`  sources: ${portalAi.sources.map((x) => x.title).join(', ')}`);
      lines.push('');
    }
    if (pages.length) lines.push('Docs:', ...pages.map((p) => `  ${p.title}  (${p.source})\n    ${p.url}`), '', 'Read one here: exotel docs show <url>');
    return lines.join('\n');
  });
}

module.exports = { ask, rank, INTENTS };
