'use strict';
// Call records and recordings: Voice v1 Calls API (bulk and single call details, pre-signed recording URLs).
// Docs: developer.exotel.com/docs/voice-v1/api-reference/call-details, …/call-features/call-recording
const fs = require('fs');
const path = require('path');
const rest = require('../rest');
const { CliError, EXIT, out, info } = require('../io');
const { need, opt, requireAuth, run } = require('../core');

const CALLS_USAGE = `Call records and recordings (Voice v1 Calls API):
  exotel calls list [--since "2026-11-01 00:00:00"] [--until "…"] [--status completed|failed|busy|no-answer]
                    [--direction inbound|outbound-dial|outbound-api] [--from <E.164>] [--to <E.164>] [--exophone <num>]
                    [--page-size 50] [--after <cursor>]        up to 6 months back, max 1-month range per request
  exotel calls get --call-sid <sid> [--details]               --details adds per-leg information
  exotel calls recording --call-sid <sid> [--out file.mp3]    download the recording (URLs are time-limited)`;

const DATE = /^\d{4}-\d{2}-\d{2}( \d{2}:\d{2}:\d{2})?$/;
const LIST = (n) => new RegExp(`^[^,]+(,[^,]+){0,${n - 1}}$`);

function list(flags) {
  const since = opt(flags, 'since', DATE, 'YYYY-MM-DD [HH:MM:SS]');
  const until = opt(flags, 'until', DATE, 'YYYY-MM-DD [HH:MM:SS]');
  // The API needs both ends of the range ("two values of DateCreated needs to be specified"): fill the missing one
  // (until = now; since = until − 30 days, the maximum range per request).
  const fmt = (d) => new Date(d.getTime() + 330 * 60000).toISOString().replace('T', ' ').slice(0, 19); // IST, as the API reports times
  const full = (v, end) => (v && v.length === 10 ? `${v} ${end}` : v);
  let from = full(since, '00:00:00');
  let to = full(until, '23:59:59');
  if (from && !to) to = fmt(new Date());
  if (to && !from) from = fmt(new Date(new Date(`${to.replace(' ', 'T')}+05:30`).getTime() - 30 * 86400000));
  const range = from ? `gte:${from};lte:${to}` : '';
  const query = {
    DateCreated: range || undefined,
    Status: opt(flags, 'status', /^(completed|failed|busy|no-answer)$/),
    Direction: opt(flags, 'direction', /^(inbound|outbound-dial|outbound-api)$/),
    From: opt(flags, 'from', LIST(5), 'up to 5 comma-separated numbers'),
    To: opt(flags, 'to', LIST(5), 'up to 5 comma-separated numbers'),
    PhoneNumber: opt(flags, 'exophone', LIST(5), 'up to 5 ExoPhones'),
    PageSize: opt(flags, 'page-size', /^([1-9]\d?|100)$/, '1–100') || 50,
    After: opt(flags, 'after', /^\S+$/),
    Before: opt(flags, 'before', /^\S+$/),
    RecordingUrlValidity: opt(flags, 'recording-url-validity', /^([5-9]|[1-5]\d|60)$/, '5–60 minutes'),
  };
  return run(flags, {
    kind: 'read', summary: 'List calls', request: { path: '/v1/Accounts/{sid}/Calls.json', query },
    render: (d) => {
      const calls = (d && d.Calls) || [];
      const next = d && d.Metadata && d.Metadata.NextPageUri;
      return `${calls.map((c) => `${c.Sid}  ${String(c.Status).padEnd(10)} ${c.Direction}  ${c.From} → ${c.To}  ${c.StartTime}  ${c.Duration ?? '-'}s${c.RecordingUrl ? '  ●rec' : ''}`).join('\n') || '(no calls)'}${next ? `\nNext page: --after ${(/After=([^&]+)/.exec(next) || [])[1] || ''}` : ''}`;
    },
  });
}

function get(flags) {
  const sid = rest.seg(flags['call-sid'], 'call-sid');
  return run(flags, { kind: 'read', summary: `Call ${sid}`, request: { path: `/v1/Accounts/{sid}/Calls/${sid}.json`, query: { details: flags.details ? 'true' : undefined } } });
}

async function recording(flags) {
  const a = requireAuth();
  const sid = rest.seg(flags['call-sid'], 'call-sid');
  const r = await rest.send(rest.buildRequest(a, { path: `/v1/Accounts/{sid}/Calls/${sid}.json` }));
  if (!r.ok) throw new CliError(`Call lookup failed (HTTP ${r.status})`, EXIT.ERROR, { data: r.data });
  const url = r.data && r.data.Call && r.data.Call.RecordingUrl;
  if (!url) throw new CliError(`Call ${sid} has no recording (was Record=true / recording enabled in the flow?)`, EXIT.ERROR);
  const outPath = path.resolve(flags.out && flags.out !== true ? String(flags.out) : `${sid}${path.extname(new URL(url).pathname) || '.mp3'}`);
  if (flags['dry-run']) return out({ dryRun: true, recordingUrl: url, out: outPath });
  // Recording URLs are served from Exotel storage; some need the account's Basic auth.
  let res = await fetch(url, { signal: AbortSignal.timeout(60000) });
  const exotelHost = /(^|\.)exotel\.(com|in)$/i.test(new URL(url).hostname);
  if ((res.status === 401 || res.status === 403) && exotelHost && new URL(url).protocol === 'https:') res = await fetch(url, { headers: { Authorization: `Basic ${a.token}` }, signal: AbortSignal.timeout(60000) });
  if (!res.ok) throw new CliError(`Download failed (HTTP ${res.status}). Recording URLs are time-limited; download within the retention period.`, EXIT.ERROR);
  const buf = Buffer.from(await res.arrayBuffer());
  fs.writeFileSync(outPath, buf);
  info(`Saved ${buf.length} bytes`);
  return out({ ok: true, callSid: sid, out: outPath, bytes: buf.length, contentType: res.headers.get('content-type') }, `✔ ${outPath} (${buf.length} bytes)`);
}

function calls(sub, flags) {
  if (sub === 'list') return list(flags);
  if (sub === 'get') return get(flags);
  if (sub === 'recording') return recording(flags);
  throw new CliError(CALLS_USAGE, EXIT.USAGE);
}

module.exports = { calls, CALLS_USAGE };
