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
