'use strict';
// Shared building blocks for product commands: login check, flag validation, the safe REST runner,
// Exotel response-envelope parsing and ExoML escaping.
const creds = require('./credentials');
const rest = require('./rest');
const { CliError, EXIT, out, confirm } = require('./io');

const E164 = /^\+[1-9]\d{6,14}$/;
const PHONE = /^\+?\d{6,15}$/;
const IPV4 = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$/;

// Canonical flag names follow Exotel's resource naming (…Sid, app id); older short names still work.
const FLAG_ALIASES = { 'leg-sid': 'leg', 'bridge-sid': 'bridge', 'trunk-sid': 'trunk', 'exophone-sid': 'sid', 'app-id': 'flow', 'bot-id': 'bot', 'campaign-sid': 'campaign' };
const DISPLAY = Object.fromEntries(Object.entries(FLAG_ALIASES).map(([k, v]) => [v, k]));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Saved (or env) Exotel credentials, or a clear "log in first" error. */
function requireAuth() {
  const c = creds.load();
  if (!c) throw new CliError('Not logged in. Run `exotel login` (or `exotel init`).', EXIT.AUTH);
  return c.auth;
}

function need(flags, name, re, msg) {
  const v = flags[name];
  const shown = DISPLAY[name] || name;
  if (v === undefined || v === true || String(v).trim() === '') throw new CliError(`--${shown} is required${msg ? ` (${msg})` : ''}`, EXIT.USAGE);
  const s = String(v).trim();
  if (re && !re.test(s)) throw new CliError(`--${shown} "${s}" is invalid${msg ? `: ${msg}` : ''}`, EXIT.USAGE);
  return s;
}

function opt(flags, name, re, msg) {
  if (flags[name] === undefined || flags[name] === true) return undefined;
  return need(flags, name, re, msg);
}

function int(flags, name) {
  return flags[name] === undefined ? undefined : Number(need(flags, name, /^\d{1,6}$/, 'whole seconds'));
}

/** `{response: {data, error_data}}` (v2 / CPaaS envelope; sometimes an array) → the inner object. */
function envelope(res) {
  const x = res && res.data && res.data.response;
  return (Array.isArray(x) ? x[0] : x) || {};
}

/** Escape for an XML attribute / text node (ExoML). */
const xmlAttr = (v) => String(v).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
const xmlText = (v) => String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;');

/**
 * Run one REST request with the CLI's safety rules.
 * kind: 'read' | 'write' (config change, no confirmation) | 'live' | 'spend' | 'destructive'
 */
async function run(flags, { kind, summary, request, render, quiet = false, notFoundHint }) {
  const a = requireAuth();
  const req = rest.buildRequest(a, request);
  if (flags['dry-run']) {
    const pv = rest.preview(req);
    if (!quiet) out({ dryRun: true, kind, summary, request: pv }, () => `Dry run (${kind}): ${summary}\n${req.method} ${req.url}${pv.body ? `\n${typeof pv.body === 'string' ? pv.body : JSON.stringify(pv.body, null, 2)}` : ''}`);
    return { dryRun: true, ok: true };
  }
  if (['write', 'live', 'spend', 'destructive'].includes(kind)) await confirm(kind, summary, flags);
  if (req.method === 'GET' || ['write', 'live', 'spend', 'destructive'].includes(kind)) rest.approve(req);
  const ui = require('./ui');
  const res = await ui.spin(summary || 'Talking to Exotel…', rest.send(req));
  if (res.status === 404 && notFoundHint) res.hint = notFoundHint;
  if (request.host === 'cpaas' && (res.status === 401 || res.status === 403)) {
    res.hint = 'Legs / ExoML need an LP-enabled API key + token and an LP number from Exotel support. If you have them, check the leg/bridge SID belongs to this account.';
  }
  // quiet: true hides all output (caller reports); quiet: 'success' hides only success output, never errors.
  if (!quiet || (quiet === 'success' && !res.ok)) {
    out({ ok: res.ok, status: res.status, ...(res.code !== undefined ? { code: res.code } : {}), ...(res.message ? { message: res.message } : {}), ...(res.hint ? { hint: res.hint } : {}), data: res.data },
      () => (res.ok
        ? (render ? render(res.data) : ui.pretty(res.data))
        : `${ui.c.red('✘')} ${res.message || `Exotel returned HTTP ${res.status}`}${res.code !== undefined ? ui.c.dim(` (code ${res.code})`) : ''}${res.hint ? `\n  ${res.hint}` : ''}\n${ui.c.dim(typeof res.data === 'string' ? res.data.slice(0, 600) : JSON.stringify(res.data).slice(0, 600))}\n${ui.c.dim(`Explain a code: exotel explain ${res.code ?? res.status}`)}`));
  }
  if (!res.ok) process.exitCode = EXIT.ERROR;
  return res;
}

module.exports = { E164, PHONE, IPV4, FLAG_ALIASES, sleep, requireAuth, need, opt, int, envelope, xmlAttr, xmlText, run };
