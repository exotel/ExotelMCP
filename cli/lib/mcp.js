'use strict';
// Minimal MCP Streamable-HTTP client for the Exotel MCP server (JSON-RPC over POST).
const DEFAULT_URL = 'https://mcp.exotel.com/mcp';
const PROTOCOL_VERSION = '2025-06-18';

function serverUrl() {
  return process.env.EXOTEL_MCP_URL || DEFAULT_URL;
}

function parseBody(text, contentType) {
  if ((contentType || '').includes('text/event-stream')) {
    // Take the last JSON-RPC message carried in `data:` lines.
    const events = text.split(/\r?\n\r?\n/);
    let last = null;
    for (const ev of events) {
      const data = ev.split(/\r?\n/).filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trimStart()).join('\n');
      if (data) last = data;
    }
    if (!last) throw new Error('Empty event stream from MCP server');
    return JSON.parse(last);
  }
  return JSON.parse(text);
}

class McpClient {
  constructor(auth, { url = serverUrl(), timeoutMs = 60000 } = {}) {
    this.auth = auth;
    this.url = url;
    this.timeoutMs = timeoutMs;
    this.sessionId = null;
    this.nextId = 1;
    this.initialized = false;
  }

  async post(message) {
    const headers = {
      'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream',
      Authorization: JSON.stringify(this.auth),
      'MCP-Protocol-Version': PROTOCOL_VERSION,
    };
    if (this.sessionId) headers['Mcp-Session-Id'] = this.sessionId;
    require('./rest').assertExotelHost(this.url);
    const res = await fetch(this.url, {
      method: 'POST',
      headers,
      body: JSON.stringify(message),
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    const sid = res.headers.get('mcp-session-id');
    if (sid) this.sessionId = sid;
    const text = await res.text();
    if (res.status === 202 || (!text && res.ok)) return null;
    if (!res.ok) {
      const err = new Error(`MCP server HTTP ${res.status}: ${text.slice(0, 300)}`);
      err.httpStatus = res.status;
      throw err;
    }
    return parseBody(text, res.headers.get('content-type'));
  }

  async request(method, params) {
    if (!this.initialized && method !== 'initialize') await this.initialize();
    const id = this.nextId++;
    const msg = await this.post({ jsonrpc: '2.0', id, method, ...(params ? { params } : {}) });
    if (!msg) throw new Error(`No response to ${method}`);
    if (msg.error) {
      const err = new Error(`MCP error ${msg.error.code}: ${msg.error.message}`);
      err.rpc = msg.error;
      throw err;
    }
    return msg.result;
  }

  async initialize() {
    this.initialized = true;
    const result = await this.request('initialize', {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: 'exotel-cli', version: require('../package.json').version },
    });
    try {
      await this.post({ jsonrpc: '2.0', method: 'notifications/initialized' });
    } catch {
      // Stateless servers may not accept notifications; harmless.
    }
    return result;
  }

  async listTools() {
    const tools = [];
    let cursor;
    do {
      const res = await this.request('tools/list', cursor ? { cursor } : undefined);
      tools.push(...(res.tools || []));
      cursor = res.nextCursor;
    } while (cursor);
    return tools;
  }

  async callTool(name, args, { approved = false } = {}) {
    // Safety net: tools that change or cost something only run after the user said yes.
    if (require('./safety').classify(name) !== 'safe' && !approved) {
      throw new (require('./io').CliError)(`Internal safety check: ${name} changes your account and was not confirmed. Nothing was sent.`, 1);
    }
    return this.request('tools/call', { name, arguments: args || {} });
  }
}

/** Flatten an MCP tool result into { ok, text, data }. */
function unwrapResult(result) {
  const parts = (result && result.content) || [];
  const text = parts.filter((p) => p.type === 'text').map((p) => p.text).join('\n');
  let data = result && result.structuredContent;
  if (data === undefined) {
    try {
      data = JSON.parse(text);
    } catch {
      data = undefined;
    }
  }
  // Exotel MCP tools report failures as text ("Error: …") or as a bare {"message": "…"} object.
  const bareMessage = data && typeof data === 'object' && !Array.isArray(data)
    && Object.keys(data).length === 1 && typeof data.message === 'string';
  const looksLikeError = /^\s*Error\b/.test(text) || bareMessage;
  return { ok: !(result && result.isError) && !looksLikeError, text, data };
}

module.exports = { McpClient, unwrapResult, serverUrl, DEFAULT_URL, parseBody };
