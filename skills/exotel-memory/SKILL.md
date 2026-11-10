---
name: exotel-memory
description: Give a voice bot, chatbot or agent memory of every contact across calls and chats with Exotel Memory - turn memory on for an Exotel VoiceBot in one step, write the bot prompt so it greets returning callers with context, save conversations from any bot, push CRM facts (offers, payments, deliveries) the bot sees on its very next call, and look up what Memory knows about a contact. Use when the user wants a bot that remembers callers, personalised or proactive calls, context across channels, or asks why a bot asked the same questions again.
---

# Exotel Memory

Exotel Memory keeps one memory per contact (by phone number, email or your own ID): who they are, open
topics with their status, promises made, notes, and recent interactions. Read it when an interaction starts;
save the interaction when it ends. It works for any bot or agent, on any channel.

Uses the `exotel` CLI (`exotel login`, then `exotel memory login` once). Every command takes `--json`.
The Exotel MCP tools named below do the same through MCP.

## Rules

- Commands that read (`memory get`, `memory search`) run freely. Commands that save or change something
  (`memory save | note | event`, `voicebot memory on | off`) change the user's account: tell the user what you
  will do, then add `--yes`. Without it the CLI stops with exit code 3. `--dry-run` shows the exact request.
- Phone numbers in E.164 (`+919876543210`). `09876543210`, `9876543210` and `919876543210` are read as Indian
  numbers, so a caller's inbound From and your outbound To are the same contact.
- Memory holds personal data. Only look up or write contacts the user is working with; never paste memory
  into places the user did not ask for.
- **Timing:** notes and events show up at once. A saved interaction is learned in the background (usually seconds,
  at most about 10 minutes). If the bot must know something on the very next call, also add a note.

## 0. Set up (once)

```bash
exotel memory login --account <Memory account SID>          # asks for the Memory API key (cm_…), hidden
exotel whoami                                               # products should include "memory"
```

Non-interactive: `EXOTEL_MEMORY_API_KEY=… exotel memory login --account <sid>` or `--key-stdin`. A non-default
Memory host: `--url https://memory.<region>.exotel.com`. MCP: add `memory_api_key` and `memory_account_sid`
(optional `memory_base_url`) to the Authorization JSON; `exotel mcp install` passes them through the proxy.

## 1. Give an Exotel VoiceBot memory

```bash
exotel voicebot list
exotel voicebot memory on --bot-id <id> --yes              # MCP: exotel_voicebot_memory_enable
```

This sets the bot's `session_start` webhook (load memory when a call starts) and `session_end` webhook (save the
call when it ends) to Exotel Memory. Inbound and outbound calls both work; nothing else in the flow changes.
If the bot already sends one of those webhooks somewhere else, the command stops and changes nothing; only add
`--replace-existing` if the user agrees to replace it. `exotel voicebot memory off --bot-id <id> --yes` removes
only the Memory webhooks; saved memory is kept.

## 2. Make the prompt use memory

Turning memory on loads these variables into every call. Add a section like this to the bot's prompt
(`exotel_voicebot_assistant_update_prompt`, or `exotel voicebot create --prompt` for a new bot):

```text
WHAT YOU REMEMBER ABOUT THIS CALLER (may be empty):
- name: {{entities.person_name}}
- summary: {{entities.memory_summary}}
- current topic: {{entities.current_topic}}
- notes and open topics: {{entities.memory_context}}
If these are empty or still show curly braces, this is a new caller.
Returning caller: greet them by name, recap what you know in one sentence, never ask again for something you
already know, and continue from the open topic. If memory_context has a note (an offer, a delivery, a payment),
mention it when it is relevant.
```

## 3. Push facts from the user's systems (CRM, billing, delivery)

```bash
exotel memory note  --phone <number> --text "Offer: Taj Exotica Goa 20% off for 18-22 Nov" --yes   # MCP: exotel_memory_add_note
exotel memory event --phone <number> --type payment --event-id PAY-77 --amount 4500 --settled --yes  # MCP: exotel_memory_record_event
```

A note is a plain fact (max 1000 characters), not an instruction; bots see it on their next call
(`--agents-only` hides it from bots). Events (`payment`, `ticket_status`, `delivery`, `other` with `--data '{json}'`)
are trusted over what the contact said and settle promises. Re-sending the same `--event-id` is ignored.

**Proactive call pattern** ("tell everyone this offer fits"): find the matching contacts in the user's own
system (Memory searches only by exact phone, email or ID, never by interest), add a note or event for each,
then call them through the bot's flow (`exotel_voice_connect_to_call_flow` with the flow's app ID, or
`exotel voice-ai connect --to <number> --app-id <app_id>`). Calls are billed and ring real phones: get the
user's yes first, listing who will be called.

## 4. Save conversations from any other bot or channel

```bash
exotel memory save --phone <number> --id <call or chat id> --messages @turns.json --yes            # MCP: exotel_memory_save_interaction
exotel memory save --phone <number> --id <id> --transcript @call.txt --channel WHATSAPP --yes
exotel memory save --phone <number> --id <id> --audio-url https://…/call.mp3 --yes
```

`turns.json` is `[{"role":"customer","text":"…"},{"role":"bot","text":"…"}]` (`user` and `contact` also mean the
customer). The same `--id` updates the interaction instead of duplicating it. To backfill a VoiceBot call made
before memory was on: `exotel voicebot transcript --call-sid <CallSid> --json`, then save the turns.

## 5. Look up a contact

```bash
exotel memory get --phone <number>                 # MCP: exotel_memory_get
exotel memory get --phone <number> --audience customer   # only what a bot may say to the contact
exotel memory search --q <phone|email|id>          # MCP: exotel_memory_search_contacts
```

A contact Memory has never seen returns 404 (MCP: `found: false`): start without memory.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Bot still asks everything again | Memory is off for the bot (`exotel voicebot memory on`), or the prompt does not use `{{entities.*}}` (step 2). |
| `exotel memory get` is 404 right after a call | The call is still being learned; wait a little. For must-know facts use a note. Check the number format matches. |
| Bot greets with the wrong person | A shared phone (family number): Memory allows up to 5 contacts per phone; pass a unique ID (`--id` / idType) when you have one. |
| 401 from Memory | Wrong key or account SID: run `exotel memory login` again. 403: the key lacks the scope (read, write, ingest). |
| `voicebot memory on` stops with "already sends session_start" | The bot has its own webhook. Ask the user before `--replace-existing`. |
