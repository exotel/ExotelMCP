'use strict';
// Put a voice bot on a call: Connect Voice AI (standard), App Bazaar Voicebot applet flow, or Legs + ExoML.
const creds = require('../credentials');
const rest = require('../rest');
const exoml = require('../exoml');
const applet = require('../applet');
const { CliError, EXIT, out, info, confirm } = require('../io');
const { requireAuth: auth, need, opt, envelope, xmlAttr: xa, xmlText: xt, run } = require('../core');

// ------------------------------------------------------------------ Connect Voice AI (the standard way to put a bot on a call)

const VOICE_AI_USAGE = `Put a voice bot on a phone call. Three ways, all over WSS:

  1. Connect Voice AI (standard, outbound, no flow):
     exotel voice-ai connect --to <E.164> --stream-url wss://your-bot/path?sample-rate=8000
  2. App Bazaar flow with a Voicebot applet (inbound + outbound; needed for ElevenLabs and Bolna):
     exotel voice-ai setup --stream-url wss://… | --platform elevenlabs|bolna     prints the WSS URL and the exact steps
     exotel voice-ai connect --to <E.164> --app-id <app_id of the flow YOU created>
  3. Legs API + ExoML <StartStream> (no flow; needs an LP-enabled API key/token + LP number from Exotel support):
     exotel voice-ai connect --via legs --exophone <LP number> --to <E.164> --stream-url wss://… --events https://<public>/events --listen <port> [--greeting "Please hold"]
     (--listen runs the event receiver: on leg_answered it sends <StartStream>; expose <port> at the --events URL.
      --ws-proxy <bot port> serves the bot's WebSocket through the same port, so one tunnel covers both.)
     Inbound, no flow: exotel voice-ai inbound --number <LP DID> --stream-url wss://… --events https://<public>/events --listen <port> [--ws-proxy <bot port>]

Your bot directly (standard):
  exotel voice-ai connect --to <E.164> --stream-url wss://your-bot/path?sample-rate=8000

Through a flow (App Bazaar flow with a Voicebot applet holding your wss URL, plus any other applets):
  exotel voice-ai connect --to <E.164> --app-id <app_id>

Options (both):  --caller-id <ExoPhone>  --record  --time-limit <sec ≤14400>  --custom-field <text>
                 --status-callback <https url>  --status-events answered,terminal,ringing  --wait
Direct only:     --recording-channels single|dual  --stream-name <label>
Flow only:       --timeout <ring seconds>  --call-type trans  --flow-url <full flow URL instead of --app-id>

Inbound calls: assign the ExoPhone to the flow (exotel numbers update --exophone-sid … --voice-url <flow url>).`;

function timeLimit(flags) {
  if (flags['time-limit'] === undefined) return undefined;
  const n = Number(need(flags, 'time-limit', /^\d{1,5}$/, 'seconds'));
  if (n < 1 || n > 14400) throw new CliError('--time-limit must be 1–14400 seconds', EXIT.USAGE);
  return String(n);
}

const PLATFORM_WSS = {
  elevenlabs: { url: (inRegion) => (inRegion ? 'wss://api.in.residency.elevenlabs.io/v1/convai/conversation/exotel' : 'wss://api.elevenlabs.io/v1/convai/conversation/exotel'),
    connect: (inRegion) => (inRegion ? 'https://api.in.residency.elevenlabs.io/v1/convai/exotel/connect-applet' : 'https://api.elevenlabs.io/v1/convai/exotel/connect-applet'),
    after: 'exotel integrate elevenlabs setup --agent <agent_id> --number <ExoPhone> --app-id <app_id>   (registers the number with ElevenLabs)' },
  bolna: { url: () => 'https://api.bolna.ai/exotel_callback   (outbound app; Bolna returns the wss URL per call)',
    inbound: 'https://api.bolna.ai/inbound_call', connect: () => 'https://api.bolna.ai/exotel_connect_transfer',
    after: 'Bolna dashboard → Settings → Integrations → Exotel: enter the outbound and inbound app ids (see exotel integrate bolna setup)' },
};

