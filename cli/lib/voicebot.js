'use strict';
// Exotel-hosted VoiceBot workflow: create → attach a number → test call → transcript → debug.
// Uses the Exotel MCP server for bot creation/calls and the VoiceBot / CPaaS REST APIs for lookups,
// so every step works against the live platform today.
const creds = require('./credentials');
const rest = require('./rest');
const { McpClient, unwrapResult } = require('./mcp');
const { CliError, EXIT, out, info, confirm } = require('./io');
const { requireAuth, sleep } = require('./core');

const USAGE = `Exotel-hosted VoiceBot, end to end:
  exotel voicebot create --prompt "<what the bot does, its greeting, language, rules>" [--wait]
  exotel voicebot list
  exotel voicebot attach --number <ExoPhone E.164> --app-id <app_id>     inbound: route the number to the bot's flow
  exotel voicebot test-call --bot-id <id> --to <your E.164> [--caller-id <ExoPhone>] [--wait]   $
  exotel voicebot transcript --call-sid <CallSid>   (or --session <uuid>)
  exotel voicebot debug --call-sid <CallSid>         why a call failed or went wrong`;

const auth = requireAuth;


/** Depth-first search for the first value under any of the given keys. */
function findKey(o, names, depth = 0) {
  if (!o || typeof o !== 'object' || depth > 6) return undefined;
  for (const n of names) if (o[n] !== undefined && o[n] !== null && typeof o[n] !== 'object') return o[n];
  for (const v of Object.values(o)) {
    const hit = findKey(v, names, depth + 1);
    if (hit !== undefined) return hit;
  }
  return undefined;
}

async function mcpCall(a, tool, args, flags = {}, summary = '') {
  const kind = require('./safety').classify(tool);
  if (kind !== 'safe') await confirm(kind, summary || `${tool} ${JSON.stringify(args).slice(0, 300)}`, flags);
  const res = unwrapResult(await new McpClient(a).callTool(tool, args, { approved: true }));
  if (!res.ok) throw new CliError(`${tool} failed: ${String(res.text).slice(0, 400)}`, EXIT.ERROR);
  return res.data !== undefined ? res.data : res.text;
}

// ------------------------------------------------------------------ create

async function create(flags) {
  const a = auth();
  const prompt = flags.prompt && flags.prompt !== true ? String(flags.prompt).trim() : '';
  if (prompt.length < 20) throw new CliError('Pass --prompt with a description of the bot (purpose, greeting, language, what to collect, when to end), at least 20 characters.', EXIT.USAGE);
  if (flags['dry-run']) return out({ dryRun: true, tool: 'exotel_voicebot_create', arguments: { textContent: prompt } });
  const started = await mcpCall(a, 'exotel_voicebot_create', { textContent: prompt }, flags, `Create a new Exotel VoiceBot from your prompt (${prompt.length} characters)`);
  const generationId = findKey(started, ['generation_id', 'generationId', 'id']);
  if (!generationId) throw new CliError('Bot creation started but no generation_id came back.', EXIT.ERROR, { response: started });
  if (!flags.wait) {
    return out({ ok: true, generationId, next: `exotel voicebot create --status ${generationId}` }, `Bot generation started: ${generationId}. Re-run with --wait to wait for it.`);
  }
  info(`Generating bot (${generationId})…`);
  const deadline = Date.now() + Number(flags.timeout || 240) * 1000;
  let status;
  let last;
  while (Date.now() < deadline) {
    last = await mcpCall(a, 'exotel_voicebot_creation_status', { generationId: String(generationId) });
    status = String(findKey(last, ['status', 'state']) || '').toLowerCase();
    info(`  ${status || 'unknown'}`);
    if (/completed|success|done/.test(status)) break;
    if (/fail|error/.test(status)) throw new CliError('Bot generation failed.', EXIT.ERROR, { response: last });
    await sleep(8000);
  }
  if (!/completed|success|done/.test(status)) throw new CliError(`Bot not ready after ${flags.timeout || 240}s; check later with \`exotel voicebot list\`.`, EXIT.ERROR, { generationId });
  const botId = findKey(last, ['bot_id', 'voicebot_id', 'botId', 'voiceBotId']);
  out({ ok: true, generationId, botId: botId || null, status, response: last },
    () => `✔ VoiceBot ready${botId ? `: ${botId}` : ''}\nNext: exotel voicebot test-call --bot ${botId || '<id from exotel voicebot list>'} --to <your number> --wait`);
}

