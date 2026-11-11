'use strict';
// Direct client for Exotel's REST APIs (HTTP Basic with the saved API key/token).
// Hosts:   api      → https://api.in.exotel.com (Mumbai) | https://api.exotel.com (Singapore)
//          cpaas    → Legs / Bridges / Actions: https://cpaas-api.in.exotel.com (derived from api_domain)
const { CliError, EXIT } = require('./io');

// Documented error codes for the v2 trunk APIs, with the fix the docs give.
const TRUNK_ERRORS = {
  1000: 'Resource not found: check the trunk SID / phone number id.',
  1001: 'A mandatory parameter is missing.',
  1002: 'Invalid parameter: check formats (E.164 numbers, IPv4, SIP URI).',
  1007: 'Malformed JSON.',
  1008: 'Already configured (duplicate). Safe to ignore.',
  1010: 'Authorization failed: check API key/token and region, that the product (SIP trunking / Legs API) is enabled, and that the trunk/leg/bridge SID belongs to this account (unknown SIDs also return 1010).',
  1011: 'Wrong content type.',
  1012: 'Legs routing has no route for this caller number (exophone). Use the LP number Exotel support assigned to you; if they give data-centre / NSO codes, pass --dc <code> --nso <code>.',
};

function hosts(auth) {
  const api = (auth.api_domain || 'https://api.in.exotel.com').replace(/\/$/, '');
  // ExoML / Legs / Bridges run only in Mumbai (cpaas-api.in.exotel.com); there is no Singapore Legs host.
  // EXOTEL_CPAAS_BASE_URL or cpaas_base_url override it if Exotel gives you another host.
  const cpaasOverride = process.env.EXOTEL_CPAAS_BASE_URL || auth.cpaas_base_url;
  const cpaas = cpaasOverride ? cpaasOverride.replace(/\/$/, '')
    : (/api\.in\.exotel\.com/.test(api) ? 'https://cpaas-api.in.exotel.com' : null);
  // Exotel-hosted VoiceBot API (voicebot.in.exotel.com for Mumbai accounts, voicebot.sg.exotel.com for Singapore).
  const voicebot = (process.env.EXOTEL_VOICEBOT_BASE_URL || auth.voicebot_base_url
    || (/api\.in\.exotel\.com/.test(api) ? 'https://voicebot.in.exotel.com/voicebot/api' : 'https://voicebot.sg.exotel.com/voicebot/api')).replace(/\/$/, '');
  // Exotel Memory (contact memory across calls and chats). memory_base_url from `exotel memory login --url`.
  const memory = `${(process.env.EXOTEL_MEMORY_BASE_URL || auth.memory_base_url || 'https://memory.in.exotel.com').replace(/\/$/, '')}/memory/api/v1`;
  return { api, cpaas, voicebot, memory };
}

/** Where a VoiceBot's session_start / session_end webhooks must point to load and save Exotel Memory. */
function memoryHookBase(auth) {
  if (auth.memory_webhook_base_url) return auth.memory_webhook_base_url.replace(/\/$/, '');
  const base = hosts(auth).memory.replace(/\/memory\/api\/v1$/, '');
  return `${base}/memory-client/memory/api/v1/accounts/${encodeURIComponent(auth.memory_account_sid || '')}/webhooks/voicebot`;
}

/** Replace {sid} in a path with the account SID. */
function expandPath(path, auth, host) {
  const id = host === 'memory' ? auth.memory_account_sid
    : host === 'voicebot' && auth.voicebot_account_id ? auth.voicebot_account_id : auth.account_sid;
  return path.replace(/\{(sid|account_sid|AccountSid)\}/g, encodeURIComponent(id));
}

function seg(v, name) {
  if (v === undefined || v === null || String(v).trim() === '') throw new CliError(`--${name} is required`, EXIT.USAGE);
  const s = String(v).trim();
  if (!/^[A-Za-z0-9_.:+@-]{1,128}$/.test(s) || /^\.+$/.test(s) || s.includes('..')) throw new CliError(`--${name} has invalid characters`, EXIT.USAGE);
  return encodeURIComponent(s);
}

