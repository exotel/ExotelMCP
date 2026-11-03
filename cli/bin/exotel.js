#!/usr/bin/env node
'use strict';
// Exotel CLI — agent-first: JSON when piped or with --json, --dry-run everywhere it matters,
// and nothing billed or destructive runs without --yes (or an interactive "y").

const fs = require('fs');
const creds = require('../lib/credentials');
const { McpClient, unwrapResult, serverUrl } = require('../lib/mcp');
const { classify } = require('../lib/safety');
const clients = require('../lib/clients');
const skills = require('../lib/skills');
const platform = require('../lib/platform');
const pkg = require('../package.json');
const { EXIT, CliError, setJson, isJson, out, info, interactive, ask, confirm: confirmKind } = require('../lib/io');

const BOOLEAN_FLAGS = new Set([
  'json', 'yes', 'y', 'dry-run', 'force', 'project', 'help', 'h', 'version', 'no-verify', 'wait', 'reveal',
  'record', 'no-skills', 'no-mcp', 'show', 'sms', 'amd', 'async-amd', 'no-record', 'key-stdin', 'offline', 'list', 'refresh', 'ai', 'details', 'absorb-dtmf', 'password-stdin', 'api-token-stdin',
]);

function parseArgs(argv) {
  const pos = [];
  const flags = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--') {
      pos.push(...argv.slice(i + 1));
      break;
    }
    if (a.startsWith('--')) {
      const eq = a.indexOf('=');
      const key = eq > 0 ? a.slice(2, eq) : a.slice(2);
      if (eq > 0) flags[key] = a.slice(eq + 1);
      else if (BOOLEAN_FLAGS.has(key)) flags[key] = true;
      else if (i + 1 < argv.length && !argv[i + 1].startsWith('--')) flags[key] = argv[++i];
      else flags[key] = true;
    } else if (a === '-y') flags.yes = true;
    else if (a === '-h') flags.help = true;
    else pos.push(a);
  }
  // Canonical Exotel-style names (--leg-sid, --app-id, …) map to the internal short keys; both spellings work.
  for (const [canonical, internal] of Object.entries(require('../lib/core').FLAG_ALIASES)) {
    if (flags[canonical] !== undefined && flags[internal] === undefined) flags[internal] = flags[canonical];
    delete flags[canonical];
  }
  return { pos, flags };
}

function confirm(summary, flags, kind = 'spend') {
  return confirmKind(kind, summary, flags);
}

function requireAuth() {
  const c = creds.load();
  if (!c) throw new CliError('Not logged in. Run `exotel login` (or set EXOTEL_ACCOUNT_SID, EXOTEL_API_KEY, EXOTEL_API_TOKEN).', EXIT.AUTH);
  return c;
}

// ---------------------------------------------------------------- commands

