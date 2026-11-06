'use strict';
// ExoML: the <Flow> XML sent to the Leg Actions API (POST /v2/accounts/{sid}/legs/{leg_sid}/actions).
// Schema from docs.exotel.com/voice-apis/request-curl-and-response-payloads. Unknown elements and
// attributes are reported as errors so typos are caught before they reach a live call.

const ENUM_DIR = ['in', 'out', 'both'];
const BOOL = ['true', 'false'];

// attrs: { name: { required?, enum?, kind? } } ; body: 'none' | 'text' | 'url' | 'digits' | 'children' | 'optional-children'
// Source: Exotel CPaaS (Legs/Bridges) API reference v2.4 — every leg and bridge action.
const INT = { kind: 'int' };
const EP = { kind: 'endpoint' };
const SCHEMA = {
  Answer: { attrs: {}, body: 'none' },
  Hangup: { attrs: {}, body: 'none' },
  Dial: {
    attrs: {
      contactUri: { required: true }, exophone: { required: true }, networkType: { enum: ['pstn', 'voip'] }, absorbDtmf: { enum: BOOL },
      timeout: INT, setupTimeout: INT, ringTimeout: INT, timeLimit: INT, customParam: {},
    },
    body: 'none',
  },
  Gather: { attrs: { numDigits: INT, timeoutInSec: INT, timeout: INT, finishOnKey: { kind: 'key' } }, body: 'optional-children', children: ['StartPlay', 'Say'] },
  Hold: { attrs: {}, body: 'optional-children', children: ['StartPlay'] },
  UnHold: { attrs: {}, body: 'none' },
  JoinBridge: { attrs: { bridgeSid: { required: true }, absorbDtmf: { enum: BOOL } }, body: 'none' },
  LeaveBridge: { attrs: {}, body: 'none' },
  Mute: { attrs: { direction: { enum: ENUM_DIR } }, body: 'none' },
  UnMute: { attrs: { direction: { enum: ENUM_DIR } }, body: 'none' },
  PlaySequence: { attrs: {}, body: 'children', children: ['StartPlay', 'Say'] },
  Refer: { attrs: { legEventEndpoint: EP, bridgeEventEndpoint: EP }, body: 'none', oneOf: ['legEventEndpoint', 'bridgeEventEndpoint'] },
  Say: {
    attrs: { loop: INT, preferredTTSEngine: { enum: ['polly', 'googletts', 'google'] }, googlettsVoiceName: {}, pollyVoiceId: {}, PollyVoiceId: {}, voice: {}, language: {} },
    body: 'text',
  },
  StopSay: { attrs: {}, body: 'none' },
  SendDigits: { attrs: { duration: INT, between: INT }, body: 'digits' },
  StartMonitoring: {
    attrs: {
      monitoringLegSid: {}, contactUri: {}, exophone: {}, networkType: { enum: ['pstn', 'voip'] },
      timeout: INT, setupTimeout: INT, ringTimeout: INT, timeLimit: INT, rxAudio: { enum: ['muted', 'enabled', 'normal'] },
    },
    body: 'none', oneOf: ['monitoringLegSid', 'contactUri'],
  },
  StopMonitoring: { attrs: {}, body: 'none' },
  StartPlay: { attrs: { loop: INT, username: {}, password: {} }, body: 'media' },
  StopPlay: { attrs: {}, body: 'none' },
  StartRecording: {
    attrs: {
      direction: { enum: ENUM_DIR }, format: { enum: ['wav', 'mp3'] }, bitrate: { enum: ['8', '24', '32', '64', '128'] }, channel: { enum: ['mono', 'stereo'] },
      storageType: { enum: ['https', 's3'] }, storageURL: { kind: 'url' }, storageURLKey: {}, storageURLToken: {},
      silenceInSec: INT, finishOnKey: { kind: 'key' }, timeoutInSec: INT, // voicemail mode
    },
    body: 'optional-children', children: ['Say', 'StartPlay'],
  },
  StopRecording: { attrs: {}, body: 'none' },
  StartStream: {
    attrs: {
      streamType: { required: true, enum: ['unidirectional', 'bidirectional'] }, streamUrl: { required: true, kind: 'ws' },
      sourceDirection: { enum: ENUM_DIR }, secureDtmf: { enum: BOOL },
    },
    body: 'none',
  },
  StopStream: { attrs: { streamSid: {} }, body: 'none' },
};
// Actions allowed on a bridge (POST /bridges/{sid}/actions).
const BRIDGE_ACTIONS = ['Refer', 'StartPlay', 'StopPlay', 'Say', 'StopSay', 'StartRecording', 'StopRecording', 'PlaySequence'];

