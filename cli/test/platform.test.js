'use strict';
const test = require('node:test');
const assert = require('node:assert');
const http = require('http');
const path = require('path');
const { spawn } = require('child_process');

const exoml = require('../lib/exoml');
const applet = require('../lib/applet');
const rest = require('../lib/rest');

const BIN = path.join(__dirname, '..', 'bin', 'exotel.js');
const AUTH = { token: Buffer.from('k:t').toString('base64'), account_sid: 'acme1', api_domain: 'https://api.in.exotel.com', caller_id: '+918000000000' };

function cli(args, input) {
  return new Promise((resolve) => {
    const p = spawn(process.execPath, [BIN, ...args, '--json'], {
      env: { ...process.env, EXOTEL_AUTH_JSON: JSON.stringify(AUTH), EXOTEL_CONFIG_DIR: '/nonexistent' },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    let stdout = '';
    p.stdout.on('data', (d) => (stdout += d));
    p.stdin.end(input || '');
    p.on('close', (code) => {
      let json;
      try {
        json = JSON.parse(stdout);
      } catch {
        json = null;
      }
      resolve({ code, json, stdout });
    });
  });
}

// ---------------------------------------------------------------- ExoML

test('exoml: valid documented examples pass', () => {
  const dial = '<?xml version="1.0" encoding="UTF-8"?><Flow><Dial contactUri="9791111865" exophone="02048566335" networkType="pstn" absorbDtmf="true"></Dial></Flow>';
  assert.deepStrictEqual(exoml.validate(dial), { valid: true, errors: [], warnings: [], actions: ['Dial'] });
  const seq = '<Flow><PlaySequence><StartPlay>https://example.com/audio.wav</StartPlay><Say>Hello</Say></PlaySequence></Flow>';
  assert.strictEqual(exoml.validate(seq).valid, true);
  const stream = '<Flow><Answer/><StartStream streamType="bidirectional" streamUrl="wss://bot.example.com/ws"/></Flow>';
  assert.strictEqual(exoml.validate(stream).valid, true);
});

test('exoml: catches the mistakes developers actually make', () => {
  const v = (x) => exoml.validate(x).errors.join(' | ');
  assert.match(v('<Response><Say>Hi</Say></Response>'), /Root element must be <Flow>/);
  assert.match(v('<Flow><say>Hi</say></Flow>'), /did you mean <Say>/);
  assert.match(v('<Flow><Dial contactUri="1"/></Flow>'), /missing required attribute exophone/);
  assert.match(v('<Flow><Dial contacturi="1" exophone="2"/></Flow>'), /did you mean contactUri/);
  assert.match(v('<Flow><Mute direction="up"/></Flow>'), /must be one of in, out, both/);
  assert.match(v('<Flow><StartStream streamType="bidirectional" streamUrl="https://x"/></Flow>'), /ws:\/\/ or wss:\/\//);
  assert.match(v('<Flow><PlaySequence><Hangup/></PlaySequence></Flow>'), /not allowed here/);
  assert.match(v('<Flow><StartPlay>not a url</StartPlay></Flow>'), /http\(s\) audio URL/);
  assert.match(v('<Flow><Say>Tom & Jerry</Say></Flow>'), /unescaped &/);
  assert.match(v('<Flow><Say>Hi</Flow>'), /expected <\/Say>/);
  assert.match(v('<!DOCTYPE x><Flow/>'), /DOCTYPE/);
  assert.match(v('<Flow></Flow>'), /empty/);
});

test('exoml validate command exit codes', async () => {
  const ok = await cli(['exoml', 'validate', '-'], '<Flow><Hangup/></Flow>');
  assert.strictEqual(ok.code, 0);
  assert.strictEqual(ok.json.valid, true);
  const bad = await cli(['exoml', 'validate', '-'], '<Flow><Foo/></Flow>');
  assert.strictEqual(bad.code, 1);
});

test('leg action refuses invalid ExoML before any request', async () => {
  const r = await cli(['leg', 'action', '--leg', 'abc123', '--xml', '<Flow><Dial/></Flow>', '--yes']);
  assert.strictEqual(r.code, 2);
  assert.match(r.json.error, /nothing was sent/);
});

test('leg action dry-run targets the cpaas host and carries the XML', async () => {
  const r = await cli(['leg', 'action', '--leg', 'abc123', '--xml', '<Flow><Hangup/></Flow>', '--dry-run']);
  assert.strictEqual(r.code, 0);
  assert.strictEqual(r.json.request.url, 'https://cpaas-api.in.exotel.com/v2/accounts/acme1/legs/abc123/actions');
  assert.strictEqual(r.json.request.body.exoml, '<Flow><Hangup/></Flow>');
  assert.strictEqual(r.json.kind, 'live');
});

// ---------------------------------------------------------------- trunk / numbers / safety

test('trunk setup dry-run plans every documented call in order', async () => {
  const r = await cli(['trunk', 'setup', '--name', 'ai_trunk', '--number', '+919876543210', '--ip', '44.248.146.11', '--uri', 'sip.acme.com', '--caller-id', '+919876543210', '--dry-run']);
  assert.strictEqual(r.code, 0, r.stdout);
  const urls = r.json.steps.map((s) => `${s.request.method} ${s.request.url}`);
  assert.deepStrictEqual(urls, [
    'POST https://api.in.exotel.com/v2/accounts/acme1/trunks',
    'POST https://api.in.exotel.com/v2/accounts/acme1/trunks/TK_NEW/phone_numbers',
    'POST https://api.in.exotel.com/v2/accounts/acme1/trunks/TK_NEW/whitelisted_ips',
    'POST https://api.in.exotel.com/v2/accounts/acme1/trunks/TK_NEW/destination_uris',
    'POST https://api.in.exotel.com/v2/accounts/acme1/trunks/TK_NEW/settings',
  ]);
  assert.deepStrictEqual(r.json.steps[0].request.body, { trunk_name: 'ai_trunk', nso_code: 'any any', domain_name: 'acme1.pstn.exotel.com' });
  assert.deepStrictEqual(r.json.steps[3].request.body, { destinations: [{ destination: 'sip.acme.com:5061;transport=tls' }] });
});

test('trunk setup validates all inputs before touching anything', async () => {
  const r = await cli(['trunk', 'setup', '--name', 'ai_trunk', '--number', '9876543210', '--yes']);
  assert.strictEqual(r.code, 2);
  assert.match(r.json.error, /--number/);
  const r2 = await cli(['trunk', 'create', '--name', 'this-name-is-too-long']);
  assert.strictEqual(r2.code, 2);
});

test('destination accepts tcp and explicit ports', async () => {
  const r = await cli(['trunk', 'destination', '--trunk', 'TK1', '--uri', '10.0.0.1', '--transport', 'tcp', '--dry-run']);
  assert.deepStrictEqual(r.json.request.body, { destinations: [{ destination: '10.0.0.1:5060;transport=tcp' }] });
});

test('billed, destructive and live-routing actions need --yes', async () => {
  for (const args of [
    ['numbers', 'update', '--sid', 'PN1', '--voice-url', 'https://my.exotel.com/acme1/exoml/start_voice/1'],
    ['trunk', 'delete', '--trunk', 'TK1'],
    ['trunk', 'map-number', '--trunk', 'TK1', '--number', '+919876543210'],
    ['voice-ai', 'connect', '--to', '+919876543210', '--stream-url', 'wss://bot.example.com/?sample-rate=8000'],
    ['voice-ai', 'connect', '--to', '+919876543210', '--flow', '40536'],
    ['leg', 'create', '--to', '+919876543210', '--exophone', '+918000000000', '--events', 'https://e.example.com/ev'],
    ['api', 'DELETE', '/v2/accounts/{sid}/trunks'],
  ]) {
    const r = await cli(args);
    assert.strictEqual(r.code, 3, `${args.join(' ')} → ${r.stdout}`);
  }
});

test('numbers: available (read), buy ($), release (!), update by --app-id', async () => {
  const b = await cli(['balance', '--dry-run']);
  assert.strictEqual(b.json.request.url, 'https://api.in.exotel.com/v1/Accounts/acme1/Balance.json');
  const av = await cli(['numbers', 'available', '--type', 'mobile', '--region', 'ka', '--contains', '80', '--dry-run']);
  assert.strictEqual(av.json.request.url, 'https://api.in.exotel.com/v2_beta/Accounts/acme1/AvailablePhoneNumbers/IN/Mobile?InRegion=ka&Contains=80');
  const buy = await cli(['numbers', 'buy', '--number', '+918000000001', '--app-id', '31549', '--dry-run']);
  assert.strictEqual(buy.json.kind, 'spend');
  assert.strictEqual(buy.json.request.method, 'POST');
  assert.match(buy.json.request.body, /PhoneNumber=%2B918000000001&VoiceUrl=http%3A%2F%2Fmy\.exotel\.com%2Facme1%2Fexoml%2Fstart_voice%2F31549/);
  assert.strictEqual((await cli(['numbers', 'buy', '--number', '+918000000001'])).code, 3);   // billed: needs --yes
  const rel = await cli(['numbers', 'release', '--exophone-sid', 'PN1', '--dry-run']);
  assert.strictEqual(rel.json.kind, 'destructive');
  assert.strictEqual(rel.json.request.method, 'DELETE');
  assert.strictEqual((await cli(['numbers', 'release', '--exophone-sid', 'PN1'])).code, 3);   // permanent: needs --yes
  const both = await cli(['numbers', 'update', '--exophone-sid', 'PN1', '--app-id', '1', '--voice-url', 'https://x.example.com', '--dry-run']);
  assert.strictEqual(both.code, 2);
  const u = await cli(['numbers', 'update', '--sid', 'PN1', '--name', 'Support', '--dry-run']);
  assert.strictEqual(u.json.request.method, 'PUT');
  assert.strictEqual(u.json.request.body, 'FriendlyName=Support');
});

test('voice-ai connect: direct bot (standard) sends the documented Connect Voice AI fields', async () => {
  const r = await cli(['voice-ai', 'connect', '--to', '+919876543210', '--stream-url', 'wss://bot.example.com/media?sample-rate=16000',
    '--record', '--recording-channels', 'dual', '--time-limit', '600', '--custom-field', 'order-42', '--stream-name', 'support',
    '--status-callback', 'https://cb.example.com/s', '--status-events', 'answered,terminal', '--dry-run']);
  assert.strictEqual(r.code, 0, r.stdout);
  assert.strictEqual(r.json.request.url, 'https://api.in.exotel.com/v1/Accounts/acme1/Calls/connect.json');
  const body = new URLSearchParams(r.json.request.body);
  assert.deepStrictEqual(Object.fromEntries([...body.keys()].map((k) => [k, body.getAll(k).length > 1 ? body.getAll(k) : body.get(k)])), {
    From: '+919876543210', CallerId: '+918000000000', StreamUrl: 'wss://bot.example.com/media?sample-rate=16000', StreamType: 'bidirectional',
    RecordingChannels: 'dual', StreamName: 'support', Record: 'true', TimeLimit: '600', CustomField: 'order-42',
    StatusCallback: 'https://cb.example.com/s', 'StatusCallbackEvents[]': ['answered', 'terminal'],
  });
});

test('voice-ai connect: flow mode builds the documented flow URL', async () => {
  const r = await cli(['voice-ai', 'connect', '--to', '+919876543210', '--flow', '40536', '--timeout', '30', '--call-type', 'trans', '--dry-run']);
  const body = new URLSearchParams(r.json.request.body);
  assert.strictEqual(body.get('Url'), 'http://my.exotel.com/acme1/exoml/start_voice/40536');
  assert.strictEqual(body.get('TimeOut'), '30');
  assert.strictEqual(body.get('CallType'), 'trans');
  assert.strictEqual(body.get('StreamUrl'), null);
});

test('voice-ai connect: input checks', async () => {
  const base = ['voice-ai', 'connect', '--to', '+919876543210'];
  assert.strictEqual((await cli(base)).code, 2, 'needs a target');
  assert.strictEqual((await cli([...base, '--stream-url', 'wss://x', '--flow', '1'])).code, 2, 'not both');
  assert.strictEqual((await cli([...base, '--stream-url', 'https://x'])).code, 2, 'wss only');
  assert.strictEqual((await cli([...base, '--stream-url', 'wss://x/?sample-rate=44100'])).code, 2);
  assert.strictEqual((await cli([...base, '--stream-url', 'wss://x', '--time-limit', '20000'])).code, 2);
  assert.strictEqual((await cli([...base, '--stream-url', 'wss://x', '--status-events', 'terminal'])).code, 2, 'events need callback');
  const old = await cli(['agentstream', 'call']);
  assert.match(old.json.error, /voice-ai connect/);
});

test('api fallback expands {sid} and never sends credentials in the preview', async () => {
  const r = await cli(['api', 'GET', '/v1/Accounts/{sid}/Calls.json', '--query', 'PageSize=5', '--dry-run']);
  assert.strictEqual(r.json.request.url, 'https://api.in.exotel.com/v1/Accounts/acme1/Calls.json?PageSize=5');
  assert.ok(!r.stdout.includes(AUTH.token));
});

test('old "exotel call" points to "exotel mcp call"', async () => {
  const r = await cli(['call', 'exotel_sms_send_single']);
  assert.strictEqual(r.code, 2);
  assert.match(r.json.error, /exotel mcp call/);
});

test('path segments are validated', () => {
  assert.throws(() => rest.seg('../x', 'trunk'), /invalid characters/);
  assert.strictEqual(rest.seg('TK123', 'trunk'), 'TK123');
});

// ---------------------------------------------------------------- applet test against a local server

function serve(handler) {
  return new Promise((resolve) => {
    const s = http.createServer(handler).listen(0, '127.0.0.1', () => resolve(s));
  });
}

test('applet test: connect, gather, dial, passthru, stream contracts', async () => {
  let seen;
  const s = await serve((req, res) => {
    seen = req;
    const u = new URL(req.url, 'http://x');
    const send = (code, type, body) => {
      res.writeHead(code, { 'Content-Type': type });
      res.end(body);
    };
    switch (u.pathname) {
      case '/connect-ok': return send(200, 'application/json', JSON.stringify({ destination: { numbers: ['+919876543210'] }, record: true, max_ringing_duration: 30 }));
      case '/connect-bad': return send(200, 'text/html', '{"destination":{"numbers":["9876543210"]}}');
      case '/gather-ok': return send(200, 'application/json', JSON.stringify({ gather_prompt: { text: 'Press 1' }, max_input_digits: 1, finish_on_key: '#' }));
      case '/gather-bad': return send(200, 'application/json', '{"gather_prompt": {"text": "x",}}');
      case '/dial-ok': return send(200, 'text/plain', '+919900000000,+919800000000');
      case '/passthru-b': res.writeHead(302, { Location: '/x' }); return res.end();
      case '/stream-ok': return send(200, 'application/json', '{"url":"wss://bot.example.com/ws"}');
      case '/slow': return setTimeout(() => send(200, 'application/json', '{}'), 1500);
      default: return send(500, 'text/plain', 'boom');
    }
  });
  const base = `http://127.0.0.1:${s.address().port}`;
  try {
    let r = await applet.test('connect', `${base}/connect-ok`);
    assert.strictEqual(r.ok, true, JSON.stringify(r.problems));
    assert.strictEqual(seen.headers['exotel-version'], '1.0');
    assert.ok(new URL(seen.url, 'http://x').searchParams.get('CallSid'));

    r = await applet.test('connect', `${base}/connect-bad`);
    assert.strictEqual(r.ok, false);
    assert.ok(r.problems.some((p) => /application\/json/.test(p)));
    assert.ok(r.problems.some((p) => /not E\.164/.test(p)));

    assert.strictEqual((await applet.test('gather', `${base}/gather-ok`)).ok, true);
    assert.ok((await applet.test('gather', `${base}/gather-bad`)).problems.some((p) => /not valid JSON/.test(p)));
    assert.deepStrictEqual((await applet.test('dial', `${base}/dial-ok`)).body, ['+919900000000', '+919800000000']);
    assert.strictEqual((await applet.test('passthru', `${base}/passthru-b`)).route, 'Choice B (302 Found)');
    assert.strictEqual((await applet.test('stream', `${base}/stream-ok`)).ok, true);
    assert.strictEqual((await applet.test('connect', `${base}/missing`)).ok, false);
    const slow = await applet.test('connect', `${base}/slow`, { timeoutMs: 500 });
    assert.match(slow.problems[0], /Fallback URL/);
  } finally {
    s.close();
  }
});

// ---------------------------------------------------------------- events endpoint

test('events listen: acks 200, parses the documented payload, flags retries, enforces Basic Auth', async () => {
  const ev = require('../lib/events');
  const got = [];
  const server = await ev.listen({ port: 0, basicUser: 'exotel_user', basicPass: 'exotel_pass', onEvent: (r) => got.push(r) });
  const url = `http://127.0.0.1:${server.address().port}/exotel/events`;
  const payload = {
    event_sid: '3IgzDXwxdn1ylY5cGVFvYxnHEZo00000', event_type: 'leg_action_event', event_name: 'leg_failed_to_create',
    event_timestamp: '2026-08-31T18:28:00.529Z',
    event_data: { data: {}, error: { error_code: '1001', message: 'Destination unreachable', description: 'Carrier returned 480' } },
    data: { leg_sid: '3IgzDZXUHsK9k67utaeupEgVNaI00000', account_sid: 'exotel60', state: 'completed', terminal_status: 'failed' },
  };
  const post = (attempt, auth = 'Basic ZXhvdGVsX3VzZXI6ZXhvdGVsX3Bhc3M=') => fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'User-Agent': 'Exotel-Hermix/1.0', 'X-Exotel-Event-Sid': payload.event_sid, 'X-Exotel-Request-Id': `${payload.event_sid}-${attempt}`, Authorization: auth },
    body: JSON.stringify(payload),
  });
  try {
    const t0 = Date.now();
    assert.strictEqual((await post(1)).status, 200);
    assert.ok(Date.now() - t0 < 1000, 'must ack well inside Exotel\'s ~1 s window');
    assert.strictEqual((await post(2)).status, 200);
    assert.strictEqual((await post(3, 'Basic d3Jvbmc6d3Jvbmc=')).status, 401);
    await new Promise((r) => setTimeout(r, 50));
    assert.strictEqual(got.length, 2);
    assert.strictEqual(got[0].duplicate, false);
    assert.strictEqual(got[1].duplicate, true);
    assert.strictEqual(got[1].request_id, `${payload.event_sid}-2`);
    const line = ev.summarize(got[0]);
    assert.match(line, /leg_failed_to_create/);
    assert.match(line, /error=1001 Destination unreachable/);
  } finally {
    server.close();
  }
});

