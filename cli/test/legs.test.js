'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const { classify } = require('../lib/safety');
const clients = require('../lib/clients');

/** Runs the CLI in a sandbox home, logged in to a Mumbai test account (no network). */
function sandbox() {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'exotel-legs-'));
  const base = { ...process.env };
  for (const k of Object.keys(base)) if (/^path$/i.test(k) || /^EXOTEL_/.test(k)) delete base[k];
  const env = { ...base, HOME: home, USERPROFILE: home, APPDATA: path.join(home, 'AppData', 'Roaming'), PATH: path.dirname(process.execPath),
    EXOTEL_CONFIG_DIR: path.join(home, '.config', 'exotel'), NODE_NO_WARNINGS: '1' };
  const bin = path.join(__dirname, '..', 'bin', 'exotel.js');
  const run = (args, input) => spawnSync(process.execPath, [bin, ...args, '--json'], { env, input: input || '', encoding: 'utf8' });
  assert.strictEqual(run(['login', '--account-sid', 'acme1', '--api-key', 'KEYabcdef012345', '--region', 'in', '--api-token-stdin', '--no-verify'], 'TESTTOKEN0123456789abcdef').status, 0);
  return run;
}

test('Legs MCP tools that place calls ask first and count as spend', () => {
  for (const t of ['exotel_legs_call_bot', 'exotel_legs_dial', 'exotel_legs_action']) assert.strictEqual(classify(t), 'spend', t);
  assert.strictEqual(classify('exotel_legs_get'), 'safe');
  for (const t of ['exotel_legs_bridge_create', 'exotel_legs_inbound_bot', 'exotel_legs_inbound_stop']) assert.notStrictEqual(classify(t), 'safe', t);
});

test('legs is a toolset agents can ask for', () => {
  assert.strictEqual(clients.normToolsets('voicebot, legs, nonsense'), 'voicebot,legs');
});

test('voice-ai --via legs needs no event server: events go to the ack-only endpoint and the CLI follows the leg', () => {
  const run = sandbox();
  const r = run(['voice-ai', 'connect', '--via', 'legs', '--to', '+919999999999', '--exophone', '02048566332', '--stream-url', 'wss://bot.example/ws?a=1&b=2', '--dry-run']);
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
  const j = JSON.parse(r.stdout);
  assert.strictEqual(j.kind, 'spend');
  assert.strictEqual(j.steps[0].request.body.leg_event_endpoint, 'https://mcp.exotel.com/legs/events');
  assert.match(j.steps[1].request.body.exoml, /streamUrl="wss:\/\/bot\.example\/ws\?a=1&amp;b=2"/);
});

test('voice-ai --via legs keeps the user\'s own event endpoint when given', () => {
  const run = sandbox();
  const r = run(['voice-ai', 'connect', '--via', 'legs', '--to', '+919999999999', '--exophone', '02048566332', '--stream-url', 'wss://bot.example/ws', '--events', 'https://me.example/ev', '--dry-run']);
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
  assert.strictEqual(JSON.parse(r.stdout).steps[0].request.body.leg_event_endpoint, 'https://me.example/ev');
});

test('voice-ai --via legs asks for a bot: --stream-url or --bot-id', () => {
  const run = sandbox();
  const r = run(['voice-ai', 'connect', '--via', 'legs', '--to', '+919999999999', '--exophone', '02048566332', '--dry-run']);
  assert.notStrictEqual(r.status, 0);
  assert.match(r.stdout + r.stderr, /--bot-id/);
});