async function cmdLogin(flags) {
  const env = process.env;
  const get = async (flag, envKey, question, opts = {}) => {
    const v = flags[flag] || env[envKey];
    if (v) return String(v);
    if (!interactive()) {
      if (opts.optional) return opts.def || '';
      throw new CliError(`Missing --${flag} (or ${envKey}). Interactive prompts need a terminal.`, EXIT.USAGE);
    }
    return ask(question, opts);
  };

  info('Exotel login — copy Account SID, API key and API token from your dashboard → Settings → API Settings\n'
    + '  Mumbai (in): https://my.in.exotel.com/<account_sid>/apisettings/site#api-credentials\n'
    + '  Singapore (sg): https://my.exotel.com/<account_sid>/apisettings/site#api-credentials');
  const accountSid = await get('account-sid', 'EXOTEL_ACCOUNT_SID', 'Account SID');
  const apiKey = await get('api-key', 'EXOTEL_API_KEY', 'API key');
  // API token is never read from argv (it would land in shell history / process list).
  let apiToken = env.EXOTEL_API_TOKEN;
  if (flags['api-token-stdin']) apiToken = fs.readFileSync(0, 'utf8').trim();
  if (!apiToken) {
    if (!interactive()) throw new CliError('Provide the API token via EXOTEL_API_TOKEN or --api-token-stdin.', EXIT.USAGE);
    apiToken = await ask('API token', { secret: true });
  }
  const regionIn = await get('region', 'EXOTEL_REGION', 'Region: in (Mumbai) or sg (Singapore)', { def: 'in', optional: true });
  const apiDomain = flags['api-domain'] || env.EXOTEL_API_BASE_URL || creds.REGIONS[String(regionIn).toLowerCase()];
  if (!apiDomain) throw new CliError(`Unknown region "${regionIn}". Use in or sg, or pass --api-domain.`, EXIT.USAGE);
  const callerId = await get('caller-id', 'EXOTEL_CALLER_ID', 'Default caller ID / ExoPhone (E.164, optional)', { optional: true });
  const whatsappFrom = await get('whatsapp-from', 'EXOTEL_WHATSAPP_FROM', 'WhatsApp sender number (optional)', { optional: true });
  const cqaApiKey = flags['cqa-api-key'] || env.EXOTEL_CQA_API_KEY || '';
  if (!accountSid || !apiKey || !apiToken) throw new CliError('Account SID, API key and API token are required.', EXIT.USAGE);

  const auth = creds.buildAuth({
    accountSid, apiKey, apiToken, apiDomain, callerId, whatsappFrom,
    cqaApiKey, cqaAccountId: flags['cqa-account-id'] || env.EXOTEL_CQA_ACCOUNT_ID, cqaHost: flags['cqa-host'] || env.EXOTEL_CQA_HOST,
  });

  let verification = { skipped: true };
  if (!flags['no-verify']) {
    info('Checking credentials against the Exotel API…');
    try {
      verification = await creds.verify(auth);
    } catch (e) {
      verification = { ok: false, error: e.message };
    }
    if (!verification.ok && (verification.status === 401 || verification.status === 403)) {
      const said = String(verification.body || '').replace(/\s+/g, ' ').slice(0, 160);
      const portal = /api\.in\./.test(apiDomain) ? 'my.in.exotel.com' : 'my.exotel.com';
      throw new CliError(`Exotel rejected these credentials (HTTP ${verification.status})${said ? `: ${said}` : ''}\n`
        + `  • Each API key has its own token: copy both from the same row on https://${portal}/${accountSid}/apisettings/site#api-credentials\n`
        + `  • Check the region: your dashboard is ${portal} → region ${/api\.in\./.test(apiDomain) ? 'in' : 'sg'}\n`
        + '  • A new or regenerated key must be active (only account Admins can see and manage keys)', EXIT.AUTH, { verification });
    }
  }
  const file = flags['dry-run'] ? null : creds.save(auth);
  out({ ok: true, saved: file, products: creds.products(auth), verification, credentials: creds.mask(auth) },
    (d) => `${d.saved ? `Saved to ${d.saved} (mode 600).` : 'Dry run: nothing saved.'}\nProducts: ${d.products.join(', ')}\n`
      + `Verification: ${d.verification.ok ? 'OK' : d.verification.skipped ? 'skipped' : `could not confirm (${d.verification.status || d.verification.error})`}`);
  return auth;
}

function cmdLogout() {
  const removed = creds.remove();
  out({ ok: true, removed, path: creds.credentialsPath() }, removed ? `Removed ${creds.credentialsPath()}` : 'No saved credentials.');
}

function cmdWhoami() {
  const { auth, source } = requireAuth();
  out({ source, products: creds.products(auth), credentials: creds.mask(auth) },
    (d) => `Source: ${d.source}\nAccount: ${auth.account_sid} (${auth.api_domain})\nProducts: ${d.products.join(', ')}\nCaller ID: ${auth.caller_id || '-'}`);
}

async function cmdDoctor() {
  const checks = [];
  const add = (name, ok, detail) => checks.push({ name, ok, detail });
  const major = Number(process.versions.node.split('.')[0]);
  add('node>=18', major >= 18, process.versions.node);
  const c = creds.load();
  add('credentials', !!c, c ? c.source : 'run `exotel login`');
  if (c) {
    add('products', creds.products(c.auth).length > 0, creds.products(c.auth).join(', '));
    try {
      const v = await creds.verify(c.auth);
      add('exotel-api', v.ok, v.ok ? 'credentials accepted' : `HTTP ${v.status}`);
    } catch (e) {
      add('exotel-api', false, e.message);
    }
    try {
      const tools = await new McpClient(c.auth).listTools();
      add('mcp-server', tools.length > 0, `${serverUrl()} — ${tools.length} tools`);
      const names = new Set(tools.map((t) => t.name));
      for (const t of ['exotel_connect_voice_ai', 'exotel_voicebot_find_session_by_call_sid', 'exotel_events_endpoint_guide']) {
        add(`tool:${t}`, names.has(t), names.has(t) ? 'available' : 'not deployed on this server yet');
      }
    } catch (e) {
      add('mcp-server', false, e.message);
    }
  }
  add('detected-clients', true, clients.detectClients().join(', ') || 'none');
  const ok = checks.every((x) => x.ok || x.name.startsWith('tool:'));
  out({ ok, checks }, () => checks.map((x) => `${x.ok ? '✔' : '✘'} ${x.name.padEnd(48)} ${x.detail}`).join('\n'));
  if (!ok) process.exitCode = EXIT.ERROR;
}

