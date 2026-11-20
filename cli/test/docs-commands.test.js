'use strict';
// Every `exotel …` command shown in the skills and docs must be one the CLI accepts (subcommand and every --flag),
// so an agent or developer copying a documented command never gets "Unknown option".
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const { check, SUBCOMMANDS } = require('../lib/validate');

const ROOT = path.join(__dirname, '..', '..');
const SOURCES = ['skills', 'docs', 'README.md', 'cli/README.md', 'cli/TESTING.md', 'examples/exotel-voice-agent/README.md'];

function files(p) {
  const abs = path.join(ROOT, p);
  if (!fs.existsSync(abs)) return [];
  if (fs.statSync(abs).isFile()) return [abs];
  return fs.readdirSync(abs, { withFileTypes: true }).flatMap((d) => (d.isDirectory()
    ? files(path.join(p, d.name)) : d.name.endsWith('.md') ? [path.join(abs, d.name)] : []));
}

/** `exotel …` commands in code blocks and inline code, joined across `\` line continuations. */
function commands(text) {
  const found = [];
  const pieces = [];
  for (const m of text.matchAll(/```[^\n]*\n([\s\S]*?)```/g)) pieces.push(...m[1].replace(/\\\n\s*/g, ' ').split('\n'));
  for (const m of text.replace(/```[\s\S]*?```/g, '').matchAll(/`([^`\n]+)`/g)) pieces.push(m[1]);
  for (const line of pieces) {
    for (const m of line.matchAll(/(?:^|[\s;&|($])exotel\s+([a-z][a-z-]*)((?:\s+[^\s;&|#)]+)*)/g)) found.push({ cmd: m[1], rest: m[2].trim(), line: line.trim() });
  }
  return found;
}

test('every documented exotel command is accepted by the CLI', () => {
  const groups = new Set([...Object.keys(SUBCOMMANDS), 'campaign', 'leg']);
  const bad = [];
  let n = 0;
  for (const src of SOURCES) {
    for (const f of files(src)) {
      for (const { cmd, rest, line } of commands(fs.readFileSync(f, 'utf8'))) {
        const words = rest ? rest.split(/\s+/) : [];
        const sub = groups.has(cmd) && words[0] && /^[a-z][a-z0-9-]*$/.test(words[0]) ? words[0] : undefined;
        const flagNames = words.filter((w) => /^--[a-z]/.test(w)).map((w) => w.slice(2).split('=')[0]);
        n++;
        try { check(cmd, sub, flagNames); } catch (e) { bad.push(`${path.relative(ROOT, f)}: ${line}\n    ${e.message.split('\n')[0]}`); }
      }
    }
  }
  assert.ok(n > 100, `expected to find documented commands, found ${n}`);
  assert.deepStrictEqual(bad, [], `${bad.length} documented commands are rejected:\n${bad.join('\n')}`);
});
