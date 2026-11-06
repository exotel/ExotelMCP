#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
[ -d .venv ] || python3 -m venv .venv
source .venv/bin/activate
pip install -q -r requirements.txt
# The bot refuses to start without a password: create one for this run if you didn't set BOT_TOKEN.
if [ -z "${BOT_TOKEN:-}" ]; then
  export BOT_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
  echo "Generated BOT_TOKEN for this run. Give Exotel this stream URL (replace the host with your tunnel):"
  echo "  wss://<your-public-host>/receptionist?sample-rate=8000&token=${BOT_TOKEN}"
fi
exec python server.py