async function cmdMcp(sub, flags) {
  if (sub === 'install') {
    const { auth } = requireAuth();
    const ids = clients.resolveClients(flags.client);
    if (!ids.length) throw new CliError('No supported agent clients detected. Pass --client claude-code|cursor|codex|vscode|windsurf|claude-desktop.', EXIT.USAGE);
    const results = clients.install(ids, { auth, url: serverUrl(), scope: flags.scope || 'user', force: !!flags.force, dryRun: !!flags['dry-run'] });
    out({ results }, () => results.map((r) => `${r.client.padEnd(15)} ${r.status}${r.file ? `  ${r.file}` : ''}${r.hint ? `  (${r.hint})` : ''}${r.error ? `  ${r.error}` : ''}${r.next && /added|replaced/.test(r.status) ? `\n${''.padEnd(16)}→ ${r.next}` : ''}`).join('\n'));
    if (results.some((r) => r.status === 'error')) process.exitCode = EXIT.ERROR;
    return results;
  }
  if (sub === 'config') {
    const { auth } = requireAuth();
    const header = flags.reveal ? JSON.stringify(auth) : JSON.stringify(creds.mask(auth));
    out({ url: serverUrl(), headers: { Authorization: header }, masked: !flags.reveal },
      `URL: ${serverUrl()}\nAuthorization: ${header}${flags.reveal ? '' : '\n(masked — pass --reveal to print real values)'}`);
    return null;
  }
  if (sub === 'clients') {
    const detected = new Set(clients.detectClients());
    const rows = Object.entries(clients.CLIENTS).map(([id, c]) => ({ id, label: c.label, detected: detected.has(id) }));
    out({ clients: rows }, () => rows.map((r) => `${r.detected ? '●' : '○'} ${r.id.padEnd(15)} ${r.label}`).join('\n'));
    return null;
  }
  throw new CliError('Usage: exotel mcp install [--client auto|all|a,b] [--scope user|project|local] [--force] [--dry-run] | mcp config [--reveal] | mcp clients', EXIT.USAGE);
}

function cmdSkill(sub, names, flags) {
  if (sub === 'list' || !sub) {
    const all = skills.list();
    out({ skills: all.map(({ name, description }) => ({ name, description })) },
      () => all.map((s) => `${s.name}\n  ${s.description}`).join('\n\n'));
    return null;
  }
  if (sub === 'install') {
    const agents = flags.dir ? [] : skills.resolveAgents(flags.agent);
    if (!flags.dir && !agents.length) throw new CliError('No agents detected. Pass --agent claude|codex|cursor or --dir <path>.', EXIT.USAGE);
    const results = skills.install(names, { agents, dir: flags.dir, project: !!flags.project, force: !!flags.force, dryRun: !!flags['dry-run'] });
    out({ results }, () => results.map((r) => `${r.agent.padEnd(7)} ${r.status.padEnd(14)} ${r.path}`).join('\n')
      + (results.some((r) => r.status === 'exists') ? '\n(existing skills kept — pass --force to overwrite)' : ''));
    return results;
  }
  throw new CliError('Usage: exotel skill list | skill install [all|<name>…] [--agent auto|all|claude,codex,cursor] [--project] [--dir <path>] [--force] [--dry-run]', EXIT.USAGE);
}

async function getTools(client) {
  return client.listTools();
}

async function cmdTools(flags) {
  const { auth } = requireAuth();
  const tools = await getTools(new McpClient(auth));
  const f = (flags.filter || '').toLowerCase();
  const rows = tools.filter((t) => !f || t.name.toLowerCase().includes(f) || (t.description || '').toLowerCase().includes(f))
    .map((t) => ({ name: t.name, safety: classify(t.name), description: t.description }));
  out({ count: rows.length, tools: rows }, () => rows.map((r) => `${r.safety === 'safe' ? ' ' : r.safety === 'spend' ? '$' : '!'} ${r.name}`).join('\n')
    + `\n\n${rows.length} tools ($ = billed / contacts a person, ! = destructive). Details: exotel mcp tool <name>`);
}

