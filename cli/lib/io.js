'use strict';
// Shared output, prompting and error handling for every command.
const readline = require('readline');

// Exit codes: 0 ok, 1 tool/API error, 2 usage error, 3 confirmation required, 4 not logged in.
const EXIT = { OK: 0, ERROR: 1, USAGE: 2, CONFIRM: 3, AUTH: 4 };

class CliError extends Error {
  constructor(message, code = EXIT.ERROR, extra = {}) {
    super(message);
    this.code = code;
    this.extra = extra;
  }
}

const state = { json: !process.stdout.isTTY };

function setJson(v) {
  state.json = !!v;
}

function isJson() {
  return state.json;
}

function out(data, human) {
  if (state.json) process.stdout.write(JSON.stringify(data, null, 2) + '\n');
  else process.stdout.write((typeof human === 'function' ? human(data) : human ?? JSON.stringify(data, null, 2)) + '\n');
}

function info(msg) {
  if (!state.json) process.stderr.write(msg + '\n');
}

const interactive = () => !!(process.stdin.isTTY && process.stderr.isTTY);

function ask(question, { secret = false, def } = {}) {
  return new Promise((resolve) => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stderr, terminal: true });
    const q = def ? `${question} [${def}]: ` : `${question}: `;
    if (secret) {
      rl._writeToOutput = (s) => {
        if (s.startsWith(q)) rl.output.write(q);
      };
    }
    rl.question(q, (ans) => {
      rl.close();
      if (secret) process.stderr.write('\n');
      resolve(ans.trim() || def || '');
    });
  });
}

const KIND_LABEL = {
  spend: 'BILLED (charges your Exotel account)',
  destructive: 'CANNOT BE UNDONE',
  live: 'CHANGES LIVE CALL ROUTING',
  write: 'CHANGES YOUR EXOTEL ACCOUNT',
};

/** Ask before anything that changes or costs something. --yes skips; non-TTY without --yes fails with exit 3. */
async function confirm(kind, summary, flags) {
  if (flags.yes || flags.y) return true;
  const label = KIND_LABEL[kind] || kind.toUpperCase();
  if (!interactive()) {
    throw new CliError(`Confirmation required (${label.toLowerCase()}). Re-run with --yes, or --dry-run to preview.`, EXIT.CONFIRM, { kind, action: summary });
  }
  process.stderr.write(`\n${label}: ${summary}\n`);
  const a = await ask('Proceed? (y/N)');
  if (!/^y(es)?$/i.test(a)) throw new CliError('Cancelled.', EXIT.CONFIRM);
  return true;
}

/** Read a secret from an env var, stdin (--<name>-stdin) or a hidden prompt. Never from argv. */
async function readSecret({ envKey, stdinFlag, flags, prompt }) {
  if (envKey && process.env[envKey]) return process.env[envKey];
  if (stdinFlag && flags[stdinFlag]) return require('fs').readFileSync(0, 'utf8').trim();
  if (!interactive()) throw new CliError(`Provide the secret via ${envKey}${stdinFlag ? ` or --${stdinFlag}` : ''}.`, EXIT.USAGE);
  return ask(prompt, { secret: true });
}

module.exports = { EXIT, CliError, setJson, isJson, out, info, interactive, ask, confirm, readSecret };
