'use strict';
// Product commands (one module per Exotel product under ./commands). Kept as a single import for bin/exotel.js.
const account = require('./commands/account');
const numbers = require('./commands/numbers');
const trunk = require('./commands/trunk');
const legs = require('./commands/legs');
const tools = require('./commands/tools');
const voiceAi = require('./commands/voice-ai');
const callsCmd = require('./commands/calls');
const campaignsCmd = require('./commands/campaigns');

module.exports = {
  balance: account.balance,
  api: account.api,
  numbers: numbers.numbers,
  trunk: trunk.trunk,
  trunkReq: trunk.trunkReq,
  leg: legs.leg,
  bridge: legs.bridge,
  eventsCmd: legs.eventsCmd,
  exomlCmd: tools.exomlCmd,
  appletCmd: tools.appletCmd,
  voiceAi: voiceAi.voiceAi,
  calls: callsCmd.calls,
  campaigns: campaignsCmd.campaigns,
  USAGE: { NUMBERS_USAGE: numbers.NUMBERS_USAGE, TRUNK_USAGE: trunk.TRUNK_USAGE, LEG_USAGE: legs.LEG_USAGE, VOICE_AI_USAGE: voiceAi.VOICE_AI_USAGE, CALLS_USAGE: callsCmd.CALLS_USAGE, CAMPAIGNS_USAGE: campaignsCmd.CAMPAIGNS_USAGE },
};