function voiceAiSetup(flags) {
  const a = creds.load() ? creds.load().auth : {};
  const inRegion = /api\.in\./.test(a.api_domain || 'https://api.in.exotel.com');
  const platform = flags.platform && flags.platform !== true ? String(flags.platform) : '';
  if (platform && !PLATFORM_WSS[platform]) throw new CliError('--platform must be elevenlabs or bolna', EXIT.USAGE);
  let url;
  if (platform) url = PLATFORM_WSS[platform].url(inRegion);
  else url = need(flags, 'stream-url', /^wss:\/\/\S{1,594}$/, 'your bot\'s wss:// URL, or pass --platform elevenlabs|bolna');
  const portal = inRegion ? 'https://my.in.exotel.com' : 'https://my.exotel.com';
  const steps = [
    `Voicebot applet URL to paste:  ${url}`,
    platform === 'bolna' ? `Inbound app Voicebot URL:      ${PLATFORM_WSS.bolna.inbound}` : null,
    '',
    'Create the flow (App Bazaar has no API, so this part is manual, about 2 minutes):',
    '  0. The Voicebot applet must be enabled on your account (Exotel support, once). If you do not see it in App Bazaar, ask support.',
    `  1. Open ${portal} → App Bazaar → Create New App. Name it, e.g. "${platform || 'my-voice-bot'}".`,
    '  2. Drag the "Voicebot" applet onto Call Start. Paste the URL above into its URL field.',
    platform ? null : '     Optional: add ?sample-rate=16000 for better audio; up to 3 custom parameters as ?key=value (256 chars).',
    platform ? `  3. Add a Connect applet after it (needed for transfers): "Configure parameters dynamically", Primary URL ${PLATFORM_WSS[platform].connect(inRegion)}` : '  3. Add a Hangup applet after it (or any other applets you want after the bot).',
    '  4. Save. Copy the app id: the number at the end of the ExoML editor URL (…/exoml/start_voice/<app_id>) or from My Apps.',
    platform === 'bolna' ? '     Repeat 1–4 for the inbound app with the inbound URL.' : null,
    '',
    'Then:',
    `  exotel voice-ai connect --to <your number> --app-id <app_id> --wait          call through the flow`,
    `  exotel voicebot attach --number <ExoPhone> --app-id <app_id>                  inbound: route the number to the flow`,
    platform ? `  ${PLATFORM_WSS[platform].after}` : null,
    '',
    'Never reuse another flow\'s app id: each flow points at one bot URL.',
  ].filter((x) => x !== null);
  return out({ platform: platform || 'own-bot', voicebotUrl: url, steps }, steps.join('\n'));
}

