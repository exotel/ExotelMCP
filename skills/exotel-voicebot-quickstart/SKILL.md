---
name: exotel-voicebot-quickstart
description: Build, call and improve an Exotel-hosted VoiceBot (AI voice agent on phone calls) end to end - create the bot from a description, call the user's phone, read the transcript, fix the prompt, call again, and route an ExoPhone to it for inbound calls. Use when the user wants a voice bot, AI calling agent, AI receptionist, outbound AI calls, or asks why an Exotel VoiceBot call failed or behaved badly.
---

# Exotel VoiceBot quickstart

Uses the `exotel` CLI (log in once with `exotel login`; `exotel doctor` checks the setup). Every command
takes `--json` for machine-readable output. The Exotel MCP tools named below are optional alternatives.

## Rules

- A call rings a real phone and is billed. **Before placing a call, tell the user the bot, the
  number to call and the caller ID, and wait for their yes.** Only call numbers the user gave you.
  The CLI refuses billed actions without `--yes` (exit code 3); add `--yes` only after the user agreed.
- Everything else (creating the bot, reading transcripts, debugging) needs no approval.
- Numbers in E.164, for example `+919876543210`.

## 1. Write the brief

From the user's request, draft a short brief: who the bot represents, the one job of the call, a short
opening line (no questions in it: "Hi, this is Exotel Bank support."), language(s), what to collect, when
to end, what never to say. Always add these rules:
- "Never use titles or guess gender (no sir, madam, Mr, Ms)."
- "One question per turn. If an answer is unclear, ask again once in one short sentence; after two failed tries, move on."
- "Read numbers back exactly as the caller said them, in groups (74111 79773); never add a 0 or a country code."
- "Never say something was done (blocked, sent, booked) unless a tool confirmed it."
Only ask the user if something essential is missing; otherwise state your assumptions and continue.

## 2. Create the bot

```bash
exotel voicebot create --prompt "<the brief>" --wait --json
```

Returns `botId` when generation completes (usually under 2 minutes). If `botId` is missing, run
`exotel voicebot list --json` and take the newest bot with the expected name.
(MCP: `exotel_voicebot_create`, then poll `exotel_voicebot_creation_status`.)

## 3. Call (needs the user's approval)

Ask which number to call (any phone) and which ExoPhone to call from; the caller ID must be one of the
account's ExoPhones (`exotel numbers list`), the saved default is used if they have no preference. The CLI
checks `--caller-id` before dialling. For a call with a purpose (an offer, a delivery, a follow-up) add
`--reason "<one sentence>"`: with memory on, the bot opens with it instead of asking how it can help.

1. Caller ID: `exotel whoami` shows the saved caller ID; otherwise pick one from `exotel numbers list`.
2. Ask: "I'll call <number> from <caller ID> with bot <name>. OK?" Wait for yes.
3. Tell the user to pick up and talk to the bot, then:

```bash
exotel voicebot call --bot-id <botId> --to <user number> --record --wait --yes --json
```

`--wait` returns when the call ends, with the `callSid` and final status.
(MCP: `exotel_voicebot_place_call` with `toNumber`, `voiceBotId`, `callerId`, `customField: ""`,
`record: true`, `statusCallback: ""`.)

## 4. Read the result (one step)

```bash
exotel voicebot result --call-sid <callSid> --json      # MCP: exotel_voicebot_call_result
```

Duration, who hung up, the transcript as bot/caller lines and, if Memory is on, what it now knows. If it
says no session yet, wait about 10 seconds and retry once. Show the user the conversation and point out
where the bot went off-brief, repeated itself, or missed information.

## 5. If the call failed or went wrong

```bash
exotel voicebot debug --call-sid <callSid>
```

It combines the call record, the bot session and the transcript and lists likely causes (not answered,
answered but the onward connection failed, no speech, very short session, wrong caller ID…).

## 6. Improve and repeat

- Prompt / behaviour: write the full new prompt to a file, then
  `exotel voicebot prompt --bot-id <botId> --file prompt.txt --yes` (MCP: `exotel_voicebot_assistant_update_prompt`
  with `assistantId` = the bot ID). It updates every place the prompt lives. Do not use angle brackets in
  prompts (the VoiceBot API removes them). Change one thing per iteration.
- Opening line: it plays before the AI replies, so keep it short and never ask for the caller's name there
  ("Hi, this is City Dental."). With memory on, the AI greets returning callers by name in its first reply.
- Voice, ASR, VAD, greeting interruption: MCP `exotel_voicebot_update_config` with only the fields to change.
- Call again (step 3, ask again) and compare transcripts.

## Remember callers across calls

`exotel voicebot memory on --bot-id <botId> --yes` (MCP: `exotel_voicebot_memory_enable`) gives the bot
Exotel Memory in one step. See the `exotel-memory` skill.

## 7. Inbound: route a number to the bot (go-live)

```bash
exotel voicebot attach --number <ExoPhone> --bot-id <botId>
```

Without `--flow` it prints the one manual step: create an App Bazaar flow with a **VoiceBot applet**
holding the bot's "Voicebot URL" (copied from the VoiceBot console → Build Bot → Configuration). Relay
those steps to the user, ask for the flow's app id, then:

```bash
exotel voicebot attach --number <ExoPhone> --app-id <app_id>     # asks for confirmation: changes live routing
```

New numbers cannot be bought from the CLI yet; the user gets them from Exotel support.

## Report back

Bot ID and name, the brief used, call SID(s) with status, the transcript, issues found, and what
to do next (iterate, or attach a number for inbound).
