'use strict';
// Account-level commands: balance and the raw `exotel api` fallback for any Exotel REST endpoint.
const fs = require('fs');
const { CliError, EXIT } = require('../io');
const { run } = require('../core');

// ------------------------------------------------------------------ balance

function balance(flags) {
  return run(flags, {
    kind: 'read', summary: 'Account balance',
    request: { path: '/v1/Accounts/{sid}/Balance.json' },
  });
}

// ------------------------------------------------------------------ raw API fallback

function parseKv(s) {
  if (!s || s === true) return undefined;
  const o = {};
  for (const [k, v] of new URLSearchParams(String(s))) o[k] = v;
  return o;
}

function api(args, flags) {
  const [method, path] = args;
  if (!method || !path || !/^(GET|POST|PUT|PATCH|DELETE)$/i.test(method) || !path.startsWith('/')) {
    throw new CliError(`Usage: exotel api <GET|POST|PUT|PATCH|DELETE> </path with {sid}> [--host api|cpaas] [--query 'a=1&b=2'] [--data '{json}'|@file.json] [--form 'a=1&b=2']
Example: exotel api GET /v1/Accounts/{sid}/Calls.json --query 'PageSize=5'`, EXIT.USAGE);
  }
  let json;
  if (flags.data && flags.data !== true) {
    const raw = String(flags.data).startsWith('@') ? fs.readFileSync(String(flags.data).slice(1), 'utf8') : String(flags.data);
    try {
      json = JSON.parse(raw);
    } catch (e) {
      throw new CliError(`--data is not valid JSON: ${e.message}`, EXIT.USAGE);
    }
  }
  const m = method.toUpperCase();
  return run(flags, {
    kind: m === 'GET' ? 'read' : m === 'DELETE' ? 'destructive' : 'live',
    summary: `${m} ${path}`,
    request: { method: m, host: flags.host === 'cpaas' ? 'cpaas' : 'api', path, query: parseKv(flags.query), json, form: json ? undefined : parseKv(flags.form) },
  });
}

module.exports = { balance, api };
