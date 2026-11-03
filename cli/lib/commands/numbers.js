'use strict';
// ExoPhones (phone numbers): list, inspect and route. Buying/releasing is via Exotel support for now.
const rest = require('../rest');
const { CliError, EXIT } = require('../io');
const { opt, run } = require('../core');

const COMING_SOON = 'Buying and releasing numbers from the CLI is coming soon. For now, get or release ExoPhones through Exotel support (hello@exotel.com) or your account manager. `exotel numbers list` shows the numbers you already have.';

// ------------------------------------------------------------------ numbers (ExoPhones)

const NUMBERS_USAGE = `exotel numbers list
exotel numbers get --exophone-sid <sid>
exotel numbers update --exophone-sid <sid> [--voice-url <flow url>] [--sms-url …] [--name …]
Buying / releasing numbers: coming soon. Contact Exotel support (hello@exotel.com) for now.`;

function numbers(sub, flags) {
  const base = '/v2_beta/Accounts/{sid}/IncomingPhoneNumbers';
  switch (sub) {
    case 'list':
      return run(flags, { kind: 'read', summary: 'List ExoPhones', request: { path: base } });
    case 'get': {
      const sid = rest.seg(flags.sid, 'exophone-sid');
      return run(flags, { kind: 'read', summary: `ExoPhone ${flags.sid}`, request: { path: `${base}/${sid}` } });
    }
    case 'available':
    case 'buy':
    case 'release':
      throw new CliError(COMING_SOON, EXIT.USAGE, { coming_soon: true });
    case 'update': {
      const sid = rest.seg(flags.sid, 'exophone-sid');
      const form = { VoiceUrl: opt(flags, 'voice-url', /^https:\/\//, 'https URL'), SMSUrl: opt(flags, 'sms-url', /^https:\/\//, 'https URL'), FriendlyName: opt(flags, 'name') };
      if (!Object.values(form).some(Boolean)) throw new CliError('Nothing to update: pass --voice-url, --sms-url or --name', EXIT.USAGE);
      return run(flags, {
        kind: form.VoiceUrl || form.SMSUrl ? 'live' : 'write',
        summary: `Update ExoPhone ${flags.sid}: ${Object.entries(form).filter(([, v]) => v).map(([k, v]) => `${k}=${v}`).join(', ')} — incoming calls/SMS to this number change immediately`,
        request: { method: 'PUT', path: `${base}/${sid}`, form },
      });
    }
    default:
      throw new CliError(`Usage:\n${NUMBERS_USAGE}`, EXIT.USAGE);
  }
}

module.exports = { numbers, NUMBERS_USAGE };
