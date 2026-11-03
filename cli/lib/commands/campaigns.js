'use strict';
// Outbound call campaigns: /v2/accounts/{sid}/campaigns (+ /lists, /contacts).
// Docs: developer.exotel.com/docs/campaigns/*, campaign-lists/*, campaigns-contacts/*
const rest = require('../rest');
const { CliError, EXIT } = require('../io');
const { need, opt, run, PHONE, requireAuth } = require('../core');

const CAMPAIGNS_USAGE = `Outbound call campaigns:
  exotel campaigns list [--status InProgress,Paused] [--name …] [--limit 20]
  exotel campaigns get --campaign-sid <id>
  exotel campaigns calls --campaign-sid <id> [--status completed|failed|busy|no-answer] [--limit 50]
  exotel campaigns lists                                         contact lists (for --lists)
  exotel campaigns create --caller-id <ExoPhone> (--numbers <a,b,…> | --lists <L1,…>) (--app-id <flow> | --text "greeting")
        [--name …] [--retries 0-3 --retry-on busy,no-answer,failed --retry-interval 15 --retry-mechanism Linear|Exponential]
        [--send-at 2026-11-04T09:00:00+05:30 --end-at …] [--throttle <calls/min>] [--custom-field …]
        [--call-status-callback https://… --call-schedule-callback https://… --status-callback https://…]      $
  exotel campaigns pause|resume --campaign-sid <id>                                                         ~
  exotel campaigns complete --campaign-sid <id>     (remaining calls marked failed)                          !
  exotel campaigns archive|delete --campaign-sid <id>                                                       !
Limits: 5,000 numbers per campaign, up to 3 retries, 60 calls/min default capacity, 30-day default duration.`;

const C = '/v2/accounts/{sid}/campaigns';
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?([+-]\d{2}:\d{2}|Z)$/;

function createBody(flags) {
  const numbers = opt(flags, 'numbers', /^\+?\d{6,15}(,\+?\d{6,15})*$/, 'comma-separated numbers');
  const lists = opt(flags, 'lists', /^[\w-]+(,[\w-]+){0,4}$/, 'up to 5 list SIDs');
  if (!numbers === !lists) throw new CliError('Pass exactly one of --numbers or --lists', EXIT.USAGE);
  if (numbers && numbers.split(',').length > 5000) throw new CliError('--numbers: at most 5,000 per campaign', EXIT.USAGE);
  const appId = opt(flags, 'flow', /^\d{1,10}$/, 'app id of your App Bazaar flow');
  const text = flags.text && flags.text !== true ? String(flags.text) : undefined;
  if (!appId === !text) throw new CliError('Pass exactly one of --app-id (IVR flow) or --text (greeting, supports @@Column variables)', EXIT.USAGE);
  const retries = flags.retries !== undefined ? Number(need(flags, 'retries', /^[0-3]$/, '0–3')) : undefined;
  const body = {
    caller_id: need(flags, 'caller-id', PHONE, 'ExoPhone'),
    ...(numbers ? { from: numbers } : { lists: lists.split(',') }),
    ...(lists && flags['campaign-type'] ? { campaign_type: need(flags, 'campaign-type', /^(static|dynamic)$/) } : {}),
    ...(appId ? { flow_type: 'ivr', url: `http://my.exotel.com/${requireAuth().account_sid}/exoml/start_voice/${appId}` } : { flow_type: 'greeting', read_via_text: text }),
    name: opt(flags, 'name', /^.{3,100}$/, '3+ characters'),
    retries: retries === undefined ? undefined : {
      number_of_retries: retries,
      interval_mins: Number(opt(flags, 'retry-interval', /^\d{1,4}$/) || 15),
      mechanism: opt(flags, 'retry-mechanism', /^(Linear|Exponential)$/) || 'Linear',
      on_status: (opt(flags, 'retry-on', /^(busy|no-answer|failed)(,(busy|no-answer|failed))*$/) || 'busy,no-answer').split(','),
    },
    schedule: flags['send-at'] || flags['end-at'] ? { send_at: opt(flags, 'send-at', RFC3339, 'RFC 3339, e.g. 2026-11-04T09:00:00+05:30'), end_at: opt(flags, 'end-at', RFC3339, 'RFC 3339') } : undefined,
    mode: flags.throttle !== undefined ? 'custom' : undefined,
    throttle: flags.throttle !== undefined ? Number(need(flags, 'throttle', /^\d{1,4}$/, 'calls per minute')) : undefined,
    custom_field: opt(flags, 'custom-field'),
    call_status_callback: opt(flags, 'call-status-callback', /^https?:\/\//),
    call_schedule_callback: opt(flags, 'call-schedule-callback', /^https?:\/\//),
    status_callback: opt(flags, 'status-callback', /^https?:\/\//),
  };
  Object.keys(body).forEach((k) => body[k] === undefined && delete body[k]);
  return body;
}

function campaigns(sub, flags) {
  const id = () => rest.seg(flags.campaign, 'campaign-sid');
  switch (sub) {
    case 'list':
      return run(flags, {
        kind: 'read', summary: 'List campaigns',
        request: { path: C, query: { limit: opt(flags, 'limit', /^\d{1,3}$/) || 20, offset: opt(flags, 'offset', /^\d+$/), status: opt(flags, 'status', /^[\w,]+$/), name: opt(flags, 'name'), sort_by: 'send_at:desc' } }, // live API: name|send_at only
      });
    case 'get':
      return run(flags, { kind: 'read', summary: `Campaign ${flags.campaign}`, request: { path: `${C}/${id()}` } });
    case 'calls':
      return run(flags, { kind: 'read', summary: `Calls of campaign ${flags.campaign}`, request: { path: `${C}/${id()}/call-details`, query: { limit: opt(flags, 'limit', /^\d{1,3}$/) || 50, status: opt(flags, 'status', /^(completed|failed|busy|no-answer)$/), sort_by: 'date_created:desc' } } });
    case 'lists':
      return run(flags, { kind: 'read', summary: 'List contact lists', request: { path: '/v2/accounts/{sid}/lists', query: { limit: opt(flags, 'limit', /^\d{1,3}$/) || 20 } } });
    case 'create': {
      const body = createBody(flags);
      const n = body.from ? body.from.split(',').length : `list(s) ${body.lists.join(',')}`;
      // The live API expects {"campaigns": [ {...} ]} ("Campaigns is mandatory" otherwise); the developer portal shows a flat body.
      return run(flags, { kind: 'spend', summary: `Create a call campaign to ${n} number(s) from ${body.caller_id}${body.schedule ? ` (scheduled ${body.schedule.send_at || ''})` : ' (starts now)'}`, request: { method: 'POST', path: C, json: { campaigns: [body] } } });
    }
    case 'pause':
    case 'resume':
      return run(flags, { kind: 'live', summary: `${sub} campaign ${flags.campaign}`, request: { method: 'PUT', path: `${C}/${id()}`, json: { action: sub } } });
    case 'complete':
    case 'archive':
      return run(flags, { kind: 'destructive', summary: `${sub} campaign ${flags.campaign}${sub === 'complete' ? ' (remaining calls are marked failed)' : ''}`, request: { method: 'PUT', path: `${C}/${id()}`, json: { action: sub } } });
    case 'delete':
      return run(flags, { kind: 'destructive', summary: `Delete campaign ${flags.campaign}`, request: { method: 'DELETE', path: `${C}/${id()}` } });
    default:
      throw new CliError(CAMPAIGNS_USAGE, EXIT.USAGE);
  }
}

module.exports = { campaigns, createBody, CAMPAIGNS_USAGE };
