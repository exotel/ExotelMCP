---
name: exotel-phone-numbers
description: Find, buy, release, see and configure Exotel phone numbers (ExoPhones - virtual landline, mobile, toll-free numbers), point them at a call flow, and check account balance. Use when the user needs a caller ID, wants incoming calls routed to a flow or bot, needs a new number, or asks about Exotel balance.
---

# Exotel phone numbers (ExoPhones)

Commands are the `exotel` CLI (`npx exotel-cli …` if not installed). Add `--dry-run` to preview any
request and `--json` for machine-readable output.

| Need | Command | Notes |
|------|---------|-------|
| Numbers on the account | `exotel numbers list` | Use these as caller IDs |
| One number's details and current flow | `exotel numbers get --exophone-sid <sid>` | |
| Route incoming calls/SMS | `exotel numbers update --exophone-sid <sid> --voice-url <flow URL> [--sms-url …]` | **Changes live routing** immediately |
| Rename | `exotel numbers update --exophone-sid <sid> --name <label>` | |
| Balance | `exotel balance` | Check before bulk calling |
| Find numbers to buy | `exotel numbers available --type landline\|mobile\|tollfree [--region KA] [--contains 80]` | Read-only |
| **Buy a number** | `exotel numbers buy --number <number> [--app-id <flow>]` | **Billed** (one-time + monthly). Indian numbers need approved KYC |
| Route to a flow by app id | `exotel numbers update --exophone-sid <sid> --app-id <flow>` | Builds the flow URL for you |
| **Release a number** | `exotel numbers release --exophone-sid <sid>` | **Permanent**; the number may go to another account |

## Rules

- Buying, re-routing and releasing need the user's explicit yes; the CLI enforces it (`--yes` or a prompt; exit code 3
  otherwise). Never pass `--yes` on the user's behalf without asking.
- Before re-routing a number, run `numbers get` and tell the user what it currently points to.
- The flow URL for `--voice-url` looks like `https://my.<cluster>.exotel.com/<account_sid>/exoml/start_voice/<flow_id>`
  (for example `https://my.mum1.exotel.com/acme/exoml/start_voice/40536`). Copy it from the flow in App
  Bazaar or from a number already using that flow (`exotel numbers get`); do not guess the cluster.
