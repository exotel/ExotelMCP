'use strict';
// Catches broken relative requires (lazy require() calls only fail when that code path runs) and checks the
// event receiver's security defaults: localhost bind and Basic auth.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const http = require('http');

const LIB = path.join(__dirname, '..', 'lib');
const files = [path.join(__dirname, '..', 'bin', 'exotel.js'),
  ...fs.readdirSync(LIB).filter((f) => f.endsWith('.js')).map((f) => path.join(LIB, f)),
  ...fs.readdirSync(path.join(LIB, 'commands')).map((f) => path.join(LIB, 'commands', f))];

test('every relative require() resolves', () => {
  const broken = [];
  for (const f of files) {
    for (const m of fs.readFileSync(f, 'utf8').matchAll(/require\('(\.{1,2}\/[^']+)'\)/g)) {
      if (m[1] === './generated.json') continue; // only exists inside the single-file binary build
      try { require.resolve(path.resolve(path.dirname(f), m[1])); } catch { broken.push(`${path.relative(LIB, f)} → ${m[1]}`); }
    }
  }
  assert.deepStrictEqual(broken, []);
});

const post = (port, auth) => new Promise((resolve, reject) => {
  const req = http.request({ host: '127.0.0.1', port, method: 'POST', path: '/', auth }, (res) => { res.resume(); resolve(res.statusCode); });
  req.on('error', reject);
  req.end('{"event_name":"leg_answered","event_sid":"e1"}');
});

test('event receiver: localhost only, Basic auth enforced when configured', async () => {
  const ev = require('../lib/events');
  const got = [];
  const server = await ev.listen({ port: 0, basicUser: 'exo', basicPass: 'pw', onEvent: (r) => got.push(r) });
  try {
    const { address, port } = server.address();
    assert.strictEqual(address, '127.0.0.1');
    assert.strictEqual(await post(port), 401);
    assert.strictEqual(await post(port, 'exo:wrong'), 401);
    assert.strictEqual(await post(port, 'exo:pw'), 200);
    await new Promise((r) => setTimeout(r, 50));
    assert.strictEqual(got.length, 1);
  } finally {
    server.close();
  }
});

test('call progress: prints each status change, ends with a plain result and next steps', async () => {
  const progress = require('../lib/progress');
  const seq = ['queued', 'ringing', 'ringing', 'in-progress', 'no-answer'];
  const lines = [];
  const orig = process.stderr.write.bind(process.stderr);
  process.stderr.write = (s) => { lines.push(String(s)); return true; };
  let r;
  try {
    r = await progress.follow(async () => ({ status: seq.shift(), data: {} }), { intervalMs: 1, timeoutSec: 5 });
  } finally {
    process.stderr.write = orig;
  }
  assert.strictEqual(r.status, 'no-answer');
  const text = lines.join('');
  for (const s of ['queued at Exotel', 'ringing…', 'in progress']) assert.ok(text.includes(s), `missing "${s}" in ${text}`);
  assert.strictEqual((text.match(/ringing…/g) || []).length, 1, 'repeated statuses are not reprinted');
  const sum = progress.summary({ status: r.status, callSid: 'CA1', next: progress.nextSteps(r.status, 'CA1', { voicebot: true }) });
  assert.match(sum, /^✘ Nobody answered/);
  assert.match(sum, /exotel explain no-answer/);
  assert.match(progress.summary({ status: 'completed', callSid: 'CA2', duration: 42, next: progress.nextSteps('completed', 'CA2', { voicebot: true }) }), /✔ Call completed\. Duration: 42s\.[\s\S]*voicebot transcript --call-sid CA2/);
});