async function voiceAiViaLegs(flags) {
  const a = auth();
  const to = need(flags, 'to', /^\+?\d{6,15}$/, 'E.164');
  const exophone = need(flags, 'exophone', /^\+?\d{6,15}$/, 'your LP number (from Exotel support)');
  const url = need(flags, 'stream-url', /^wss:\/\/\S{1,594}$/, 'bot wss:// URL');
  const events = need(flags, 'events', /^https:\/\/\S+$/, 'public HTTPS URL of your event endpoint');
  const port = flags.listen !== undefined ? Number(need(flags, 'listen', /^\d{2,5}$/, 'local port behind the --events URL')) : null;
  const greeting = flags.greeting && flags.greeting !== true ? String(flags.greeting) : '';
  const P = '/v2/accounts/{sid}/legs';
  const streamXml = `<Flow><StartStream streamType="bidirectional" streamUrl="${xa(url)}"/></Flow>`;
  const sayXml = greeting ? `<Flow><Say>${xt(greeting)}</Say></Flow>` : null;
  const create = { method: 'POST', host: 'cpaas', path: P, json: { contact_uri: to, exophone, leg_event_endpoint: events, network_type: 'pstn', custom_param: opt(flags, 'custom-field') || 'exotel-cli', timeout: 40,
    ...(flags.dc && flags.dc !== true ? { requested_dc_code: String(flags.dc) } : {}), ...(flags.nso && flags.nso !== true ? { requested_nso_code: String(flags.nso) } : {}) } };
  const plan = [
    { when: 'now', request: rest.preview(rest.buildRequest(a, create)) },
    ...(sayXml ? [{ when: 'on leg_answered', request: { method: 'POST', url: `…${P}/<leg_sid>/actions`, body: { exoml: sayXml } } }] : []),
    { when: 'on leg_answered', request: { method: 'POST', url: `…${P}/<leg_sid>/actions`, body: { exoml: streamXml } } },
    ...(sayXml ? [{ when: 'on stream_started', request: { method: 'POST', url: `…${P}/<leg_sid>/actions`, body: { exoml: '<Flow><StopSay/></Flow>' } } }] : []),
  ];
  if (flags['dry-run']) return out({ dryRun: true, kind: 'spend', listen: port, steps: plan });
  await confirm('spend', `Call ${to} from ${exophone} (Legs API) and stream it to ${url}`, flags);

  const data = envelope;
  // Approved by the confirmation above: the call and the actions the CLI sends on its events.
  const act = (leg, xml, tag) => rest.send(rest.approve(rest.buildRequest(a, { method: 'POST', host: 'cpaas', path: `${P}/${leg}/actions`, json: { action_custom_param: tag, exoml: xml } })));
  const timeline = [];
  let server = null;
  let finish;
  const finished = new Promise((r) => { finish = r; });
  let legSid = null;
  let sayDone = false;
  if (port) {
    const ev = require('../events');
    server = await ev.listen({
      port, host: '127.0.0.1', ...ev.authFromEnv(), wsProxyPort: flags['ws-proxy'] ? Number(need(flags, 'ws-proxy', /^\d{2,5}$/)) : undefined,
      onEvent: async (rec) => {
        const e = rec.event || {};
        const d = e.data || {};
        if (legSid && d.leg_sid && d.leg_sid !== legSid && e.event_name !== 'stream_started') return;
        timeline.push({ at: rec.received_at, event: e.event_name, state: d.state, error: e.event_data && e.event_data.error });
        info(`  ${rec.received_at.slice(11, 23)} ${e.event_name}`);
        if (e.event_name === 'leg_answered' && !rec.duplicate) {
          if (sayXml) timeline.push({ action: 'Say', status: (await act(legSid, sayXml, 'greeting')).status });
          timeline.push({ action: 'StartStream', status: (await act(legSid, streamXml, 'start-stream')).status });
        }
        if (e.event_name === 'say_completed') sayDone = true;
        // Stop the greeting only if it is still playing (StopSay after say_completed returns invalid_request).
        if (e.event_name === 'stream_started' && sayXml && !sayDone && !rec.duplicate) timeline.push({ action: 'StopSay', status: (await act(legSid, '<Flow><StopSay/></Flow>', 'stop-greeting')).status });
        if (/^(leg_terminated|leg_failed_to_create)$/.test(e.event_name)) finish();
      },
    });
    info(`Event receiver on :${port} (must be reachable at ${events}).`);
  }
  const c = await rest.send(rest.approve(rest.buildRequest(a, create)));
  legSid = (data(c).data || {}).leg_sid;
  if (!c.ok || !legSid) {
    if (server) server.close();
    throw new CliError(`Create leg failed (HTTP ${c.status}${c.code ? `, code ${c.code}` : ''}). ${c.status === 401 ? 'This API key has no Legs (LP) access. Get an LP-enabled API key + token and an LP number from Exotel support (an account can have several keys; only some have Legs).' : (c.hint || c.message || '')}`, EXIT.ERROR, { data: c.data });
  }
  if (!server) {
    return out({ ok: true, leg: legSid, next: 'Your event endpoint must send these ExoML actions to POST /legs/<leg_sid>/actions', onAnswered: [sayXml, streamXml].filter(Boolean) },
      `Leg ${legSid} created. Your event endpoint (${events}) must, on leg_answered, POST to /legs/${legSid}/actions:\n${[sayXml, streamXml].filter(Boolean).join('\n')}\n(or rerun with --listen <port> to let the CLI do it)`);
  }
  info(`Leg ${legSid} created; waiting for events…`);
  let timer;
  await Promise.race([finished, new Promise((r) => { timer = setTimeout(r, Number(flags['wait-timeout'] || 300) * 1000); })]);
  clearTimeout(timer); // otherwise the process stays alive until the timeout, after the call has ended
  server.close();
  const streamed = timeline.some((t) => t.event === 'stream_started');
  out({ ok: streamed, leg: legSid, timeline }, () => `${streamed ? '✔' : '✘'} Leg ${legSid}\n` + timeline.map((t) => (t.action ? `    → ${t.action} ${t.status}` : `  ${t.at.slice(11, 23)} ${t.event}${t.error ? ` ${JSON.stringify(t.error)}` : ''}`)).join('\n'));
  if (!streamed) process.exitCode = EXIT.ERROR;
}

