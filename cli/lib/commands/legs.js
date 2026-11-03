'use strict';
// Legs API + ExoML (LP): legs, bridges, one-line ExoML actions, inbound DID registration, event receiver.
const fs = require('fs');
const rest = require('../rest');
const exoml = require('../exoml');
const { CliError, EXIT, out, info } = require('../io');
const { need, opt, int, xmlAttr: xa, xmlText: xt, run } = require('../core');

// ------------------------------------------------------------------ legs, bridges, ExoML actions

const LEG_USAGE = `Legs API + ExoML (LP). From Exotel support you need: an LP-enabled API key + token (not every key on the\naccount has Legs access) and an LP number (DID) to use as --exophone. Host: cpaas-api.in.exotel.com
Legs (one call connection each).
exotel leg create --to <number|sip:user@host> --exophone <ExoPhone> --events <https://…>
                  [--network pstn|voip] [--param <correlation id>] [--timeout 30] [--setup-timeout 30] [--ring-timeout 30]
                  [--time-limit 14400] [--amd] [--async-amd] [--reference-leg <sid>] [--priority normal|high] [--dc <code> --nso <code>]
exotel leg get --leg-sid <leg_sid> [--include bridge,recording,hold,gather,play,tx_audio,rx_audio]
exotel leg events --leg-sid <leg_sid> [--name leg_answered]          event history (no endpoint needed)
One-line ExoML actions on a live leg (all POST /legs/<leg_sid>/actions):
  exotel leg say --leg-sid <sid> --text "…" [--voice Aditi --language en --loop 1]   |  stop-say
  exotel leg play --leg-sid <sid> --url https://…/a.wav|sound:<file> [--loop 1]      |  stop-play
  exotel leg gather --leg-sid <sid> --digits 4 --timeout 10 --finish-on "#" [--prompt "Enter your PIN"]
  exotel leg record --leg-sid <sid> [--format mp3 --channel stereo --storage s3|https …]  |  stop-record
  exotel leg voicemail --leg-sid <sid> --prompt "Leave a message" --silence 5 --timeout 30 --finish-on "#"
  exotel leg stream --leg-sid <sid> --url wss://bot/path [--direction unidirectional]  |  stop-stream [--stream <sid>]
  exotel leg answer | hangup | hold [--moh-url …] | unhold | mute|unmute [--direction in|out|both] --leg-sid <sid>
  exotel leg dial --leg-sid <sid> --to <num> --exophone <num>   |  send-digits --leg-sid <sid> --digits 1234#
  exotel leg monitor --leg-sid <sid> (--monitor-leg <sid> | --to <supervisor>) [--rx muted|enabled]  |  stop-monitor
  exotel leg refer --leg-sid <sid> --events <new endpoint>
Inbound calls without a flow: exotel leg inbound --number <LP DID> --events https://…   ~
exotel leg action --leg-sid <leg_sid> (--file flow.xml | --xml '<Flow>…</Flow>') [--param <tag>]

Bridges (join legs into one conversation)
exotel bridge create --legs <leg1,leg2> --events <https://…> [--absorb-dtmf] [--max 10] [--record]
exotel bridge get --bridge-sid <bridge_sid>
exotel bridge update --bridge-sid <bridge_sid> [--record | --no-record] [--max 15]
exotel bridge stop --bridge-sid <bridge_sid>                                   (ends the bridge, disconnects legs)
exotel bridge join --bridge-sid <bridge_sid> --leg-sid <leg_sid> [--absorb-dtmf]   (sends <JoinBridge> to the leg)
exotel bridge leave --leg-sid <leg_sid>                                        (sends <LeaveBridge>)
exotel bridge action --bridge-sid <bridge_sid> (--file flow.xml | --xml …)     (plays/records for everyone on the bridge)

Events
exotel events listen [--port 8080] [--log events.jsonl] [--ws-proxy <bot port>]   local receiver (+ bot WebSocket on the same port)
exotel events scaffold --lang node|python [--dir .]          starter endpoint code`;

