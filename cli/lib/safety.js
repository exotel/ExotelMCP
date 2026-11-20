'use strict';
// Classifies Exotel MCP tools so the CLI asks before anything that changes or costs something.
// Deny by default: a tool is 'safe' (runs without asking) only when its name is clearly a read AND contains no
// write verb. Everything else needs confirmation:
//   spend       billed or reaches a real person (calls, SMS/WhatsApp, campaigns, billed analyses, LLM runs)
//   destructive cannot be undone (delete, revoke, terminate, archive, detach)
//   write       any other change (create, update, publish, attach, configure, API keys, logins…)

const SPEND = [
  /place_call/, /call_initiate/, /connect_two_numbers/, /connect_to_call_flow/, /connect_voice_ai/,
  /sms_send/, /whatsapp_send/, /engage_create/, /tenant_spawn/, /cqa_ingest/, /smoke_test/, /version_test/,
  /improve_description/,
];
const DESTRUCTIVE = [/(^|_)delete($|_)/, /revoke/, /terminate/, /archive/, /detach/];
// Any of these words in a tool name means it changes something, even if it also says get/list.
const WRITE_VERB = /(^|_)(create|update|delete|publish|send|place|initiate|connect|spawn|terminate|revoke|archive|detach|attach|ingest|import|upload|login|configure|duplicate|push|improve|smoke|test|set|add|remove|assign|enable|disable|start|stop|pause|resume|retry|run|execute|trigger|reset|rotate)(_|$)/;
const READ = /(^|_)(get|list|search|status|details|metadata|whoami|guide|track|timeline|settings|wait|build|play|open|download|template|providers|voices|languages)(_|$)/;

function classify(toolName) {
  const name = String(toolName).replace(/^exotel_/, '');
  if (SPEND.some((r) => r.test(name))) return 'spend';
  if (DESTRUCTIVE.some((r) => r.test(name))) return 'destructive';
  if (!WRITE_VERB.test(name) && READ.test(name)) return 'safe';
  return 'write';
}

module.exports = { classify };
