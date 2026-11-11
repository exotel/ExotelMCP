'use strict';
// Exotel Memory: remember every contact across calls and chats, for any bot or agent.
// API contract: Confluence "Exotel Memory API" — {memory_base_url}/memory/api/v1/accounts/{memory_account_sid}/…
// Auth: Bearer <Memory API key, cm_…>, saved by `exotel memory login` next to the Exotel credentials (mode 600).
const fs = require('fs');
const creds = require('./credentials');
const rest = require('./rest');
const { CliError, EXIT, out, confirm, readSecret, info } = require('./io');
const { run, need, opt, requireAuth } = require('./core');

const USAGE = `Exotel Memory: remember every contact across calls and chats (any bot, any channel).
exotel memory login [--account <Memory account SID>] [--url https://memory.in.exotel.com]   key: prompt, EXOTEL_MEMORY_API_KEY or --key-stdin
exotel memory get --phone <number> | --profile-id <id> [--query "…"] [--audience customer]   what Memory knows
exotel memory search [--q <phone|email|id>] [--limit 20]                                    find contacts (exact match, never by name)
exotel memory forget --phone <number> | --profile-id <id>                                  delete a contact's memory (start from zero)
exotel memory save --phone <number> --id <interaction id> (--messages @turns.json | --transcript @call.txt | --audio-url https://…)
                   [--channel VOICE|CHAT|WHATSAPP|SMS|EMAIL] [--direction inbound|outbound] [--source voicebot]
exotel memory note --phone <number> --text "Offer: 20% off this week" [--agents-only]      bots see it on the very next call
exotel memory event --phone <number> --type payment|ticket_status|delivery|other --event-id <id>
                    [--amount 4500 --settled] [--status delivered] [--ref ORDER-1] [--data '{json}']
exotel voicebot memory on|off --bot-id <id> [--greeting \"Hi, …\" | --keep-greeting] [--replace-existing]   give any VoiceBot memory in one step
exotel voicebot result --call-sid <CallSid>                                               transcript + what Memory learned

Phones: +919876543210; 09876543210 / 9876543210 / 919876543210 are read as Indian numbers, so a caller's inbound
From and your outbound To always match the same contact. Notes are instant; saved interactions are learned in the background.`;

/** E.164; Indian numbers accepted as 0XXXXXXXXXX, XXXXXXXXXX or 91XXXXXXXXXX. */
function phone(v) {
  let s = String(v || '').replace(/[\s()-]/g, '');
  if (/^0\d{10}$/.test(s)) s = '+91' + s.slice(1);
  else if (/^\d{10}$/.test(s)) s = '+91' + s;
  else if (/^91\d{10}$/.test(s)) s = '+' + s;
  if (!/^\+\d{8,15}$/.test(s)) throw new CliError(`--phone "${v}" is invalid: use international format, e.g. +919876543210`, EXIT.USAGE);
  return s;
}

/** Memory roles are customer, agent, bot, system; transcripts often say user / contact / assistant. */
function role(r) {
  const x = String(r || '').trim().toLowerCase();
  if (['bot', 'assistant', 'ai'].includes(x)) return 'bot';
  if (['agent', 'human'].includes(x)) return 'agent';
  if (x === 'system') return 'system';
  return 'customer';
}

const readArg = (v) => (String(v).startsWith('@') ? fs.readFileSync(String(v).slice(1), 'utf8') : String(v));

function memoryAuth() {
  const a = requireAuth();
  if (!a.memory_api_key || !a.memory_account_sid) {
    throw new CliError('Exotel Memory is not set up. Run: exotel memory login (Memory API key cm_… and your Memory account SID).', EXIT.AUTH);
  }
  return a;
}