const INCLUDE = { bridge: 'bridge_status', recording: 'recording_status', tx_audio: 'tx_audio_status', rx_audio: 'rx_audio_status', hold: 'hold_status', gather: 'gather_status', play: 'play_status' };
const EVENT_ENDPOINT = /^https:\/\/\S+$/;
const EVENT_MSG = 'a public https:// URL of your event endpoint; see `exotel events --help`';

function readXml(flags) {
  if (flags.xml && flags.xml !== true) return String(flags.xml);
  if (flags.file && flags.file !== true) return fs.readFileSync(flags.file === '-' ? 0 : String(flags.file), 'utf8');
  throw new CliError('Pass --file <flow.xml> (or - for stdin) or --xml \'<Flow>…</Flow>\'', EXIT.USAGE);
}


/** Validate ExoML and send it to a leg or bridge. */
function sendExoml(flags, { target, id, xml, label }) {
  const v = exoml.validate(xml, { target: target === 'bridges' ? 'bridge' : 'leg' });
  if (!v.valid) throw new CliError(`ExoML is invalid — nothing was sent:\n- ${v.errors.join('\n- ')}`, EXIT.USAGE, { validation: v });
  v.warnings.forEach((w) => info(`Warning: ${w}`));
  const kind = v.actions.some((x) => ['Dial', 'Refer'].includes(x)) ? 'spend' : 'live';
  return run(flags, {
    kind, summary: `Run ${v.actions.join(', ')} on live ${label}`,
    request: { method: 'POST', host: 'cpaas', path: `/v2/accounts/{sid}/${target}/${id}/actions`, json: { action_custom_param: opt(flags, 'param') || 'exotel-cli', exoml: xml.trim() } },
  });
}

function attrs(flags, map) {
  return Object.entries(map).map(([flag, attr]) => (flags[flag] !== undefined && flags[flag] !== true ? ` ${attr}="${xa(flags[flag])}"` : (flags[flag] === true ? ` ${attr}="true"` : ''))).join('');
}
const req = (flags, name, msg) => need(flags, name, /^[\s\S]+$/, msg);
// One-line ExoML for every leg action in the Legs reference (v2.4). All are sent via POST /legs/{sid}/actions.
const LEG_VERBS = {
  answer: () => '<Flow><Answer/></Flow>',
  hangup: () => '<Flow><Hangup/></Flow>',
  say: (f) => `<Flow><Say${attrs(f, { loop: 'loop', engine: 'preferredTTSEngine', voice: 'pollyVoiceId', 'google-voice': 'googlettsVoiceName', language: 'language' })}>${xt(req(f, 'text', 'text to speak'))}</Say></Flow>`,
  'stop-say': () => '<Flow><StopSay/></Flow>',
  play: (f) => `<Flow><StartPlay${attrs(f, { loop: 'loop', username: 'username', password: 'password' })}>${xt(req(f, 'url', 'https://…/file.wav or sound:<file>'))}</StartPlay></Flow>`,
  'stop-play': () => '<Flow><StopPlay/></Flow>',
  gather: (f) => `<Flow><Gather${attrs(f, { digits: 'numDigits', timeout: 'timeoutInSec', 'finish-on': 'finishOnKey' })}>${f.prompt && f.prompt !== true ? `<Say>${xt(f.prompt)}</Say>` : ''}${f['prompt-url'] && f['prompt-url'] !== true ? `<StartPlay>${xt(f['prompt-url'])}</StartPlay>` : ''}</Gather></Flow>`,
  record: (f) => `<Flow><StartRecording${attrs(f, { direction: 'direction', format: 'format', bitrate: 'bitrate', channel: 'channel', storage: 'storageType', 'storage-url': 'storageURL', 'storage-key': 'storageURLKey', 'storage-token': 'storageURLToken' })}/></Flow>`,
  voicemail: (f) => `<Flow><StartRecording${attrs(f, { silence: 'silenceInSec', 'finish-on': 'finishOnKey', timeout: 'timeoutInSec' })}>${f.prompt && f.prompt !== true ? `<Say>${xt(f.prompt)}</Say>` : ''}</StartRecording></Flow>`,
  'stop-record': () => '<Flow><StopRecording/></Flow>',
  mute: (f) => `<Flow><Mute${attrs(f, { direction: 'direction' })}/></Flow>`,
  unmute: (f) => `<Flow><UnMute${attrs(f, { direction: 'direction' })}/></Flow>`,
  hold: (f) => `<Flow><Hold>${f['moh-url'] && f['moh-url'] !== true ? `<StartPlay>${xt(f['moh-url'])}</StartPlay>` : ''}</Hold></Flow>`,
  unhold: () => '<Flow><UnHold/></Flow>',
  dial: (f) => `<Flow><Dial contactUri="${xa(req(f, 'to', 'number or sip: URI'))}" exophone="${xa(req(f, 'exophone', 'ExoPhone'))}"${attrs(f, { network: 'networkType', timeout: 'timeout', 'ring-timeout': 'ringTimeout', 'time-limit': 'timeLimit', 'custom-param': 'customParam', 'absorb-dtmf': 'absorbDtmf' })}/></Flow>`,
  'send-digits': (f) => `<Flow><SendDigits${attrs(f, { duration: 'duration', between: 'between' })}>${xt(req(f, 'digits', 'DTMF digits'))}</SendDigits></Flow>`,
  stream: (f) => `<Flow><StartStream streamType="${xa(f.direction && f.direction !== true ? f.direction : 'bidirectional')}" streamUrl="${xa(req(f, 'url', 'bot wss:// URL'))}"${attrs(f, { 'source-direction': 'sourceDirection', 'secure-dtmf': 'secureDtmf' })}/></Flow>`,
  'stop-stream': (f) => `<Flow><StopStream${attrs(f, { stream: 'streamSid' })}/></Flow>`,
  monitor: (f) => (f['monitor-leg'] && f['monitor-leg'] !== true
    ? `<Flow><StartMonitoring monitoringLegSid="${xa(f['monitor-leg'])}"${attrs(f, { rx: 'rxAudio' })}/></Flow>`
    : `<Flow><StartMonitoring contactUri="${xa(req(f, 'to', 'supervisor number'))}"${attrs(f, { exophone: 'exophone', rx: 'rxAudio' })}/></Flow>`),
  'stop-monitor': () => '<Flow><StopMonitoring/></Flow>',
  refer: (f) => `<Flow><Refer legEventEndpoint="${xa(req(f, 'events', 'new event endpoint'))}"/></Flow>`,
};

