---
topic: playbooks/appointment-booking
title: Appointment booking and rescheduling
description: Use when the bot books, reschedules or cancels appointments through several tool calls (find slots, book, change), must cope with callers changing their answers, and must confirm before committing.
tools: exotel_voicebot_te_route_create, exotel_voicebot_te_route_attach_as_tool, exotel_voicebot_catalog_llm_models, exotel_tools_server_tool_create, exotel_tools_server_tool_version_test, exotel_tools_server_tool_update, exotel_voicebot_update_config
---
> Draft: recommended settings pending review by the voicebot team.

## When to use

Clinics, service centres, test drives, site visits: the caller picks a date and time from real availability and
the bot writes the booking to your system.

## Recommended configuration

| Area | Setting | Why |
|---|---|---|
| Tools | `find_slots(date, service)`, `book_slot(slot_id, name, phone)`, `change_booking(booking_id, action, slot_id)`, each an `openapi` tool | One clear job per tool; the model chains them in order |
| Multi-step logic | A Tools Extension route (`exotel_voicebot_te_route_create`, then `exotel_voicebot_te_route_attach_as_tool`) when one booking needs several backend calls | Keeps the bot's tool list short |
| Today's date | Return `today` (date and weekday) from the `session_start` webhook as an entity | The model cannot resolve "next Tuesday" without it |
| LLM | A model with strong tool calling from `exotel_voicebot_catalog_llm_models`, plus `fallback_llm_config` | Several tool calls per call |
| Fillers | Static, `trigger_delay_ms` 600, "Let me check the calendar." | Covers slot lookups |
| Interruption | `enable_interruption: true` | Callers change their mind mid-sentence |
| Capture | Dates and phone numbers per playbooks/number-id-capture | Read-back before booking |
| Post-call | Intents `booked`, `rescheduled`, `cancelled`, `no_slot`, `needs_human` | Booking funnel reporting |

## Starter template

Create each tool with `exotel_tools_server_tool_create`, test it with real inputs using
`exotel_tools_server_tool_version_test`, activate it with `exotel_tools_server_tool_update`. Then
`exotel_voicebot_update_config` data (send every tool the agent keeps):

```json
{"assistant_config": {"agents": [{"id": "AGENT_ID", "name": "main", "description": "Booking agent",
  "instruction": "INSTRUCTION", "llm_config": {},
  "mcp_tools": [
    {"tool_name": "tool_server.find_slots", "name": "find_slots",
     "description": "List free slots for a service on a date. Call before offering any time."},
    {"tool_name": "tool_server.book_slot", "name": "book_slot",
     "description": "Book a slot. Call only after the caller says yes to the full summary."},
    {"tool_name": "tool_server.change_booking", "name": "change_booking",
     "description": "Reschedule or cancel an existing booking by its id."}]}]}}
```

Instruction outline:
1. Ask for the service, then the preferred day. Today is `{today}`; resolve relative dates against it.
2. Call `find_slots`; offer at most three times, nearest first.
3. If the caller changes the day, time or service at any point, drop the earlier choice and call `find_slots` again.
   Never book a slot from an earlier search.
4. Before `book_slot`, read one summary: service, weekday, date, time, name. Book only on a clear yes.
5. After booking, give the time once more and say goodbye.
6. If a tool fails or no slot fits, offer another day once, then an agent.

## Known problems

| Symptom | Fix |
|---|---|
| Booked the old time after the caller changed it | Add rule 3 and make `book_slot` take the `slot_id` from the latest `find_slots` result |
| Booked without confirmation | Put "only after the caller says yes" in the tool description and the instruction |
| Wrong date for "tomorrow" | Pass `{today}` from `session_start`; read back the weekday with the date |
| Agent lost a tool after an update | An agent you change keeps only the `mcp_tools` you send; resend all three |
| Long silence between tool calls | Each tool must answer well under 10 s; slow tools delay the next turn |
| Tool works in a test but not on calls | Activate the tested version; reference it as `tool_server.NAME` with `name` set |

## Test checklist

- Book, then change the time before confirming: only the new time is booked.
- Ask for "next Friday evening": the right date with its weekday.
- Ask for a full day: the bot offers another day.
- Reschedule and cancel an existing booking.
- Check every tool call and its arguments in the test transcript.