async function login(flags) {
  const url = flags.url && flags.url !== true ? String(flags.url).replace(/\/$/, '') : (process.env.EXOTEL_MEMORY_BASE_URL || undefined);
  // The Memory key goes only to an Exotel Memory host over HTTPS (same rule as the MCP server).
  if (url && !/^https:\/\/memory(\.[a-z0-9-]+)*\.(exotel\.(com|in)|qaexotel\.com)$/i.test(url)) {
    throw new CliError(`--url must be an Exotel Memory host, https://memory.….exotel.com (got ${url}). Nothing was saved.`, EXIT.USAGE);
  }
  const c = creds.load();
  if (!c) throw new CliError('Log in to Exotel first (exotel login), then add Memory with: exotel memory login', EXIT.AUTH);
  if (c.source === 'env') throw new CliError('Credentials come from EXOTEL_* environment variables; set EXOTEL_MEMORY_API_KEY and EXOTEL_MEMORY_ACCOUNT_SID there instead.', EXIT.USAGE);
  const account = flags.account && flags.account !== true ? String(flags.account).trim() : (process.env.EXOTEL_MEMORY_ACCOUNT_SID || c.auth.account_sid);
  if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(account || '')) throw new CliError('--account must be your Memory account SID (letters, digits, _ . : -)', EXIT.USAGE);
  const key = await readSecret({ envKey: 'EXOTEL_MEMORY_API_KEY', stdinFlag: 'key-stdin', flags, prompt: 'Exotel Memory API key (cm_…, hidden): ' });
  if (!/^cm_[A-Za-z0-9_-]{8,}$/.test(key)) throw new CliError('That does not look like a Memory API key (it starts with cm_).', EXIT.USAGE);
  const auth = { ...c.auth, memory_api_key: key, memory_account_sid: account };
  if (url) auth.memory_base_url = url; else delete auth.memory_base_url;
  // Check the key with a read that cannot change anything.
  let verified = 'skipped';
  if (!flags['no-verify']) {
    const res = await rest.send(rest.buildRequest(auth, { host: 'memory', path: '/accounts/{sid}/profiles', query: { limit: 1 } }));
    if (!res.ok) throw new CliError(`Memory rejected the key (HTTP ${res.status}${res.message ? `: ${res.message}` : ''}). Nothing was saved.`, EXIT.AUTH);
    verified = 'ok';
  }
  const file = flags['dry-run'] ? null : creds.save(auth);
  return out({ ok: true, saved: file, memory_account_sid: account, memory_base_url: rest.hosts(auth).memory.replace(/\/memory\/api\/v1$/, ''), verified },
    () => `✔ Exotel Memory added${file ? ` to ${file}` : ' (dry run: not saved)'} (account ${account}, key checked: ${verified}).\n  Give a VoiceBot memory: exotel voicebot memory on --bot-id <id>`);
}

function get(flags) {
  memoryAuth();
  const profileId = flags['profile-id'] !== undefined ? need(flags, 'profile-id', /^[A-Za-z0-9_.:-]{1,128}$/, 'profile ID from exotel memory search') : undefined;
  const p = profileId ? undefined : phone(need(flags, 'phone'));
  return run(flags, {
    kind: 'read', summary: `What Memory knows about ${p || profileId}`,
    request: { host: 'memory', path: '/accounts/{sid}/memory', query: { phone: p, profile_id: profileId, format: 'json', audience: flags.audience === 'customer' ? 'customer' : 'internal', query: opt(flags, 'query') } },
    notFoundHint: 'Memory has nothing for this contact yet. Calls are saved automatically once the bot has memory on (exotel voicebot memory on).',
    render: (d) => {
      const pr = d.profile || {};
      const lines = [`${pr.displayName || 'Unknown name'} · ${p || profileId}`];
      if (pr.summary) lines.push(pr.summary);
      for (const c of d.openCases || []) {
        lines.push(`• Open: ${c.title} (${String(c.status || '').replace(/_/g, ' ')})${c.next ? ` → next: ${c.next}` : ''}`);
      }
      for (const n of (d.notes || []).slice(0, 5)) lines.push(`📝 ${n.text}`);
      for (const r of (d.recent || []).slice(0, 3)) lines.push(`↺ ${String(r.at || '').slice(0, 10)} ${r.channel || ''}: ${r.summary || r.outcome || ''}`);
      return lines.join('\n');
    },
  });
}

function search(flags) {
  memoryAuth();
  const q = flags.q && flags.q !== true ? String(flags.q).trim() : '';
  const looksPhone = /^\+?[\d\s()-]{10,18}$/.test(q);
  return run(flags, {
    kind: 'read', summary: q ? `Find contact ${q}` : 'Recently active contacts',
    request: { host: 'memory', path: '/accounts/{sid}/profiles', query: { q: q ? (looksPhone ? phone(q) : q) : undefined, limit: Math.min(200, Number(flags.limit) || 20) } },
    render: (d) => require('./ui').table(d.profiles || [], [
      { key: 'displayName', label: 'NAME', fmt: (v) => v || '—' },
      { key: 'identifiers', label: 'CONTACT', fmt: (v) => ((v || [])[0] || {}).value || '' },
      { key: 'topCase', label: 'OPEN TOPIC', max: 40, fmt: (v) => v || '' },
      { key: 'interactions', label: 'INTERACTIONS' },
      { key: 'lastActivityAt', label: 'LAST', fmt: (v) => String(v || '').slice(0, 16).replace('T', ' ') },
    ]),
  });
}

