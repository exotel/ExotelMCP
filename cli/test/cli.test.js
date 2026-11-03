'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { parseArgs, shapeArgs, parseToolArgs, findCallSid, findStatus } = require('../bin/exotel');
const { classify } = require('../lib/safety');
const creds = require('../lib/credentials');
const skills = require('../lib/skills');
const clients = require('../lib/clients');
const { parseBody, unwrapResult } = require('../lib/mcp');

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'exotel-cli-'));

test('parseArgs handles booleans, values, = form and -y', () => {
  const { pos, flags } = parseArgs(['call', 'x', 'a=1', '--json', '--to', '+91', '--scope=user', '-y', '--dry-run']);
  assert.deepStrictEqual(pos, ['call', 'x', 'a=1']);
  assert.deepStrictEqual(flags, { json: true, to: '+91', scope: 'user', yes: true, 'dry-run': true });
});

test('classify marks billed and destructive tools', () => {
  assert.strictEqual(classify('exotel_voicebot_place_call'), 'spend');
  assert.strictEqual(classify('exotel_connect_voice_ai'), 'spend');
  assert.strictEqual(classify('exotel_sms_send_single'), 'spend');
  assert.strictEqual(classify('exotel_whatsapp_send_message'), 'spend');
  assert.strictEqual(classify('exotel_voicebot_delete'), 'destructive');
  assert.strictEqual(classify('exotel_cqa_revoke_api_key'), 'destructive');
  assert.strictEqual(classify('exotel_voicebot_list_all'), 'safe');
  assert.strictEqual(classify('exotel_voicebot_transcript_get'), 'safe');
  assert.strictEqual(classify('exotel_sms_get_delivery_status'), 'safe');
});

test('shapeArgs coerces types, fills server-required blanks, rejects unknown keys', () => {
  const tool = {
    name: 't',
    inputSchema: {
      properties: { toNumber: { type: 'string' }, record: { type: 'boolean' }, customField: { type: 'string' }, tags: { type: 'array' } },
      required: ['toNumber', 'record', 'customField'],
    },
  };
  const { args, filled } = shapeArgs(tool, { toNumber: '+91', record: 'true', tags: 'a,b' });
  assert.deepStrictEqual(args, { toNumber: '+91', record: true, tags: ['a', 'b'], customField: '' });
  assert.deepStrictEqual(filled, ['customField']);
  assert.throws(() => shapeArgs(tool, { nope: '1' }), /Unknown parameter/);
});

test('parseToolArgs merges --args JSON and key=value', () => {
  assert.deepStrictEqual(parseToolArgs(['b=2'], { args: '{"a":1}' }), { a: 1, b: '2' });
  assert.throws(() => parseToolArgs(['bad'], {}), /key=value/);
});

test('buildAuth produces the MCP header shape and mask hides secrets', () => {
  const a = creds.buildAuth({ accountSid: 'acme1', apiKey: 'KEY123456789', apiToken: 'TOKEN987654321', apiDomain: creds.REGIONS.in, callerId: '+918000000000' });
  assert.strictEqual(Buffer.from(a.token, 'base64').toString(), 'KEY123456789:TOKEN987654321');
  assert.strictEqual(a.voicebot_account_id, 'acme1');
  assert.strictEqual(a.from_number, '+918000000000');
  assert.deepStrictEqual(creds.products(a), ['cpaas', 'voicebot']);
  const m = creds.mask(a);
  assert.ok(!JSON.stringify(m).includes('TOKEN987654321'));
  assert.strictEqual(m.account_sid, 'acme1');
  assert.deepStrictEqual(creds.apiKeyPair(a), { apiKey: 'KEY123456789', apiToken: 'TOKEN987654321' });
});

test('credentials save with mode 600 and load back', () => {
  process.env.EXOTEL_CONFIG_DIR = tmp();
  const saved = { process: process.env.EXOTEL_ACCOUNT_SID, json: process.env.EXOTEL_AUTH_JSON };
  delete process.env.EXOTEL_ACCOUNT_SID;
  delete process.env.EXOTEL_AUTH_JSON;
  const file = creds.save({ token: 'x', account_sid: 's' });
  assert.strictEqual(fs.statSync(file).mode & 0o777, 0o600);
  assert.deepStrictEqual(creds.load().auth, { token: 'x', account_sid: 's' });
  assert.ok(creds.remove());
  assert.strictEqual(creds.load(), null);
  if (saved.process) process.env.EXOTEL_ACCOUNT_SID = saved.process;
  if (saved.json) process.env.EXOTEL_AUTH_JSON = saved.json;
});

