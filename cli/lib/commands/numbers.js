'use strict';
// ExoPhones (phone numbers): search, buy, list, inspect, route to a flow, release.
// Public ExoPhones API: developer.exotel.com/docs/exophones/api-reference/*
const rest = require('../rest');
const { CliError, EXIT, out } = require('../io');
const { need, opt, run, requireAuth } = require('../core');

const NUMBERS_USAGE = `exotel numbers list [--page 2] [--page-size 100] | --all
exotel numbers get (--exophone-sid <sid> | --number <phone number>)
exotel numbers available [--country IN] [--type landline|mobile|tollfree] [--region KA] [--contains 80] [--sms]
exotel numbers buy --number <number from 'available'> [--app-id <flow> | --voice-url <flow url>] [--sms-url …] [--name …]   $
exotel numbers update (--exophone-sid <sid> | --number <phone>) (--app-id <flow> | --voice-url <flow url>) [--sms-url …] [--name …]          ~
exotel numbers release (--exophone-sid <sid> | --number <phone>)                                                                           !
KYC: Indian numbers need your account's KYC to be approved; Exotel support can help if a purchase is refused.`;

const BASE = '/v2_beta/Accounts/{sid}/IncomingPhoneNumbers';
const TYPES = { landline: 'Landline', mobile: 'Mobile', tollfree: 'TollFree' };
// Flow URLs are Exotel's own (http or https my*.exotel.com) or your https endpoint.
const FLOW_URL = /^(https:\/\/\S+|http:\/\/my(\.[a-z0-9]+)?\.exotel\.com\/\S+)$/;

/** VoiceUrl from --app-id (App Bazaar flow) or --voice-url; same flow URL format the live API returns. */
function voiceUrl(flags) {
  const appId = opt(flags, 'flow', /^\d{1,10}$/, 'app id of your App Bazaar flow');
  const url = opt(flags, 'voice-url', FLOW_URL, 'flow URL (https://…, or http://my.exotel.com/…)');
  if (appId && url) throw new CliError('Pass --app-id or --voice-url, not both', EXIT.USAGE);
  return appId ? `http://my.exotel.com/${requireAuth().account_sid}/exoml/start_voice/${appId}` : url;
}

function routing(flags) {
  return {
    VoiceUrl: voiceUrl(flags),
    SMSUrl: opt(flags, 'sms-url', FLOW_URL, 'flow URL'),
    FriendlyName: opt(flags, 'name', /^.{1,100}$/),
  };
}