async function cmdTool(name) {
  if (!name) throw new CliError('Usage: exotel tool <name>', EXIT.USAGE);
  const { auth } = requireAuth();
  const t = (await getTools(new McpClient(auth))).find((x) => x.name === name);
  if (!t) throw new CliError(`No tool named ${name}. See: exotel mcp tools --filter <text>`);
  out({ ...t, safety: classify(t.name) }, () => `${t.name}  [${classify(t.name)}]\n\n${t.description}\n\nParameters:\n`
    + Object.entries((t.inputSchema && t.inputSchema.properties) || {}).map(([k, v]) => `  ${k} (${v.type})${(t.inputSchema.required || []).includes(k) ? '' : ' optional'}${v.description && v.description !== 'String parameter' ? ` — ${v.description}` : ''}`).join('\n'));
}

function parseToolArgs(pairs, flags) {
  let args = {};
  if (flags.args) {
    const raw = String(flags.args).startsWith('@') ? fs.readFileSync(String(flags.args).slice(1), 'utf8') : String(flags.args);
    try {
      args = JSON.parse(raw);
    } catch (e) {
      throw new CliError(`--args is not valid JSON: ${e.message}`, EXIT.USAGE);
    }
  }
  for (const p of pairs) {
    const i = p.indexOf('=');
    if (i <= 0) throw new CliError(`Expected key=value, got "${p}"`, EXIT.USAGE);
    args[p.slice(0, i)] = p.slice(i + 1);
  }
  return args;
}

/** Coerce values to the tool's schema types and send "" for absent string params the server marks required. */
function shapeArgs(tool, args) {
  const props = (tool.inputSchema && tool.inputSchema.properties) || {};
  const shaped = { ...args };
  const filled = [];
  for (const [k, v] of Object.entries(shaped)) {
    const type = props[k] && props[k].type;
    if (typeof v !== 'string') continue;
    if (type === 'boolean') shaped[k] = /^(true|1|yes)$/i.test(v);
    else if (type === 'number' || type === 'integer') shaped[k] = Number(v);
    else if (type === 'array' || type === 'object') {
      try {
        shaped[k] = JSON.parse(v);
      } catch {
        if (type === 'array') shaped[k] = v.split(',');
      }
    }
  }
  const unknown = Object.keys(shaped).filter((k) => !props[k]);
  if (unknown.length && Object.keys(props).length) {
    throw new CliError(`Unknown parameter(s) for ${tool.name}: ${unknown.join(', ')}. Expected: ${Object.keys(props).join(', ')}`, EXIT.USAGE);
  }
  for (const k of (tool.inputSchema && tool.inputSchema.required) || []) {
    if (shaped[k] === undefined) {
      const type = props[k] && props[k].type;
      shaped[k] = type === 'boolean' ? false : '';
      filled.push(k);
    }
  }
  return { args: shaped, filled };
}

async function runTool(name, rawArgs, flags, { quiet = false } = {}) {
  const { auth } = requireAuth();
  const client = new McpClient(auth);
  const tool = (await getTools(client)).find((t) => t.name === name);
  if (!tool) throw new CliError(`No tool named ${name} on ${serverUrl()}. See: exotel mcp tools --filter <text>`, EXIT.USAGE);
  const { args, filled } = shapeArgs(tool, rawArgs);
  const safety = classify(name);
  if (flags['dry-run']) {
    const preview = { dryRun: true, tool: name, safety, arguments: args, defaultedToEmpty: filled, server: serverUrl() };
    if (!quiet) out(preview);
    return { dryRun: true, preview };
  }
  if (safety !== 'safe') {
    await confirm(`${name} ${JSON.stringify(args)}`, flags, safety);
  }
  const res = unwrapResult(await client.callTool(name, args));
  if (!quiet) {
    out({ ok: res.ok, tool: name, result: res.data !== undefined ? res.data : res.text, defaultedToEmpty: filled }, () => (res.data !== undefined ? JSON.stringify(res.data, null, 2) : res.text));
  }
  if (!res.ok) process.exitCode = EXIT.ERROR;
  return res;
}

function findCallSid(data) {
  if (!data || typeof data !== 'object') return null;
  for (const [k, v] of Object.entries(data)) {
    if (/^(call_?sid|sid)$/i.test(k) && typeof v === 'string') return v;
    if (v && typeof v === 'object') {
      const hit = findCallSid(v);
      if (hit) return hit;
    }
  }
  return null;
}