function save(flags) {
  memoryAuth();
  const p = phone(need(flags, 'phone'));
  const id = need(flags, 'id', /^.{1,255}$/, 'your ID for this interaction, e.g. the call SID');
  const channel = String(opt(flags, 'channel') || 'VOICE').toUpperCase();
  if (!['VOICE', 'CHAT', 'WHATSAPP', 'SMS', 'EMAIL', 'VIDEO', 'SOCIAL_MEDIA'].includes(channel)) throw new CliError('--channel must be VOICE, CHAT, WHATSAPP, SMS, EMAIL, VIDEO or SOCIAL_MEDIA', EXIT.USAGE);
  const body = {
    external_interaction_id: id, channel_type: channel, source: opt(flags, 'source') || 'voicebot',
    direction: flags.direction === 'outbound' ? 'outbound' : 'inbound', contact_details: [{ type: 'phone', value: p }],
  };
  if (flags.messages && flags.messages !== true) {
    let turns;
    try { turns = JSON.parse(readArg(flags.messages)); } catch (e) { throw new CliError(`--messages is not valid JSON: ${e.message}`, EXIT.USAGE); }
    if (!Array.isArray(turns) || !turns.length) throw new CliError('--messages must be a JSON array of {"role","text"} turns', EXIT.USAGE);
    body.messages = turns.filter((t) => t && t.text).map((t) => ({ role: role(t.role), text: String(t.text) }));
  } else if (flags.transcript && flags.transcript !== true) {
    body.transcript_text = readArg(flags.transcript);
  } else if (flags['audio-url'] && flags['audio-url'] !== true) {
    body.audio_url = need(flags, 'audio-url', /^https:\/\/\S+$/, 'an https:// link to the recording');
  } else {
    throw new CliError('Give the conversation: --messages @turns.json, --transcript @call.txt or --audio-url https://…', EXIT.USAGE);
  }
  return run(flags, {
    kind: 'write', summary: `Save ${channel.toLowerCase()} interaction ${id} for ${p} to Exotel Memory`,
    request: { host: 'memory', method: 'POST', path: '/accounts/{sid}/memory', json: body },
    render: (d) => `✔ Saved (${d.status || 'queued'}); Memory learns from it in the background. Check: exotel memory get --phone ${p}`,
  });
}

function note(flags) {
  memoryAuth();
  const p = phone(need(flags, 'phone'));
  const text = need(flags, 'text', /^[\s\S]{1,1000}$/, 'a plain fact, max 1000 characters');
  const bots = !flags['agents-only'];
  return run(flags, {
    kind: 'write', summary: `Add a note for ${p}${bots ? ' (bots see it on the next call)' : ' (agents only)'}: "${text.slice(0, 80)}"`,
    request: { host: 'memory', method: 'POST', path: '/accounts/{sid}/notes', json: { contact_details: [{ type: 'phone', value: p }], text, visible_to_bots: bots } },
    render: () => `✔ Note saved. ${bots ? 'The bot sees it on its next call with this contact.' : 'Only human agents see it.'}`,
  });
}

function event(flags) {
  memoryAuth();
  const p = phone(need(flags, 'phone'));
  const type = need(flags, 'type', /^(payment|ticket_status|delivery|other)$/, 'payment, ticket_status, delivery or other');
  const body = {
    type, at: opt(flags, 'at') || new Date().toISOString().replace(/\.\d+Z$/, 'Z'), contact_details: [{ type: 'phone', value: p }],
    event_id: need(flags, 'event-id', /^[A-Za-z0-9_.:-]{1,128}$/, 'your ID; sending it again is ignored'),
  };
  if (flags.amount !== undefined) body.amount = Number(need(flags, 'amount', /^\d+(\.\d+)?$/, 'a number'));
  if (flags.settled) body.settled = true;
  if (opt(flags, 'status')) body.status = opt(flags, 'status');
  if (opt(flags, 'ref')) body.external_ref = opt(flags, 'ref');
  if (flags.data && flags.data !== true) {
    try { body.payload = JSON.parse(readArg(flags.data)); } catch (e) { throw new CliError(`--data is not valid JSON: ${e.message}`, EXIT.USAGE); }
    if (!body.payload || typeof body.payload !== 'object' || Array.isArray(body.payload)) throw new CliError('--data must be a JSON object, e.g. \'{"order":"A12"}\'', EXIT.USAGE);
  }
  return run(flags, {
    kind: 'write', summary: `Record ${type} event ${body.event_id} for ${p}`,
    request: { host: 'memory', method: 'POST', path: '/accounts/{sid}/events', json: body },
    render: () => '✔ Event recorded.',
  });
}