// ------------------------------------------------------------------ attach a number (inbound)

async function listAllNumbers(a, maxPages = 50) {
  const seen = new Map();
  // Pages start at 1 (Page=0 repeats page 1).
  for (let page = 1; page <= maxPages; page++) {
    const r = await rest.send(rest.buildRequest(a, { path: '/v2_beta/Accounts/{sid}/IncomingPhoneNumbers', query: { Page: page, PageSize: 100 } }));
    if (!r.ok) throw new CliError(`Could not list numbers (HTTP ${r.status})`, EXIT.ERROR, { data: r.data });
    const items = (r.data && r.data.incoming_phone_numbers) || [];
    for (const n of items) if (!seen.has(n.sid)) seen.set(n.sid, n);
    if (items.length < 100) break;
  }
  return [...seen.values()];
}

function normalize(num) {
  const d = String(num).replace(/[^\d]/g, '');
  return d.length > 10 ? d.slice(-10) : d;
}

async function attach(flags) {
  const a = auth();
  const number = flags.number && flags.number !== true ? String(flags.number) : '';
  if (!/^\+?\d{6,15}$/.test(number)) throw new CliError(`Pass --number <ExoPhone in E.164>.\n\n${USAGE}`, EXIT.USAGE);
  const all = await listAllNumbers(a);
  const exo = all.find((n) => normalize(n.phone_number) === normalize(number));
  if (!exo) throw new CliError(`${number} is not an ExoPhone on this account. See \`exotel numbers list\`. New numbers: via Exotel support for now.`, EXIT.USAGE);

  const flow = flags.flow && flags.flow !== true ? String(flags.flow) : '';
  if (!/^\d{1,10}$/.test(flow)) {
    const steps = [
      'One manual step (no API for it yet), about 2 minutes:',
      `1. Open the VoiceBot console (${/\.in\./.test(a.api_domain || 'in') ? 'voicebot.in.exotel.com' : 'voicebot.sg.exotel.com'}) → Build Bot → select your bot${flags.bot ? ` (${flags.bot})` : ''} → Configuration → copy the "Voicebot URL".`,
      '2. Open my.exotel.com → App Bazaar → Create New App → drag the "VoiceBot" applet onto the canvas → paste the Voicebot URL → Save.',
      '   (Add other applets around it if you want: greeting, Gather, Passthru, Connect to an agent.)',
      '3. Note the app id (App Bazaar → My Apps), then run:',
      `   exotel voicebot attach --number ${number} --app-id <app_id>`,
    ];
    return out({ ok: false, needsFlow: true, number: exo.phone_number, exophoneSid: exo.sid, currentVoiceUrl: exo.voice_url, steps },
      () => `${exo.phone_number} currently routes to: ${exo.voice_url || '(nothing)'}\n\n${steps.join('\n')}`);
  }

  // Build the flow URL on the same host the account's numbers already use (e.g. my.mum1.exotel.com).
  const sample = all.map((n) => n.voice_url).find((u) => /\/exoml\/start_voice\/\d+$/.test(u || ''));
  const base = sample ? sample.replace(/\/exoml\/start_voice\/\d+$/, '') : `http://my.exotel.com/${a.account_sid}` /* live-verified flow URL host for all regions */;
  const voiceUrl = `${base}/exoml/start_voice/${flow}`;
  if (exo.voice_url === voiceUrl) return out({ ok: true, unchanged: true, number: exo.phone_number, voiceUrl }, `${exo.phone_number} already routes to flow ${flow}.`);

  const req = rest.buildRequest(a, { method: 'PUT', path: `/v2_beta/Accounts/{sid}/IncomingPhoneNumbers/${encodeURIComponent(exo.sid)}`, form: { VoiceUrl: voiceUrl } });
  if (flags['dry-run']) return out({ dryRun: true, kind: 'live', from: exo.voice_url, to: voiceUrl, request: rest.preview(req) });
  await confirm('live', `Route incoming calls on ${exo.phone_number} to flow ${flow} (currently ${exo.voice_url || 'nothing'})`, flags);
  const r = await rest.send(rest.approve(req));
  if (!r.ok) throw new CliError(`Update failed (HTTP ${r.status})`, EXIT.ERROR, { data: r.data });
  const check = await rest.send(rest.buildRequest(a, { path: `/v2_beta/Accounts/{sid}/IncomingPhoneNumbers/${encodeURIComponent(exo.sid)}` }));
  const now = check.data && check.data.voice_url;
  out({ ok: now === voiceUrl, number: exo.phone_number, previousVoiceUrl: exo.voice_url, voiceUrl: now },
    () => `${now === voiceUrl ? '✔' : '✘'} ${exo.phone_number} → flow ${flow}\n   ${now}\nCall ${exo.phone_number} from your phone to talk to the bot.`);
  if (now !== voiceUrl) process.exitCode = EXIT.ERROR;
}