const flowOf = (u) => {
  const m = /\/exoml\/start(?:_voice)?\/(\d+)/.exec(u || '');
  return m ? `flow ${m[1]}` : (u ? u.replace(/^https?:\/\//, '').slice(0, 40) : 'not routed');
};
const NUMBER_COLS = [
  { key: 'phone_number', label: 'NUMBER' },
  { key: 'friendly_name', label: 'NAME', max: 28 },
  { key: 'voice_url', label: 'CALLS GO TO', fmt: flowOf },
  { key: 'sid', label: 'EXOPHONE SID' },
];

/** ExoPhone SID from --exophone-sid, or by looking the phone number up across all pages (--number). */
async function resolveSid(flags) {
  if (flags.sid && flags.sid !== true) return { sid: rest.seg(flags.sid, 'exophone-sid'), label: String(flags.sid) };
  const number = need(flags, 'number', /^\+?\d{6,15}$/, 'phone number, or pass --exophone-sid');
  const vb = require('../voicebot');
  const all = await vb.listAllNumbers(requireAuth());
  const hit = all.find((n) => vb.normalize(n.phone_number) === vb.normalize(number));
  if (!hit) throw new CliError(`${number} is not on this account (checked ${all.length} ExoPhones)`, EXIT.ERROR);
  return { sid: encodeURIComponent(hit.sid), label: `${hit.phone_number} (${hit.sid})` };
}

async function numbers(sub, flags) {
  switch (sub) {
    case 'list': {
      if (flags.all) {
        const all = await require('../voicebot').listAllNumbers(requireAuth());
        return out({ ok: true, count: all.length, numbers: all.map((n) => ({ sid: n.sid, phone_number: n.phone_number, friendly_name: n.friendly_name, voice_url: n.voice_url })) },
          () => `${require('../ui').table(all, NUMBER_COLS)}\n${all.length} ExoPhones`);
      }
      const page = opt(flags, 'page', /^\d{1,4}$/);
      return run(flags, {
        kind: 'read', summary: 'List ExoPhones', request: { path: BASE, query: { Page: page ? Math.max(1, Number(page)) : undefined, PageSize: opt(flags, 'page-size', /^([1-9]\d?|100)$/, '1–100') } },
        render: (d) => {
          const ui = require('../ui');
          const rows = (d && d.incoming_phone_numbers) || [];
          const more = d && d.next_page_uri && rows.length ? `\n${ui.c.dim(`More: exotel numbers list --page ${Number(d.page || page || 1) + 1}   ·   everything: exotel numbers list --all`)}` : '';
          return ui.table(rows, NUMBER_COLS) + more;
        },
      });
    }
    case 'get': {
      const { sid, label } = await resolveSid(flags);
      return run(flags, { kind: 'read', summary: `ExoPhone ${label}`, request: { path: `${BASE}/${sid}` } });
    }
    case 'available': {
      const country = (opt(flags, 'country', /^[A-Za-z]{2}$/, 'two-letter country code') || 'IN').toUpperCase();
      const type = TYPES[String(opt(flags, 'type', /^(landline|mobile|tollfree)$/i, 'landline, mobile or tollfree') || 'landline').toLowerCase()];
      return run(flags, {
        kind: 'read', summary: `Available ${type} numbers in ${country}`,
        request: {
          path: `/v2_beta/Accounts/{sid}/AvailablePhoneNumbers/${country}/${type}`,
          query: {
            InRegion: opt(flags, 'region', /^[A-Za-z]{2}$/, 'telecom circle, e.g. KA, DL, MH'),
            Contains: opt(flags, 'contains', /^\d{1,10}$/, 'digits'),
            IncomingSMS: flags.sms ? 'true' : undefined,
          },
        },
        render: (d) => {
          const list = Array.isArray(d) ? d : (d && (d.AvailablePhoneNumbers || d.available_phone_numbers || d.data)) || [];
          return list.length
            ? list.map((n) => {
              const money = (v) => (v === undefined || v === null || v === '' ? '?' : String(Number(v)));
              return `${n.phone_number || n.PhoneNumber}  ${n.region || n.Region || ''}  ${n.number_type || ''}  ₹${money(n.one_time_price ?? n.OneTimePrice)} one-time · ₹${money(n.rental_price ?? n.RentalPrice)}/month`;
            }).join('\n')
              + `\nBuy one: exotel numbers buy --number <PhoneNumber> [--app-id <flow>]`
            : 'No numbers match. Try another --type or --region.';
        },
      });
    }
    case 'buy': {
      const number = need(flags, 'number', /^\+?\d{6,15}$/, "a number from 'exotel numbers available'");
      const form = { PhoneNumber: number, ...routing(flags) };
      return run(flags, {
        kind: 'spend',
        summary: `Buy ExoPhone ${number} (one-time + monthly rental charges apply)${form.VoiceUrl ? `, route calls to ${form.VoiceUrl}` : ''}`,
        request: { method: 'POST', path: BASE, form },
      });
    }
    case 'update': {
      const form = routing(flags);
      if (!Object.values(form).some(Boolean)) throw new CliError('Nothing to update: pass --app-id, --voice-url, --sms-url or --name', EXIT.USAGE);
      const { sid, label } = await resolveSid(flags);
      return run(flags, {
        kind: form.VoiceUrl || form.SMSUrl ? 'live' : 'write',
        summary: `Update ExoPhone ${label}: ${Object.entries(form).filter(([, v]) => v).map(([k, v]) => `${k}=${v}`).join(', ')} — incoming calls/SMS to this number change immediately`,
        request: { method: 'PUT', path: `${BASE}/${sid}`, form },
      });
    }
    case 'release': {
      const { sid, label } = await resolveSid(flags);
      return run(flags, {
        kind: 'destructive',
        summary: `Release ExoPhone ${label}: the number leaves your account permanently and may be given to someone else. Check no flow or campaign uses it`,
        request: { method: 'DELETE', path: `${BASE}/${sid}` },
      });
    }
    default:
      throw new CliError(`Usage:\n${NUMBERS_USAGE}`, EXIT.USAGE);
  }
}

module.exports = { numbers, NUMBERS_USAGE };
