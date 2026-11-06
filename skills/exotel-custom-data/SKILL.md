---
name: exotel-custom-data
description: Pass your own data (order id, customer id, language, campaign, session context) through an Exotel call - Connect API CustomField, Voicebot applet custom parameters, AgentStream/StartStream query parameters, Legs custom_param and action_custom_param, Connect applet custom_params sent as custom SIP headers (X-…) to a SIP trunk, and what comes back in callbacks and events. Use when the user asks about custom params, custom headers, SIP X- headers, passing context to a bot or agent, or correlating calls with their own records.
---

# Custom data through an Exotel call

Pick the place by **where the data must arrive**. Limits are from the Exotel docs; where the docs disagree it
says so.

| You set it in | Field | It arrives at | Limits / notes |
|---------------|-------|---------------|----------------|
| Connect API (`POST /v1/Accounts/{sid}/Calls/connect.json`), incl. Connect Voice AI | `CustomField` | Applet Dynamic URLs and Passthru (`CustomField` query param), StatusCallback (`CustomField`), the call record | One opaque string; use your own encoding (e.g. JSON or `k=v;k=v`) |
| Voicebot applet URL (App Bazaar) | query string on the wss URL: `wss://bot/path?lang=hi&tier=gold` | Your bot, in the `start` message as `start.custom_parameters` | **Up to 3 parameters, 256 characters total** |
| Connect Voice AI `StreamUrl` | query string on the URL | Your bot (in the URL it is opened with; `start.custom_parameters` where provided) | `StreamUrl` under 600 characters |
| Legs API `<StartStream streamUrl="…">` | query string on `streamUrl` | Your bot, in the WebSocket messages | **Up to 25 parameters, 800 characters total**; write `&` as `&amp;` inside ExoML |
| Legs API create leg | `custom_param` | Every event for that leg (`data.custom_param`) | Correlate legs with your records |
| Legs API action | `action_custom_param` | Events caused by that action (`action_custom_parameter`) | Tag each action (e.g. `greeting`, `ivr-step-2`) |
| Connect applet Dynamic URL reply, trunk destination | `custom_params: "k1=v1&k2=v2"` | Your SIP system, as **custom SIP headers** on the INVITE | Newer SIP docs: **up to 10 headers, 800 bytes each, 4 KB total**. Older Flow-API page: 3 headers, 200 bytes. Stay within the smaller limit until confirmed. Prefixes **`Exotel-` and `Veeno-` are reserved** |
| Bot → later applets | (bot sends parameters on the stream) | — | Not available yet; do not promise it |

## Patterns

**CRM id end to end (outbound, own bot):**

```bash
exotel voice-ai connect --to +91XXXXXXXXXX --stream-url "wss://bot.example.com/agent?crm_id=C-88" --custom-field "crm_id=C-88"
```

The bot reads `crm_id` from its URL; your StatusCallback and the call record carry `CustomField=crm_id=C-88`.

**Inbound bot with language from the number dialled:** one flow per language, each Voicebot applet URL with
`?lang=hi` / `?lang=en`; the bot reads `start.custom_parameters.lang`.

**Context to your PBX / contact centre over SIP:** Connect applet Dynamic URL replies
`{"destination": {"trunk": "<trunk_sid>"}, "custom_params": "X-CRM-Id=C-88&X-Intent=refund"}`; your SBC reads
the `X-` headers. Prefer `X-` names; never `Exotel-…` or `Veeno-…`. See `exotel-connect-applet`.

**Legs (no flow):**

```bash
exotel leg create --to +91… --exophone <LP number> --events https://… --param order-4521
exotel leg stream --leg-sid <sid> --url "wss://bot/agent?order=4521&lang=hi"
```

The CLI escapes `&` for ExoML; events for the leg carry `custom_param: "order-4521"`.

## Rules

- Never put secrets or full personal data in these fields: they appear in logs, call records and headers.
  Pass an id and look the rest up server-side.
- URL-encode values in query strings; keep header values ASCII.
- Check what arrived: your bot logs `start.custom_parameters`; `exotel leg events --leg-sid <sid>` shows
  `custom_param`; `exotel applet test connect <url> --param CustomField=…` shows what your handler receives.
