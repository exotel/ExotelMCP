#!/usr/bin/env node
'use strict';
// The event-endpoint starter code has one source: cli/lib/events.js (TEMPLATES). The MCP server hands the same
// files to agents (exotel_events_endpoint_guide), so this writes them to src/main/resources/templates/events/.
// Run after changing a template: node scripts/sync-templates.js   (a test fails if they drift).
const fs = require('fs');
const path = require('path');
const { TEMPLATES } = require('../lib/events');

const dest = path.resolve(__dirname, '..', '..', 'src', 'main', 'resources', 'templates', 'events');
fs.mkdirSync(dest, { recursive: true });
for (const t of Object.values(TEMPLATES)) {
  fs.writeFileSync(path.join(dest, t.file), t.content);
  console.log(`synced ${path.relative(process.cwd(), path.join(dest, t.file))}`);
}