test('events scaffold writes runnable node and python endpoints', async () => {
  const fs = require('fs');
  const os = require('os');
  const ev = require('../lib/events');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'exotel-ev-'));
  const n = ev.scaffold('node', dir);
  const py = ev.scaffold('python', dir);
  assert.strictEqual(n.status, 'created');
  assert.strictEqual(ev.scaffold('node', dir).status, 'exists');
  const { spawnSync } = require('child_process');
  assert.strictEqual(spawnSync(process.execPath, ['--check', n.file]).status, 0, 'node template must parse');
  const pyc = spawnSync('python3', ['-I', '-c', `import ast,sys;ast.parse(open(sys.argv[1]).read())`, py.file]);
  if (pyc.error === undefined) assert.strictEqual(pyc.status, 0, String(pyc.stderr));
});

test('leg create accepts only https event endpoints', async () => {
  const base = ['leg', 'create', '--to', '+919876543210', '--exophone', '+918000000000', '--dry-run'];
  const h = await cli([...base, '--events', 'https://voice.example.com/exotel/events', '--async-amd', '--param', 'order-42']);
  assert.strictEqual(h.json.request.url, 'https://cpaas-api.in.exotel.com/v2/accounts/acme1/legs');
  assert.deepStrictEqual(h.json.request.body, {
    contact_uri: '+919876543210', exophone: '+918000000000', leg_event_endpoint: 'https://voice.example.com/exotel/events',
    custom_param: 'order-42', amd_enable: true, async_amd: true,
  });
  assert.strictEqual((await cli([...base, '--events', 'grpc://10.0.0.1:9001'])).code, 2);
  assert.strictEqual((await cli([...base, '--events', 'http://insecure.example.com'])).code, 2);
  const sip = await cli(['leg', 'create', '--to', 'sip:bot@sip.example.com', '--exophone', '+918000000000', '--events', 'https://e.example.com', '--dry-run']);
  assert.strictEqual(sip.json.request.body.network_type, 'voip');
});

