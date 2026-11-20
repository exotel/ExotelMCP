'use strict';
// Catch wrong input before anything runs: unknown subcommands get "did you mean" plus the valid list, and
// unknown flags stop the command. A silently ignored typo of the dry-run flag could otherwise place a
// real, billed call. Known flags are collected from the CLI's own source, so new flags never need registering.
const fs = require('fs');
const path = require('path');
const { CliError, EXIT } = require('./io');
const { suggest } = require('./ui');

const SUBCOMMANDS = {
  numbers: ['list', 'get', 'available', 'buy', 'update', 'release'],
  calls: ['list', 'get', 'recording'],
  campaigns: ['list', 'get', 'calls', 'lists', 'create', 'pause', 'resume', 'complete', 'archive', 'delete'],
  campaign: null, // alias of campaigns, checked through it
  voicebot: ['list', 'create', 'attach', 'test-call', 'transcript', 'debug'],
  'voice-ai': ['connect', 'setup', 'inbound'],
  bridge: ['create', 'get', 'update', 'stop', 'join', 'leave', 'action', 'say', 'play', 'record'],
  events: ['listen', 'scaffold'],
  exoml: ['validate'],
  applet: ['test', 'scaffold'],
  trunk: ['setup', 'create', 'map-number', 'set-number-mode', 'whitelist-ip', 'destination', 'credentials', 'caller-id', 'delete', 'list', 'get'],
  mcp: ['install', 'config', 'clients', 'tools', 'tool', 'call', 'proxy'],
  skill: ['list', 'install'],
  skills: ['list', 'install'],
  integrate: ['elevenlabs', 'bolna'],
  config: ['list', 'show', 'get', 'set', 'unset'],
  docs: ['search', 'show', 'refresh'],
  completion: ['zsh', 'bash', 'fish'],
};

function legVerbs() {
  try {
    return ['create', 'get', 'events', 'inbound', 'action', ...Object.keys(require('./commands/legs').LEG_VERBS)];
  } catch {
    return null;
  }
}

let known;
function knownFlags() {
  if (known) return known;
  known = new Set(['json', 'yes', 'y', 'help', 'h', 'version', 'quiet', 'dry-run']);
  let src;
  try {
    const root = path.join(__dirname, '..');
    const files = [path.join(root, 'bin', 'exotel.js')];
    for (const dir of ['lib', path.join('lib', 'commands')]) {
      // This file is excluded: it must never teach itself flags from its own comments or messages.
      for (const f of fs.readdirSync(path.join(root, dir))) if (f.endsWith('.js') && f !== 'validate.js') files.push(path.join(root, dir, f));
    }
    src = files.map((f) => fs.readFileSync(f, 'utf8')).join('\n');
  } catch {
    // Single-file binary: no source on disk; the build embedded the same list.
    for (const f of require('./generated.json').flags) known.add(f);
    return known;
  }
  for (const m of src.matchAll(/--([a-z][a-z0-9-]*[a-z0-9])/g)) known.add(m[1]);
  for (const m of src.matchAll(/flags\[['"]([a-z][a-z0-9-]+)['"]\]|flags\.([a-z][a-zA-Z0-9]*)\b/g)) known.add(m[1] || m[2]);
  // Options read through helpers: need(flags, 'x'), opt(flags, 'x'), int(flags, 'x'), get('x', …) in login, readSecret stdin flags.
  for (const m of src.matchAll(/\b(?:need|opt|int|get|req)\((?:flags|f), ['"]([a-z][a-z0-9-]+)['"]/g)) known.add(m[1]);
  for (const m of src.matchAll(/\bget\(['"]([a-z][a-z0-9-]+)['"], ['"]EXOTEL_/g)) known.add(m[1]);
  for (const m of src.matchAll(/stdinFlag: ['"]([a-z][a-z0-9-]+)['"]/g)) known.add(m[1]);
  // ExoML one-liners map flag names to XML attributes: attrs(f, { 'storage-url': 'storageURL', engine: … })
  for (const m of src.matchAll(/attrs\(f, \{([^}]*)\}/g)) {
    for (const k of m[1].matchAll(/['"]?([a-z][a-z0-9-]*)['"]?\s*:/g)) known.add(k[1]);
  }
  for (const [canonical, internal] of Object.entries(require('./core').FLAG_ALIASES)) {
    known.add(canonical);
    known.add(internal);
  }
  return known;
}

/** Throws a usage error with a suggestion when the subcommand or a flag is not one the CLI knows. */
function check(cmd, sub, rawFlagNames) {
  const group = cmd === 'campaign' ? 'campaigns' : cmd;
  const subs = group === 'leg' ? legVerbs() : SUBCOMMANDS[group];
  if (subs && sub && !subs.includes(sub)) {
    const near = suggest(sub.replace(/[.,;:!?]+$/, ''), subs);
    throw new CliError(`"${sub}" is not an "exotel ${cmd}" command.${near ? ` Did you mean: exotel ${cmd} ${near}` : ''}\n`
      + `Valid: ${subs.join(', ')}\nHelp: exotel help ${cmd}`, EXIT.USAGE);
  }
  const flags = knownFlags();
  for (const name of rawFlagNames) {
    if (flags.has(name)) continue;
    const near = suggest(name, [...flags]);
    throw new CliError(`Unknown option --${name}.${near ? ` Did you mean --${near}?` : ''} Nothing was run.\nHelp: exotel help ${cmd || ''}`.trim(), EXIT.USAGE);
  }
}

module.exports = { check, SUBCOMMANDS, knownFlags };