const TERMINAL = /^(completed|failed|busy|no-answer|canceled|cancelled)$/i;

function findStatus(data) {
  if (!data || typeof data !== 'object') return null;
  for (const [k, v] of Object.entries(data)) {
    if (/^status$/i.test(k) && typeof v === 'string') return v;
    if (v && typeof v === 'object') {
      const s = findStatus(v);
      if (s) return s;
    }
  }
  return null;
}

async function waitForCall(callSid, statusTool, flags) {
  const deadline = Date.now() + Number(flags.timeout || 300) * 1000;
  let last = null;
  while (Date.now() < deadline) {
    const res = await runTool(statusTool, { callSid }, { ...flags, 'dry-run': false }, { quiet: true });
    last = res.data !== undefined ? res.data : res.text;
    const status = findStatus(res.data);
    info(`  status: ${status || 'unknown'}`);
    if (status && TERMINAL.test(status)) return { status, details: last };
    await new Promise((r) => setTimeout(r, 10000));
  }
  return { status: 'timeout', details: last };
}

async function placeAndMaybeWait(tool, args, flags, statusTool) {
  const res = await runTool(tool, args, flags, { quiet: true });
  if (res.dryRun) return out(res.preview);
  const callSid = findCallSid(res.data);
  const result = { ok: res.ok, tool, callSid, response: res.data !== undefined ? res.data : res.text };
  if (res.ok && callSid && flags.wait) {
    info(`Call placed: ${callSid}. Waiting for it to finish…`);
    result.final = await waitForCall(callSid, statusTool, flags);
  }
  out(result, (d) => `${d.ok ? 'Call placed' : 'Call failed'}${d.callSid ? `: ${d.callSid}` : ''}${d.final ? `\nFinal status: ${d.final.status}` : ''}${d.ok ? '' : `\n${typeof d.response === 'string' ? d.response : JSON.stringify(d.response, null, 2)}`}`);
}

async function cmdVoicebot(sub, flags) {
  if (sub === 'list') return runTool('exotel_voicebot_list_all', { status: flags.status || '', limit: String(flags.limit || 20), offset: String(flags.offset || 0) }, flags);
  if (sub === 'test-call') {
    if (!flags.bot || !flags.to) throw new CliError('Usage: exotel voicebot test-call --bot-id <voiceBotId> --to <E.164> [--caller-id <ExoPhone>] [--record] [--wait] [--yes]', EXIT.USAGE);
    const { auth } = requireAuth();
    const callerId = flags['caller-id'] || auth.caller_id;
    if (!callerId) throw new CliError('Pass --caller-id (an ExoPhone on the account; see `exotel numbers list`).', EXIT.USAGE);
    return placeAndMaybeWait('exotel_voicebot_place_call', {
      toNumber: flags.to, voiceBotId: flags.bot, callerId, customField: '', record: !!flags.record, statusCallback: '',
    }, flags, 'exotel_voicebot_call_status');
  }
  const vb = require('../lib/voicebot');
  if (sub === 'create') return vb.create(flags);
  if (sub === 'attach') return vb.attach(flags);
  if (sub === 'transcript') return vb.transcript(flags);
  if (sub === 'debug') return vb.debug(flags);
  throw new CliError(require('../lib/voicebot').USAGE, EXIT.USAGE);
}