test('bridge create requires legs and events (as the live API does); join sends JoinBridge', async () => {
  assert.strictEqual((await cli(['bridge', 'create', '--events', 'https://e.example.com', '--dry-run'])).code, 2);
  assert.strictEqual((await cli(['bridge', 'create', '--legs', 'a,b', '--dry-run'])).code, 2);
  const b = await cli(['bridge', 'create', '--legs', 'a1,b2', '--events', 'https://e.example.com/ev', '--max', '4', '--dry-run']);
  assert.deepStrictEqual(b.json.request.body, { leg_sids: ['a1', 'b2'], bridge_event_endpoint: 'https://e.example.com/ev', max_participants: 4 });
  const j = await cli(['bridge', 'join', '--bridge', 'BR1', '--leg', 'L1', '--dry-run']);
  assert.strictEqual(j.json.request.url, 'https://cpaas-api.in.exotel.com/v2/accounts/acme1/legs/L1/actions');
  assert.strictEqual(j.json.request.body.exoml, '<Flow><JoinBridge bridgeSid="BR1"/></Flow>');
  const s = await cli(['bridge', 'stop', '--bridge', 'BR1']);
  assert.strictEqual(s.code, 3);
});

test('MCP server event templates match the CLI scaffolds', () => {
  const fs = require('fs');
  const ev = require('../lib/events');
  for (const t of Object.values(ev.TEMPLATES)) {
    const res = path.join(__dirname, '..', '..', 'src', 'main', 'resources', 'templates', 'events', t.file);
    assert.strictEqual(fs.readFileSync(res, 'utf8'), t.content, `${t.file} is out of sync: regenerate from cli/lib/events.js`);
  }
});

