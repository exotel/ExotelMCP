---
name: exotel-messaging-and-calls
description: Send SMS (single, bulk, personalised, DLT-compliant), WhatsApp messages, and place or bridge phone calls through Exotel, then confirm delivery. Use when the user wants to text, message, call, click-to-call, or connect two phone numbers with Exotel.
---

# Exotel SMS, WhatsApp and calls

Needs the Exotel MCP tools (`exotel_*`). If they are missing, follow the `exotel-setup` skill.

## Rules

- Check `exotel balance` before bulk sends.
- Every send or call is billed and reaches a real person. Show the exact recipients, sender and text,
  and get a yes before calling a send/call tool. For bulk sends, show the count and two sample rows.
- Never message numbers the user did not supply. No marketing to numbers without consent.
- Numbers in E.164 (`+919876543210`).

## SMS

| Need | Tool |
|------|------|
| One recipient | `exotel_sms_send_single` (`toNumber`, `message`, `dltTemplateId`, `dltEntityId`) |
| Same text to many | `exotel_sms_send_bulk_same_message` |
| Different text per recipient | `exotel_sms_send_bulk_personalized` |
| Campaign via Engage | `exotel_engage_create_sms_campaign` |
| Delivery status | `exotel_sms_get_delivery_status` with the recipient number |

India: every SMS needs a DLT template ID and entity ID, and the message must match the registered
template character for character (variables only where the template has `{#var#}`). If the user does
not have them, stop and tell them; do not guess IDs. Outside India pass `""` for both.

## WhatsApp

`exotel_whatsapp_send_message`:
- First message to a user, or after 24 h of silence: `messageType: "template"`, `templateName`, and
  `templateParams` in body order. The template must be approved in the WhatsApp manager.
- Reply within 24 h of the user's last message: `messageType: "text"` with the body.
- The sender is `whatsapp_from` from the credentials (E.164 number, not the WABA id).

## Calls

| Need | Tool |
|------|------|
| Call one number from the account's default number | `exotel_voice_call_initiate` (`toNumber`) |
| Bridge two numbers (click-to-call: rings `fromNumber` first, then connects `toNumber`) | `exotel_voice_connect_two_numbers` |
| Send a caller into an IVR / call flow (applet) | `exotel_voice_connect_to_call_flow` |
| AI agent handles the call | `exotel-voicebot-quickstart` (Exotel-hosted) or `exotel-connect-voice-ai` (own bot) |
| Real-time control of a live call | `exotel-call-control` |
| Route calls by your own logic (webhook) | `exotel-flow-webhooks` |

After any call, keep the Call SID and use `exotel api GET /v1/Accounts/{sid}/Calls/<CallSid>.json` to report status,
duration and recording URL. For a range of calls use `exotel_voice_get_bulk_call_details`.
For problems, use the `exotel-call-debugging` skill.
