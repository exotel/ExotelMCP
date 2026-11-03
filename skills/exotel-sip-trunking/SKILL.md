---
name: exotel-sip-trunking
description: Connect a SIP system (PBX, SBC, FreeSWITCH, Asterisk, Kamailio, LiveKit, ElevenLabs, Vapi, any Voice AI or contact-centre platform) to the Indian PSTN through Exotel Dynamic SIP Trunking - create the trunk, map numbers, whitelist IPs or add digest credentials, set inbound destinations and caller ID, and debug SIP errors. Use for SIP trunk, BYOC, PSTN connectivity, DID mapping, or SIP 403/404/488 problems with Exotel.
---

# Exotel SIP trunking

Commands below are the `exotel` CLI (`npx exotel-cli …` if it is not installed; log in once with
`exotel login`). Every command takes `--dry-run` (show the exact request) and `--json`.

## Before you start

- SIP trunking must be enabled on the account and KYC done (the user emails hello@exotel.com).
  A 401/403 on every trunk call means it is not enabled yet; stop and tell the user.
- Collect from the user: their public **static IP(s)** (outbound), their **SIP host/FQDN and port** (inbound),
  **TLS or TCP** (TLS 5061 recommended), which **ExoPhone(s)** to use (`exotel numbers list`), and whether calls
  go straight to PSTN (`pstn`, default) or through an Exotel Voice AI flow (`flow`).
- Cloud platforms with changing IPs (ElevenLabs, LiveKit, Vapi): use digest credentials instead of IP whitelisting.

## Set up in one command

```bash
exotel trunk setup --name ai_trunk --number +919876543210 --ip 44.248.146.11 \
  --uri sip.customer.com --transport tls --caller-id +919876543210 --dry-run
```

Show the dry-run to the user, then re-run without `--dry-run` (it asks for confirmation; add `--yes` in
non-interactive shells only after the user agreed). It runs create → map number → whitelist IP →
destination → caller ID, stops at the first failure, and prints the trunk SID. Omit flags for steps
you do not need. Error code 1008 (already configured) is treated as success.

## Individual steps

| Need | Command |
|------|---------|
| Create trunk | `exotel trunk create --name <≤16 letters/digits/_>` → save `trunk_sid` |
| Route a number via the trunk | `exotel trunk map-number --trunk-sid <sid> --number +91… [--mode pstn\|flow]` → save numeric `id` |
| Switch number between PSTN / Voice AI flow | `exotel trunk set-number-mode --trunk-sid <sid> --id <id> --number +91… --mode flow` |
| Allow outbound from an IP | `exotel trunk whitelist-ip --trunk-sid <sid> --ip <IPv4> [--mask 32]` |
| Inbound destination | `exotel trunk destination --trunk-sid <sid> --uri <host[:port]> --transport tls\|tcp` |
| Digest auth (dynamic IPs) | `exotel trunk credentials add --trunk-sid <sid> --user <name> --label <≤32>`; password via hidden prompt, `EXOTEL_SIP_PASSWORD` or `--password-stdin` |
| List / remove credentials | `exotel trunk credentials list --trunk-sid <sid>` · `credentials delete --trunk-sid <sid> --id <id>` |
| Caller ID shown on outbound | `exotel trunk caller-id --trunk-sid <sid> --number +91…` |
| Delete everything | `exotel trunk delete --trunk-sid <sid>` (irreversible) |

Never put the SIP password in a command line, file in the repo, or the chat.

## Inbound calls need one manual step

Exotel routes inbound PSTN calls through a flow. Tell the user: in App Bazaar, build a flow with a
**Connect** applet whose *Dial whom* is `sip:<trunk_sid>`, and assign the ExoPhone to that flow
(`exotel numbers update --exophone-sid <sid> --voice-url <flow URL>`). For per-call routing, the
Connect applet can use a Dynamic URL that returns `{"destination": {"trunk": "<trunk_sid>"}, "custom_params": "X-…"}`
(see `exotel-connect-applet`). Custom SIP headers: newer docs allow 10 headers, 800 bytes each, 4 KB total; an
older page says 3 headers / 200 bytes, so stay within the smaller until confirmed. `Exotel-` and `Veeno-` prefixes
are reserved. Details: `exotel-custom-data`.

## Network

Signalling SIP over TLS 5061 or TCP 5060; media RTP on **UDP 10000–40000**; codecs **G.711 A-law
(PCMA)**, also PCMU. The authoritative IP list is at
https://docs.exotel.com/dynamic-sip-trunking/network-and-firewall-configuration — send the user there
rather than guessing IPs.

## SIP errors

| Code | Usual cause → fix |
|------|-------------------|
| 401 | Digest credentials wrong → re-check user/password on the trunk |
| 403 | Source IP not whitelisted → `exotel trunk whitelist-ip` |
| 404 | Number not mapped to the trunk → `exotel trunk map-number` |
| 408 / 504 | Firewall or reachability → open signalling ports, check NAT |
| 415 / 488 | Codec mismatch → enable G.711 A-law only |
| 480 | Their PBX/SBC unavailable |
| 484 | Number not in E.164 |
| No audio / one-way audio | UDP 10000–40000 blocked, or private IP in SDP |