test('voice-ai setup: WSS URL + manual flow steps, never a made-up app id', async () => {
  const own = await cli(['voice-ai', 'setup', '--stream-url', 'wss://bot.example.com/echo']);
  assert.strictEqual(own.json.voicebotUrl, 'wss://bot.example.com/echo');
  assert.ok(own.json.steps.some((x) => /App Bazaar/.test(x)));
  assert.ok(own.json.steps.some((x) => /--app-id <app_id>/.test(x)));
  const el = await cli(['voice-ai', 'setup', '--platform', 'elevenlabs']);
  assert.strictEqual(el.json.voicebotUrl, 'wss://api.in.residency.elevenlabs.io/v1/convai/conversation/exotel');
  const bn = await cli(['voice-ai', 'setup', '--platform', 'bolna']);
  assert.match(bn.json.voicebotUrl, /api\.bolna\.ai\/exotel_callback/);
  assert.strictEqual((await cli(['voice-ai', 'setup'])).code, 2);
});

test('voice-ai connect --via legs: create leg; on leg_answered send ExoML Say + StartStream', async () => {
  const r = await cli(['voice-ai', 'connect', '--via', 'legs', '--to', '+919876543210', '--exophone', '08000000001',
    '--stream-url', 'wss://bot.example.com/echo?sample-rate=8000&x=1', '--events', 'https://ev.example.com/events', '--listen', '8787', '--greeting', 'Please hold', '--dry-run']);
  assert.strictEqual(r.code, 0, r.stdout);
  assert.strictEqual(r.json.steps[0].request.url, 'https://cpaas-api.in.exotel.com/v2/accounts/acme1/legs');
  assert.strictEqual(r.json.steps[0].request.body.leg_event_endpoint, 'https://ev.example.com/events');
  assert.deepStrictEqual(r.json.steps.map((s) => s.when), ['now', 'on leg_answered', 'on leg_answered', 'on stream_started']);
  assert.strictEqual(r.json.steps[2].request.body.exoml, '<Flow><StartStream streamType="bidirectional" streamUrl="wss://bot.example.com/echo?sample-rate=8000&amp;x=1"/></Flow>');
  assert.strictEqual(r.json.steps[3].request.body.exoml, '<Flow><StopSay/></Flow>');
  assert.strictEqual((await cli(['voice-ai', 'connect', '--via', 'legs', '--to', '+919876543210', '--exophone', '08000000001', '--stream-url', 'wss://x'])).code, 2, 'needs --events');
});