// ------------------------------------------------------------------ transcript / debug

async function findConversation(a, callSid, { pages = 6, pageSize = 50 } = {}) {
  for (let p = 0; p < pages; p++) {
    const r = await rest.send(rest.buildRequest(a, { host: 'voicebot', path: '/v2/accounts/{sid}/voicebot-conversations', query: { limit: pageSize, offset: p * pageSize } }));
    if (!r.ok) throw new CliError(`VoiceBot conversations API returned HTTP ${r.status}`, EXIT.ERROR, { data: r.data });
    const items = Array.isArray(r.data && r.data.response) ? r.data.response : [];
    const hit = items.map((x) => x.data || x).find((x) => x && (x.call_sid === callSid || x.external_id === callSid));
    if (hit) return hit;
    if (items.length < pageSize) break;
  }
  return null;
}

async function getTranscript(a, sessionId) {
  const r = await rest.send(rest.buildRequest(a, { host: 'voicebot', path: `/v2/accounts/{sid}/voicebot-conversations/${encodeURIComponent(sessionId)}`, query: { fields: 'transcript' } }));
  if (!r.ok) throw new CliError(`Transcript lookup returned HTTP ${r.status}`, EXIT.ERROR, { data: r.data });
  const turns = [];
  const walk = (o) => {
    if (Array.isArray(o)) return o.forEach(walk);
    if (o && typeof o === 'object') {
      if (typeof o.speaker === 'string' && typeof o.text === 'string') turns.push({ speaker: o.speaker, text: o.text, start: o.start_timestamp || o.start_time || null });
      else Object.values(o).forEach(walk);
    }
  };
  walk(r.data);
  return { turns, raw: r.data };
}

const renderTurns = (turns) => (turns.length ? turns.map((t) => `${t.speaker.padStart(9)}: ${t.text}`).join('\n') : '(no speech in this session)');

async function transcript(flags) {
  const a = auth();
  let session = flags.session && flags.session !== true ? String(flags.session) : '';
  let conv = null;
  if (!session) {
    const callSid = flags['call-sid'] && flags['call-sid'] !== true ? String(flags['call-sid']) : '';
    if (!/^[A-Za-z0-9]{8,64}$/.test(callSid)) throw new CliError('Pass --call-sid <CallSid> (or --session <uuid>).', EXIT.USAGE);
    conv = await findConversation(a, callSid);
    if (!conv) throw new CliError(`No VoiceBot session for ${callSid} yet. If the call just ended, wait ~10 s and retry; if the callee never answered there is no session (see \`exotel voicebot debug\`).`, EXIT.ERROR);
    session = conv.session_id;
  }
  const t = await getTranscript(a, session);
  out({ ok: true, sessionId: session, callSid: conv ? conv.call_sid : undefined, outcome: conv ? conv.outcome : undefined, durationSec: conv ? conv.call_duration : undefined, turns: t.turns },
    () => `${conv ? `Session ${session} · ${conv.call_duration ?? '?'}s · ${conv.outcome || ''}\n` : ''}${renderTurns(t.turns)}`);
}

