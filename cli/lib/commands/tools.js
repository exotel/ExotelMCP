'use strict';
// Offline developer tools: ExoML validation and Dynamic URL (applet webhook) testing.
const fs = require('fs');
const exoml = require('../exoml');
const applet = require('../applet');
const { CliError, EXIT, out } = require('../io');

// ------------------------------------------------------------------ exoml / applet tools (no account access)

function exomlCmd(sub, args, flags) {
  if (sub !== 'validate') throw new CliError('Usage: exotel exoml validate <file.xml | -> (or --xml \'<Flow>…</Flow>\')', EXIT.USAGE);
  const xml = flags.xml && flags.xml !== true ? String(flags.xml) : fs.readFileSync(!args[0] || args[0] === '-' ? 0 : args[0], 'utf8');
  const v = exoml.validate(xml);
  out(v, () => (v.valid ? `✔ Valid ExoML: ${v.actions.join(' → ')}` : `✘ Invalid ExoML\n- ${v.errors.join('\n- ')}`) + (v.warnings.length ? `\nWarnings:\n- ${v.warnings.join('\n- ')}` : ''));
  if (!v.valid) process.exitCode = EXIT.ERROR;
}

async function appletCmd(sub, args, flags) {
  if (sub === 'scaffold') {
    if (!args[0]) throw new CliError(`Usage: exotel applet scaffold <${Object.keys(applet.SCAFFOLDS).join('|')}> [--lang node|python] [--dir .]`, EXIT.USAGE);
    const r = applet.scaffold(args[0], flags.lang && flags.lang !== true ? String(flags.lang) : 'node', flags.dir && flags.dir !== true ? String(flags.dir) : '.', { force: !!flags.force });
    return out(r, () => `${r.status === 'created' ? '✔ Created' : '• Exists (use --force)'} ${r.file}\nRun it, expose it over HTTPS, then: exotel applet test ${args[0]} https://<host>/`);
  }
  if (sub !== 'test' || !args[0] || !args[1]) {
    throw new CliError(`Usage: exotel applet test <${Object.keys(applet.TYPES).join('|')}> <your https URL> [--param Key=Value,Key2=Value2]`, EXIT.USAGE);
  }
  const params = {};
  if (flags.param && flags.param !== true) {
    String(flags.param).split(',').forEach((p) => {
      const i = p.indexOf('=');
      if (i > 0) params[p.slice(0, i)] = p.slice(i + 1);
    });
  }
  const r = await applet.test(args[0], args[1], { params });
  out(r, () => [
    `${r.ok ? '✔' : '✘'} ${r.type} applet → ${r.status ?? 'no response'}${r.ms !== undefined ? ` in ${r.ms} ms` : ''}${r.route ? ` → ${r.route}` : ''}`,
    ...r.problems.map((p) => `  ✘ ${p}`),
    ...r.notes.map((n) => `  • ${n}`),
  ].join('\n'));
  if (!r.ok) process.exitCode = EXIT.ERROR;
}

module.exports = { exomlCmd, appletCmd };