async function cmdInit(flags) {
  const steps = {};
  let c = creds.load();
  if (!c || flags.force) {
    const auth = await cmdLogin(flags);
    c = { auth, source: flags['dry-run'] ? 'dry-run' : creds.credentialsPath() };
  } else if (c.source === 'env') {
    // init must leave the developer logged in: persist credentials that came from environment variables.
    const file = flags['dry-run'] ? null : creds.save(c.auth);
    info(`Saved credentials from EXOTEL_* environment variables${file ? ` to ${file}` : ' (dry run: not saved)'}.`);
    c = { auth: c.auth, source: file || 'env' };
  } else {
    info(`Using saved credentials (${c.source}). Run \`exotel login\` to change them.`);
  }
  steps.credentials = { source: c.source, products: creds.products(c.auth) };
  if (!flags['no-mcp']) {
    const ids = clients.resolveClients(flags.client);
    steps.mcp = ids.length ? clients.install(ids, { auth: c.auth, url: serverUrl(), scope: flags.scope || 'user', force: !!flags.force, dryRun: !!flags['dry-run'] }) : [];
  }
  if (!flags['no-skills']) {
    const agents = skills.resolveAgents(flags.agent);
    steps.skills = agents.length ? skills.install([], { agents, project: !!flags.project, force: !!flags.force, dryRun: !!flags['dry-run'] }) : [];
  }
  out({ ok: true, ...steps }, () => [
    `✔ Credentials: ${steps.credentials.products.join(', ')}`,
    steps.mcp ? (steps.mcp.length ? steps.mcp.map((r) => `${/added|replaced|would/.test(r.status) ? '✔' : r.status === 'exists' ? '•' : '✘'} MCP → ${r.client}: ${r.status}${r.hint ? ` (${r.hint})` : ''}${r.error ? ` — ${r.error}` : ''}`).join('\n') : '• MCP: no agent clients detected (use --client)') : null,
    steps.skills ? `✔ Skills: ${steps.skills.filter((r) => /installed|would/.test(r.status)).length} installed, ${steps.skills.filter((r) => r.status === 'exists').length} already present` : null,
    '',
    'Next steps:',
    '  1. Start your coding agent in your project folder (for Claude Code: type `claude` and press Enter).',
    '     Already running? Quit it and start it again so it picks up Exotel.',
    '  2. Inside the agent, check Exotel shows "connected" (Claude Code: type /mcp inside it, not in this shell).',
    '  3. Ask it in plain words, for example:',
    '     "What is my Exotel balance and which phone numbers do I have?"',
    '     "Create an Exotel VoiceBot that confirms tomorrow\'s appointment, then test-call +91XXXXXXXXXX"',
    '  Prefer the terminal? Try: exotel balance · exotel numbers list · exotel ask "connect my bot to a call"',
  ].filter((x) => x !== null).join('\n'));
}

const HELP = `exotel ${pkg.version} — Exotel from the terminal, built for coding agents

NOT SURE WHICH API?
  exotel ask "connect my OpenAI bot to a phone call"     recommends the API, the command and the gotchas
                                       [--ai]          also ask the developer portal's AI box (logs your question)
  exotel docs search <words> | docs show <url>           search/read developer.exotel.com + docs.exotel.com
  exotel explain <code|word> | explain --list            what a status / error code means (e.g. 408, 1012, no-answer, 23005)

GET STARTED
  exotel init                 log in, connect your coding agents (MCP), install Exotel skills
  exotel login | logout | whoami | doctor
  exotel balance              account balance

CALLS, RECORDINGS, CAMPAIGNS  → exotel calls --help, exotel campaigns --help
  exotel calls list [--since 2026-11-01 --status completed …] | get --call-sid <sid> [--details] | recording --call-sid <sid>
  exotel campaigns list | get | calls | lists | create … $ | pause | resume ~ | complete | archive | delete !

PHONE NUMBERS (ExoPhones)
  exotel numbers list | get --exophone-sid <sid>
  exotel numbers available [--type landline|mobile|tollfree --region KA]   search numbers you can buy
  exotel numbers buy --number <number> [--app-id <flow>]               $  one-time + monthly charges
  exotel numbers update --exophone-sid <sid> --app-id <flow>           ~  changes live routing
  exotel numbers release --exophone-sid <sid>                          !  permanent

SIP TRUNKING (Dynamic SIP trunks)
  exotel trunk setup --name <n> --number <E.164> --ip <IPv4> --uri <host:port>   create + configure in one go
  exotel trunk create | map-number | set-number-mode | whitelist-ip | destination | caller-id | delete
  exotel trunk credentials add | list | delete
  → exotel trunk --help for every flag

AI VOICE  → exotel voice-ai --help
  exotel voice-ai connect --to <E.164> --stream-url wss://…   $   call a number, connect it to YOUR bot (Connect Voice AI)
  exotel voice-ai connect --to <E.164> --app-id <app_id>        $   …or to an App Bazaar flow with a Voicebot applet
  exotel voicebot create | attach | test-call | transcript | debug   Exotel-hosted VoiceBots → exotel voicebot --help

VOICE-AI PLATFORMS (already connected to Exotel)  → exotel integrate --help
  exotel integrate elevenlabs|bolna status | setup | call --agent <id> --to <E.164> [--wait] | result --id <id>

CALL CONTROL (Legs API + ExoML; needs enablement by Exotel support)  → exotel leg --help
  exotel leg create --to <num|sip:> --exophone <num> --events <https://…>  $
  exotel leg get | events | action --leg-sid <sid> --file flow.xml
  exotel bridge create --legs <a,b> --events <url> | get | update | stop | join | leave | action
  exotel events listen [--port 8080]      receive live call events locally (acks within Exotel's 1 s)
  exotel events scaffold --lang node|python   starter HTTPS event endpoint
  exotel exoml validate <flow.xml>         check ExoML before sending it

FLOW WEBHOOKS (your Dynamic URLs)
  exotel applet test connect|gather|dial|passthru|stream <https URL>   call it like Exotel does, check the reply
  exotel applet scaffold connect|gather|passthru|dial|stream [--lang node|python]   starter Dynamic URL handler

EVERYTHING ELSE
  exotel api <GET|POST|PUT|DELETE> </path/with/{sid}> [--host api|cpaas] [--query …] [--data '{json}'] [--form …]
  exotel mcp tools [--filter text] | mcp tool <name> | mcp call <tool> key=value…   (VoiceBot, SMS, WhatsApp, CQA, AI Assist…)
  exotel mcp install | config | clients
  exotel skill list | install

  $ = billed or rings a real phone   ~ = changes live routing   ! = cannot be undone
  These need --yes (or a "y" at the prompt). Without it the CLI stops with exit code 3.

FLAGS   --json  --dry-run  --yes/-y  --force
EXIT    0 ok · 1 error · 2 usage · 3 confirmation needed · 4 not logged in
ENV     EXOTEL_ACCOUNT_SID EXOTEL_API_KEY EXOTEL_API_TOKEN EXOTEL_API_BASE_URL EXOTEL_CALLER_ID
        EXOTEL_AUTH_JSON EXOTEL_MCP_URL EXOTEL_CONFIG_DIR`;

