---
name: exotel-exoml-flows
description: Recipes for complete call flows built with the Exotel Legs API and ExoML (no App Bazaar flow) - IVR menus, outbound and inbound voice bots with a greeting, warm transfer, conference bridges, voicemail, answering-machine detection campaigns, supervisor listen/whisper, record and post-process - each as the event sequence plus the exact ExoML and CLI commands. Use when the user wants to design or debug a programmable call flow, asks "how do I do X with ExoML / Legs", or ports an App Bazaar flow to code.
---

# ExoML call-flow recipes (Legs API)

Prerequisites, event endpoint and the full action list are in `exotel-call-control`. Two rules decide whether a
flow works:

1. **Your event endpoint drives the flow.** Reply 2xx within ~1 s, then send the next ExoML to
   `POST /v2/accounts/{sid}/legs/{leg_sid}/actions` (or `/bridges/{bridge_sid}/actions`).
2. **Act only on the right event.** Actions sent before `leg_answered` are lost. Wait for each action's
   completion event (`say_completed`, `gather_success`, `stream_started`, `dial_success`…) before the next step.

Start every recipe from the scaffold: `exotel events scaffold --lang node` (handles `leg_incoming` → Answer and
de-duplicates retries), then add the `switch (event.event_name)` cases below. Tag actions with
`action_custom_param` so you know which step an event belongs to.

## 1. IVR menu

```
leg_answered  → <Flow><Gather numDigits="1" timeoutInSec="6" finishOnKey="#"><Say>Press 1 for sales, 2 for support</Say></Gather></Flow>
gather_success (event_data.data.digits) →
   "1" → <Flow><Dial contactUri="<sales>" exophone="<LP>"/></Flow>
   "2" → <Flow><StartStream streamType="bidirectional" streamUrl="wss://bot/support"/></Flow>
gather_failed / no digits → repeat once, then <Flow><Say>Goodbye</Say></Flow> → say_completed → <Flow><Hangup/></Flow>
```

## 2. Outbound voice bot with a greeting (no dead air)

```
create leg (custom_param=<your id>) → leg_answered
  → <Flow><Say>Connecting you to our assistant</Say></Flow>
  → <Flow><StartStream streamType="bidirectional" streamUrl="wss://bot/agent?id=…"/></Flow>
stream_started → if the greeting is still playing: <Flow><StopSay/></Flow>   (StopSay after say_completed is rejected)
```

CLI (does exactly this): `exotel voice-ai connect --via legs --exophone <LP> --to <num> --stream-url wss://… --events https://… --listen 8787 --greeting "…"`

## 3. Inbound voice bot on a DID

```
(once) Exotel support attaches the DID to your event endpoint — exotel leg inbound --number … --events … prints the steps
leg_incoming → <Flow><Answer/></Flow> → leg_answered → <Flow><StartStream …/></Flow>
```

CLI: `exotel voice-ai inbound --number <LP DID> --stream-url wss://… --events https://… --listen 8787`

## 4. Bot hands over to a human

```
stream running; bot decides to transfer (it tells your server, e.g. over your own API)
→ <Flow><StopStream/></Flow> → stream_stopped
→ <Flow><Say>Connecting you to an agent</Say></Flow>
→ <Flow><Dial contactUri="<agent>" exophone="<LP>"/></Flow> → dial_success (bridged) … dial_completed → Hangup
```

## 5. Warm transfer (agent 1 → agent 2)

```
customer leg C and agent leg A1 on a call
→ on C:  <Flow><Hold><StartPlay>https://…/hold.wav</StartPlay></Hold></Flow>   → hold_success
→ on A1: <Flow><Dial contactUri="<agent2>" exophone="<LP>"/></Flow>        → dial_initiated → dial_success
         (agent 2 joins the call; A1 briefs A2 while C hears hold music)
→ on A1: <Flow><Hangup/></Flow>                                             → A1 leg_terminated
→ on C:  <Flow><UnHold/></Flow>                                             → unhold_success; C and A2 continue
```

This is the warm-transfer sequence from the Legs API reference.

## 6. Conference / multi-party

```
create legs L1, L2 (reference_leg_sid=L1 on L2 to co-locate) → both leg_answered
→ exotel bridge create --legs L1,L2 --events https://… → bridge_created, leg_joined_bridge ×2
→ add L3: create leg (reference_leg_sid=L1) → leg_answered → <Flow><JoinBridge bridgeSid="<bridge>"/></Flow>
→ announcement to all: exotel bridge say --bridge-sid <b> --text "Recording starts now" ; bridge record
→ end: exotel bridge stop --bridge-sid <b>
```

## 7. Voicemail

```
leg_answered → <Flow><StartRecording silenceInSec="5" finishOnKey="#" timeoutInSec="60"><Say>Please leave a message after the tone</Say></StartRecording></Flow>
recording_stopped (stop_reason: silence_timeout | finish_on_key | max_duration_timeout) → recording_available (url) → <Flow><Hangup/></Flow>
```

## 8. Outbound campaign with answering-machine detection

```
create leg with amd_enable=true (async_amd=true for a separate event)
leg_answered (answered_by HUMAN|MACHINE|BEEP) or amd_result_success
  HUMAN   → <Flow><StartStream …/></Flow> or <Say>…
  MACHINE / BEEP → <Flow><Say>Message for the voicemail</Say></Flow> → say_completed → <Flow><Hangup/></Flow>
```

CLI: `exotel leg create … --amd` / `--async-amd`.

## 9. Supervisor listen / whisper

```
agent leg A live → on A: <Flow><StartMonitoring contactUri="<supervisor>" exophone="<LP>" rxAudio="muted"/></Flow>   (listen)
rxAudio="enabled" lets the supervisor talk to the agent (whisper) → monitoring_started … <StopMonitoring/>
```

## 10. Record, then process

```
leg_answered → <Flow><StartRecording direction="both" channel="stereo" format="wav" storageType="s3"/></Flow>
… <Flow><StopRecording/></Flow> → recording_available (url) → send the URL to transcription / QA
   (Exotel CQA: exotel mcp call exotel_cqa_ingest_interaction audioUrl=<url> …)
```

For your own storage use `storageType="https"` with `storageURL`, `storageURLKey`, `storageURLToken` (basic auth).

## Check before going live

- `exotel exoml validate step.xml` for every ExoML you send (the CLI one-liners validate automatically).
- `exotel leg events --leg-sid <sid>` to see the actual event sequence of a test call.
- `exotel explain <code>` for any `*_failed` event's `error_code`.