/** Minimal XML parser: elements, attributes, text. Enough for ExoML; rejects DTDs and entities beyond the XML five. */
function parseXml(src) {
  let i = 0;
  const s = src.replace(/^﻿/, '');
  const err = (m) => {
    const line = s.slice(0, i).split('\n').length;
    throw new Error(`XML line ${line}: ${m}`);
  };
  const decode = (t) => t.replace(/&(lt|gt|amp|quot|apos);/g, (_, e) => ({ lt: '<', gt: '>', amp: '&', quot: '"', apos: "'" })[e]);
  const skipWs = () => {
    while (i < s.length && /\s/.test(s[i])) i++;
  };
  const skipMisc = () => {
    for (;;) {
      skipWs();
      if (s.startsWith('<?', i)) {
        const e = s.indexOf('?>', i);
        if (e < 0) err('unterminated <?');
        i = e + 2;
      } else if (s.startsWith('<!--', i)) {
        const e = s.indexOf('-->', i);
        if (e < 0) err('unterminated comment');
        i = e + 3;
      } else if (s.startsWith('<!', i)) err('DOCTYPE / DTD is not allowed');
      else return;
    }
  };
  function element() {
    if (s[i] !== '<') err('expected <');
    i++;
    const nm = /^[A-Za-z_][\w.-]*/.exec(s.slice(i));
    if (!nm) err('expected element name');
    const node = { name: nm[0], attrs: {}, children: [], text: '' };
    i += nm[0].length;
    for (;;) {
      skipWs();
      if (s.startsWith('/>', i)) {
        i += 2;
        return node;
      }
      if (s[i] === '>') {
        i++;
        break;
      }
      const an = /^[A-Za-z_][\w.:-]*/.exec(s.slice(i));
      if (!an) err(`bad attribute in <${node.name}>`);
      i += an[0].length;
      skipWs();
      if (s[i] !== '=') err(`attribute ${an[0]} needs a value`);
      i++;
      skipWs();
      const q = s[i];
      if (q !== '"' && q !== "'") err(`attribute ${an[0]} value must be quoted`);
      const e = s.indexOf(q, i + 1);
      if (e < 0) err('unterminated attribute value');
      if (Object.prototype.hasOwnProperty.call(node.attrs, an[0])) err(`duplicate attribute ${an[0]}`);
      node.attrs[an[0]] = decode(s.slice(i + 1, e));
      i = e + 1;
    }
    for (;;) {
      if (i >= s.length) err(`<${node.name}> is not closed`);
      if (s.startsWith('</', i)) {
        i += 2;
        const cn = /^[A-Za-z_][\w.-]*/.exec(s.slice(i));
        if (!cn || cn[0] !== node.name) err(`expected </${node.name}>`);
        i += cn[0].length;
        skipWs();
        if (s[i] !== '>') err('expected >');
        i++;
        node.text = node.text.trim();
        return node;
      }
      if (s.startsWith('<!--', i)) {
        const e = s.indexOf('-->', i);
        if (e < 0) err('unterminated comment');
        i = e + 3;
      } else if (s.startsWith('<![CDATA[', i)) {
        const e = s.indexOf(']]>', i);
        if (e < 0) err('unterminated CDATA');
        node.text += s.slice(i + 9, e);
        i = e + 3;
      } else if (s[i] === '<') node.children.push(element());
      else {
        const e = s.indexOf('<', i);
        const chunk = s.slice(i, e < 0 ? s.length : e);
        if (/&(?!(lt|gt|amp|quot|apos);)/.test(chunk)) err('unescaped & in text (use &amp;)');
        node.text += decode(chunk);
        i = e < 0 ? s.length : e;
      }
    }
  }
  skipMisc();
  const root = element();
  skipMisc();
  if (i < s.length) err('content after the root element');
  return root;
}