/** Run one Exotel MCP tool with the CLI's rules: --dry-run previews, changes ask first, output is compact. */
async function tool(name, args, flags, { kind = 'write', summary, render }) {
  const a = requireAuth();
  if (flags['dry-run']) return out({ dryRun: true, kind, summary, tool: name, arguments: args }, () => `Dry run (${kind}): ${summary}\n${name} ${JSON.stringify(args)}`);
  if (kind !== 'read') await confirm(kind, summary, flags);
  const { McpClient, unwrapResult } = require('./mcp');
  const res = unwrapResult(await require('./ui').spin(summary, new McpClient(a).callTool(name, args, { approved: true })));
  if (!res.ok) {
    process.exitCode = EXIT.ERROR;
    const unknown = /Unknown tool/i.test(res.text || '');
    return out({ ok: false, error: res.text, ...(unknown ? { hint: 'Your Exotel MCP server does not have this tool yet (needs the Memory release).' } : {}) },
      () => `✘ ${res.text}${unknown ? '\n  The Exotel MCP server does not have this tool yet (needs the Memory release).' : ''}`);
  }
  return out({ ok: true, ...(res.data && typeof res.data === 'object' ? res.data : { text: res.text }) }, () => render(res.data || {}, res.text));
}

/** `exotel voicebot memory on|off --bot-id <id>`: one step; the server sets the Memory webhooks, the prompt's memory section and the opening line. */
async function voicebot(action, flags) {
  if (!['on', 'off'].includes(action)) throw new CliError('Usage: exotel voicebot memory on|off --bot-id <id> [--greeting "Hi, this is …" | --keep-greeting] [--replace-existing]', EXIT.USAGE);
  const a = action === 'on' ? memoryAuth() : requireAuth();
  let bot = need(flags, 'bot', /^[A-Za-z0-9-]{8,64}$/, 'the VoiceBot ID (exotel voicebot list)');
  // `voicebot create` prints a generation ID first and people often copy it: resolve it only if no bot has this ID.
  const isBot = (await rest.send(rest.buildRequest(a, { host: 'voicebot', path: `/v2/accounts/{sid}/voicebots/${encodeURIComponent(bot)}` }))).ok;
  if (!isBot) {
    const realBot = await botFromGeneration(a, bot);
    if (!realBot) throw new CliError(`No VoiceBot ${bot} on this account. List your bots: exotel voicebot list`, EXIT.USAGE);
    info(`${bot} is a bot generation ID; using its bot ${realBot}.`);
    bot = realBot;
  }
  if (action === 'off') {
    return tool('exotel_voicebot_memory_disable', { voiceBotId: bot }, flags, {
      summary: `Turn memory OFF for VoiceBot ${bot} (saved memory is kept)`,
      render: () => '✔ Memory is OFF: webhooks and the prompt\'s memory section removed. Saved memory is kept.',
    });
  }
  const args = { voiceBotId: bot };
  if (flags.greeting && flags.greeting !== true) args.greeting = String(flags.greeting);
  if (flags['keep-greeting']) args.keepGreeting = true;
  if (flags['replace-existing']) args.replaceExisting = true;
  return tool('exotel_voicebot_memory_enable', args, flags, {
    summary: `Turn memory ON for VoiceBot ${bot}: Memory webhooks, a memory section in its prompt${args.keepGreeting || args.greeting ? '' : ', and its first line written with memory (fixed opening line off)'}`,
    render: (d) => [`✔ Memory is ON for ${bot}.`, `  Opening line: ${d.greeting || 'unchanged'}`,
      `  Prompt: ${d.prompt && d.prompt.changed ? `memory section added (version ${d.prompt.version}, ${d.prompt.promptLocations} places)` : (d.prompt && d.prompt.note) || 'unchanged'}`,
      '  Next: exotel voicebot call --bot-id <id> --to <number>, then exotel voicebot result --call-sid <CallSid>'].join('\n'),
  });
}

