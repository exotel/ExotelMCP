'use strict';
// Live progress for a placed call: each status change with elapsed time, a heartbeat while nothing changes,
// then a plain-English result and the next command to run. Progress goes to stderr (info); the final
// result goes through out(), so --json output stays machine-readable.
// Progress always goes to stderr (also in --json mode, so agents and pipes see it); stdout stays clean.
// Silence it with --quiet or EXOTEL_QUIET=1.
const info = (msg) => {
  if (process.env.EXOTEL_QUIET === '1' || process.argv.includes('--quiet')) return;
  process.stderr.write(msg + '\n');
};

const TERMINAL = /^(completed|failed|busy|no-answer|canceled|cancelled)$/i;

const NOW = {
  queued: 'queued at Exotel',
  initiated: 'dialling…',
  ringing: 'ringing…',
  'in-progress': 'in progress (ringing or connected)',
  answered: 'answered, connected',
};

const RESULT = {
  completed: 'Call completed.',
  'no-answer': 'Nobody answered: the phone rang until Exotel stopped trying (about 30–45 s).',
  busy: 'The line was busy, or the call was rejected.',
  failed: 'The call could not be connected (number unreachable or switched off, DND, invalid caller ID, or an account limit).',
  canceled: 'The call was cancelled before it connected.',
  cancelled: 'The call was cancelled before it connected.',
  timeout: 'Stopped waiting; the call may still be running.',
};

const clock = (ms) => {
  const s = Math.max(0, Math.round(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};

/**
 * Poll until the call ends. poll() returns { status, data }.
 * Returns { status, data, seconds }.
 */
async function follow(poll, { timeoutSec = 300, intervalMs = 3000 } = {}) {
  const t0 = Date.now();
  let last = null;
  let lastPrint = t0;
  let data = null;
  while (Date.now() - t0 < timeoutSec * 1000) {
    let status = null;
    try {
      ({ status, data } = await poll());
    } catch (e) {
      info(`  [${clock(Date.now() - t0)}] could not read the call status (${e.message}); retrying`);
    }
    const key = status && String(status).toLowerCase();
    if (key && key !== last) {
      if (!TERMINAL.test(key)) info(`  [${clock(Date.now() - t0)}] ${NOW[key] || key}`);
      last = key;
      lastPrint = Date.now();
    } else if (Date.now() - lastPrint >= 15000) {
      info(`  [${clock(Date.now() - t0)}] still ${last ? (NOW[last] || last).replace(/…$/, '') : 'waiting for Exotel'}…`);
      lastPrint = Date.now();
    }
    if (key && TERMINAL.test(key)) return { status: key, data, seconds: Math.round((Date.now() - t0) / 1000) };
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  return { status: 'timeout', data, seconds: timeoutSec };
}

/** Human summary for the end of a call. */
function summary({ status, callSid, duration, next = [] }) {
  const ok = status === 'completed';
  const talk = duration !== undefined && duration !== null && duration !== '' ? ` Duration: ${duration}s.` : '';
  return [
    `${ok ? '✔' : '✘'} ${RESULT[status] || `Final status: ${status}.`}${talk}`,
    `  CallSid: ${callSid}`,
    ...(next.length ? ['Next:', ...next.map((n) => `  ${n}`)] : []),
  ].join('\n');
}

/** Suggested follow-up commands for a finished call. */
function nextSteps(status, callSid, { voicebot = false } = {}) {
  const steps = [];
  if (status === 'completed' && voicebot) steps.push(`exotel voicebot transcript --call-sid ${callSid}    what was said`);
  if (status !== 'completed') steps.push(`exotel explain ${status}    what this status means`);
  steps.push(`exotel calls get --call-sid ${callSid} --details    full call record`);
  if (voicebot && status !== 'completed') steps.push(`exotel voicebot debug --call-sid ${callSid}    bot-side diagnosis`);
  return steps;
}

module.exports = { follow, summary, nextSteps, info, TERMINAL };