async function voiceAiInbound(flags) {
  // Inbound without a flow: Exotel support attaches the DID to the event endpoint (no public API); the CLI answers
  // each call and streams it to the bot.
  const a = auth();
  const number = need(flags, 'number', /^\+?\d{6,15}$/, 'your LP number (DID) from Exotel support');
  const url = need(flags, 'stream-url', /^wss:\/\/\S{1,594}$/, 'bot wss:// URL');
  const events = need(flags, 'events', /^https:\/\/\S+$/, 'public HTTPS URL of the event endpoint');
  const port = Number(need(flags, 'listen', /^\d{2,5}$/, 'local port behind the --events URL'));
  const greeting = flags.greeting && flags.greeting !== true ? String(flags.greeting) : '';
  const P = '/v2/accounts/{sid}/legs';
  const setup = require('./legs').inboundSteps(number, events);
  if (flags['dry-run']) return out({ dryRun: true, setup, onLegIncoming: '<Flow><Answer/></Flow>', onLegAnswered: `<Flow><StartStream streamType="bidirectional" streamUrl="${xa(url)}"/></Flow>` });
  info(`${setup[0]}\n${setup[1]}\n${setup[2]}`);
  await confirm('live', `While this runs, answer every call that reaches ${events} for ${number} and stream it to ${url}`, flags);
  const act = (leg, xml, tag) => rest.send(rest.approve(rest.buildRequest(a, { method: 'POST', host: 'cpaas', path: `${P}/${leg}/actions`, json: { action_custom_param: tag, exoml: xml } })));
  const handled = new Set();
  const ev = require('../events');
  const server = await ev.listen({
    port, host: '127.0.0.1', ...ev.authFromEnv(), wsProxyPort: flags['ws-proxy'] ? Number(need(flags, 'ws-proxy', /^\d{2,5}$/, 'local bot port')) : undefined,
    onEvent: async (rec) => {
      if (rec.duplicate) return;
      const e = rec.event || {};
      const leg = (e.data || {}).leg_sid;
      info(`  ${rec.received_at.slice(11, 23)} ${String(e.event_name).padEnd(20)} leg=${leg} from=${(e.data || {}).contact_uri || ''}`);
      if (e.event_name === 'leg_incoming') info(`    → Answer ${(await act(leg, '<Flow><Answer/></Flow>', 'answer')).status}`);
      if (e.event_name === 'leg_answered' && !handled.has(leg)) {
        handled.add(leg);
        if (greeting) info(`    → Say ${(await act(leg, `<Flow><Say>${xt(greeting)}</Say></Flow>`, 'greeting')).status}`);
        info(`    → StartStream ${(await act(leg, `<Flow><StartStream streamType="bidirectional" streamUrl="${xa(url)}"/></Flow>`, 'start-stream')).status}`);
      }
    },
  });
  info(`Listening on 127.0.0.1:${port} (expose it at ${events}). Once support has attached ${number}, call it; Ctrl+C to stop.`);
  await new Promise((resolve) => {
    const stop = () => server.close(resolve);
    process.on('SIGINT', stop);
    process.on('SIGTERM', stop);
    if (flags['wait-timeout']) setTimeout(stop, Number(flags['wait-timeout']) * 1000).unref();
  });
  return out({ ok: true, number, events, callsHandled: handled.size, setup }, `Stopped. Calls handled: ${handled.size}. ${number} stays attached to ${events} until Exotel support changes it.`);
}

