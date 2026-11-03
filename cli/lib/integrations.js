'use strict';
// Voice-AI platforms that already speak Exotel: ElevenLabs Agents and Bolna.
// Keys come from ELEVENLABS_API_KEY / BOLNA_API_KEY, or are saved with `exotel integrate <p> login`
// to ~/.config/exotel/integrations.json (mode 600). Calls are billed and need --yes (or a prompt).
const fs = require('fs');
const path = require('path');
const creds = require('./credentials');
const { CliError, EXIT, out, info, confirm, readSecret } = require('./io');
const { sleep, E164 } = require('./core');

const USAGE = `Connect Exotel to a voice-AI platform and test it.

  exotel integrate elevenlabs login | status | setup | call | result
  exotel integrate bolna      login | status | setup | call | result

  login                         save the platform API key (prompt, or ELEVENLABS_API_KEY / BOLNA_API_KEY)
  status                        agents, and which Exotel numbers are already linked
  setup [--agent <id> --number <ExoPhone> --app-id <app_id>]
                                the steps to link Exotel (ElevenLabs: does the API part for you)
  call --agent <id> --to <E.164> [--number <linked number>] [--wait]      $  outbound test call
  result --id <conversation_id | execution_id>                            transcript and outcome`;

const PLATFORMS = {
  elevenlabs: { env: 'ELEVENLABS_API_KEY', base: 'https://api.elevenlabs.io', auth: (k) => ({ 'xi-api-key': k }) },
  bolna: { env: 'BOLNA_API_KEY', base: 'https://api.bolna.ai', auth: (k) => ({ Authorization: `Bearer ${k}` }) },
};

const ELEVENLABS_WSS = {
  default: 'wss://api.elevenlabs.io/v1/convai/conversation/exotel',
  india: 'wss://api.in.residency.elevenlabs.io/v1/convai/conversation/exotel',
  eu: 'wss://api.eu.residency.elevenlabs.io/v1/convai/conversation/exotel',
};

function storePath() {
  return path.join(creds.configDir(), 'integrations.json');
}

function loadStore() {
  try {
    return JSON.parse(fs.readFileSync(storePath(), 'utf8'));
  } catch {
    return {};
  }
}

function apiKey(p) {
  const k = process.env[PLATFORMS[p].env] || loadStore()[p];
  if (!k) throw new CliError(`No ${p} API key. Run \`exotel integrate ${p} login\` or set ${PLATFORMS[p].env}.`, EXIT.AUTH);
  return k;
}

