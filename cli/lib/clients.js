'use strict';
// Registers the Exotel MCP server in agent clients. Every file edit keeps a timestamped backup.
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const HOME = os.homedir();

function onPath(cmd) {
  const r = spawnSync(process.platform === 'win32' ? 'where' : 'which', [cmd], { encoding: 'utf8' });
  return r.status === 0;
}

function appSupport(...p) {
  if (process.platform === 'darwin') return path.join(HOME, 'Library', 'Application Support', ...p);
  if (process.platform === 'win32') return path.join(process.env.APPDATA || path.join(HOME, 'AppData', 'Roaming'), ...p);
  return path.join(process.env.XDG_CONFIG_HOME || path.join(HOME, '.config'), ...p);
}

function backup(file) {
  if (!fs.existsSync(file)) return null;
  const b = `${file}.bak-${new Date().toISOString().replace(/[:.]/g, '-')}`;
  fs.copyFileSync(file, b);
  return b;
}

function readJson(file) {
  if (!fs.existsSync(file)) return {};
  const raw = fs.readFileSync(file, 'utf8').trim();
  if (!raw) return {};
  try {
    return JSON.parse(raw);
  } catch (e) {
    throw new Error(`${file} is not plain JSON (comments?). Edit it by hand or fix it first: ${e.message}`);
  }
}

function writeJsonEntry(file, rootKey, name, entry, { force, dryRun }) {
  const cfg = readJson(file);
  cfg[rootKey] = cfg[rootKey] || {};
  const existed = Object.prototype.hasOwnProperty.call(cfg[rootKey], name);
  if (existed && !force) return { file, status: 'exists', hint: 'pass --force to replace it' };
  cfg[rootKey][name] = entry;
  if (dryRun) return { file, status: existed ? 'would-replace' : 'would-add' };
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const bak = backup(file);
  fs.writeFileSync(file, JSON.stringify(cfg, null, 2) + '\n', { mode: 0o600 });
  return { file, status: existed ? 'replaced' : 'added', backup: bak };
}

function tomlString(s) {
  if (!s.includes("'") && !/[\r\n]/.test(s)) return `'${s}'`;
  return JSON.stringify(s); // JSON string escaping is valid TOML basic-string escaping for these characters
}

