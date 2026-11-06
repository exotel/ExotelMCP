'use strict';
// Guided inputs: when a value is missing, ask for it (pick from a list where we can) instead of failing,
// and remember the answers. Non-interactive runs (agents, scripts, pipes) never prompt: they get an error
// that names the exact flag, or use a saved default.
const rest = require('./rest');
const cfg = require('./config');
const ui = require('./ui');
const { CliError, EXIT, ask, interactive } = require('./io');
const { requireAuth } = require('./core');

const PHONE = /^\+?\d{6,15}$/;

async function offerSave(key, value, label) {
  if (cfg.get(key) === value) return;
  const a = await ask(`Save ${value} as your ${label} for next time? (Y/n)`, { def: 'y' });
  if (!/^n/i.test(a)) {
    cfg.set(key, value);
    process.stderr.write(ui.c.dim(`  saved: exotel config set ${key} ${value}\n`, process.stderr));
  }
}

/** --to, else your saved number, else ask. */
async function to(flags) {
  if (flags.to && flags.to !== true) return String(flags.to).replace(/\s+/g, '');
  const saved = cfg.get('my-number');
  if (!interactive()) {
    if (saved) return saved;
    throw new CliError('Pass --to <phone number, e.g. +919876543210>, or save yours once: exotel config set my-number +91…', EXIT.USAGE);
  }
  for (;;) {
    const a = (await ask('Which phone should we call? (e.g. +919876543210)', { def: saved })).replace(/\s+/g, '');
    if (PHONE.test(a)) {
      await offerSave('my-number', a, 'phone number');
      return a;
    }
    process.stderr.write(ui.c.yellow('  That does not look like a phone number. Use digits, optionally starting with +.\n', process.stderr));
  }
}

/** --caller-id, else saved, else pick one of your ExoPhones. */
async function callerId(flags) {
  if (flags['caller-id'] && flags['caller-id'] !== true) return String(flags['caller-id']);
  const saved = cfg.get('caller-id');
  if (saved) return saved;
  if (!interactive()) throw new CliError('Pass --caller-id <one of your ExoPhones> (see: exotel numbers list), or save one: exotel config set caller-id <number>', EXIT.USAGE);
  const a = requireAuth();
  const r = await ui.spin('Loading your ExoPhones…', rest.send(rest.buildRequest(a, { path: '/v2_beta/Accounts/{sid}/IncomingPhoneNumbers', query: { Page: 1, PageSize: 100 } })));
  const nums = (r.ok && r.data && r.data.incoming_phone_numbers) || [];
  const v = await ui.select('Which of your Exotel numbers should the call come from? (caller ID)',
    nums.map((n) => ({ label: n.phone_number, value: n.phone_number, hint: n.friendly_name && n.friendly_name !== n.phone_number ? n.friendly_name : '' })),
    { allowCustom: true, customLabel: 'type a number' });
  await offerSave('caller-id', v, 'caller ID');
  return v;
}

/** --bot-id (id or name), else saved, else pick from your VoiceBots. listBots() returns [{id,name,status,date_created}]. */
async function bot(flags, listBots) {
  const given = flags.bot && flags.bot !== true ? String(flags.bot) : '';
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(given)) return given;
  const saved = cfg.get('bot');
  if (!given && saved && !interactive()) return saved;
  if (!given && !interactive()) throw new CliError('Pass --bot-id <VoiceBot id or name> (see: exotel voicebot list), or save one: exotel config set bot <id>', EXIT.USAGE);
  const bots = (await ui.spin('Loading your VoiceBots…', listBots())).sort((x, y) => String(y.date_created || '').localeCompare(String(x.date_created || '')));
  if (given) {
    const hits = bots.filter((b) => String(b.name || '').toLowerCase() === given.toLowerCase() || String(b.id).startsWith(given));
    if (hits.length === 1) { flags._botName = hits[0].name; return hits[0].id; }
    if (!hits.length) throw new CliError(`No VoiceBot called "${given}". See them with: exotel voicebot list`, EXIT.USAGE);
    if (!interactive()) throw new CliError(`"${given}" matches ${hits.length} VoiceBots; pass the id. See: exotel voicebot list`, EXIT.USAGE);
  }
  if (!bots.length) throw new CliError('You have no VoiceBots yet. Create one: exotel voicebot create --prompt "what the bot should do" --wait', EXIT.USAGE);
  const items = bots.map((b) => ({ label: b.name || b.id, value: b.id, hint: `${b.status || ''}${b.date_created ? ` · created ${String(b.date_created).slice(0, 10)}` : ''}` }));
  const def = Math.max(0, items.findIndex((i) => i.value === saved));
  const v = await ui.select('Which VoiceBot should talk on the call?', items, { def });
  flags._botName = (bots.find((x) => x.id === v) || {}).name;
  if (v !== saved) {
    const a = await ask('Use this bot by default next time? (y/N)', { def: 'n' });
    if (/^y/i.test(a)) cfg.set('bot', v);
  }
  return v;
}

module.exports = { to, callerId, bot };
