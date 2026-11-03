'use strict';
// Search Exotel's two documentation portals from the terminal.
// Both publish an llms.txt index (title, URL, description per page) and serve every page as Markdown at URL + ".md".
//   developer.exotel.com — API reference and developer guides
//   docs.exotel.com      — product, applet and ExoML/Legs docs
// Indexes are cached for 24 h in ~/.cache/exotel/docs-index.json (works offline from the cache).
const fs = require('fs');
const os = require('os');
const path = require('path');

const SOURCES = [
  { id: 'developer', label: 'developer.exotel.com', url: 'https://developer.exotel.com/llms.txt' },
  { id: 'docs', label: 'docs.exotel.com', url: 'https://docs.exotel.com/llms.txt' },
];
const TTL_MS = 24 * 3600 * 1000;

// Telephony vocabulary: a query word also matches these.
const SYNONYMS = {
  bot: ['voicebot', 'agent', 'agentstream', 'ai'],
  voicebot: ['bot', 'agentstream'],
  wss: ['websocket', 'stream', 'agentstream'],
  websocket: ['wss', 'stream'],
  stream: ['streaming', 'websocket', 'agentstream'],
  call: ['calls', 'voice', 'connect'],
  outbound: ['connect', 'calls'],
  inbound: ['incoming', 'exophone'],
  number: ['exophone', 'did', 'numbers'],
  did: ['exophone', 'number'],
  ivr: ['gather', 'applet', 'flow'],
  flow: ['applet', 'app bazaar', 'exoml'],
  exoml: ['legs', 'flow', 'actions'],
  legs: ['leg', 'exoml', 'bridge'],
  sip: ['trunk', 'trunking'],
  trunk: ['sip', 'trunking'],
  sms: ['message', 'dlt'],
  otp: ['sms', 'verify'],
  record: ['recording'],
  recording: ['record'],
  transcript: ['transcription', 'conversation'],
  webhook: ['callback', 'status', 'passthru'],
  callback: ['webhook', 'status'],
};

function cacheFile() {
  const base = process.env.EXOTEL_CACHE_DIR || path.join(process.env.XDG_CACHE_HOME || path.join(os.homedir(), '.cache'), 'exotel');
  return path.join(base, 'docs-index.json');
}

/** Parse an llms.txt: lines like "- [Title](https://…/page.md): description". */
function parseLlmsTxt(text, source) {
  const pages = [];
  let section = '';
  for (const line of String(text).split(/\r?\n/)) {
    const h = /^#{2,3}\s+(.+)/.exec(line);
    if (h) {
      section = h[1].trim();
      continue;
    }
    const m = /^\s*[-*]\s*\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)\s*(?::\s*(.*))?$/.exec(line);
    if (!m) continue;
    const url = m[2].replace(/\.md$/, '');
    pages.push({ source, title: m[1].trim(), url, description: (m[3] || '').trim(), section });
  }
  return pages;
}

async function loadIndex({ refresh = false, offline = !!process.env.EXOTEL_DOCS_OFFLINE } = {}) {
  const file = cacheFile();
  let cached = null;
  try {
    cached = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    cached = null;
  }
  const fresh = cached && Date.now() - cached.fetchedAt < TTL_MS;
  if (offline || (fresh && !refresh)) return cached ? cached.pages : [];
  const pages = [];
  const failed = [];
  await Promise.all(SOURCES.map(async (s) => {
    try {
      const res = await fetch(s.url, { signal: AbortSignal.timeout(20000) });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      pages.push(...parseLlmsTxt(await res.text(), s.label));
    } catch (e) {
      failed.push(`${s.label}: ${e.message}`);
      if (cached) pages.push(...cached.pages.filter((p) => p.source === s.label));
    }
  }));
  if (pages.length && !failed.length) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify({ fetchedAt: Date.now(), pages }));
  }
  return pages;
}

const STOP = new Set('a an the to for of on in with and or how do i my me can is are what which use using from by via it this that be want need get set up api apis exotel'.split(' '));

function terms(q) {
  return String(q).toLowerCase().replace(/[^a-z0-9+#.\s-]/g, ' ').split(/\s+/).filter((w) => w.length > 1 && !STOP.has(w));
}

function search(pages, query, { limit = 8 } = {}) {
  const base = terms(query);
  if (!base.length) return [];
  const expanded = new Map(base.map((t) => [t, 1]));
  for (const t of base) for (const s of SYNONYMS[t] || []) if (!expanded.has(s)) expanded.set(s, 0.4);
  // IDF: rare words ("openai", "elevenlabs") outweigh common ones ("call", "bot").
  const n = pages.length || 1;
  const idf = new Map();
  for (const t of expanded.keys()) {
    let df = 0;
    for (const p of pages) if (`${p.title} ${p.description} ${p.url}`.toLowerCase().includes(t)) df += 1;
    idf.set(t, Math.log(1 + n / (1 + df)));
  }
  const scored = pages.map((p) => {
    const title = p.title.toLowerCase();
    const desc = p.description.toLowerCase();
    const url = p.url.toLowerCase();
    const sec = (p.section || '').toLowerCase();
    let score = 0;
    let hits = 0;
    for (const [t, w] of expanded) {
      let s = 0;
      if (title.includes(t)) s += 5;
      if (url.includes(t)) s += 3;
      if (desc.includes(t)) s += 2;
      if (sec.includes(t)) s += 1;
      if (s && w === 1) hits += 1;
      score += s * w * idf.get(t);
    }
    if (hits === base.length && base.length > 1) score *= 1.5; // every query word matched
    if (/deprecated|superseded/.test(title)) score *= 0.3;
    return { ...p, score: Math.round(score * 10) / 10 };
  }).filter((p) => p.score > 0);
  scored.sort((a, b) => b.score - a.score || a.title.length - b.title.length);
  const seen = new Set();
  return scored.filter((p) => (seen.has(p.url) ? false : seen.add(p.url))).slice(0, limit);
}

/** Fetch one page as Markdown (URL + ".md"). */
async function show(url, { maxChars = 20000 } = {}) {
  if (!/^https:\/\/(developer|docs)\.exotel\.com\//.test(url)) throw new Error('Only developer.exotel.com and docs.exotel.com pages');
  const mdUrl = url.endsWith('.md') ? url : `${url.replace(/\/$/, '')}.md`;
  const res = await fetch(mdUrl, { signal: AbortSignal.timeout(30000) });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${mdUrl}`);
  let text = await res.text();
  // docs.exotel.com Markdown carries escaped characters and HTML entities; make it readable.
  text = text.replace(/&#x20;/g, ' ').replace(/&#x7B;/g, '{').replace(/\\([_{}[\]<>*#|-])/g, '$1').replace(/:::\w*\{?[^\n]*\n?/g, '');
  const truncated = text.length > maxChars;
  return { url: mdUrl, text: truncated ? `${text.slice(0, maxChars)}\n…(truncated; ${text.length} chars)` : text, truncated };
}

module.exports = { SOURCES, parseLlmsTxt, loadIndex, search, show, terms, cacheFile };
