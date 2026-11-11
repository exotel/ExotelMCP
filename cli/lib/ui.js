'use strict';
// Human-facing terminal UI: colours, tables, spinner, pickers, "did you mean", and a generic pretty-printer
// that turns API JSON into a table or a short key/value list. Zero dependencies. Everything here is for
// people at a terminal: with --json, when piped, or with NO_COLOR, it degrades to plain text or nothing.

const tty = () => !!process.stdout.isTTY;
const errTty = () => !!process.stderr.isTTY;
const colorOn = (stream = process.stdout) => !!stream.isTTY && !process.env.NO_COLOR && process.env.TERM !== 'dumb';

const wrap = (open, close) => (s, stream) => (colorOn(stream) ? `\x1b[${open}m${s}\x1b[${close}m` : String(s));
const c = {
  bold: wrap(1, 22), dim: wrap(2, 22), red: wrap(31, 39), green: wrap(32, 39), yellow: wrap(33, 39),
  cyan: wrap(36, 39), gray: wrap(90, 39),
};
const visible = (s) => String(s).replace(/\x1b\[[0-9;]*m/g, '');

// ------------------------------------------------------------------ tables

/** rows: array of objects; cols: [{ key, label, fmt?, max? }]. Fits the terminal width. */
function table(rows, cols) {
  if (!rows.length) return c.dim('(none)');
  const cells = rows.map((r) => cols.map((col) => {
    const v = col.fmt ? col.fmt(r[col.key], r) : r[col.key];
    let s = v === undefined || v === null || v === '' ? '–' : String(v);
    if (col.max && visible(s).length > col.max) s = `${visible(s).slice(0, col.max - 1)}…`;
    return s;
  }));
  const widths = cols.map((col, i) => Math.max(col.label.length, ...cells.map((r) => visible(r[i]).length)));
  const total = process.stdout.columns || 160;
  // Shrink the widest column until the table fits the terminal.
  while (widths.reduce((a, b) => a + b + 2, 0) > total) {
    const i = widths.indexOf(Math.max(...widths));
    if (widths[i] <= 12) break;
    widths[i] -= 1;
  }
  const fit = (s, w) => {
    const v = visible(s);
    return v.length > w ? `${v.slice(0, w - 1)}…` : s + ' '.repeat(w - v.length);
  };
  const head = cols.map((col, i) => c.bold(fit(col.label, widths[i]))).join('  ');
  return [head, ...cells.map((r) => r.map((s, i) => fit(s, widths[i])).join('  ').trimEnd())].join('\n');
}

const COLUMN_PREFERENCE = [
  'name', 'friendly_name', 'phone_number', 'id', 'sid', 'Sid', 'status', 'Status', 'direction', 'Direction', 'from', 'From',
  'to', 'To', 'number_type', 'region', 'start_time', 'StartTime', 'date_created', 'DateCreated', 'duration', 'Duration',
  'type', 'send_at', 'voice_url',
];
const SKIP = /(_?uri|Uri|url_list|account_id|AccountSid|request_id|^links?$|^metadata$|^Metadata$)$/;

function findRows(data, depth = 0) {
  if (Array.isArray(data)) return data.length && typeof data[0] === 'object' ? data : null;
  if (!data || typeof data !== 'object' || depth > 3) return null;
  for (const [k, v] of Object.entries(data)) {
    if (SKIP.test(k)) continue;
    const r = findRows(v, depth + 1);
    if (r) return r;
  }
  return null;
}

/** Generic human rendering for an API response. */
function pretty(data) {
  if (data === undefined || data === null || data === '') return c.green('✔ Done');
  if (typeof data !== 'object') return String(data);
  const rows = findRows(data);
  if (rows) {
    const keys = Object.keys(rows[0]).filter((k) => !SKIP.test(k) && rows.some((r) => r[k] !== null && typeof r[k] !== 'object'));
    const pick = [...COLUMN_PREFERENCE.filter((k) => keys.includes(k)), ...keys.filter((k) => !COLUMN_PREFERENCE.includes(k))].slice(0, 6);
    const human = (k) => k.replace(/_/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2').toUpperCase();
    return `${table(rows, pick.map((k) => ({ key: k, label: human(k), max: /url/i.test(k) ? 48 : 40, fmt: (v) => (/^status$/i.test(k) ? statusColor(v) : v) })))}\n${c.dim(`${rows.length} row${rows.length === 1 ? '' : 's'} · add --json for everything`)}`;
  }
  // Single object: flatten one level into "Key: value" lines.
  const lines = [];
  const walk = (o, prefix = '') => {
    for (const [k, v] of Object.entries(o)) {
      if (SKIP.test(k)) continue;
      if (v && typeof v === 'object' && !Array.isArray(v) && !prefix) walk(v, `${k}.`);
      else if (v !== null && typeof v !== 'object') lines.push([`${prefix}${k}`, v]);
    }
  };
  walk(data);
  if (!lines.length) return JSON.stringify(data, null, 2);
  const w = Math.min(28, Math.max(...lines.map(([k]) => k.length)));
  return lines.slice(0, 40).map(([k, v]) => `${c.dim(k.padEnd(w))}  ${v}`).join('\n');
}

function statusColor(s) {
  const v = String(s ?? '');
  if (/^(completed|active|success|ok|done|answered)$/i.test(v)) return c.green(v);
  if (/^(failed|busy|no-answer|error|canceled|cancelled|inactive)$/i.test(v)) return c.red(v);
  if (/progress|ringing|queued|paused|pending|created/i.test(v)) return c.yellow(v);
  return v;
}

// ------------------------------------------------------------------ spinner

/** Spinner on stderr while a promise runs; silent when not a terminal or in --json mode. */
async function spin(text, promise) {
  const json = require('./io').isJson();
  if (!errTty() || json || process.env.EXOTEL_QUIET === '1' || process.env.EXOTEL_NO_SPIN === '1') return promise;
  const frames = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'];
  let i = 0;
  const draw = () => process.stderr.write(`\r${c.cyan(frames[i++ % frames.length], process.stderr)} ${text}`);
  draw();
  const t = setInterval(draw, 80);
  try {
    return await promise;
  } finally {
    clearInterval(t);
    process.stderr.write(`\r\x1b[2K`);
  }
}

// ------------------------------------------------------------------ prompts

/** Numbered picker. items: [{ label, value, hint? }]. Returns value. Typing text filters; Enter takes default. */
async function select(question, items, { def = 0, allowCustom = false, customLabel = 'type a value' } = {}) {
  const { ask } = require('./io');
  if (!items.length && !allowCustom) throw new Error(`Nothing to choose for: ${question}`);
  let shown = items;
  for (;;) {
    process.stderr.write(`\n${c.bold(question, process.stderr)}\n`);
    shown.slice(0, 15).forEach((it, i) => process.stderr.write(`  ${c.cyan(String(i + 1).padStart(2), process.stderr)}. ${it.label}${it.hint ? `  ${c.dim(it.hint, process.stderr)}` : ''}\n`));
    if (shown.length > 15) process.stderr.write(c.dim(`  … ${shown.length - 15} more: type part of a name to filter\n`, process.stderr));
    const a = (await ask(`Choose 1-${Math.min(shown.length, 15)}${allowCustom ? ` or ${customLabel}` : ''}`, { def: shown.length ? String(Math.min(def, shown.length - 1) + 1) : '' })).trim();
    if (/^\d+$/.test(a) && Number(a) >= 1 && Number(a) <= Math.min(shown.length, 15)) return shown[Number(a) - 1].value;
    const f = items.filter((it) => it.label.toLowerCase().includes(a.toLowerCase()) || String(it.value).toLowerCase() === a.toLowerCase());
    if (f.length === 1) return f[0].value;
    if (allowCustom && a && !f.length) return a;
    if (f.length) shown = f;
    else process.stderr.write(c.yellow(`No match for "${a}".\n`, process.stderr));
  }
}

// ------------------------------------------------------------------ suggestions

function distance(a, b) {
  const d = Array.from({ length: a.length + 1 }, (_, i) => [i, ...Array(b.length).fill(0)]);
  for (let j = 1; j <= b.length; j++) d[0][j] = j;
  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      // swapped neighbours ("sya" → "say") count as one edit
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
    }
  }
  return d[a.length][b.length];
}

/** Closest option within a small edit distance, or a prefix match. */
function suggest(word, options) {
  const w = String(word || '').toLowerCase();
  if (!w) return null;
  const prefix = options.filter((o) => o.startsWith(w));
  // A unique prefix wins, unless another option is a one-letter typo away ("too" means "to", not "toolsets").
  if (prefix.length === 1 && !options.some((o) => o !== prefix[0] && distance(w, o) <= 1)) return prefix[0];
  let best = null;
  let bestD = Infinity;
  for (const o of options) {
    const dd = distance(w, o);
    if (dd < bestD) { bestD = dd; best = o; }
  }
  return bestD <= (w.length < 4 ? 1 : Math.max(2, Math.floor(w.length / 3))) ? best : null;
}

module.exports = { c, table, pretty, findRows, statusColor, spin, select, suggest, colorOn, tty };
