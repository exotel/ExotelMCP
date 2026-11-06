'use strict';
// Credential storage for the Exotel CLI.
// File: $EXOTEL_CONFIG_DIR or $XDG_CONFIG_HOME/exotel or ~/.config/exotel → credentials.json (mode 0600).
// The stored object is exactly the JSON the Exotel MCP server expects in its Authorization header.
const fs = require('fs');
const os = require('os');
const path = require('path');

const SECRET_KEYS = new Set([
  'token', 'voicebot_api_key', 'voicebot_api_token', 'calls_api_key', 'calls_api_token', 'cqa_api_key',
]);

const REGIONS = {
  in: 'https://api.in.exotel.com',
  mumbai: 'https://api.in.exotel.com',
  sg: 'https://api.exotel.com',
  singapore: 'https://api.exotel.com',
};

function configDir() {
  if (process.env.EXOTEL_CONFIG_DIR) return process.env.EXOTEL_CONFIG_DIR;
  const base = process.env.XDG_CONFIG_HOME || path.join(os.homedir(), '.config');
  return path.join(base, 'exotel');
}

function credentialsPath() {
  return path.join(configDir(), 'credentials.json');
}

/** Build the Authorization-header object from plain inputs. */
function buildAuth({ accountSid, apiKey, apiToken, apiDomain, callerId, fromNumber, whatsappFrom,
  voicebotApiKey, voicebotApiToken, voicebotAccountId, cqaApiKey, cqaAccountId, cqaHost, portalUrl }) {
  const auth = {
    token: Buffer.from(`${apiKey}:${apiToken}`).toString('base64'),
    account_sid: accountSid,
    api_domain: apiDomain,
    // Mumbai accounts use my.in.exotel.com (dashboard links, flow URLs shown to the user); Singapore uses my.exotel.com.
    exotel_portal_url: portalUrl || (/\.in\.exotel\.com/.test(String(apiDomain)) ? 'https://my.in.exotel.com' : 'https://my.exotel.com'),
  };
  if (callerId) auth.caller_id = callerId;
  if (fromNumber || callerId) auth.from_number = fromNumber || callerId;
  if (whatsappFrom) auth.whatsapp_from = whatsappFrom;
  // VoiceBot and Calls APIs normally use the same key/token/SID as CPaaS.
  auth.voicebot_api_key = voicebotApiKey || apiKey;
  auth.voicebot_api_token = voicebotApiToken || apiToken;
  auth.voicebot_account_id = voicebotAccountId || accountSid;
  auth.calls_api_key = voicebotApiKey || apiKey;
  auth.calls_api_token = voicebotApiToken || apiToken;
  auth.calls_account_id = voicebotAccountId || accountSid;
  auth.calls_base_url = apiDomain;
  if (cqaApiKey) {
    auth.cqa_api_key = cqaApiKey;
    auth.cqa_account_id = cqaAccountId || accountSid;
    auth.cqa_host = cqaHost || 'https://cqa-console.in.exotel.com';
  }
  return auth;
}

/** Credentials from the environment, if complete. EXOTEL_AUTH_JSON wins over the individual variables. */
function fromEnv(env = process.env) {
  if (env.EXOTEL_AUTH_JSON) {
    try {
      return JSON.parse(env.EXOTEL_AUTH_JSON);
    } catch {
      throw new Error('EXOTEL_AUTH_JSON is not valid JSON');
    }
  }
  if (env.EXOTEL_ACCOUNT_SID && env.EXOTEL_API_KEY && env.EXOTEL_API_TOKEN) {
    return buildAuth({
      accountSid: env.EXOTEL_ACCOUNT_SID,
      apiKey: env.EXOTEL_API_KEY,
      apiToken: env.EXOTEL_API_TOKEN,
      apiDomain: env.EXOTEL_API_BASE_URL || env.EXOTEL_API_DOMAIN || REGIONS.in,
      callerId: env.EXOTEL_CALLER_ID,
      fromNumber: env.EXOTEL_FROM_NUMBER,
      whatsappFrom: env.EXOTEL_WHATSAPP_FROM,
      cqaApiKey: env.EXOTEL_CQA_API_KEY,
      cqaAccountId: env.EXOTEL_CQA_ACCOUNT_ID,
      cqaHost: env.EXOTEL_CQA_HOST,
    });
  }
  return null;
}

function load() {
  const env = fromEnv();
  if (env) return { auth: env, source: 'env' };
  const file = credentialsPath();
  if (!fs.existsSync(file)) return null;
  const auth = JSON.parse(fs.readFileSync(file, 'utf8'));
  return { auth, source: file };
}

function save(auth) {
  const dir = configDir();
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const file = credentialsPath();
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(auth, null, 2) + '\n', { mode: 0o600 });
  fs.renameSync(tmp, file);
  fs.chmodSync(file, 0o600);
  return file;
}

function remove() {
  const file = credentialsPath();
  if (fs.existsSync(file)) {
    fs.rmSync(file);
    return true;
  }
  return false;
}

function maskValue(key, value) {
  if (typeof value !== 'string') return value;
  if (!SECRET_KEYS.has(key)) return value;
  if (value.length <= 8) return '****';
  return `${value.slice(0, 4)}…${value.slice(-4)}`;
}

function mask(auth) {
  const out = {};
  for (const [k, v] of Object.entries(auth || {})) out[k] = maskValue(k, v);
  return out;
}

function products(auth) {
  const p = [];
  if (auth.token && auth.account_sid) p.push('cpaas');
  if (auth.voicebot_api_key && auth.voicebot_account_id) p.push('voicebot');
  if (auth.whatsapp_from) p.push('whatsapp');
  if (auth.cqa_api_key) p.push('cqa');
  return p;
}

/** Decode key/token from the base64 token field. */
function apiKeyPair(auth) {
  const decoded = Buffer.from(auth.token || '', 'base64').toString('utf8');
  const i = decoded.indexOf(':');
  if (i < 0) return null;
  return { apiKey: decoded.slice(0, i), apiToken: decoded.slice(i + 1) };
}

/** Best-effort credential check against the Exotel CPaaS API (read-only call-list endpoint). */
async function verify(auth, { timeoutMs = 15000 } = {}) {
  const url = `${auth.api_domain.replace(/\/$/, '')}/v1/Accounts/${encodeURIComponent(auth.account_sid)}/Calls.json?PageSize=1`;
  const res = await fetch(url, {
    headers: { Authorization: `Basic ${auth.token}`, Accept: 'application/json' },
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (res.status === 200) return { ok: true, status: 200 };
  const body = (await res.text()).slice(0, 300);
  return { ok: false, status: res.status, body };
}

module.exports = {
  REGIONS, configDir, credentialsPath, buildAuth, fromEnv, load, save, remove, mask, products, apiKeyPair, verify,
};