test('leg one-liners build valid ExoML for every documented action', async () => {
  const cases = {
    say: [['--text', 'Hello & welcome', '--voice', 'Aditi'], '<Flow><Say pollyVoiceId="Aditi">Hello &amp; welcome</Say></Flow>'],
    play: [['--url', 'sound:mono_audio.wav', '--loop', '2'], '<Flow><StartPlay loop="2">sound:mono_audio.wav</StartPlay></Flow>'],
    gather: [['--digits', '4', '--timeout', '10', '--finish-on', '#', '--prompt', 'Enter PIN'], '<Flow><Gather numDigits="4" timeoutInSec="10" finishOnKey="#"><Say>Enter PIN</Say></Gather></Flow>'],
    record: [['--format', 'wav', '--channel', 'stereo', '--direction', 'both'], '<Flow><StartRecording direction="both" format="wav" channel="stereo"/></Flow>'],
    voicemail: [['--prompt', 'Leave a message', '--silence', '5', '--timeout', '30'], '<Flow><StartRecording silenceInSec="5" timeoutInSec="30"><Say>Leave a message</Say></StartRecording></Flow>'],
    stream: [['--url', 'wss://b/x'], '<Flow><StartStream streamType="bidirectional" streamUrl="wss://b/x"/></Flow>'],
    'stop-stream': [[], '<Flow><StopStream/></Flow>'],
    dial: [['--to', '09163816623', '--exophone', '08030752400'], '<Flow><Dial contactUri="09163816623" exophone="08030752400"/></Flow>'],
    'send-digits': [['--digits', '123#'], '<Flow><SendDigits>123#</SendDigits></Flow>'],
    hold: [[], '<Flow><Hold></Hold></Flow>'],
    unhold: [[], '<Flow><UnHold/></Flow>'],
    mute: [['--direction', 'out'], '<Flow><Mute direction="out"/></Flow>'],
    monitor: [['--monitor-leg', 'L2', '--rx', 'muted'], '<Flow><StartMonitoring monitoringLegSid="L2" rxAudio="muted"/></Flow>'],
    refer: [['--events', 'https://new-host.example.com/events'], '<Flow><Refer legEventEndpoint="https://new-host.example.com/events"/></Flow>'],
    answer: [[], '<Flow><Answer/></Flow>'],
    hangup: [[], '<Flow><Hangup/></Flow>'],
  };
  for (const [verb, [args, xml]] of Object.entries(cases)) {
    const r = await cli(['leg', verb, '--leg', 'L1', ...args, '--dry-run']);
    assert.strictEqual(r.code, 0, `${verb}: ${r.stdout}`);
    assert.strictEqual(r.json.request.url, 'https://cpaas-api.in.exotel.com/v2/accounts/acme1/legs/L1/actions', verb);
    assert.strictEqual(r.json.request.body.exoml, xml, verb);
  }
});