/** `exotel voicebot prompt --bot-id <id> (--file prompt.txt | --text "…")`: replace the prompt everywhere it lives. */
async function prompt(flags) {
  const bot = need(flags, 'bot', /^[A-Za-z0-9-]{8,64}$/, 'the VoiceBot ID');
  const text = flags.file && flags.file !== true ? fs.readFileSync(String(flags.file), 'utf8') : need(flags, 'text', /^[\s\S]{20,50000}$/, 'the new prompt, 20 to 50,000 characters');
  return tool('exotel_voicebot_assistant_update_prompt', { assistantId: bot, newInstruction: text, versionDescription: opt(flags, 'note') || 'Prompt updated with exotel voicebot prompt' }, flags, {
    summary: `Replace the prompt of VoiceBot ${bot} (${text.length} characters) and publish it`,
    render: (d) => `✔ Prompt published${d.version ? ` as ${d.version}` : ''}${d.promptLocations ? ` (${d.promptLocations} places)` : ''}.`,
  });
}

/** `exotel voicebot result --call-sid <CallSid>`: transcript and what Memory learned, in one step. */
async function result(flags) {
  const sid = need(flags, 'call-sid', /^[A-Za-z0-9]{8,64}$/, 'the CallSid printed by voicebot call');
  return tool('exotel_voicebot_call_result', { callSid: sid }, flags, {
    kind: 'read', summary: `Result of call ${sid}`,
    render: (d, text) => {
      if (d.found === false) return `No VoiceBot session for ${sid} yet: still running, not answered, or not indexed (retry in ~10 s).`;
      const lines = [`${d.durationSec || '?'} s · ${d.outcome || ''}${d.hungUpBy ? ` · hung up by ${d.hungUpBy}` : ''}`];
      for (const t of d.transcript || []) lines.push(`  ${t}`);
      const m = d.memory && d.memory.memory;
      if (m) lines.push('', 'What Memory knows now:', m.replace(/^<<<MEMORY[^\n]*\n/, '').trim());
      return lines.join('\n') || text;
    },
  });
}

async function botFromGeneration(a, id) {
  try {
    const { McpClient, unwrapResult } = require('./mcp');
    const r = unwrapResult(await new McpClient(a).callTool('exotel_voicebot_creation_status', { generationId: id }));
    const d = r.data && ((r.data.response && r.data.response.data) || r.data.data || r.data);
    return d && d.bot_id && d.bot_id !== id ? d.bot_id : null;
  } catch {
    return null;
  }
}

/** Deletes one contact's memory so the next call starts from zero. Cannot be undone; needs the profile:delete scope. */
async function forget(flags) {
  const auth = memoryAuth();
  let id = flags['profile-id'] !== undefined ? need(flags, 'profile-id', /^[A-Za-z0-9_.:-]{1,128}$/, 'profile ID from exotel memory search') : null;
  const who = id || phone(need(flags, 'phone'));
  if (!id) {
    const r = await rest.send(rest.buildRequest(auth, { host: 'memory', path: '/accounts/{sid}/memory', query: { phone: who, format: 'markdown', budget: 100 } }));
    if (r.status === 404) return out({ ok: true, deleted: false, note: 'Memory has nothing for this contact: it already starts from zero.' }, `Memory has nothing for ${who}: it already starts from zero.`);
    id = r.data && r.data.profileId;
    if (!r.ok || !id) throw new CliError(`Could not find ${who} in Memory (HTTP ${r.status}).`, EXIT.ERROR);
  }
  const res = await run(flags, {
    kind: 'destructive', summary: `Delete everything Memory knows about ${who} (profile ${id}). Cannot be undone`,
    request: { method: 'DELETE', host: 'memory', path: `/accounts/{sid}/profiles/${rest.seg(id, 'profile-id')}` },
    render: () => `✔ Deleted. The next call with ${who} starts from zero; memory stays on for your bots.`,
  });
  return res;
}

function cmd(sub, flags) {
  switch (sub) {
    case 'login': return login(flags);
    case 'get': return get(flags);
    case 'search': return search(flags);
    case 'save': return save(flags);
    case 'note': return note(flags);
    case 'event': return event(flags);
    case 'forget': return forget(flags);
    default: throw new CliError(USAGE, EXIT.USAGE);
  }
}

module.exports = { cmd, voicebot, prompt, result, phone, role, USAGE, info };