const SUBHELP = {
  numbers: platform.USAGE.NUMBERS_USAGE,
  trunk: platform.USAGE.TRUNK_USAGE,
  leg: platform.USAGE.LEG_USAGE,
  bridge: platform.USAGE.LEG_USAGE,
  events: platform.USAGE.LEG_USAGE,
  'voice-ai': platform.USAGE.VOICE_AI_USAGE,
  calls: platform.USAGE.CALLS_USAGE,
  campaigns: platform.USAGE.CAMPAIGNS_USAGE,
  voicebot: require('../lib/voicebot').USAGE,
  integrate: require('../lib/integrations').USAGE,
  voiceai: platform.USAGE.VOICE_AI_USAGE,
};

async function main(argv) {
  const { pos, flags } = parseArgs(argv);
  if (flags.json) setJson(true);
  const [cmd, sub, ...rest] = pos;
  if (flags.version) return out({ version: pkg.version }, pkg.version);
  // Help is always plain text, piped or not. `exotel <cmd> --help` and `exotel help <cmd>` both work.
  const helpFor = cmd === 'help' ? sub : (flags.help ? cmd : null);
  if (helpFor) {
    const lines = HELP.split('\n').filter((l) => new RegExp(`exotel ${helpFor}\\b`).test(l));
    const text = SUBHELP[helpFor] || (lines.length ? lines.map((l) => l.trim()).join('\n') : null);
    if (text) return void process.stdout.write(`${text}\n`);
  }
  if (!cmd || flags.help || cmd === 'help') return void process.stdout.write(HELP + '\n');
  switch (cmd) {
    case 'init': return cmdInit(flags);
    case 'login': return cmdLogin(flags);
    case 'logout': return cmdLogout();
    case 'whoami': return cmdWhoami();
    case 'doctor': return cmdDoctor();
    case 'mcp':
      if (sub === 'tools') return cmdTools(flags);
      if (sub === 'tool') return cmdTool(rest[0]);
      if (sub === 'call') {
        if (!rest[0]) throw new CliError('Usage: exotel mcp call <tool> [key=value…]', EXIT.USAGE);
        return runTool(rest[0], parseToolArgs(rest.slice(1), flags), flags);
      }
      return cmdMcp(sub, flags);
    case 'skill':
    case 'skills': return cmdSkill(sub, rest, flags);
    case 'balance': return platform.balance(flags);
    case 'calls': return platform.calls(sub, flags);
    case 'campaigns':
    case 'campaign': return platform.campaigns(sub, flags);
    case 'numbers': return platform.numbers(sub || 'list', flags);
    case 'trunk': return platform.trunk(sub, rest, flags);
    case 'leg': return platform.leg(sub, flags);
    case 'bridge': return platform.bridge(sub, flags);
    case 'events': return platform.eventsCmd(sub, flags);
    case 'exoml': return platform.exomlCmd(sub, rest, flags);
    case 'applet': return platform.appletCmd(sub, rest, flags);
    case 'voice-ai':
    case 'voiceai': return platform.voiceAi(sub, flags);
    case 'agentstream':
      throw new CliError('"exotel agentstream call" is now "exotel voice-ai connect" (Connect Voice AI API). Run: exotel voice-ai --help', EXIT.USAGE);
    case 'api': return platform.api([sub, ...rest], flags);
    case 'voicebot': return cmdVoicebot(sub, flags);
    case 'integrate': return require('../lib/integrations').run(sub, rest[0], flags);
    case 'ask': return require('../lib/ask').ask([sub, ...rest].filter(Boolean).join(' '), flags);
    case 'explain': {
      const ref = require('../lib/reference');
      const rows = await ref.load({ refresh: !!flags.refresh, offline: !!flags.offline });
      const term = [sub, ...rest].filter(Boolean).join(' ');
      if (flags.list || !term) {
        const fam = ref.families(rows);
        return out({ families: fam }, () => fam.map((f) => `${f.family}\n  ${f.values.join(', ')}\n  ${f.url || ''}`).join('\n\n') + '\n\nDetails: exotel explain <code or word>');
      }
      const hits = ref.lookup(rows, term, { limit: Number(flags.limit || 25) });
      return out({ term, matches: hits.map((h) => ({ area: h.heading, page: h.page, url: h.url, source: h.source, fields: h.header && h.header.length === h.cells.length ? Object.fromEntries(h.header.map((k, i) => [k, h.cells[i]])) : h.cells })) },
        () => (hits.length ? `${hits.length} match(es) for "${term}":\n\n${hits.map((h) => `• ${ref.formatRow(h)}`).join('\n')}` : `No status/error code "${term}" in the docs reference. Try: exotel docs search ${term}`));
    }
    case 'docs': {
      const d = require('../lib/docs');
      if (sub === 'search') {
        const q = rest.join(' ');
        if (!q) throw new CliError('Usage: exotel docs search <words>   |   exotel docs show <url>   |   exotel docs refresh', EXIT.USAGE);
        const pages = d.search(await d.loadIndex(), q, { limit: Number(flags.limit || 10) });
        return out({ query: q, results: pages }, () => (pages.length ? pages.map((p) => `${p.title}  (${p.source})\n  ${p.url}${p.description ? `\n  ${p.description.slice(0, 160)}` : ''}`).join('\n') : 'No matches. Try other words.'));
      }
      if (sub === 'show') {
        if (!rest[0]) throw new CliError('Usage: exotel docs show <developer.exotel.com or docs.exotel.com URL>', EXIT.USAGE);
        const r = await d.show(rest[0], { maxChars: Number(flags['max-chars'] || 20000) });
        return out(r, r.text);
      }
      if (sub === 'refresh') {
        const pages = await d.loadIndex({ refresh: true });
        return out({ pages: pages.length, cache: d.cacheFile() }, `Indexed ${pages.length} pages from developer.exotel.com and docs.exotel.com → ${d.cacheFile()}`);
      }
      throw new CliError('Usage: exotel docs search <words>   |   exotel docs show <url>   |   exotel docs refresh', EXIT.USAGE);
    }
    case 'tools':
    case 'tool':
    case 'call':
      throw new CliError(`"exotel ${cmd}" moved to "exotel mcp ${cmd}" (it runs an MCP tool; it does not place a phone call).`, EXIT.USAGE);
    default: {
      const clean = String(cmd).replace(/[.,;:!?'"`]+$/, '');
      const hint = clean !== cmd ? ` Did you mean "exotel ${clean}"? (a punctuation mark was copied with it)` : ' Run: exotel --help';
      throw new CliError(`Unknown command "${cmd}".${hint}`, EXIT.USAGE);
    }
  }
}

if (require.main === module) {
  main(process.argv.slice(2)).catch((e) => {
    const code = e instanceof CliError ? e.code : EXIT.ERROR;
    if (isJson()) process.stdout.write(JSON.stringify({ ok: false, error: e.message, code, ...(e.extra || {}) }, null, 2) + '\n');
    else process.stderr.write(`exotel: ${e.message}\n`);
    process.exit(code);
  });
}

module.exports = { parseArgs, shapeArgs, parseToolArgs, findCallSid, findStatus, main };