const CLIENTS = {
  'claude-code': {
    label: 'Claude Code',
    detect: () => onPath('claude'),
    install(ctx) {
      const scope = ctx.scope === 'project' ? 'project' : ctx.scope === 'local' ? 'local' : 'user';
      const exists = spawnSync('claude', ['mcp', 'get', ctx.name], { encoding: 'utf8' }).status === 0;
      const addArgs = ['mcp', 'add', '--transport', 'http', ctx.name, ctx.url, '--scope', scope,
        '--header', `Authorization: ${ctx.authJson}`];
      if (ctx.dryRun) {
        return { command: `claude mcp add --transport http ${ctx.name} ${ctx.url} --scope ${scope} --header 'Authorization: <credentials>'`, status: exists ? 'would-replace' : 'would-add' };
      }
      if (exists) {
        if (!ctx.force) return { status: 'exists', hint: `"${ctx.name}" is already registered in Claude Code; pass --force to replace it` };
        spawnSync('claude', ['mcp', 'remove', ctx.name, '-s', scope], { encoding: 'utf8' });
      }
      const r = spawnSync('claude', addArgs, { encoding: 'utf8' });
      if (r.status !== 0) return { status: 'error', error: (r.stderr || r.stdout || '').trim().slice(0, 400) };
      return { status: exists ? 'replaced' : 'added', scope, next: 'Restart Claude Code or run /mcp' };
    },
  },
  cursor: {
    label: 'Cursor',
    detect: () => fs.existsSync(path.join(HOME, '.cursor')),
    install(ctx) {
      const file = ctx.scope === 'project' ? path.join(process.cwd(), '.cursor', 'mcp.json') : path.join(HOME, '.cursor', 'mcp.json');
      return { ...writeJsonEntry(file, 'mcpServers', ctx.name, { url: ctx.url, headers: { Authorization: ctx.authJson } }, ctx), next: 'Reload Cursor; enable the server under Settings → MCP' };
    },
  },
  windsurf: {
    label: 'Windsurf',
    detect: () => fs.existsSync(path.join(HOME, '.codeium', 'windsurf')),
    install(ctx) {
      const file = path.join(HOME, '.codeium', 'windsurf', 'mcp_config.json');
      return { ...writeJsonEntry(file, 'mcpServers', ctx.name, { serverUrl: ctx.url, headers: { Authorization: ctx.authJson } }, ctx), next: 'Refresh MCP servers in Windsurf' };
    },
  },
  vscode: {
    label: 'VS Code',
    detect: () => fs.existsSync(appSupport('Code', 'User')),
    install(ctx) {
      const file = ctx.scope === 'project' ? path.join(process.cwd(), '.vscode', 'mcp.json') : appSupport('Code', 'User', 'mcp.json');
      return { ...writeJsonEntry(file, 'servers', ctx.name, { type: 'http', url: ctx.url, headers: { Authorization: ctx.authJson } }, ctx), next: 'Run "MCP: List Servers" in VS Code and start exotel' };
    },
  },
  'claude-desktop': {
    label: 'Claude Desktop',
    detect: () => fs.existsSync(appSupport('Claude')),
    install(ctx) {
      const file = appSupport('Claude', 'claude_desktop_config.json');
      const entry = {
        command: 'npx',
        args: ['-y', 'mcp-remote', ctx.url, '--header', 'Authorization:${AUTH_HEADER}'],
        env: { AUTH_HEADER: ctx.authJson },
      };
      return { ...writeJsonEntry(file, 'mcpServers', ctx.name, entry, ctx), next: 'Quit and reopen Claude Desktop' };
    },
  },
  codex: {
    label: 'Codex CLI',
    detect: () => onPath('codex') || fs.existsSync(path.join(HOME, '.codex')),
    install(ctx) {
      const file = path.join(process.env.CODEX_HOME || path.join(HOME, '.codex'), 'config.toml');
      const header = `[mcp_servers.${ctx.name}]`;
      const block = `${header}\nurl = ${tomlString(ctx.url)}\nhttp_headers = { "Authorization" = ${tomlString(ctx.authJson)} }\n`;
      const current = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
      const lines = current.split(/\r?\n/);
      const start = lines.findIndex((l) => l.trim() === header);
      const existed = start >= 0;
      if (existed && !ctx.force) return { file, status: 'exists', hint: 'pass --force to replace it' };
      if (ctx.dryRun) return { file, status: existed ? 'would-replace' : 'would-add' };
      let next;
      if (existed) {
        let end = lines.findIndex((l, i) => i > start && /^\s*\[/.test(l));
        if (end < 0) end = lines.length;
        next = [...lines.slice(0, start), ...block.trimEnd().split('\n'), '', ...lines.slice(end)].join('\n');
      } else {
        next = (current && !current.endsWith('\n') ? current + '\n' : current) + (current ? '\n' : '') + block;
      }
      fs.mkdirSync(path.dirname(file), { recursive: true });
      const bak = backup(file);
      fs.writeFileSync(file, next, { mode: 0o600 });
      return { file, status: existed ? 'replaced' : 'added', backup: bak, next: 'Restart Codex' };
    },
  },
};

function detectClients() {
  return Object.keys(CLIENTS).filter((k) => {
    try {
      return CLIENTS[k].detect();
    } catch {
      return false;
    }
  });
}

function resolveClients(spec) {
  if (!spec || spec === 'auto') return detectClients();
  if (spec === 'all') return Object.keys(CLIENTS);
  const list = spec.split(',').map((s) => s.trim()).filter(Boolean);
  const unknown = list.filter((c) => !CLIENTS[c]);
  if (unknown.length) throw new Error(`Unknown client(s): ${unknown.join(', ')}. Known: ${Object.keys(CLIENTS).join(', ')}`);
  return list;
}

function install(clientIds, { auth, url, name = 'exotel', scope = 'user', force = false, dryRun = false }) {
  const ctx = { authJson: JSON.stringify(auth), url, name, scope, force, dryRun };
  return clientIds.map((id) => {
    try {
      return { client: id, ...CLIENTS[id].install(ctx) };
    } catch (e) {
      return { client: id, status: 'error', error: e.message };
    }
  });
}

module.exports = { CLIENTS, detectClients, resolveClients, install, tomlString };