function validate(xml, { target = 'leg' } = {}) {
  const errors = [];
  const warnings = [];
  let root;
  try {
    root = parseXml(xml);
  } catch (e) {
    return { valid: false, errors: [e.message], warnings, actions: [] };
  }
  if (root.name !== 'Flow') errors.push(`Root element must be <Flow>, found <${root.name}>`);
  if (Object.keys(root.attrs).length) errors.push('<Flow> takes no attributes');
  if (root.text) errors.push('<Flow> must contain only action elements, not text');
  if (!root.children.length) errors.push('<Flow> is empty: add at least one action');

  const check = (node, where, allowed) => {
    const spec = SCHEMA[node.name];
    const at = `${where}<${node.name}>`;
    if (!spec) {
      const near = Object.keys(SCHEMA).find((k) => k.toLowerCase() === node.name.toLowerCase());
      errors.push(`${at}: unknown element${near ? ` (did you mean <${near}>? names are case-sensitive)` : ''}`);
      return;
    }
    if (allowed && !allowed.includes(node.name)) errors.push(`${at}: not allowed here (allowed: ${allowed.map((a) => `<${a}>`).join(', ')})`);
    for (const [a, v] of Object.entries(node.attrs)) {
      const as = spec.attrs[a];
      if (!as) {
        const near = Object.keys(spec.attrs).find((k) => k.toLowerCase() === a.toLowerCase());
        errors.push(`${at}: unknown attribute ${a}${near ? ` (did you mean ${near}?)` : ''}`);
        continue;
      }
      if (as.enum && !as.enum.includes(v)) errors.push(`${at}: ${a}="${v}" must be one of ${as.enum.join(', ')}`);
      if (as.kind === 'int' && !/^\d+$/.test(v)) errors.push(`${at}: ${a} must be a whole number`);
      if (as.kind === 'key' && !/^([0-9*#]?)$/.test(v)) errors.push(`${at}: ${a} must be one of 0-9, *, # or empty`);
      if (as.kind === 'ws' && !/^wss?:\/\/\S+$/.test(v)) errors.push(`${at}: ${a} must be a ws:// or wss:// URL`);
      if (as.kind === 'url' && !/^https?:\/\/\S+$/.test(v)) errors.push(`${at}: ${a} must be an http(s) URL`);
      if (as.kind === 'endpoint' && !/^https:\/\/\S+$/.test(v)) errors.push(`${at}: ${a} must be an https:// endpoint`);
      if (as.kind === 'ws' && v.startsWith('ws://')) warnings.push(`${at}: ${a} uses ws:// — use wss:// in production`);
    }
    for (const [a, as] of Object.entries(spec.attrs)) {
      if (as.required && !(a in node.attrs)) errors.push(`${at}: missing required attribute ${a}`);
    }
    if (spec.oneOf && !spec.oneOf.some((k) => k in node.attrs)) errors.push(`${at}: needs one of ${spec.oneOf.join(' or ')}`);
    if (node.name === 'StartRecording' && node.attrs.storageType === 'https') {
      for (const k of ['storageURL', 'storageURLKey', 'storageURLToken']) if (!(k in node.attrs)) errors.push(`${at}: ${k} is required when storageType="https"`);
    }
    if (node.name === 'StartRecording' && node.attrs.channel === 'stereo' && node.attrs.direction && node.attrs.direction !== 'both') errors.push(`${at}: channel="stereo" needs direction="both"`);
    if (node.name === 'StartStream' && node.attrs.secureDtmf && node.attrs.streamType !== 'bidirectional') warnings.push(`${at}: secureDtmf applies only to bidirectional streams`);
    if (node.name === 'StartStream' && node.attrs.sourceDirection && node.attrs.streamType !== 'unidirectional') warnings.push(`${at}: sourceDirection applies only to unidirectional streams`);
    if (spec.body === 'children' || spec.body === 'optional-children') {
      if (node.text) errors.push(`${at}: contains text; put it inside a child element`);
      if (spec.body === 'children' && !node.children.length) errors.push(`${at}: empty`);
      node.children.forEach((c) => check(c, `${at} `, spec.children));
    } else {
      if (node.children.length) errors.push(`${at}: cannot contain elements`);
      if (spec.body === 'none' && node.text) errors.push(`${at}: takes no text content`);
      if (spec.body === 'text' && !node.text) errors.push(`${at}: needs text to speak`);
      if (spec.body === 'media' && !/^(https?:\/\/\S+|sound:\S+)$/.test(node.text)) errors.push(`${at}: body must be an http(s) audio URL or sound:<file>`);
      if (spec.body === 'digits' && !/^[0-9*#A-Da-dwW]+$/.test(node.text)) errors.push(`${at}: body must be DTMF digits (0-9, *, #, A-D)`);
    }
  };
  if (root.name === 'Flow') root.children.forEach((c) => check(c, '', target === 'bridge' ? BRIDGE_ACTIONS : undefined));
  return { valid: errors.length === 0, errors, warnings, actions: root.children.map((c) => c.name) };
}

module.exports = { SCHEMA, BRIDGE_ACTIONS, parseXml, validate };
