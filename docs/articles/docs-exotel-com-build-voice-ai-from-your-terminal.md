---
title: Build and test voice AI on Exotel from your terminal
slug: voice-apis/build-and-test-voice-ai-from-your-terminal
excerpt: Use the Exotel CLI (or your coding agent) to put an AI voice bot on a real phone call, test it on your own number and read the transcript, in minutes.
---

## Overview

The **Exotel CLI** (`exotel`) lets developers and their coding agents (Claude Code, Cursor, Codex) set up and
test Exotel voice from a terminal: create a VoiceBot, connect your own AI bot, call your phone, read the
transcript and find out why a call failed — without building API requests by hand.

You can use it directly, or ask your coding agent in plain words; the agent runs the CLI for you and asks
before anything that costs money.

## Before you begin

- An Exotel account with KYC completed and at least one ExoPhone.
- Your **Account SID**, **API key** and **API token** from **my.exotel.com → Settings → API Settings**.
- A computer with **Node.js 20 or later**.
- For your own bot: a public `wss://` address for it (while testing, a tunnel such as ngrok).

Find and buy numbers with `exotel numbers available` and `exotel numbers buy` (Indian numbers need approved KYC). Real-time call control (Legs API) needs an LP-enabled API key and
an LP number from Exotel support.

## Step 1: Connect

```bash
npx exotel-cli init
```

Enter your Account SID, API key and API token when asked. The CLI checks them, connects any coding agents it
finds to Exotel, and installs Exotel's agent skills. Check everything with:

```bash
exotel doctor
```

## Step 2: Choose how your bot joins the call

| You have | Use | What you do |
|----------|-----|-------------|
| No bot yet | **Exotel VoiceBot** | Describe the bot; Exotel hosts it |
| Your own bot (OpenAI Realtime, Gemini, Pipecat…) | **Connect Voice AI** | Give its `wss://` address |
| An ElevenLabs or Bolna agent | **App Bazaar flow with a Voicebot applet** | Create the flow once; the CLI prints the steps |
| You want full control, no flow | **Legs API + ExoML** | Run your own event endpoint |

Not sure? Ask:

```bash
exotel ask "connect my OpenAI bot to a phone call"
```

## Step 3: Make a test call

**Exotel VoiceBot**

```bash
exotel voicebot create --prompt "Friendly receptionist for Acme. Greet, ask name and reason, end politely." --wait
exotel voicebot test-call --bot-id <bot id> --to +91XXXXXXXXXX --wait
exotel voicebot transcript --call-sid <CallSid>
```

**Your own bot**

```bash
exotel voice-ai connect --to +91XXXXXXXXXX --stream-url "wss://your-bot.example.com/agent?sample-rate=8000" --wait
```

**Through your App Bazaar flow** (a flow with a Voicebot applet that points at the bot)

```bash
exotel voice-ai setup --platform elevenlabs        # or bolna, or --stream-url wss://… for your own bot
exotel voice-ai connect --to +91XXXXXXXXXX --app-id <your flow's app id> --wait
```

Your phone rings. Answer and talk to the bot. Every call asks you to confirm first.

## Step 4: Take calls on your number

Route your ExoPhone to the flow that holds your bot:

```bash
exotel voicebot attach --number <ExoPhone> --app-id <app id>
```

## Step 5: If something goes wrong

```bash
exotel voicebot debug --call-sid <CallSid>    # call record, bot session, transcript and the likely cause
exotel explain no-answer                      # what any status or error code means, with the source page
exotel explain 408
```

## Using a coding agent

After `exotel init`, open your agent and ask, for example:

> Set up a VoiceBot that greets callers and test it on my number +91XXXXXXXXXX

The agent creates the bot, asks you to approve the call, calls you, and shows the transcript with suggestions.

## Good to know

- Calls are billed. The CLI never places a call, buys anything or changes call routing without your
  confirmation (`--yes` in scripts).
- `--dry-run` shows exactly what would be sent, without credentials.
- Your credentials are stored only on your computer, readable only by you.

## Related articles

- [Connect Voice AI API](https://developer.exotel.com/docs/agentstream/connect-voice-ai)
- [Working with the Stream and Voicebot Applet](https://developer.exotel.com/docs/agentstream/stream-voicebot-applet)
- [Connect Your Number to Your Voicebot](https://docs.exotel.com/intelligent-voice-agents/connect-your-number-to-your-voicebot)
- [Implementing your HTTPS event endpoint for real-time call events](https://docs.exotel.com/voice-apis/implementing-your-https-event-endpoint-for-real-time-call-events)