test('leg inbound gives support steps (no undocumented API call); bridge rejects leg-only actions', async () => {
  const r = await cli(['leg', 'inbound', '--number', '08000000001', '--events', 'https://ev.example.com/events']);
  assert.strictEqual(r.code, 0);
  assert.strictEqual(r.json.viaSupport, true);
  assert.strictEqual(r.json.request, undefined);
  assert.match(r.json.steps.join('\n'), /Exotel support to attach 08000000001 to your HTTPS event endpoint https:\/\/ev\.example\.com\/events/);
  const v = await cli(['voice-ai', 'inbound', '--number', '08000000001', '--stream-url', 'wss://bot.example.com/echo', '--events', 'https://ev.example.com/events', '--listen', '8799', '--dry-run']);
  assert.strictEqual(v.json.register, undefined);
  assert.match(v.json.onLegAnswered, /StartStream/);
  const bad = await cli(['bridge', 'action', '--bridge', 'B1', '--xml', '<Flow><Mute/></Flow>', '--yes']);
  assert.strictEqual(bad.code, 2);
});

test('Legs host follows region (Singapore documented as cpaas-api.exotel.com)', () => {
  assert.strictEqual(rest.hosts({ api_domain: 'https://api.exotel.com' }).cpaas, 'https://cpaas-api.exotel.com');
  assert.strictEqual(rest.hosts({ api_domain: 'https://api.in.exotel.com' }).cpaas, 'https://cpaas-api.in.exotel.com');
});

// ---------------------------------------------------------------- ask / docs (offline)

test('docs: llms.txt parsing and ranked search with synonyms and IDF', () => {
  const d = require('../lib/docs');
  const pages = [
    ...d.parseLlmsTxt('# X\n## AgentStream\n- [Connect Voice AI API](https://developer.exotel.com/docs/agentstream/connect-voice-ai.md): Dial a number and connect the call to your bot over WebSocket\n- [SIP trunks](https://developer.exotel.com/docs/sip-trunking/dynamic-sip-trunking.md)', 'developer.exotel.com'),
    ...d.parseLlmsTxt('- [OpenAI Realtime-AgentStream Integration](https://docs.exotel.com/exotel-agentstream/openai-realtime.md): OpenAI bot on calls\n- [Send SMS](https://docs.exotel.com/messaging-apis/send-sms.md): DLT', 'docs.exotel.com'),
  ];
  assert.strictEqual(pages.length, 4);
  assert.strictEqual(pages[0].url, 'https://developer.exotel.com/docs/agentstream/connect-voice-ai');
  assert.strictEqual(pages[0].section, 'AgentStream');
  assert.strictEqual(d.search(pages, 'openai bot')[0].title, 'OpenAI Realtime-AgentStream Integration');
  assert.strictEqual(d.search(pages, 'websocket bot')[0].title, 'Connect Voice AI API');
  assert.strictEqual(d.search(pages, 'trunk')[0].title, 'SIP trunks');
  assert.deepStrictEqual(d.search(pages, 'the and of'), []);
});