async function voiceAi(sub, flags) {
  if (sub === 'setup') return voiceAiSetup(flags);
  if (sub === 'inbound') return voiceAiInbound(flags);
  if (sub === 'connect' && flags.via === 'legs') return voiceAiViaLegs(flags);
  if (sub !== 'connect') throw new CliError(VOICE_AI_USAGE, EXIT.USAGE);
  const a = auth();
  const to = need(flags, 'to', /^\+?\d{6,15}$/, 'E.164, e.g. +919876543210');
  const direct = flags['stream-url'] !== undefined;
  const viaFlow = flags.flow !== undefined || flags['flow-url'] !== undefined;
  if (direct === viaFlow) throw new CliError(`Say what should answer the call: --stream-url wss://… (your own bot) or --app-id <flow id> (an App Bazaar flow).\nOr just run: exotel call   (it asks you step by step)`, EXIT.USAGE);
  const callerId = opt(flags, 'caller-id', /^\+?\d{6,15}$/) || a.caller_id;
  if (!callerId) throw new CliError('Pass --caller-id (an ExoPhone on your account; see `exotel numbers list`).', EXIT.USAGE);

  const form = { From: to, CallerId: callerId };
  let target;
  if (direct) {
    const streamUrl = need(flags, 'stream-url', /^wss:\/\/\S{1,594}$/, 'wss:// URL under 600 characters (ws:// is refused: call audio must be encrypted)');
    const rate = /[?&]sample-rate=([^&]*)/.exec(streamUrl);
    if (rate && !['8000', '16000', '24000'].includes(rate[1])) throw new CliError('sample-rate must be 8000, 16000 or 24000', EXIT.USAGE);
    Object.assign(form, {
      StreamUrl: streamUrl,
      StreamType: 'bidirectional',
      RecordingChannels: opt(flags, 'recording-channels', /^(single|dual)$/, 'single or dual'),
      StreamName: opt(flags, 'stream-name', /^.{1,64}$/),
    });
    target = `your bot at ${streamUrl.replace(/\/\/[^@/]*@/, '//***@')}`;
  } else {
    const flowUrl = opt(flags, 'flow-url', /^https?:\/\/\S+\/exoml\/start_voice\/\d+$/, 'https://my.exotel.com/<sid>/exoml/start_voice/<app_id>')
      || `http://my.exotel.com/${a.account_sid}/exoml/start_voice/${need(flags, 'flow', /^\d{1,10}$/, 'numeric flow (app) id from App Bazaar → My Apps')}`;
    Object.assign(form, {
      Url: flowUrl,
      TimeOut: flags.timeout !== undefined ? need(flags, 'timeout', /^\d{1,4}$/) : undefined,
      CallType: opt(flags, 'call-type', /^trans$/, 'trans'),
    });
    target = `flow ${flowUrl.split('/').pop()}`;
  }
  Object.assign(form, {
    Record: flags.record ? 'true' : 'false',
    TimeLimit: timeLimit(flags),
    CustomField: opt(flags, 'custom-field', /^.{1,256}$/),
    StatusCallback: opt(flags, 'status-callback', /^https:\/\//, 'https URL'),
  });
  if (flags['status-events'] !== undefined) {
    const ev = need(flags, 'status-events', /^(answered|terminal|ringing)(,(answered|terminal|ringing))*$/, 'answered, terminal, ringing');
    if (!form.StatusCallback) throw new CliError('--status-events needs --status-callback', EXIT.USAGE);
    form['StatusCallbackEvents[]'] = ev.split(',');
  } else if (form.StatusCallback) form['StatusCallbackEvents[]'] = ['terminal'];

  if (!flags['dry-run']) require('../progress').info(`Placing a call to ${to} from ${callerId} → ${target}…`);
  const res = await run(flags, {
    kind: 'spend', summary: `Call ${to} from ${callerId} and connect it to ${target}`,
    request: { method: 'POST', path: '/v1/Accounts/{sid}/Calls/connect.json', form }, quiet: flags.wait && !flags['dry-run'] ? 'success' : false,
  });
  if (res.status === 403) info('403: Connect Voice AI may not be enabled on this account. Ask Exotel support to enable it.');
  if (!flags.wait || !res.ok || res.dryRun) {
    const sid = res.ok && res.data && res.data.Call && res.data.Call.Sid;
    if (sid && !flags.wait && !res.dryRun) info(`✔ Exotel accepted the call (CallSid ${sid}). Your phone should ring in a few seconds.\n  Follow it: exotel calls get --call-sid ${sid}   (or add --wait to watch it live)`);
    return res;
  }
  const callSid = res.data && res.data.Call && res.data.Call.Sid;
  if (!callSid) return out({ ok: true, data: res.data });
  require('../progress').info(`✔ Exotel accepted the call (CallSid ${callSid}). Your phone should ring in a few seconds; answer it.`);
  const progress = require('../progress');
  const r = await progress.follow(async () => {
    const x = await rest.send(rest.buildRequest(a, { path: `/v1/Accounts/{sid}/Calls/${encodeURIComponent(callSid)}.json` }));
    return { status: x.data && x.data.Call && x.data.Call.Status, data: x.data };
  }, { timeoutSec: Number(flags['wait-timeout'] || 300) });
  const c = (r.data && r.data.Call) || {};
  if (r.status !== 'completed') process.exitCode = EXIT.ERROR;
  const next = progress.nextSteps(r.status, callSid, { voicebot: !!flags.flow });
  if (c.RecordingUrl) next.unshift(`exotel calls recording --call-sid ${callSid}    download the recording`);
  out({ ok: r.status === 'completed', callSid, status: r.status, final: r.data }, () => progress.summary({ status: r.status, callSid, duration: c.Duration, next }));
  return res;
}

module.exports = { voiceAi, VOICE_AI_USAGE };
