'use strict';
// Status and error-code reference, built from the official docs (developer.exotel.com + docs.exotel.com):
// every page whose title is about statuses / error codes is fetched as Markdown and its table rows are indexed.
// Plus a few codes verified on live calls that the docs do not list yet (marked source "live-verified").
// Cached 24 h in ~/.cache/exotel/reference.json.
const fs = require('fs');
const path = require('path');
const docs = require('./docs');

const TTL_MS = 24 * 3600 * 1000;
const PAGE_TITLE = /(status codes?|error codes?|call status|statuscallback|status callback|status values|hangup cause|response codes|http status|sip error|troubleshoot)/i;
const EXTRA_PAGES = [
  'https://developer.exotel.com/docs/sip-trunking/dynamic-sip-trunking',
  'https://docs.exotel.com/voice-apis/implementing-your-https-event-endpoint-for-real-time-call-events',
  'https://developer.exotel.com/docs/agentstream/websocket-protocol',
  'https://developer.exotel.com/docs/voice-v1/api-reference/status-callback',
];

// Seen on live calls (3 Nov 2026), not (yet) in the docs.
const LIVE = [
  { area: 'Legs API (cpaas-api)', cells: ['401', '1010', 'Authentication failed', 'API key has no Legs (LP) access, or the leg/bridge SID is not on this account. Get an LP-enabled key/token from Exotel support.'] },
  { area: 'Legs API (cpaas-api)', cells: ['503', '1012', 'no route found in Sif for requested server/dc/nso', 'Caller number (exophone) is not an LP number with a route. Use the LP number from Exotel support.'] },
  { area: 'Legs API (cpaas-api)', cells: ['400', '1013', 'Hostname is nil for legsid', 'Action sent while the leg was still being set up. Send actions only after the leg_answered event.'] },
  { area: 'Legs API (cpaas-api)', cells: ['500', '1004', 'redis: nil', 'Action sent before the leg was ready (transient). Wait for leg_answered.'] },
  { area: 'Legs API (cpaas-api)', cells: ['404', '1000', 'Not Found', 'Unknown leg/bridge SID; also returned for /actions/start_stream style JSON endpoints, which do not exist (use ExoML on /actions).'] },
  { area: 'Legs API (cpaas-api)', cells: ['405', '1005', 'Method not implemented', 'e.g. GET /legs/{sid}/actions is not supported.'] },
  { area: 'Legs events', cells: ['invalid_request', 'Cannot perform this Action on Leg right now', 'e.g. <StopSay/> after say_completed: the say had already finished.'] },
  { area: 'Bridges API', cells: ['400', '1001', 'LegSids are mandatory / BridgeEventEndpoint is mandatory', 'Create Bridge needs leg_sids and bridge_event_endpoint.'] },
  { area: 'Voice v1 / ExoPhones (api.exotel.com)', cells: ['401', '34010', 'Unauthorized', 'Wrong API key/token or region.'] },
  { area: 'Voice v1 / ExoPhones (api.exotel.com)', cells: ['404', '34003', 'Resource not found', 'e.g. no available numbers of that type/region.'] },
  { area: 'Bolna', cells: ['provider_error', 'Call answered, but the Exotel app flow never connected it to the Bolna agent', 'Bolna outbound/inbound App Bazaar apps missing or misconfigured.'] },
  { area: 'ElevenLabs WSS', cells: ['403', 'WebSocket rejected', 'ElevenLabs accepts Exotel streams only from numbers imported into ElevenLabs.'] },
];

function cacheFile() {
  return path.join(path.dirname(docs.cacheFile()), 'reference.json');
}

