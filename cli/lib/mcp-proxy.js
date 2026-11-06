'use strict';
// `exotel mcp proxy`: a local stdio MCP server that relays to the hosted Exotel MCP server, adding your
// credentials from ~/.config/exotel/credentials.json (or EXOTEL_* environment variables) on each request.
// Coding agents are configured to run this command, so their config files never contain your API token:
// there is exactly one copy of it, and `exotel logout` removes it.
//
// Protocol: newline-delimited JSON-RPC on stdin/stdout (MCP stdio transport). Logs go to stderr only.
const readline = require('readline');
const { McpClient } = require('./mcp');

async function run() {
  const creds = require('./credentials');
  const c = creds.load();
  if (!c) {
    process.stderr.write('exotel mcp proxy: not logged in. Run `exotel login` (or `exotel init`) in a terminal first.\n');
    process.exit(4);
  }
  const client = new McpClient(c.auth);
  const write = (msg) => process.stdout.write(`${JSON.stringify(msg)}\n`);
  const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  let pending = Promise.resolve();
  rl.on('line', (line) => {
    if (!line.trim()) return;
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      write({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } });
      return;
    }
    // Keep request order (the hosted server is stateless per request, but clients expect ordered replies).
    pending = pending.then(async () => {
      try {
        const reply = await client.post(msg);
        if (reply && msg.id !== undefined) write(reply);
      } catch (e) {
        if (msg.id !== undefined) write({ jsonrpc: '2.0', id: msg.id, error: { code: -32603, message: `Exotel MCP server: ${e.message}` } });
        else process.stderr.write(`exotel mcp proxy: ${e.message}\n`);
      }
    });
  });
  rl.on('close', () => pending.then(() => process.exit(0)));
}

module.exports = { run };
