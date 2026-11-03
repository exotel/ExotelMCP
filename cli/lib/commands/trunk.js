'use strict';
// Dynamic SIP trunking: /v2/accounts/{sid}/trunks and sub-resources.
const rest = require('../rest');
const applet = require('../applet');
const { CliError, EXIT, out, info, confirm, readSecret } = require('../io');
const { E164, IPV4, requireAuth: auth, need, opt, run } = require('../core');

// ------------------------------------------------------------------ trunk (Dynamic SIP trunking)

const TRUNK_USAGE = `exotel trunk setup --name <name> [--number <E.164>] [--ip <IPv4>] [--uri <host:port>] [--transport tls|tcp] [--mode pstn|flow] [--caller-id <E.164>]
exotel trunk create --name <name ≤16 chars, letters/digits/_>
exotel trunk map-number --trunk-sid <sid> --number <E.164> [--mode pstn|flow]
exotel trunk set-number-mode --trunk-sid <sid> --id <phone_number_id> --number <E.164> --mode pstn|flow
exotel trunk whitelist-ip --trunk-sid <sid> --ip <IPv4> [--mask 32]
exotel trunk destination --trunk-sid <sid> --uri <ip_or_fqdn:port> [--transport tls|tcp]
exotel trunk credentials add --trunk-sid <sid> --user <name> [--label <friendly name>]   (password: prompt, EXOTEL_SIP_PASSWORD or --password-stdin)
exotel trunk credentials list --trunk-sid <sid>
exotel trunk credentials delete --trunk-sid <sid> --id <credential_id>
exotel trunk caller-id --trunk-sid <sid> --number <E.164>
exotel trunk delete --trunk-sid <sid>`;

const T = (sid) => `/v2/accounts/{sid}/trunks/${sid}`;

function trunkReq(sub, flags, a) {
  switch (sub) {
    case 'create': {
      const name = need(flags, 'name', /^[A-Za-z0-9_]{1,16}$/, 'letters, digits, underscore; max 16');
      return {
        kind: 'write', summary: `Create SIP trunk ${name}`,
        request: { method: 'POST', path: '/v2/accounts/{sid}/trunks', json: { trunk_name: name, nso_code: flags.nso || 'any any', domain_name: `${a.account_sid}.pstn.exotel.com` } },
      };
    }
    case 'map-number': {
      const trunk = rest.seg(flags.trunk, 'trunk-sid');
      const number = need(flags, 'number', E164, 'E.164, e.g. +919876543210');
      const mode = opt(flags, 'mode', /^(pstn|flow)$/, 'pstn or flow') || 'pstn';
      return { kind: 'live', summary: `Route ${number} through trunk ${flags.trunk} (mode ${mode})`, request: { method: 'POST', path: `${T(trunk)}/phone_numbers`, json: { phone_number: number, mode } } };
    }
    case 'set-number-mode': {
      const trunk = rest.seg(flags.trunk, 'trunk-sid');
      const id = rest.seg(need(flags, 'id', /^\d+$/, 'numeric id returned by map-number'), 'id');
      const number = need(flags, 'number', E164, 'E.164');
      const mode = need(flags, 'mode', /^(pstn|flow)$/, 'pstn or flow');
      return { kind: 'live', summary: `Switch ${number} on trunk ${flags.trunk} to ${mode} routing`, request: { method: 'PUT', path: `${T(trunk)}/phone_numbers/${id}`, json: { phone_number: number, mode } } };
    }
    case 'whitelist-ip': {
      const trunk = rest.seg(flags.trunk, 'trunk-sid');
      const ip = need(flags, 'ip', IPV4, 'public IPv4');
      const mask = Number(opt(flags, 'mask', /^([0-9]|[12]\d|3[0-2])$/, '0–32') || 32);
      if (mask < 24) info(`Warning: /${mask} whitelists ${2 ** (32 - mask)} addresses.`);
      return { kind: 'write', summary: `Allow outbound SIP from ${ip}/${mask} on trunk ${flags.trunk}`, request: { method: 'POST', path: `${T(trunk)}/whitelisted_ips`, json: { ip, mask } } };
    }
    case 'destination': {
      const trunk = rest.seg(flags.trunk, 'trunk-sid');
      let uri = need(flags, 'uri', /^(sip:)?[A-Za-z0-9.-]+(:\d{2,5})?(;transport=(tls|tcp))?$/i, 'host or IP with optional :port');
      uri = uri.replace(/^sip:/i, '');
      const transport = (opt(flags, 'transport', /^(tls|tcp)$/i, 'tls or tcp') || (/;transport=/i.test(uri) ? uri.split(/;transport=/i)[1] : 'tls')).toLowerCase();
      let hostPort = uri.split(';')[0];
      if (!/:\d+$/.test(hostPort)) hostPort += transport === 'tls' ? ':5061' : ':5060';
      const destination = `${hostPort};transport=${transport}`;
      return { kind: 'live', summary: `Send inbound calls on trunk ${flags.trunk} to ${destination}`, request: { method: 'POST', path: `${T(trunk)}/destination_uris`, json: { destinations: [{ destination }] } } };
    }
    case 'caller-id': {
      const trunk = rest.seg(flags.trunk, 'trunk-sid');
      const number = need(flags, 'number', E164, 'E.164 ExoPhone');
      return { kind: 'live', summary: `Show ${number} as caller ID on trunk ${flags.trunk}`, request: { method: 'POST', path: `${T(trunk)}/settings`, json: { settings: [{ name: 'trunk_external_alias', value: number }] } } };
    }
    case 'delete': {
      const trunk = rest.seg(flags.trunk, 'trunk-sid');
      return { kind: 'destructive', summary: `Delete trunk ${flags.trunk} and all its numbers, IP whitelist entries and destinations`, request: { method: 'DELETE', path: '/v2/accounts/{sid}/trunks', query: { trunk_sid: flags.trunk } } };
    }
    default:
      return null;
  }
}