test('ask: routes common goals to the right recipe', () => {
  const { rank } = require('../lib/ask');
  const top = (q) => (rank(q)[0] || {}).it && rank(q)[0].it.id;
  assert.strictEqual(top('connect my OpenAI bot to a phone call'), 'own-bot-outbound');
  assert.strictEqual(top('inbound calls to my bot without app bazaar'), 'own-bot-inbound');
  assert.ok(rank('inbound calls to my bot without app bazaar').slice(0, 2).some((r) => r.it.id === 'legs'));
  assert.strictEqual(top('use elevenlabs agent on my number'), 'elevenlabs');
  assert.strictEqual(top('bolna'), 'bolna');
  assert.strictEqual(top('warm transfer to another agent'), 'legs');
  assert.strictEqual(top('set up sip trunk for asterisk'), 'sip');
  assert.strictEqual(top('send OTP by SMS'), 'sms');
  assert.strictEqual(top('buy a new toll free number'), 'numbers');
  assert.strictEqual(top('why did my call fail'), 'debug');
  assert.strictEqual(top('build an IVR menu'), 'ivr');
});

test('ask command works offline with JSON output', async () => {
  const p = spawn(process.execPath, [BIN, 'ask', 'connect', 'my', 'openai', 'bot', '--json', '--offline'], { env: { ...process.env, EXOTEL_CACHE_DIR: '/nonexistent-cache' } });
  let s = '';
  p.stdout.on('data', (d) => (s += d));
  const code = await new Promise((r) => p.on('close', r));
  assert.strictEqual(code, 0);
  const d = JSON.parse(s);
  assert.strictEqual(d.recommendations[0].id, 'own-bot-outbound');
  assert.ok(d.recommendations[0].run[0].startsWith('exotel voice-ai connect'));
});

test('reference: extracts status/error tables from docs Markdown and looks up codes and words', () => {
  const ref = require('../lib/reference');
  const md = [
    '# SIP error codes',
    '## 4xx',
    '| Code | Meaning | Fix |',
    '|------|---------|-----|',
    '| `408` | Request timeout | Check firewall / reachability |',
    '| `1408` | Not a SIP code | x |',
    '## Leg Status Values',
    '| Status | Description |',
    '|---|---|',
    '| `completed` | Leg completed successfully |',
    '| `no-answer` | Called party did not answer |',
  ].join('\n');
  const rows = ref.extractRows(md, { title: 'SIP errors', url: 'https://developer.exotel.com/x', source: 'developer.exotel.com' });
  assert.strictEqual(rows.length, 4);
  const hit = ref.lookup(rows, '408');
  assert.strictEqual(hit.length, 1, 'whole-token match only (not 1408)');
  assert.deepStrictEqual(hit[0].cells, ['408', 'Request timeout', 'Check firewall / reachability']);
  assert.strictEqual(hit[0].heading, '4xx');
  assert.strictEqual(ref.lookup(rows, 'no-answer')[0].cells[1], 'Called party did not answer');
  const fam = ref.families(rows);
  assert.ok(fam.some((f) => f.family === 'SIP errors › Leg Status Values' && f.values.join() === 'completed,no-answer'));
});

test('reference: live-verified Legs codes are available offline', async () => {
  const p = spawn(process.execPath, [BIN, 'explain', '1013', '--json', '--offline'], { env: { ...process.env, EXOTEL_CACHE_DIR: '/nonexistent-cache' } });
  let s = '';
  p.stdout.on('data', (d) => (s += d));
  assert.strictEqual(await new Promise((r) => p.on('close', r)), 0);
  const d = JSON.parse(s);
  assert.ok(d.matches.some((m) => m.source === 'live-verified' && /leg_answered/.test(JSON.stringify(m.fields))));
});

test('canonical Exotel flag names and the old short names give the same request', async () => {
  const a = await cli(['leg', 'say', '--leg-sid', 'L9', '--text', 'hi', '--dry-run']);
  const b = await cli(['leg', 'say', '--leg', 'L9', '--text', 'hi', '--dry-run']);
  assert.deepStrictEqual(a.json.request, b.json.request);
  const c = await cli(['voice-ai', 'connect', '--to', '+919876543210', '--app-id', '40536', '--dry-run']);
  assert.strictEqual(new URLSearchParams(c.json.request.body).get('Url'), 'http://my.exotel.com/acme1/exoml/start_voice/40536');
  const e = await cli(['leg', 'say', '--text', 'hi', '--dry-run']);
  assert.match(e.json.error, /--leg-sid is required/);
});

test('connect applet: custom_params (SIP headers) and playback checks', () => {
  const { checkCustomParams } = require('../lib/applet');
  const run = (body) => { const p = []; const n = []; checkCustomParams(body, p, n); return { p, n }; };
  assert.deepStrictEqual(run({ destination: { trunk: 'TK1' }, custom_params: 'X-Order=4521&X-Lang=hi' }).p, []);
  assert.match(run({ destination: { trunk: 'TK1' }, custom_params: 'Exotel-Foo=1' }).p.join(), /reserved prefix/);
  assert.match(run({ destination: { trunk: 'TK1' }, custom_params: Array.from({ length: 11 }, (_, i) => `X-${i}=v`).join('&') }).p.join(), /maximum is 10/);
  assert.match(run({ destination: { trunk: 'TK1' }, custom_params: `X-Big=${'a'.repeat(900)}` }).p.join(), /over 800 bytes/);
  assert.match(run({ destination: { numbers: ['+919800000001'] }, custom_params: 'X-A=1' }).n.join(), /only when destination is a trunk/);
  assert.match(run({ destination: { trunk: 'TK1' }, custom_params: 'X-A=1&X-B=2&X-C=3&X-D=4' }).n.join(), /Older Exotel docs/);
});