function leg(sub, flags) {
  const P = '/v2/accounts/{sid}/legs';
  if (sub === 'create') {
    const to = need(flags, 'to', /^(\+?\d{6,15}|sips?:\S+)$/, 'number or SIP URI');
    const body = {
      contact_uri: to,
      exophone: need(flags, 'exophone', /^(\+?\d{6,15}|sips?:\S+)$/),
      leg_event_endpoint: need(flags, 'events', EVENT_ENDPOINT, EVENT_MSG),
      network_type: opt(flags, 'network', /^(pstn|voip)$/) || (to.startsWith('sip') ? 'voip' : undefined),
      custom_param: opt(flags, 'param'),
      timeout: int(flags, 'timeout'),
      setup_timeout: int(flags, 'setup-timeout'),
      ring_timeout: int(flags, 'ring-timeout'),
      time_limit: int(flags, 'time-limit'),
      amd_enable: flags.amd || flags['async-amd'] ? true : undefined,
      async_amd: flags['async-amd'] ? true : undefined,
      reference_leg_sid: opt(flags, 'reference-leg', /^[A-Za-z0-9_-]+$/),
      requested_dc_code: opt(flags, 'dc', /^[\w -]{1,32}$/),
      requested_nso_code: opt(flags, 'nso', /^[\w -]{1,32}$/),
      priority: opt(flags, 'priority', /^(normal|high)$/),
    };
    Object.keys(body).forEach((k) => body[k] === undefined && delete body[k]);
    return run(flags, { kind: 'spend', summary: `Dial ${to} from ${body.exophone} (new call leg; events → ${body.leg_event_endpoint})`, request: { method: 'POST', host: 'cpaas', path: P, json: body } });
  }
  if (sub === 'get') {
    const id = rest.seg(flags.leg, 'leg-sid');
    const query = {};
    String(flags.include && flags.include !== true ? flags.include : '').split(',').filter(Boolean).forEach((k) => {
      if (!INCLUDE[k.trim()]) throw new CliError(`--include: unknown "${k}". Use ${Object.keys(INCLUDE).join(', ')}`, EXIT.USAGE);
      query[INCLUDE[k.trim()]] = 'true';
    });
    return run(flags, { kind: 'read', summary: `Leg ${flags.leg}`, request: { host: 'cpaas', path: `${P}/${id}`, query } });
  }
  if (sub === 'events') {
    const id = rest.seg(flags.leg, 'leg-sid');
    return run(flags, { kind: 'read', summary: `Events for leg ${flags.leg}`, request: { host: 'cpaas', path: `${P}/${id}/external_events`, query: { event_name: opt(flags, 'name', /^[a-z_]+$/) } } });
  }
  if (sub === 'inbound') {
    // Map a DID to your event endpoint so inbound calls arrive as leg_incoming events (no App Bazaar flow).
    const number = need(flags, 'number', /^\+?\d{6,15}$/, 'the DID (LP number from Exotel support)');
    const url = need(flags, 'events', /^https:\/\/\S+$/, 'your HTTPS event endpoint (https://…)');
    return run(flags, { kind: 'live', summary: `Send inbound calls on ${number} to ${url} as Legs events (replaces the number's current inbound handling)`,
      request: { method: 'POST', host: 'cpaas', path: '/v2/accounts/{sid}/callback', json: { phonenumber: number, callbackurl: url } } });
  }
  if (LEG_VERBS[sub]) {
    const xml = LEG_VERBS[sub](flags);
    return sendExoml(flags, { target: 'legs', id: rest.seg(flags.leg, 'leg-sid'), xml, label: `leg ${flags.leg}` });
  }
  if (sub === 'action') {
    return sendExoml(flags, { target: 'legs', id: rest.seg(flags.leg, 'leg-sid'), xml: readXml(flags), label: `leg ${flags.leg}` });
  }
  throw new CliError(LEG_USAGE, EXIT.USAGE);
}