async function trunkCredentials(action, flags) {
  const trunk = rest.seg(flags.trunk, 'trunk-sid');
  if (action === 'list') return run(flags, { kind: 'read', summary: `Credentials on trunk ${flags.trunk}`, request: { path: `${T(trunk)}/credentials` } });
  if (action === 'delete') {
    const id = need(flags, 'id', /^[A-Za-z0-9_-]+$/);
    return run(flags, { kind: 'destructive', summary: `Delete SIP credential ${id} from trunk ${flags.trunk} — devices using it stop authenticating`, request: { method: 'DELETE', path: `${T(trunk)}/credentials`, query: { id } } });
  }
  if (action === 'add') {
    const user = need(flags, 'user', /^[A-Za-z0-9_.-]{3,64}$/, '3–64 letters, digits, _ . -');
    const label = opt(flags, 'label', /^.{1,32}$/, 'max 32 chars');
    const password = flags['dry-run'] ? '<hidden>' : await readSecret({ envKey: 'EXOTEL_SIP_PASSWORD', stdinFlag: 'password-stdin', flags, prompt: 'SIP password' });
    if (!flags['dry-run'] && (password.length < 12 || !/[A-Za-z]/.test(password) || !/\d/.test(password))) {
      throw new CliError('Use a strong SIP password: at least 12 characters with letters and digits.', EXIT.USAGE);
    }
    return run(flags, { kind: 'write', summary: `Add SIP digest user ${user} to trunk ${flags.trunk}`, request: { method: 'POST', path: `${T(trunk)}/credentials`, json: { user_name: user, password, friendly_name: label } } });
  }
  throw new CliError(`Usage:\n${TRUNK_USAGE}`, EXIT.USAGE);
}

/** One-shot: create → map number → whitelist IP → destination → caller ID. Stops at the first failure and reports what exists. */
async function trunkSetup(flags) {
  const a = auth();
  const steps = [];
  const plan = [['create', {}]];
  if (flags.number) plan.push(['map-number', { number: flags.number, mode: flags.mode }]);
  if (flags.ip) plan.push(['whitelist-ip', { ip: flags.ip, mask: flags.mask }]);
  if (flags.uri) plan.push(['destination', { uri: flags.uri, transport: flags.transport }]);
  if (flags['caller-id']) plan.push(['caller-id', { number: flags['caller-id'] }]);
  // Validate every step before touching anything.
  const placeholder = flags.trunk || 'TK_NEW';
  for (const [sub, extra] of plan) trunkReq(sub, { ...flags, ...extra, trunk: sub === 'create' ? undefined : placeholder }, a);
  if (!flags['dry-run']) {
    await confirm('live', `Set up SIP trunk ${flags.name}: ${plan.map(([s]) => s).join(' → ')}`, flags);
  }
  let trunkSid = flags['dry-run'] ? placeholder : null;
  for (const [sub, extra] of plan) {
    const spec = trunkReq(sub, { ...flags, ...extra, trunk: trunkSid || undefined }, a);
    const res = await run({ ...flags, yes: true }, { ...spec, quiet: true });
    const data = res.data;
    steps.push({ step: sub, ok: res.ok, ...(res.dryRun ? { dryRun: true, request: rest.preview(rest.buildRequest(a, spec.request)) } : { status: res.status, data }), ...(res.hint ? { hint: res.hint } : {}) });
    if (!res.ok) break;
    if (sub === 'create' && !res.dryRun) {
      trunkSid = data && (data.trunk_sid || (data.data && data.data.trunk_sid));
      if (!trunkSid) {
        steps.push({ step: 'create', ok: false, error: 'Trunk created but no trunk_sid in the response; continue manually with --trunk' });
        break;
      }
    }
  }
  const ok = steps.every((s) => s.ok);
  out({ ok, trunkSid, steps }, () => [
    ...steps.map((s) => `${s.ok ? '✔' : '✘'} ${s.step}${s.status ? ` (HTTP ${s.status})` : ''}${s.hint ? ` — ${s.hint}` : ''}${s.error ? ` — ${s.error}` : ''}`),
    trunkSid ? `Trunk SID: ${trunkSid}` : '',
    ok && flags.uri ? `Inbound: in App Bazaar, add a Connect applet with Dial whom = sip:${trunkSid}` : '',
  ].filter(Boolean).join('\n'));
  if (!ok) process.exitCode = EXIT.ERROR;
}

async function trunk(sub, rest_, flags) {
  if (sub === 'setup') return trunkSetup(flags);
  if (sub === 'credentials') return trunkCredentials(rest_[0], flags);
  const spec = trunkReq(sub, flags, auth());
  if (!spec) throw new CliError(`Usage:\n${TRUNK_USAGE}`, EXIT.USAGE);
  return run(flags, spec);
}

module.exports = { trunk, trunkReq, TRUNK_USAGE };