function stripMd(s) {
  return String(s).replace(/`/g, '').replace(/\*\*/g, '').replace(/\[([^\]]+)\]\([^)]+\)/g, '$1').trim();
}

/** Table rows (with their header and nearest heading) and code-bearing bullets from one Markdown page. */
function extractRows(markdown, page) {
  const rows = [];
  let heading = '';
  let header = null;
  for (const raw of String(markdown).split(/\r?\n/)) {
    const line = raw.trim();
    const h = /^#{1,4}\s+(.*)/.exec(line);
    if (h) {
      heading = stripMd(h[1]);
      header = null;
      continue;
    }
    if (line.startsWith('|')) {
      const cells = line.replace(/^\||\|$/g, '').split('|').map(stripMd);
      if (cells.every((c) => /^:?-{2,}:?$/.test(c) || c === '')) continue;
      if (!header) {
        header = cells;
        continue;
      }
      rows.push({ page: page.title, url: page.url, source: page.source, heading, header, cells });
    } else {
      header = null;
      if (/^[-*]\s/.test(line) && /\b\d{3,5}\b/.test(line)) rows.push({ page: page.title, url: page.url, source: page.source, heading, header: null, cells: [stripMd(line.slice(2))] });
    }
  }
  return rows;
}

async function load({ refresh = false, offline = !!process.env.EXOTEL_DOCS_OFFLINE } = {}) {
  let cached = null;
  try {
    cached = JSON.parse(fs.readFileSync(cacheFile(), 'utf8'));
  } catch {
    cached = null;
  }
  const live = LIVE.map((r) => ({ page: 'Verified on live calls', url: null, source: 'live-verified', heading: r.area, header: null, cells: r.cells }));
  if (offline || (cached && !refresh && Date.now() - cached.fetchedAt < TTL_MS)) return [...live, ...(cached ? cached.rows : [])];
  const index = await docs.loadIndex();
  const pages = index.filter((p) => PAGE_TITLE.test(p.title)).slice(0, 60);
  for (const url of EXTRA_PAGES) if (!pages.some((p) => p.url === url)) pages.push({ title: (index.find((p) => p.url === url) || {}).title || url, url, source: url.includes('developer.') ? 'developer.exotel.com' : 'docs.exotel.com' });
  const rows = [];
  let i = 0;
  await Promise.all(Array.from({ length: 6 }, async () => {
    while (i < pages.length) {
      const p = pages[i++];
      try {
        const { text } = await docs.show(p.url, { maxChars: 400000 });
        rows.push(...extractRows(text, p));
      } catch {
        // skip pages that fail; others still help
      }
    }
  }));
  if (rows.length) {
    fs.mkdirSync(path.dirname(cacheFile()), { recursive: true });
    fs.writeFileSync(cacheFile(), JSON.stringify({ fetchedAt: Date.now(), pages: pages.length, rows }));
  }
  return [...live, ...(rows.length ? rows : (cached ? cached.rows : []))];
}

/** Rows that mention a code (as a whole token) or a word/phrase. */
function lookup(rows, term, { limit = 25 } = {}) {
  const t = String(term).trim().toLowerCase();
  if (!t) return [];
  const isCode = /^\d{3,5}$/.test(t);
  const re = isCode ? new RegExp(`(^|[^\\d.])${t}([^\\d]|$)`) : null;
  const hits = rows.filter((r) => {
    const text = r.cells.join(' | ').toLowerCase();
    if (isCode) return r.cells.some((c) => re.test(c));
    return text.includes(t) || (r.heading || '').toLowerCase().includes(t);
  });
  // Rows where the code/term is the first cell are the definitions; list them first.
  hits.sort((a, b) => (String(b.cells[0]).toLowerCase() === t) - (String(a.cells[0]).toLowerCase() === t) || (b.source === 'live-verified') - (a.source === 'live-verified'));
  return hits.slice(0, limit);
}

/** Status families documented on the portals: one entry per (page, heading) whose table looks like status values. */
function families(rows) {
  const map = new Map();
  for (const r of rows) {
    if (!r.header) continue;
    const h = r.header.join(' ').toLowerCase();
    if (!/status|code|value|event|reason/.test(h)) continue;
    const key = `${r.page} › ${r.heading}`;
    if (!map.has(key)) map.set(key, { family: key, url: r.url, source: r.source, values: [] });
    const v = r.cells[0];
    if (v && map.get(key).values.length < 40 && !map.get(key).values.includes(v)) map.get(key).values.push(v);
  }
  return [...map.values()].filter((f) => f.values.length >= 2);
}

function formatRow(r) {
  const cells = r.header && r.header.length === r.cells.length ? r.cells.map((c, i) => `${r.header[i]}: ${c}`).join(' · ') : r.cells.join(' · ');
  return `${cells}\n    — ${r.page}${r.heading ? ` › ${r.heading}` : ''}${r.url ? `  ${r.url}` : ''}`;
}

module.exports = { load, lookup, families, extractRows, formatRow, LIVE };
