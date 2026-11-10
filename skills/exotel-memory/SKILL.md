---
name: exotel-memory
description: Give a voice bot, chatbot or agent memory of every contact across calls and chats with Exotel Memory - turn memory on for an Exotel VoiceBot in one step, push CRM facts (offers, payments, deliveries) the bot knows on its very next call, save conversations from any bot, and look up what Memory knows about a contact. Use when the user wants a bot that remembers callers, personalised or proactive calls, context across channels, or asks why a bot asked the same questions again.
---

# Exotel Memory

One memory per contact (phone, email or your own ID): name, summary, open topics and promises, notes,
recent interactions. Works for any bot or agent on any channel.

## Fast path (one command each)

| The user wants… | CLI | MCP tool |
|---|---|---|
| A VoiceBot that remembers callers | `exotel voicebot memory on --bot-id <id> --yes` | `exotel_voicebot_memory_enable` |
| What happened on a call + what Memory learned | `exotel voicebot result --call-sid <sid>` | `exotel_voicebot_call_result` |
| What Memory knows about someone | `exotel memory get --phone <number>` | `exotel_memory_get` |
| The bot to know a CRM fact on its next call | `exotel memory note --phone <number> --text "…" --yes` | `exotel_memory_add_note` |
| The bot to call someone for a reason it opens with | `exotel voicebot call --bot-id <id> --to <number> --reason "…" [--caller-id <ExoPhone>]` | `exotel_voicebot_call_customer` |
| A trusted business event (payment, delivery…) | `exotel memory event --phone <n> --type delivery --event-id <id> --yes` | `exotel_memory_record_event` |
| Save a conversation from any other bot | `exotel memory save --phone <n> --id <id> --messages @turns.json --yes` | `exotel_memory_save_interaction` |
| Turn memory off | `exotel voicebot memory off --bot-id <id> --yes` | `exotel_voicebot_memory_disable` |

`memory on` does the whole job in one call: Memory webhooks on the bot, a memory section in every place the
prompt lives (greet returning callers by name, never re-ask, new notes first, no titles), and a shorter
opening line if the fixed greeting asks for the caller's name (it plays before the AI, so returning callers
would be asked again). Pass `--greeting "Hi, this is …"` to choose the line, or `--keep-greeting`. Running it
again changes nothing. Use the bot ID; a generation ID from `voicebot create` also works.

## Rules

- Reads run freely. Anything that saves or changes something asks first: tell the user what you will do, then
  add `--yes` (without it the CLI exits with code 3). `--dry-run` shows the exact request.
- Calls ring real phones and are billed: get the user's yes first and say who will be called.
- Phones: `+919876543210`; `09876543210`, `9876543210`, `919876543210` mean the same Indian number, so an
  inbound From and an outbound To match.
- Memory holds personal data: only look up or write contacts the user is working with.
- Notes and events show up at once. A saved conversation is learned in the background (seconds, at most about
  10 minutes). If the bot must know something on the very next call, add a note.

## Setup (once)

```bash
exotel memory login --account <Memory account SID>     # asks for the Memory API key (cm_…), hidden
exotel whoami                                          # products include "memory"
```

Non-interactive: `EXOTEL_MEMORY_API_KEY=… exotel memory login --account <sid>`; another host:
`--url https://memory.<region>.exotel.com`. Agents connected with `exotel mcp install` get the Memory tools
automatically; `exotel mcp install --toolsets voicebot,memory` gives them only these tools (faster).

## Proactive call ("tell the customers this offer fits")

Find the matching contacts in the user's own system (Memory searches only by exact phone, email or ID, never by
interest). For each, with the user's yes (billed): `exotel voicebot call --bot-id <id> --to <number> --reason
"<one plain sentence>"` (MCP: `exotel_voicebot_call_customer`). It saves the reason as an `Outbound call:` note first;
the bot cannot otherwise tell its own calls from incoming ones, so it opens with the reason and asks if now is a
good time. `exotel voicebot result --call-sid <sid>` (MCP: `exotel_voicebot_call_result`) afterwards marks the
note done, so later incoming calls are treated as incoming. The CLI does that itself when it waits for the call.

## Backfill a call made before memory was on

`exotel voicebot result --call-sid <sid> --json` gives the turns; save them with `exotel memory save --phone
<caller> --id <sid> --messages @turns.json --yes` (roles: `bot`, `customer`; `user` also means the customer).

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Bot asks the name, then says "welcome back" | The fixed opening line asks for the name. `exotel voicebot memory on --bot-id <id> --greeting "Hi, this is …"`. |
| Bot still asks everything again | Memory is off (`voicebot memory on`), or the call's details were not learned: check `voicebot result`. |
| `memory get` shows no name after a clear call | Memory may have judged the call off-topic for the account's business (disposition wrong_number). Tell the user; the Memory account must be set up for this business. |
| 401 / 403 from Memory | Run `exotel memory login` again; 403 = the key lacks the scope. |
| "already sends session_start" | The bot has its own webhook. Ask the user before `--replace-existing`. |