function bridge(sub, flags) {
  const P = '/v2/accounts/{sid}/bridges';
  if (sub === 'create') {
    // Live API (Nov 2026): leg_sids and bridge_event_endpoint are mandatory (error 1001 otherwise).
    const legs = need(flags, 'legs', /^[A-Za-z0-9_-]+(,[A-Za-z0-9_-]+)+$/, 'two or more answered leg SIDs, comma-separated');
    const body = {
      leg_sids: legs.split(','),
      bridge_event_endpoint: need(flags, 'events', EVENT_ENDPOINT, EVENT_MSG),
      absorb_dtmf: flags['absorb-dtmf'] ? true : undefined,
      bridge_type: opt(flags, 'type', /^[a-z]+$/),
      max_participants: flags.max !== undefined ? Number(need(flags, 'max', /^\d{1,3}$/)) : undefined,
      recording: flags.record ? true : undefined,
    };
    Object.keys(body).forEach((k) => body[k] === undefined && delete body[k]);
    return run(flags, { kind: 'live', summary: `Bridge legs ${legs} into one conversation`, request: { method: 'POST', host: 'cpaas', path: P, json: body } });
  }
  if (sub === 'stop') {
    return run(flags, { kind: 'live', summary: `Stop bridge ${flags.bridge} and disconnect its legs`, request: { method: 'PUT', host: 'cpaas', path: `${P}/${rest.seg(flags.bridge, 'bridge-sid')}`, json: { action: 'stop' } } });
  }
  if (sub === 'get') return run(flags, { kind: 'read', summary: `Bridge ${flags.bridge}`, request: { host: 'cpaas', path: `${P}/${rest.seg(flags.bridge, 'bridge-sid')}` } });
  if (sub === 'update') {
    const body = {
      recording: flags.record ? true : flags['no-record'] ? false : undefined,
      max_participants: flags.max !== undefined ? Number(need(flags, 'max', /^\d{1,3}$/)) : undefined,
    };
    Object.keys(body).forEach((k) => body[k] === undefined && delete body[k]);
    if (!Object.keys(body).length) throw new CliError('Nothing to update: pass --record, --no-record or --max <n>', EXIT.USAGE);
    return run(flags, { kind: 'live', summary: `Update bridge ${flags.bridge}: ${JSON.stringify(body)}`, request: { method: 'PUT', host: 'cpaas', path: `${P}/${rest.seg(flags.bridge, 'bridge-sid')}`, json: body } });
  }
  if (sub === 'join') {
    const b = need(flags, 'bridge', /^[A-Za-z0-9_-]+$/);
    const xml = `<Flow><JoinBridge bridgeSid="${b}"${flags['absorb-dtmf'] ? ' absorbDtmf="true"' : ''}/></Flow>`;
    return sendExoml(flags, { target: 'legs', id: rest.seg(flags.leg, 'leg-sid'), xml, label: `leg ${flags.leg}` });
  }
  if (sub === 'leave') {
    return sendExoml(flags, { target: 'legs', id: rest.seg(flags.leg, 'leg-sid'), xml: '<Flow><LeaveBridge/></Flow>', label: `leg ${flags.leg}` });
  }
  if (sub === 'action') {
    return sendExoml(flags, { target: 'bridges', id: rest.seg(flags.bridge, 'bridge-sid'), xml: readXml(flags), label: `bridge ${flags.bridge}` });
  }
  if (['say', 'play', 'stop-say', 'stop-play', 'record', 'stop-record'].includes(sub)) {
    return sendExoml(flags, { target: 'bridges', id: rest.seg(flags.bridge, 'bridge-sid'), xml: LEG_VERBS[sub](flags), label: `bridge ${flags.bridge}` });
  }
  throw new CliError(LEG_USAGE, EXIT.USAGE);
}