function buildRequest(auth, { method = 'GET', host = 'api', path, query, json, form }) {
  const h = hosts(auth);
  const base = h[host];
  if (!base && host === 'cpaas') {
    throw new CliError('Legs, Bridges and ExoML are available only on Mumbai (India) accounts. This login is for Singapore (api.exotel.com). '
      + 'Log in with a Mumbai account (exotel login --region in), or use Connect Voice AI / a Voicebot flow instead.', EXIT.USAGE);
  }
  if (!base) throw new Error(`Unknown host ${host}`);
  const url = new URL(base + expandPath(path, auth, host));
  for (const [k, v] of Object.entries(query || {})) {
    if (v === undefined || v === null || v === '') continue;
    if (Array.isArray(v)) v.forEach((x) => url.searchParams.append(k, String(x)));
    else url.searchParams.append(k, String(v));
  }
  // VoiceBot API uses its own key/token when given (normally the same as the CPaaS key/token).
  const basic = host === 'voicebot' && auth.voicebot_api_key && auth.voicebot_api_token
    ? Buffer.from(`${auth.voicebot_api_key}:${auth.voicebot_api_token}`).toString('base64')
    : auth.token;
  if (host === 'memory' && !(auth.memory_api_key && auth.memory_account_sid)) {
    throw new CliError('Exotel Memory is not set up. Run: exotel memory login (you need the Memory API key, cm_…, and your Memory account SID).', EXIT.AUTH);
  }
  const headers = { Authorization: host === 'memory' ? `Bearer ${auth.memory_api_key}` : `Basic ${basic}`, Accept: 'application/json' };
  let body;
  if (json !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(json);
  } else if (form) {
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
    const p = new URLSearchParams();
    for (const [k, v] of Object.entries(form)) {
      if (v === undefined || v === null || v === '') continue;
      if (Array.isArray(v)) v.forEach((x) => p.append(k, String(x)));
      else p.append(k, String(v));
    }
    body = p.toString();
  }
  return { method: method.toUpperCase(), url: url.toString(), headers, body };
}

/** Request preview with the credential removed, for --dry-run. */
function preview(req) {
  let body = req.body;
  if (body && req.headers['Content-Type'] === 'application/json') body = JSON.parse(body);
  return { method: req.method, url: req.url, contentType: req.headers['Content-Type'] || null, body: body ?? null };
}

/** Mark a request as approved by the user (after confirm(), or because the user ran a read). */
function approve(req) {
  Object.defineProperty(req, 'approved', { value: true, enumerable: false });
  return req;
}

/**
 * Credentials only ever go to Exotel over HTTPS (*.exotel.com / *.exotel.in). A typo, a copied command or a
 * misled agent cannot send the API key and token to another host. Self-hosted MCP or test servers:
 * EXOTEL_ALLOW_CUSTOM_HOST=1.
 */
function assertExotelHost(url) {
  let u;
  try { u = new URL(url); } catch { throw new CliError(`Invalid URL: ${url}`, EXIT.USAGE); }
  if (process.env.EXOTEL_ALLOW_CUSTOM_HOST === '1') return;
  if (u.protocol !== 'https:' || !/(^|\.)(exotel\.(com|in)|qaexotel\.com)$/i.test(u.hostname)) {
    throw new CliError(`Refusing to send your Exotel credentials to ${u.protocol}//${u.host}: only https://*.exotel.com or *.exotel.in. `
      + 'Check the region / api_domain (exotel whoami). For a self-hosted server set EXOTEL_ALLOW_CUSTOM_HOST=1. Nothing was sent.', EXIT.USAGE);
  }
}

async function send(req, { timeoutMs = 30000 } = {}) {
  if (req.headers && Object.keys(req.headers).some((h) => h.toLowerCase() === 'authorization')) assertExotelHost(req.url);
  // Safety net: no request that changes anything leaves the CLI unless a command asked the user first.
  if (req.method && req.method !== 'GET' && !req.approved) {
    throw new CliError(`Internal safety check: ${req.method} ${String(req.url).replace(/\?.*/, '')} was not confirmed. Nothing was sent.`, EXIT.ERROR);
  }
  const res = await fetch(req.url, {
    method: req.method, headers: req.headers, body: req.body, signal: AbortSignal.timeout(timeoutMs),
  });
  const text = await res.text();
  let data;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  const result = { ok: res.ok, status: res.status, data };
  if (!res.ok) {
    // v2 envelope: { response: [ { error_data: { code, message } } ] } (or a single object)
    const r0 = data && typeof data === 'object' ? (Array.isArray(data.response) ? data.response[0] : data.response) : undefined;
    const code = data && typeof data === 'object'
      ? ((r0 && r0.error_data && r0.error_data.code) ?? data.code ?? data.error_code ?? (data.error && data.error.code))
      : undefined;
    if (r0 && r0.error_data) result.message = [r0.error_data.message, r0.error_data.description].filter((x, i, a) => x && a.indexOf(x) === i).join(': ');
    else if (data && typeof data === 'object' && typeof data.detail === 'string') result.message = data.detail; // Memory API (RFC 7807)
    if (code !== undefined && TRUNK_ERRORS[code]) result.hint = TRUNK_ERRORS[code];
    else if (res.status === 403 && /^Not allowed: [\w:.-]+$/.test(result.message || '')) result.hint = `This API key lacks the ${result.message.slice(13)} permission. Ask the Exotel Memory team to add it to the key.`;
    else if (res.status === 401 || res.status === 403) result.hint = 'Credentials rejected or this API is not enabled on the account. Check `exotel whoami` and the region; ask Exotel support to enable the product if needed.';
    else if (res.status === 429) result.hint = 'Rate limited. Wait and retry.';
    if (code !== undefined) result.code = code;
    if (code === 1008) result.ok = true; // duplicate config is harmless
  }
  return result;
}

module.exports = { approve, hosts, memoryHookBase, expandPath, seg, buildRequest, preview, send, TRUNK_ERRORS, assertExotelHost };
