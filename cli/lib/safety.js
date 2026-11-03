'use strict';
// Classifies Exotel MCP tools so the CLI can require explicit confirmation before
// anything that is billed, reaches a real person, or cannot be undone.

const SPEND = [
  /place_call/, /call_initiate/, /connect_two_numbers/, /connect_to_call_flow/, /connect_voice_ai/,
  /sms_send/, /whatsapp_send/, /engage_create/, /tenant_spawn/,
];
const DESTRUCTIVE = [/_delete($|_)/, /revoke/, /terminate/, /archive/, /detach/];

function classify(toolName) {
  if (SPEND.some((r) => r.test(toolName))) return 'spend';
  if (DESTRUCTIVE.some((r) => r.test(toolName))) return 'destructive';
  return 'safe';
}

module.exports = { classify };