async function debug(flags) {
  const a = auth();
  const callSid = flags['call-sid'] && flags['call-sid'] !== true ? String(flags['call-sid']) : '';
  if (!/^[A-Za-z0-9]{8,64}$/.test(callSid)) throw new CliError('Pass --call-sid <CallSid>.', EXIT.USAGE);
  const report = { callSid, findings: [] };
  const say = (s) => report.findings.push(s);

  const cr = await rest.send(rest.buildRequest(a, { path: `/v1/Accounts/{sid}/Calls/${encodeURIComponent(callSid)}.json` }));
  const call = cr.ok && cr.data && cr.data.Call;
  report.call = call || { error: `HTTP ${cr.status}` };
  if (!call) say(`Call not found on this account (HTTP ${cr.status}). Check the CallSid and that you are logged in to the right account.`);

  let conv = null;
  try {
    conv = await findConversation(a, callSid);
  } catch (e) {
    say(`Could not read VoiceBot conversations: ${e.message}`);
  }
  report.session = conv ? {
    sessionId: conv.session_id, bot: conv.bot_name, botId: conv.bot_id, outcome: conv.outcome, disconnectedBy: conv.disconnected_by,
    durationSec: conv.call_duration, start: conv.start_time, end: conv.end_time,
  } : null;

  if (call) {
    const st = String(call.Status || '');
    if (/^(busy|no-answer)$/.test(st)) say(`The callee did not pick up (${st}). Retry, or check the number is reachable (exotel mcp call exotel_number_get_metadata …).`);
    const answered = /human|machine/i.test(String(call.AnsweredBy || ''));
    if (st === 'failed' && !conv && answered) say(`The first leg was answered (${call.AnsweredBy}) but connecting it onward failed: the number/flow it was sent to (${call.To}) did not take the call. Check that flow in App Bazaar (is a VoiceBot applet configured with a valid Voicebot URL?).`);
    if (st === 'failed' && !conv && !answered) say('The call failed before anyone answered: usually the caller ID is not an ExoPhone on this account, the number format is wrong (use E.164), DND/KYC restrictions, or low balance (exotel balance).');
    if (st === 'failed' && conv) say('Exotel marks the call failed, but the bot session exists: the bot ran; check the session outcome and transcript below.');
    if (/^(queued|in-progress|ringing)$/.test(st)) say(`The call is still ${st}. Re-run when it has ended.`);
  }
  if (conv) {
    const t = await getTranscript(a, conv.session_id);
    report.transcript = t.turns;
    if (!t.turns.length) say('The bot session has no speech: the bot never heard the caller or never spoke. Check the bot greeting / TTS voice (exotel voicebot list → details) and that the caller spoke.');
    else if (!t.turns.some((x) => x.speaker !== 'assistant')) say('Only the bot spoke: the caller was silent or not heard (ASR). Check language settings match the caller.');
    if ((conv.call_duration || 0) < 5) say(`Very short session (${conv.call_duration}s), ended by ${conv.disconnected_by || 'unknown'}.`);
    if (conv.disconnected_by && conv.disconnected_by !== 'user' && (conv.call_duration || 0) < 15) say(`The ${conv.disconnected_by} ended the call early; check the bot's end-of-call rules in its prompt.`);
  } else if (call && call.Status === 'completed') {
    say('No VoiceBot session for this call: it was not a VoiceBot call (e.g. a flow without a VoiceBot applet), or the session is still being written; retry in ~10 s.');
  }
  if (!report.findings.length) say('No problems detected: the call connected and the bot conversed. Read the transcript for behaviour issues.');

  out(report, () => [
    `Call ${callSid}: ${call ? `${call.Status} · ${call.From} → ${call.To} · ${call.StartTime} → ${call.EndTime || '…'}${call.RecordingUrl ? `\n  recording: ${call.RecordingUrl}` : ''}` : 'not found'}`,
    conv ? `Bot session ${conv.session_id} (${conv.bot_name}): ${conv.outcome || '?'} · ${conv.call_duration ?? '?'}s · ended by ${conv.disconnected_by || '?'}` : 'Bot session: none',
    report.transcript ? `Transcript:\n${renderTurns(report.transcript)}` : '',
    'Findings:',
    ...report.findings.map((f) => `  • ${f}`),
  ].filter(Boolean).join('\n'));
}

module.exports = { USAGE, create, attach, transcript, debug, findConversation, getTranscript, listAllNumbers, normalize };