test('applet scaffolds are valid code and the Connect handler passes applet test', async () => {
  const fsx = require('fs');
  const os = require('os');
  const { spawnSync, spawn: sp } = require('child_process');
  const dir = fsx.mkdtempSync(path.join(os.tmpdir(), 'exotel-applet-'));
  for (const t of Object.keys(applet.SCAFFOLDS)) {
    const r = applet.scaffold(t, 'node', dir);
    assert.strictEqual(spawnSync(process.execPath, ['--check', r.file]).status, 0, `${t} node template must parse`);
  }
  const py = applet.scaffold('connect', 'python', dir);
  const pyc = spawnSync('python3', ['-I', '-c', 'import ast,sys;ast.parse(open(sys.argv[1]).read())', py.file]);
  if (!pyc.error) assert.strictEqual(pyc.status, 0, String(pyc.stderr));
  // run the generated Connect handler and test it the way Exotel calls it
  const port = 30000 + Math.floor(Math.random() * 20000);
  const child = sp(process.execPath, [path.join(dir, 'exotel-connect-handler.js')], { env: { ...process.env, PORT: String(port) } });
  try {
    for (let i = 0; i < 40; i++) { try { await fetch(`http://127.0.0.1:${port}/`); break; } catch { await new Promise((r) => setTimeout(r, 100)); } }
    const r = await applet.test('connect', `http://127.0.0.1:${port}/connect`, { params: { digits: '2' } });
    assert.strictEqual(r.ok, true, JSON.stringify(r.problems));
    assert.deepStrictEqual(r.body.destination.numbers, ['+919800000002', '+919800000003']);
  } finally {
    child.kill();
  }
});

test('ask: routes Connect applet and custom-data questions', () => {
  const { rank } = require('../lib/ask');
  const top = (q) => rank(q)[0] && rank(q)[0].it.id;
  assert.strictEqual(top('how to set up connect applet with dynamic url'), 'connect-applet');
  assert.strictEqual(top('send custom SIP headers to my PBX'), 'custom-data');
  assert.strictEqual(top('pass custom params to my bot'), 'custom-data');
});

test('calls list fills the missing end of DateCreated; campaigns create wraps the body', async () => {
  const c = await cli(['calls', 'list', '--since', '2026-11-01', '--status', 'completed', '--dry-run']);
  const u = new URL(c.json.request.url);
  assert.match(u.searchParams.get('DateCreated'), /^gte:2026-11-01 00:00:00;lte:\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
  assert.strictEqual(u.searchParams.get('Status'), 'completed');
  const g = await cli(['calls', 'get', '--call-sid', 'abc123def456', '--details', '--dry-run']);
  assert.strictEqual(g.json.request.url, 'https://api.in.exotel.com/v1/Accounts/acme1/Calls/abc123def456.json?details=true');
  const k = await cli(['campaigns', 'create', '--caller-id', '+918000000000', '--numbers', '+919800000001,+919800000002', '--app-id', '926',
    '--retries', '2', '--retry-on', 'busy,no-answer', '--send-at', '2026-11-04T09:00:00+05:30', '--dry-run']);
  assert.strictEqual(k.code, 0, k.stdout);
  const body = k.json.request.body.campaigns[0];
  assert.deepStrictEqual(body, {
    caller_id: '+918000000000', from: '+919800000001,+919800000002', flow_type: 'ivr', url: 'http://my.exotel.com/acme1/exoml/start_voice/926',
    retries: { number_of_retries: 2, interval_mins: 15, mechanism: 'Linear', on_status: ['busy', 'no-answer'] },
    schedule: { send_at: '2026-11-04T09:00:00+05:30' },
  });
  assert.strictEqual((await cli(['campaigns', 'create', '--caller-id', '+918000000000', '--numbers', '+919800000001', '--yes'])).code, 2, 'needs --app-id or --text');
  assert.strictEqual((await cli(['campaigns', 'create', '--caller-id', '+918000000000', '--numbers', '+919800000001', '--text', 'Hi'])).code, 3, 'billed: needs --yes');
  assert.strictEqual((await cli(['campaigns', 'complete', '--campaign-sid', 'C1'])).code, 3, 'destructive: needs --yes');
  const p = await cli(['campaigns', 'pause', '--campaign-sid', 'C1', '--dry-run']);
  assert.deepStrictEqual(p.json.request.body, { action: 'pause' });
});

test('ask: routes call records, campaigns and SDK questions', () => {
  const { rank } = require('../lib/ask');
  const top = (q) => rank(q)[0] && rank(q)[0].it.id;
  assert.strictEqual(top('download call recordings from last week'), 'call-records');
  assert.strictEqual(top('get call logs report for yesterday'), 'call-records');
  assert.strictEqual(top('run a reminder campaign to 2000 numbers'), 'campaigns');
  assert.strictEqual(top('add calling to my android app with webrtc'), 'voice-sdks');
});
