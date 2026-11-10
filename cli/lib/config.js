'use strict';
// Remembered defaults so people don't retype them: your own phone number, the caller ID (ExoPhone) to show,
// and a default VoiceBot. Stored next to the credentials (no secrets here), mode 600.
const fs = require('fs');
const path = require('path');
const creds = require('./credentials');
const { CliError, EXIT, out } = require('./io');

const KEYS = {
  'my-number': { desc: 'your phone, used as --to when you call yourself', re: /^\+?\d{6,15}$/ },
  'caller-id': { desc: 'ExoPhone shown to the person you call (--caller-id)', re: /^\+?\d{6,15}$/ },
  bot: { desc: 'default VoiceBot id (--bot-id)', re: /^[\w-]{6,64}$/ },
};

const file = () => path.join(creds.configDir(), 'config.json');

function load() {
  try {
    return JSON.parse(fs.readFileSync(file(), 'utf8'));
  } catch {
    return {};
  }
}

function get(key) {
  const v = load()[key];
  if (v) return v;
  if (key === 'caller-id') {
    const c = creds.load();
    return (c && c.auth && c.auth.caller_id) || undefined;
  }
  return undefined;
}

function set(key, value) {
  if (!KEYS[key]) throw new CliError(`Unknown setting "${key}". Settings: ${Object.keys(KEYS).join(', ')}`, EXIT.USAGE);
  if (!KEYS[key].re.test(String(value))) throw new CliError(`"${value}" is not a valid ${key}`, EXIT.USAGE);
  const cfg = load();
  cfg[key] = String(value);
  fs.mkdirSync(path.dirname(file()), { recursive: true, mode: 0o700 });
  fs.writeFileSync(file(), JSON.stringify(cfg, null, 2) + '\n', { mode: 0o600 });
  return cfg;
}

function unset(key) {
  const cfg = load();
  delete cfg[key];
  fs.mkdirSync(path.dirname(file()), { recursive: true, mode: 0o700 });
  fs.writeFileSync(file(), JSON.stringify(cfg, null, 2) + '\n', { mode: 0o600 });
}

const CONFIG_USAGE = `exotel config                     show your saved defaults
exotel config set <key> <value>   e.g. exotel config set my-number +919876543210
exotel config unset <key>
Keys: ${Object.entries(KEYS).map(([k, v]) => `\n  ${k.padEnd(10)} ${v.desc}`).join('')}`;

function cmd(sub, args) {
  if (!sub || sub === 'list' || sub === 'show') {
    const rows = Object.keys(KEYS).map((k) => ({ key: k, value: get(k) || '', about: KEYS[k].desc }));
    return out({ file: file(), settings: Object.fromEntries(rows.map((r) => [r.key, r.value || null])) },
      () => `${require('./ui').table(rows, [{ key: 'key', label: 'SETTING' }, { key: 'value', label: 'VALUE' }, { key: 'about', label: 'USED FOR' }])}\nChange: exotel config set <setting> <value>`);
  }
  if (sub === 'set') {
    const [key, value] = args;
    if (!key || !value) throw new CliError(CONFIG_USAGE, EXIT.USAGE);
    set(key, value);
    return out({ ok: true, [key]: value }, `✔ Saved ${key} = ${value}`);
  }
  if (sub === 'unset') {
    if (!args[0]) throw new CliError(CONFIG_USAGE, EXIT.USAGE);
    unset(args[0]);
    return out({ ok: true, unset: args[0] }, `✔ Removed ${args[0]}`);
  }
  if (sub === 'get') return out({ [args[0]]: get(args[0]) || null }, get(args[0]) || '');
  throw new CliError(CONFIG_USAGE, EXIT.USAGE);
}

module.exports = { KEYS, get, set, unset, load, cmd, CONFIG_USAGE, file };
