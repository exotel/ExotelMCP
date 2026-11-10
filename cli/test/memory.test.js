'use strict';
// Exotel Memory: offline tests (no network). Live behaviour was checked against the QA Memory service.
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const { spawn } = require('child_process');

const memory = require('../lib/memory');
const rest = require('../lib/rest');
const { classify } = require('../lib/safety');

const BIN = path.join(__dirname, '..', 'bin', 'exotel.js');
const BASE = { token: Buffer.from('k:t').toString('base64'), account_sid: 'acme1', api_domain: 'https://api.in.exotel.com' };
const MEM = { ...BASE, memory_api_key: 'cm_test_0123456789', memory_account_sid: 'mem_acme' };

function cli(args, auth = MEM) {
  return new Promise((resolve) => {
    const env = { ...process.env, EXOTEL_AUTH_JSON: JSON.stringify(auth), EXOTEL_CONFIG_DIR: '/nonexistent' };
    for (const k of Object.keys(env)) if (/^EXOTEL_MEMORY_/.test(k)) delete env[k];
    const p = spawn(process.execPath, [BIN, ...args, '--json'], { env, stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '';
    p.stdout.on('data', (d) => (stdout += d));
    p.stdin.end('');
    p.on('close', (code) => { let json = null; try { json = JSON.parse(stdout); } catch { /* not json */ } resolve({ code, json }); });
  });
}

test('one phone key for inbound From, outbound To and WhatsApp', () => {
  for (const v of ['+919876543210', '09876543210', '9876543210', '919876543210', '+91 98765-43210']) {
    assert.strictEqual(memory.phone(v), '+919876543210', v);
  }
  assert.strictEqual(memory.phone('+14155550100'), '+14155550100');
  assert.throws(() => memory.phone('12345'), /international format/);
});

test('transcript roles map to Memory roles', () => {
  assert.strictEqual(memory.role('user'), 'customer');
  assert.strictEqual(memory.role('contact'), 'customer');
  assert.strictEqual(memory.role('assistant'), 'bot');
  assert.strictEqual(memory.role('agent'), 'agent');
  assert.strictEqual(memory.role('system'), 'system');
});

test('Memory requests use the Memory key (Bearer) and Memory account, never the Exotel API token', () => {
  const r = rest.buildRequest(MEM, { host: 'memory', path: '/accounts/{sid}/memory', query: { phone: '+919876543210' } });
  assert.strictEqual(r.url, 'https://memory.in.exotel.com/memory/api/v1/accounts/mem_acme/memory?phone=%2B919876543210');
  assert.strictEqual(r.headers.Authorization, 'Bearer cm_test_0123456789');
  const qa = rest.buildRequest({ ...MEM, memory_base_url: 'https://memory.us3.qaexotel.com/' }, { host: 'memory', path: '/accounts/{sid}/notes' });
  assert.strictEqual(qa.url, 'https://memory.us3.qaexotel.com/memory/api/v1/accounts/mem_acme/notes');
  assert.throws(() => rest.buildRequest(BASE, { host: 'memory', path: '/accounts/{sid}/memory' }), /exotel memory login/);
});

test('VoiceBot memory hooks point at the account\'s Memory service', () => {
  assert.strictEqual(rest.memoryHookBase(MEM), 'https://memory.in.exotel.com/memory-client/memory/api/v1/accounts/mem_acme/webhooks/voicebot');
  assert.strictEqual(rest.memoryHookBase({ ...MEM, memory_webhook_base_url: 'https://memory.x.exotel.com/hooks/' }), 'https://memory.x.exotel.com/hooks');
});

test('credential guard allows Exotel QA hosts for Memory and still blocks everything else', () => {
  assert.doesNotThrow(() => rest.assertExotelHost('https://memory.us3.qaexotel.com/memory/api/v1'));
  assert.throws(() => rest.assertExotelHost('https://memory.qaexotel.com.evil.io/'), /Refusing/);
  assert.throws(() => rest.assertExotelHost('http://memory.in.exotel.com/'), /Refusing/);
});

test('MCP Memory tools: reads run freely, anything that saves or changes asks first', () => {
  assert.strictEqual(classify('exotel_memory_get'), 'safe');
  assert.strictEqual(classify('exotel_memory_search_contacts'), 'safe');
  for (const t of ['exotel_memory_save_interaction', 'exotel_memory_add_note', 'exotel_memory_record_event', 'exotel_voicebot_memory_enable', 'exotel_voicebot_memory_disable']) {
    assert.notStrictEqual(classify(t), 'safe', t);
  }
});

test('memory note: asks first, dry-run previews the exact request, nothing is sent', async () => {
  const ask = await cli(['memory', 'note', '--phone', '09876543210', '--text', 'Offer: 10% off this week']);
  assert.strictEqual(ask.code, 3);
  assert.match(ask.json.error, /Confirmation required/);
  const dry = await cli(['memory', 'note', '--phone', '09876543210', '--text', 'Offer: 10% off this week', '--dry-run']);
  assert.strictEqual(dry.code, 0);
  assert.strictEqual(dry.json.request.url, 'https://memory.in.exotel.com/memory/api/v1/accounts/mem_acme/notes');
  assert.deepStrictEqual(dry.json.request.body, { contact_details: [{ type: 'phone', value: '+919876543210' }], text: 'Offer: 10% off this week', visible_to_bots: true });
});

test('memory event and save validate input before anything is sent', async () => {
  const badType = await cli(['memory', 'event', '--phone', '+919876543210', '--type', 'refund', '--event-id', 'E1', '--dry-run']);
  assert.strictEqual(badType.code, 2);
  const save = await cli(['memory', 'save', '--phone', '+919876543210', '--id', 'CALL-1', '--transcript', 'Contact: hi\nBot: hello', '--dry-run']);
  assert.strictEqual(save.code, 0);
  assert.strictEqual(save.json.request.body.transcript_text, 'Contact: hi\nBot: hello');
  assert.strictEqual(save.json.request.body.channel_type, 'VOICE');
  const none = await cli(['memory', 'save', '--phone', '+919876543210', '--id', 'CALL-1', '--dry-run']);
  assert.strictEqual(none.code, 2);
});

test('without Memory set up, commands say how to add it', async () => {
  const r = await cli(['memory', 'get', '--phone', '+919876543210'], BASE);
  assert.strictEqual(r.code, 4);
  assert.match(r.json.error, /exotel memory login/);
  const t = await cli(['memory', 'get', '--phne', '+919876543210'], BASE);
  assert.strictEqual(t.code, 2);
  assert.match(t.json.error, /Did you mean --phone/);
});

test('agents can ask for only the tools they need (toolsets)', () => {
  const clients = require('../lib/clients');
  assert.strictEqual(clients.normToolsets('voicebot, memory,bogus'), 'voicebot,memory');
  assert.strictEqual(clients.normToolsets(undefined), '');
  const pc = clients.proxyCommand('voicebot,memory');
  assert.deepStrictEqual(pc.args.slice(-2), ['--toolsets', 'voicebot,memory']);
  assert.ok(!clients.proxyCommand().args.includes('--toolsets'));
});

test('voicebot memory on / prompt / result are one MCP tool call each (dry-run shows which)', async () => {
  const prompt = await cli(['voicebot', 'prompt', '--bot-id', '1333a954-b9d4-4458-b77b-a1ab91bf719b', '--text', 'You are a friendly receptionist for a clinic.', '--dry-run']);
  assert.strictEqual(prompt.code, 0);
  assert.strictEqual(prompt.json.tool, 'exotel_voicebot_assistant_update_prompt');
  assert.strictEqual(prompt.json.arguments.assistantId, '1333a954-b9d4-4458-b77b-a1ab91bf719b');
  const bad = await cli(['voicebot', 'result', '--call-sid', 'x']);
  assert.strictEqual(bad.code, 2);
  const usage = await cli(['voicebot', 'memory', 'maybe', '--bot-id', 'abc']);
  assert.strictEqual(usage.code, 2);
  assert.match(usage.json.error, /--greeting/);
});