test('bundled skills have name + description frontmatter', () => {
  const list = skills.list();
  assert.ok(list.length >= 6, `expected >= 6 skills, got ${list.length}`);
  for (const s of list) {
    assert.match(s.name, /^exotel-[a-z-]+$/);
    assert.ok(s.description.length > 40, `${s.name} needs a description`);
    assert.strictEqual(path.basename(s.dir), s.name, 'folder name must equal skill name');
  }
});

test('skill install copies folders, respects existing, converts for cursor', () => {
  const dir = tmp();
  const r1 = skills.install(['exotel-setup'], { dir });
  assert.strictEqual(r1[0].status, 'installed');
  assert.ok(fs.existsSync(path.join(dir, 'exotel-setup', 'SKILL.md')));
  assert.strictEqual(skills.install(['exotel-setup'], { dir })[0].status, 'exists');
  assert.strictEqual(skills.install(['exotel-setup'], { dir, force: true })[0].status, 'installed');
  assert.throws(() => skills.install(['nope'], { dir }), /Unknown skill/);

  const cwd = process.cwd();
  const proj = tmp();
  process.chdir(proj);
  try {
    const r = skills.install(['exotel-setup'], { agents: ['cursor'] });
    const mdc = fs.readFileSync(r[0].path, 'utf8');
    assert.match(mdc, /^---\ndescription: Start here for anything Exotel/);
    assert.match(mdc, /alwaysApply: false/);
  } finally {
    process.chdir(cwd);
  }
});

test('codex config: add, refuse without force, replace in place, keep other sections', () => {
  process.env.CODEX_HOME = tmp();
  const file = path.join(process.env.CODEX_HOME, 'config.toml');
  fs.writeFileSync(file, 'model = "x"\n\n[mcp_servers.other]\nurl = "https://o"\n');
  const auth = { token: 'abc', account_sid: 's' };
  let [r] = clients.install(['codex'], { auth, url: 'https://mcp.exotel.com/mcp' });
  assert.strictEqual(r.status, 'added');
  [r] = clients.install(['codex'], { auth, url: 'https://mcp.exotel.com/mcp' });
  assert.strictEqual(r.status, 'exists');
  [r] = clients.install(['codex'], { auth: { token: 'new' }, url: 'https://mcp.exotel.com/mcp', force: true });
  assert.strictEqual(r.status, 'replaced');
  const toml = fs.readFileSync(file, 'utf8');
  assert.strictEqual((toml.match(/\[mcp_servers\.exotel\]/g) || []).length, 1);
  assert.match(toml, /\[mcp_servers\.other\]/);
  assert.match(toml, /http_headers = \{ "Authorization" = '\{"token":"new"\}' \}/);
});

test('tomlString escapes single quotes', () => {
  assert.strictEqual(clients.tomlString('a'), "'a'");
  assert.strictEqual(clients.tomlString("it's"), '"it\'s"');
});

test('MCP body parsing: JSON and SSE; result unwrapping', () => {
  assert.deepStrictEqual(parseBody('{"a":1}', 'application/json'), { a: 1 });
  assert.deepStrictEqual(parseBody('event: message\ndata: {"b":2}\n\n', 'text/event-stream'), { b: 2 });
  assert.strictEqual(unwrapResult({ content: [{ type: 'text', text: 'Error: nope' }] }).ok, false);
  assert.strictEqual(unwrapResult({ content: [{ type: 'text', text: '{"message":"toNumber is required"}' }] }).ok, false);
  const ok = unwrapResult({ content: [{ type: 'text', text: '{"Call":{"Sid":"abc123","Status":"queued"}}' }] });
  assert.strictEqual(ok.ok, true);
  assert.strictEqual(findCallSid(ok.data), 'abc123');
  assert.strictEqual(findStatus(ok.data), 'queued');
});
