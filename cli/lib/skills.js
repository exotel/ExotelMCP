'use strict';
// Installs the bundled Exotel agent skills (SKILL.md folders) into coding agents.
const fs = require('fs');
const os = require('os');
const path = require('path');

const HOME = os.homedir();

function skillsRoot() {
  const packaged = path.resolve(__dirname, '..', 'skills');
  if (fs.existsSync(packaged)) return packaged;
  const repo = path.resolve(__dirname, '..', '..', 'skills'); // running from a git checkout
  if (fs.existsSync(repo)) return repo;
  // Single-file binary: the skills are embedded; write them out once per version.
  let embedded;
  try {
    embedded = require('./generated.json');
  } catch {
    throw new Error('Bundled skills not found');
  }
  const dir = path.join(require('./credentials').configDir(), `skills-${embedded.version}`);
  if (!fs.existsSync(path.join(dir, '.complete'))) {
    for (const [name, content] of Object.entries(embedded.skills)) {
      fs.mkdirSync(path.join(dir, name), { recursive: true });
      fs.writeFileSync(path.join(dir, name, 'SKILL.md'), content);
    }
    fs.writeFileSync(path.join(dir, '.complete'), '');
  }
  return dir;
}

function parseFrontmatter(md) {
  const m = md.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/);
  if (!m) return { meta: {}, body: md };
  const meta = {};
  for (const line of m[1].split(/\r?\n/)) {
    const i = line.indexOf(':');
    if (i > 0) meta[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  return { meta, body: m[2] };
}

function list() {
  const root = skillsRoot();
  return fs.readdirSync(root, { withFileTypes: true })
    .filter((d) => d.isDirectory() && fs.existsSync(path.join(root, d.name, 'SKILL.md')))
    .map((d) => {
      const { meta } = parseFrontmatter(fs.readFileSync(path.join(root, d.name, 'SKILL.md'), 'utf8'));
      return { name: meta.name || d.name, dir: path.join(root, d.name), description: meta.description || '' };
    });
}

const AGENTS = {
  claude: {
    label: 'Claude Code',
    detect: () => fs.existsSync(path.join(HOME, '.claude')),
    target: (project) => (project ? path.join(process.cwd(), '.claude', 'skills') : path.join(HOME, '.claude', 'skills')),
    format: 'skill-dir',
  },
  codex: {
    label: 'Codex',
    detect: () => fs.existsSync(path.join(HOME, '.codex')),
    target: (project) => (project ? path.join(process.cwd(), '.codex', 'skills') : path.join(process.env.CODEX_HOME || path.join(HOME, '.codex'), 'skills')),
    format: 'skill-dir',
  },
  cursor: {
    label: 'Cursor (project rules)',
    detect: () => fs.existsSync(path.join(process.cwd(), '.cursor')) || fs.existsSync(path.join(HOME, '.cursor')),
    // Cursor reads rules per project; there is no file-based global rules folder.
    target: () => path.join(process.cwd(), '.cursor', 'rules'),
    format: 'cursor-mdc',
  },
};

function resolveAgents(spec) {
  if (!spec || spec === 'auto') return Object.keys(AGENTS).filter((a) => AGENTS[a].detect());
  if (spec === 'all') return Object.keys(AGENTS);
  const listed = spec.split(',').map((s) => s.trim()).filter(Boolean);
  const unknown = listed.filter((a) => !AGENTS[a]);
  if (unknown.length) throw new Error(`Unknown agent(s): ${unknown.join(', ')}. Known: ${Object.keys(AGENTS).join(', ')} (or use --dir)`);
  return listed;
}

function installOne(skill, destRoot, format, { force, dryRun }) {
  if (format === 'cursor-mdc') {
    const file = path.join(destRoot, `${skill.name}.mdc`);
    if (fs.existsSync(file) && !force) return { skill: skill.name, path: file, status: 'exists' };
    if (dryRun) return { skill: skill.name, path: file, status: 'would-install' };
    const { meta, body } = parseFrontmatter(fs.readFileSync(path.join(skill.dir, 'SKILL.md'), 'utf8'));
    const mdc = `---\ndescription: ${meta.description || skill.name}\nglobs:\nalwaysApply: false\n---\n${body}`;
    fs.mkdirSync(destRoot, { recursive: true });
    fs.writeFileSync(file, mdc);
    return { skill: skill.name, path: file, status: 'installed' };
  }
  const dest = path.join(destRoot, skill.name);
  if (fs.existsSync(dest) && !force) return { skill: skill.name, path: dest, status: 'exists' };
  if (dryRun) return { skill: skill.name, path: dest, status: 'would-install' };
  fs.rmSync(dest, { recursive: true, force: true });
  fs.mkdirSync(destRoot, { recursive: true });
  fs.cpSync(skill.dir, dest, { recursive: true });
  return { skill: skill.name, path: dest, status: 'installed' };
}

function install(names, { agents = [], dir, project = false, force = false, dryRun = false }) {
  const all = list();
  const wanted = !names.length || names.includes('all') ? all : names.map((n) => {
    const s = all.find((x) => x.name === n);
    if (!s) throw new Error(`Unknown skill "${n}". Available: ${all.map((x) => x.name).join(', ')}`);
    return s;
  });
  const targets = dir
    ? [{ agent: 'dir', root: path.resolve(dir), format: 'skill-dir' }]
    : agents.map((a) => ({ agent: a, root: AGENTS[a].target(project), format: AGENTS[a].format }));
  const results = [];
  for (const t of targets) {
    for (const s of wanted) results.push({ agent: t.agent, ...installOne(s, t.root, t.format, { force, dryRun }) });
  }
  return results;
}

module.exports = { list, install, resolveAgents, AGENTS, parseFrontmatter, skillsRoot };
