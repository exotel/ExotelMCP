'use strict';
// Shell tab-completion for zsh, bash and fish. The scripts are generated from the CLI's own command,
// subcommand and flag lists, so they always match the installed version.
const { CliError, EXIT } = require('./io');

const COMMANDS = {
  init: 'log in and connect your coding agent', login: 'save your API credentials', logout: 'forget saved credentials',
  whoami: 'who you are logged in as', doctor: 'check your setup', config: 'saved defaults (your number, caller ID, bot)',
  call: 'place a test call, step by step', balance: 'account balance', numbers: 'search, buy, route, release numbers',
  calls: 'call logs and recordings', campaigns: 'outbound call campaigns', voicebot: 'Exotel-hosted AI voice agents',
  memory: 'remember contacts across calls and chats',
  'voice-ai': 'your own bot over WebSocket', integrate: 'ElevenLabs and Bolna', leg: 'call control with ExoML (Legs API)',
  bridge: 'join legs into one conversation', events: 'receive call events locally', exoml: 'validate ExoML',
  applet: 'test or scaffold flow webhooks', trunk: 'SIP trunking', api: 'call any Exotel REST endpoint',
  mcp: 'Exotel MCP server and tools', skill: 'install agent skills', ask: 'which API to use',
  explain: 'what a status or error code means', docs: 'search the Exotel docs', completion: 'shell tab-completion', help: 'help',
};

function data() {
  const v = require('./validate');
  const subs = { ...v.SUBCOMMANDS };
  delete subs.campaign;
  delete subs.skills;
  try {
    subs.leg = ['create', 'get', 'events', 'inbound', 'action', ...Object.keys(require('./commands/legs').LEG_VERBS)];
  } catch { /* keep static list */ }
  subs.completion = ['zsh', 'bash', 'fish'];
  subs.help = Object.keys(COMMANDS).filter((c) => c !== 'help');
  const flags = [...v.knownFlags()].filter((f) => f.length > 1 && !/^[A-Z]/.test(f)).sort();
  return { commands: Object.keys(COMMANDS), subs, flags };
}

function zsh() {
  const { subs, flags } = data();
  const cmdSpec = Object.entries(COMMANDS).map(([k, d]) => `'${k}:${d.replace(/'/g, '')}'`).join(' ');
  const cases = Object.entries(subs).map(([k, list]) => `      ${k}) _values 'subcommand' ${list.map((s) => `'${s}'`).join(' ')} ;;`).join('\n');
  return `#compdef exotel
# exotel tab-completion for zsh. Install: exotel completion zsh > "\${fpath[1]}/_exotel" && compinit
_exotel() {
  local -a cmds flags
  cmds=(${cmdSpec})
  flags=(${flags.map((f) => `'--${f}'`).join(' ')})
  if [[ "\${words[CURRENT]}" == -* ]]; then
    compadd -a flags
    return
  fi
  if (( CURRENT == 2 )); then
    _describe 'command' cmds
    return
  fi
  if (( CURRENT == 3 )); then
    case "\${words[2]}" in
${cases}
    esac
  fi
}
compdef _exotel exotel
`;
}

function bash() {
  const { commands, subs, flags } = data();
  const cases = Object.entries(subs).map(([k, list]) => `      ${k}) COMPREPLY=( $(compgen -W "${list.join(' ')}" -- "$cur") ); return ;;`).join('\n');
  return `# exotel tab-completion for bash. Install: exotel completion bash > ~/.exotel-completion.bash && echo 'source ~/.exotel-completion.bash' >> ~/.bashrc
_exotel() {
  local cur="\${COMP_WORDS[COMP_CWORD]}"
  if [[ "$cur" == -* ]]; then
    COMPREPLY=( $(compgen -W "${flags.map((f) => `--${f}`).join(' ')}" -- "$cur") ); return
  fi
  if [[ $COMP_CWORD -eq 1 ]]; then
    COMPREPLY=( $(compgen -W "${commands.join(' ')}" -- "$cur") ); return
  fi
  if [[ $COMP_CWORD -eq 2 ]]; then
    case "\${COMP_WORDS[1]}" in
${cases}
    esac
  fi
}
complete -F _exotel exotel
`;
}

function fish() {
  const { subs, flags } = data();
  const lines = ['# exotel tab-completion for fish. Install: exotel completion fish > ~/.config/fish/completions/exotel.fish', 'complete -c exotel -f'];
  for (const [k, d] of Object.entries(COMMANDS)) lines.push(`complete -c exotel -n '__fish_use_subcommand' -a '${k}' -d '${d.replace(/'/g, '')}'`);
  for (const [k, list] of Object.entries(subs)) lines.push(`complete -c exotel -n '__fish_seen_subcommand_from ${k}' -a '${list.join(' ')}'`);
  for (const f of flags) lines.push(`complete -c exotel -l '${f}'`);
  return `${lines.join('\n')}\n`;
}

const COMPLETION_USAGE = `Tab-completion for commands, subcommands and options:
  zsh:   exotel completion zsh > "\${fpath[1]}/_exotel"   then open a new terminal
         (or add to ~/.zshrc:  eval "$(exotel completion zsh)")
  bash:  exotel completion bash > ~/.exotel-completion.bash && echo 'source ~/.exotel-completion.bash' >> ~/.bashrc
  fish:  exotel completion fish > ~/.config/fish/completions/exotel.fish
Homebrew installs it for you.`;

function cmd(shell) {
  const gen = { zsh, bash, fish }[shell];
  if (!gen) throw new CliError(COMPLETION_USAGE, EXIT.USAGE);
  process.stdout.write(gen());
}

module.exports = { cmd, zsh, bash, fish, COMPLETION_USAGE, COMMANDS };
