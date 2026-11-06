#!/usr/bin/env node
'use strict';
// Writes the "Command reference" page for developer.exotel.com from the CLI's own help text, so the
// docs can't drift from the code. Usage:
//   node scripts/docs-commands.js > <developer-docs>/docs/cli/commands.md
const { execFileSync } = require('child_process');
const path = require('path');

const BIN = path.join(__dirname, '..', 'bin', 'exotel.js');
const pkg = require('../package.json');
const help = (args) => execFileSync(process.execPath, [BIN, ...args], {
  env: { ...process.env, NO_COLOR: '1', EXOTEL_CONFIG_DIR: '/nonexistent', NODE_NO_WARNINGS: '1' },
}).toString().replace(/\s+$/, '');

const GROUPS = [
  ['Get started', [['init', 'Log in, connect your coding agents, install skills'], ['config', 'Saved defaults'], ['completion', 'Tab-completion']]],
  ['Calls and voice AI', [['call', 'Guided test call'], ['voice-ai', 'Your own bot over WebSocket (AgentStream)'], ['voicebot', 'Exotel-hosted VoiceBots'], ['integrate', 'ElevenLabs and Bolna']]],
  ['Numbers, records, campaigns', [['numbers', 'Phone numbers (ExoPhones)'], ['calls', 'Call logs and recordings'], ['campaigns', 'Outbound call campaigns']]],
  ['Call control (Legs API + ExoML)', [['leg', 'Legs, bridges and ExoML actions'], ['events', 'Receive call events locally'], ['exoml', 'Validate ExoML']]],
  ['Flows, SIP and anything else', [['applet', 'Test or scaffold flow webhooks'], ['trunk', 'SIP trunking'], ['mcp', 'Exotel MCP server and tools'], ['skill', 'Agent skills'], ['api', 'Any Exotel REST endpoint']]],
  ['Help', [['ask', 'Which API to use'], ['explain', 'What a status or error code means'], ['docs', 'Search the Exotel docs']]],
];

const out = [`---
id: commands
title: Exotel CLI Command Reference
description: "Every exotel command and option: calls, voice AI, VoiceBots, numbers, call records, campaigns, Legs API and ExoML, SIP trunks, MCP and help commands."
sidebar_label: Command reference
sidebar_position: 4
---

# Command reference

Generated from \`exotel-cli\` ${pkg.version} (\`exotel help <command>\`). Run \`exotel help <command>\` for the version you have installed.

Markers: \`$\` billed, \`~\` changes live call routing, \`!\` cannot be undone. These ask for confirmation (or need \`--yes\`). Every command accepts \`--json\`, and the ones that call Exotel accept \`--dry-run\`.
`];
for (const [section, cmds] of GROUPS) {
  out.push(`## ${section}\n`);
  for (const [cmd, title] of cmds) {
    let text;
    try {
      text = help(['help', cmd]);
    } catch (e) {
      text = (e.stdout || '').toString().trim();
    }
    out.push(`### exotel ${cmd}\n\n${title}.\n\n\`\`\`text\n${text}\n\`\`\`\n`);
  }
}
out.push(`## All commands\n\n\`\`\`text\n${help(['help', 'all'])}\n\`\`\`\n`);
process.stdout.write(out.join('\n'));