async function eventsCmd(sub, flags) {
  const ev = require('../events');
  if (sub === 'scaffold') {
    const r = ev.scaffold(need(flags, 'lang', /^(node|python)$/, 'node or python'), flags.dir && flags.dir !== true ? flags.dir : '.', { force: !!flags.force });
    out(r, () => `${r.status === 'created' ? '✔ Created' : '• Exists (use --force)'} ${r.file}\nRun it, put it behind HTTPS, then: exotel leg create … --events https://<your-host>/`);
    return r;
  }
  if (sub === 'listen') {
    const port = Number(opt(flags, 'port', /^\d{2,5}$/) || 8080);
    const logFile = flags.log && flags.log !== true ? String(flags.log) : undefined;
    const server = await ev.listen({
      port, host: flags.host && flags.host !== true ? String(flags.host) : '127.0.0.1',
      ...ev.authFromEnv(), logFile,
      wsProxyPort: flags['ws-proxy'] ? Number(need(flags, 'ws-proxy', /^\d{2,5}$/, 'local bot port')) : undefined,
      onEvent: (r) => (require('../io').isJson() ? process.stdout.write(JSON.stringify(r) + '\n') : process.stdout.write(ev.summarize(r) + '\n')),
    });
    info(`Listening for Exotel events on http://${server.address().address}:${server.address().port}/ (acks 200 immediately).
Exotel needs a public HTTPS URL: expose this port (e.g. \`ngrok http ${port}\` or \`cloudflared tunnel --url http://localhost:${port}\`)
and pass that https URL as --events. Exotel callbacks come from ${ev.EXOTEL_SOURCE_IPS.join(', ')}.${logFile ? `\nLogging to ${logFile}` : ''}
Ctrl+C to stop.`);
    await new Promise((resolve) => {
      process.on('SIGINT', () => server.close(resolve));
      process.on('SIGTERM', () => server.close(resolve));
    });
    return null;
  }
  throw new CliError(LEG_USAGE.slice(LEG_USAGE.indexOf('Events')), EXIT.USAGE);
}

module.exports = { leg, bridge, eventsCmd, LEG_USAGE, LEG_VERBS };