async function http(p, method, urlPath, body) {
  const res = await fetch(PLATFORMS[p].base + urlPath, {
    method,
    headers: { ...PLATFORMS[p].auth(apiKey(p)), Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(30000),
  });
  const text = await res.text();
  let data;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  if (!res.ok) {
    const msg = data && typeof data === 'object' ? JSON.stringify(data.detail || data.message || data.error || data).slice(0, 400) : String(data).slice(0, 400);
    let hint;
    if (/verified phone numbers/i.test(msg)) hint = 'Bolna trial account: verify this number in the Bolna dashboard (or upgrade), then retry.';
    else if (res.status === 401 || res.status === 403) hint = `Check the ${p} API key (exotel integrate ${p} login).`;
    throw new CliError(`${p} API ${method} ${urlPath} → HTTP ${res.status}: ${msg}${hint ? `\nHint: ${hint}` : ''}`, EXIT.ERROR, { status: res.status, data, hint });
  }
  return data;
}

const digits = (n) => String(n || '').replace(/\D/g, '').slice(-10);
const isExotelTrunk = (n) => /exotel\.(com|in)/i.test(String((n.outbound_trunk && n.outbound_trunk.address) || (n.provider_config && n.provider_config.address) || ''));

// ------------------------------------------------------------------ login

async function login(p, flags) {
  const key = await readSecret({ envKey: PLATFORMS[p].env, stdinFlag: 'key-stdin', flags, prompt: `${p} API key` });
  if (!key) throw new CliError('No key given.', EXIT.USAGE);
  const store = loadStore();
  store[p] = key;
  process.env[PLATFORMS[p].env] = key;
  // verify before saving
  if (p === 'elevenlabs') await http(p, 'GET', '/v1/convai/agents?page_size=1');
  else await http(p, 'GET', '/user/me');
  fs.mkdirSync(creds.configDir(), { recursive: true, mode: 0o700 });
  fs.writeFileSync(storePath(), JSON.stringify(store, null, 2) + '\n', { mode: 0o600 });
  fs.chmodSync(storePath(), 0o600);
  out({ ok: true, platform: p, saved: storePath() }, `✔ ${p} key works; saved to ${storePath()} (mode 600)`);
}

// ------------------------------------------------------------------ status

async function status(p) {
  if (p === 'elevenlabs') {
    const agents = (await http(p, 'GET', '/v1/convai/agents?page_size=100')).agents || [];
    const numbers = (await http(p, 'GET', '/v1/convai/phone-numbers')) || [];
    const linked = numbers.filter((n) => n.provider === 'exotel' || isExotelTrunk(n)).map((n) => ({
      phone_number: n.phone_number, phone_number_id: n.phone_number_id, via: n.provider === 'exotel' ? 'WSS (Exotel Voicebot applet → ElevenLabs)' : `SIP trunk (${(n.outbound_trunk || n.provider_config || {}).address})`,
      agent: n.assigned_agent ? `${n.assigned_agent.agent_name} (${n.assigned_agent.agent_id})` : null, outbound: !!n.supports_outbound, inbound: !!n.supports_inbound,
    }));
    return out({ platform: p, agents: agents.map((a) => ({ agent_id: a.agent_id, name: a.name })), exotelNumbers: linked },
      () => [`Agents (${agents.length}):`, ...agents.map((a) => `  ${a.agent_id}  ${a.name}`),
        `Exotel numbers linked (${linked.length}):`, ...(linked.length ? linked.map((n) => `  ${n.phone_number}  ${n.via}  → ${n.agent || 'no agent'}  [${n.phone_number_id}]`) : ['  none — run `exotel integrate elevenlabs setup`'])].join('\n'));
  }
  const me = await http(p, 'GET', '/user/me');
  const agents = (await http(p, 'GET', '/v2/agent/all')) || [];
  const numbers = (await http(p, 'GET', '/phone-numbers/all')) || [];
  const exo = numbers.filter((n) => n.telephony_provider === 'exotel');
  const rows = agents.map((a) => {
    const tc = ((a.tasks || [])[0] || {}).tools_config || {};
    const prov = (tc.output || {}).provider || null;
    return { agent_id: a.id, name: a.agent_name, telephony: prov, transport: prov === 'exotel' ? 'WSS (Exotel Voicebot applet → Bolna)' : prov, inbound_number: a.inbound_phone_number || null };
  });
  out({ platform: p, wallet: me.wallet, agents: rows, exotelNumbers: exo.length },
    () => [`Wallet: ${me.wallet}`, `Agents (${rows.length}):`, ...rows.map((a) => `  ${a.agent_id}  ${a.name}  ${a.transport || 'telephony=?'}${a.inbound_number ? `  inbound=${a.inbound_number}` : ''}`),
      `Exotel numbers visible to Bolna: ${exo.length}${exo.length ? '' : ' — connect Exotel first: exotel integrate bolna setup'}`].join('\n'));
}

// ------------------------------------------------------------------ setup

async function setup(p, flags) {
  const a = (creds.load() || {}).auth;
  if (p === 'elevenlabs') {
    const region = /api\.in\./.test((a && a.api_domain) || '') ? 'india' : 'default';
    const steps = [
      'ElevenLabs ↔ Exotel over WSS (native; ElevenLabs only accepts streams from Exotel numbers imported into it):',
      '1. Exotel support must enable the Voicebot applet on your account (once; they quote 1–2 business days).',
      `2. App Bazaar → Create New App → Voicebot applet with URL ${ELEVENLABS_WSS[region]}  → Save. Note the app id.`,
      '3. Assign that app (flow) to the ExoPhone for inbound calls (exotel voicebot attach … / exotel numbers update …).',
      `4. exotel integrate elevenlabs setup --agent <agent_id> --number <ExoPhone> --app-id <app_id> --yes   (registers the number with ElevenLabs using your Exotel login)`,
      'Alternative: SIP trunk (ElevenLabs sip.rtc.elevenlabs.io ↔ Exotel trunk). See the exotel-sip-trunking skill.',
    ];
    if (!flags.agent || !flags.number || !flags.flow) return out({ platform: p, steps, voicebotUrl: ELEVENLABS_WSS[region] }, steps.join('\n'));
    if (!a) throw new CliError('Exotel login needed first: exotel login', EXIT.AUTH);
    const tok = creds.apiKeyPair(a);
    const body = {
      provider: 'exotel', phone_number: String(flags.number), label: `Exotel ${flags.number}`, agent_id: String(flags.agent),
      account_sid: a.account_sid, api_key: tok.apiKey, api_token: tok.apiToken,
      api_subdomain: /api\.in\./.test(a.api_domain) ? 'api.in.exotel.com' : 'api.exotel.com', app_id: String(flags.flow),
    };
    if (flags['dry-run']) return out({ dryRun: true, request: { method: 'POST', url: `${PLATFORMS[p].base}/v1/convai/phone-numbers`, body: { ...body, api_token: '***' } } });
    await confirm('live', `Give ElevenLabs your Exotel API credentials and link ${flags.number} (flow ${flags.flow}) to agent ${flags.agent}`, flags);
    const r = await http(p, 'POST', '/v1/convai/phone-numbers', body);
    return out({ ok: true, phone_number_id: r.phone_number_id }, `✔ Linked: phone_number_id ${r.phone_number_id}\nTest: exotel integrate elevenlabs call --agent ${flags.agent} --to <your number> --wait`);
  }
  const steps = [
    'Bolna ↔ Exotel over WSS (the Voicebot applet calls Bolna, which returns a per-call wss URL). Bolna dashboard; no API for this part:',
    '1. Exotel support enables the Voicebot applet on your account (once).',
    '2. App Bazaar, outbound app: Voicebot applet URL https://api.bolna.ai/exotel_callback, then a Connect applet with',
    '   "Configure parameters dynamically" Primary URL https://api.bolna.ai/exotel_connect_transfer. Note the app id.',
    '3. App Bazaar, inbound app: Voicebot applet URL https://api.bolna.ai/inbound_call, Connect applet as above.',
    '   Attach it to your ExoPhone (Installed App). Note the app id.',
    '4. Bolna dashboard → Settings → Integrations → Telephony → Exotel → Connect. Enter API_KEY, API_TOKEN, ACCOUNT_SID,',
    `   DOMAIN ${a && /api\.in\./.test(a.api_domain) ? 'api.in.exotel.com' : 'api.exotel.com'}, EXOTEL_OUTBOUND_APP_ID, EXOTEL_INBOUND_APP_ID, PHONE_NUMBER.`,
    '   (`exotel whoami` shows your SID; the key/token are in my.exotel.com → Settings → API Settings.)',
    '5. In the agent, set telephony input/output provider to Exotel. Check with: exotel integrate bolna status',
  ];
  return out({ platform: p, steps }, steps.join('\n'));
}

// ------------------------------------------------------------------ call + result

async function call(p, flags) {
  const agent = flags.agent && flags.agent !== true ? String(flags.agent) : '';
  const to = flags.to && flags.to !== true ? String(flags.to) : '';
  if (!agent) throw new CliError('Pass --agent <id> (see `exotel integrate ' + p + ' status`).', EXIT.USAGE);
  if (!E164.test(to)) throw new CliError('Pass --to in E.164, e.g. +919876543210.', EXIT.USAGE);
  let req;
  if (p === 'elevenlabs') {
    const numbers = (await http(p, 'GET', '/v1/convai/phone-numbers')) || [];
    const want = flags.number && flags.number !== true ? String(flags.number) : '';
    const candidates = numbers.filter((n) => (n.provider === 'exotel' || isExotelTrunk(n)) && n.supports_outbound);
    const num = want ? candidates.find((n) => n.phone_number_id === want || digits(n.phone_number) === digits(want)) : candidates.find((n) => n.assigned_agent && n.assigned_agent.agent_id === agent) || candidates[0];
    if (!num) throw new CliError('No outbound-capable Exotel number linked in ElevenLabs. Run `exotel integrate elevenlabs setup`.', EXIT.USAGE);
    const endpoint = num.provider === 'exotel' ? '/v1/convai/exotel/outbound-call' : '/v1/convai/sip-trunk/outbound-call';
    req = { method: 'POST', path: endpoint, body: { agent_id: agent, agent_phone_number_id: num.phone_number_id, to_number: to }, from: num.phone_number };
  } else {
    req = { method: 'POST', path: '/call', body: { agent_id: agent, recipient_phone_number: to, ...(flags.from && flags.from !== true ? { from_phone_number: String(flags.from) } : {}) }, from: flags.from || '(agent default)' };
  }
  if (flags['dry-run']) return out({ dryRun: true, kind: 'spend', request: { method: req.method, url: PLATFORMS[p].base + req.path, body: req.body } });
  await confirm('spend', `${p}: call ${to} from ${req.from} with agent ${agent}`, flags);
  const r = await http(p, req.method, req.path, req.body);
  const id = p === 'elevenlabs' ? r.conversation_id : (r.execution_id || r.id);
  if (!flags.wait || !id) return out({ ok: r.success !== false, platform: p, id, response: r }, `Call placed. id: ${id}\nNext: exotel integrate ${p} result --id ${id}`);
  info(`Call placed (${id}). Waiting for it to finish…`);
  return result(p, { ...flags, id }, { poll: true });
}

async function result(p, flags, { poll = false } = {}) {
  const id = flags.id && flags.id !== true ? String(flags.id) : '';
  if (!id) throw new CliError('Pass --id <conversation_id | execution_id>.', EXIT.USAGE);
  const deadline = Date.now() + Number(flags['wait-timeout'] || 300) * 1000;
  let d;
  for (;;) {
    d = p === 'elevenlabs' ? await http(p, 'GET', `/v1/convai/conversations/${encodeURIComponent(id)}`) : await http(p, 'GET', `/executions/${encodeURIComponent(id)}`);
    const st = String(d.status || '').toLowerCase();
    if (!poll || /^(done|failed|completed|call-disconnected|no-answer|busy|error|stopped|canceled)$/.test(st) || Date.now() > deadline) break;
    info(`  status: ${st || 'unknown'}`);
    await sleep(8000);
  }
  let turns;
  let summary;
  if (p === 'elevenlabs') {
    turns = (d.transcript || []).map((t) => ({ speaker: t.role, text: t.message })).filter((t) => t.text);
    summary = { status: d.status, durationSec: d.metadata && d.metadata.call_duration_secs, termination: d.metadata && d.metadata.termination_reason, callSuccessful: d.analysis && d.analysis.call_successful, summary: d.analysis && d.analysis.transcript_summary };
  } else {
    const tr = d.transcript || '';
    turns = String(tr).split('\n').filter(Boolean).map((l) => {
      const i = l.indexOf(':');
      return i > 0 ? { speaker: l.slice(0, i).trim(), text: l.slice(i + 1).trim() } : { speaker: '', text: l };
    });
    summary = { status: d.status, durationSec: d.conversation_duration ?? d.conversation_time, hangupBy: d.telephony_data && d.telephony_data.hangup_by, cost: d.total_cost, extracted: d.extracted_data || null, summary: d.summary || null, recording: d.telephony_data && d.telephony_data.recording_url };
  }
  out({ platform: p, id, ...summary, turns },
    () => [`${p} ${id}: ${summary.status}${summary.durationSec != null ? ` · ${summary.durationSec}s` : ''}${summary.termination ? ` · ${summary.termination}` : ''}${summary.hangupBy ? ` · hangup by ${summary.hangupBy}` : ''}`,
      ...turns.map((t) => `${String(t.speaker).padStart(9)}: ${t.text}`), summary.summary ? `\nSummary: ${summary.summary}` : ''].join('\n'));
}

async function run(platform, sub, flags) {
  if (!platform || !PLATFORMS[platform]) throw new CliError(USAGE, EXIT.USAGE);
  switch (sub) {
    case 'login': return login(platform, flags);
    case 'status': return status(platform);
    case 'setup': return setup(platform, flags);
    case 'call': return call(platform, flags);
    case 'result': return result(platform, flags);
    default: throw new CliError(USAGE, EXIT.USAGE);
  }
}

module.exports = { run, USAGE, ELEVENLABS_WSS };
