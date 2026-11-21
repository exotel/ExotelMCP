"""Exotel real-time call event endpoint (HTTPS), Python 3.9+, standard library only.
Contract: POST application/json, reply 2xx within ~1 s, then act via the ExoML APIs.
Docs: https://docs.exotel.com/voice-apis/implementing-your-https-event-endpoint-for-real-time-call-events

Run:  EXOTEL_API_KEY=... EXOTEL_API_TOKEN=... EXOTEL_ACCOUNT_SID=... EXOTEL_EVENTS_TOKEN=<long random> python3 exotel_events.py
Then give Exotel this event URL (behind HTTPS):  https://<your-host>/exotel/events/<EXOTEL_EVENTS_TOKEN>
If Exotel support configured Basic auth for your endpoint, set EXOTEL_EVENTS_USER / EXOTEL_EVENTS_PASS instead.
"""
import base64, hmac, json, os, re, sys, threading, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8080"))
TOKEN = os.environ.get("EXOTEL_EVENTS_TOKEN", "")
BASIC_USER = os.environ.get("EXOTEL_EVENTS_USER", "")
BASIC_PASS = os.environ.get("EXOTEL_EVENTS_PASS", "")
CPAAS = "https://cpaas-api.in.exotel.com"
AUTH = "Basic " + base64.b64encode(f"{os.environ.get('EXOTEL_API_KEY')}:{os.environ.get('EXOTEL_API_TOKEN')}".encode()).decode()
MAX_BODY = 256 * 1024
seen = {}  # event_sid -> None, oldest first (dicts keep insertion order)
lock = threading.Lock()

if not TOKEN and not BASIC_USER and os.environ.get("ALLOW_UNAUTHENTICATED") != "1":
    sys.exit("Set EXOTEL_EVENTS_TOKEN (secret in the event URL) or EXOTEL_EVENTS_USER/PASS. Anyone could post fake call events otherwise.")
if bool(BASIC_USER) != bool(BASIC_PASS):
    sys.exit("Basic auth needs both EXOTEL_EVENTS_USER and EXOTEL_EVENTS_PASS. Set both, or neither.")


def authorized(handler):
    if TOKEN:
        return hmac.compare_digest(handler.path.split("?")[0], f"/exotel/events/{TOKEN}")
    if BASIC_USER:
        want = "Basic " + base64.b64encode(f"{BASIC_USER}:{BASIC_PASS}".encode()).decode()
        return hmac.compare_digest(handler.headers.get("Authorization", ""), want)
    return True  # ALLOW_UNAUTHENTICATED=1: local testing only


def leg_action(leg_sid, exoml, param=""):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", str(leg_sid or "")):
        return
    url = f"{CPAAS}/v2/accounts/{os.environ['EXOTEL_ACCOUNT_SID']}/legs/{leg_sid}/actions"
    req = urllib.request.Request(url, data=json.dumps({"exoml": exoml, "action_custom_param": param}).encode(),
                                 headers={"Authorization": AUTH, "Content-Type": "application/json"}, method="POST")
    try:
        urllib.request.urlopen(req, timeout=10).read()
    except Exception as e:
        print("action failed:", e)


def handle(event):
    data = event.get("data") if isinstance(event.get("data"), dict) else {}
    leg, name = data.get("leg_sid"), event.get("event_name")
    if name == "leg_incoming":  # inbound call on a DID attached to this endpoint: answer it
        leg_action(leg, "<Flow><Answer/></Flow>", "answer")
    elif name == "leg_answered":  # act only after answer; actions sent while ringing are lost
        leg_action(leg, "<Flow><Say>Hello from your Exotel event endpoint.</Say></Flow>", "greeting")
        if os.environ.get("BOT_WSS"):
            url = os.environ["BOT_WSS"].replace("&", "&amp;")
            leg_action(leg, f'<Flow><StartStream streamType="bidirectional" streamUrl="{url}"/></Flow>', "stream")
    elif name in ("leg_terminated", "leg_failed_to_create"):
        print("call ended", leg, data.get("terminal_status"))


class Handler(BaseHTTPRequestHandler):
    def reply(self, code):
        self.send_response(code); self.send_header("Content-Length", "0"); self.end_headers()

    def do_POST(self):
        if not authorized(self):
            return self.reply(401)
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.reply(400)
        if length < 0 or length > MAX_BODY:
            return self.reply(413)
        body = self.rfile.read(length)
        self.reply(200)                                      # 1) ack immediately
        try:
            event = json.loads(body)
        except ValueError:
            return
        if not isinstance(event, dict) or not isinstance(event.get("event_name"), str):
            return
        sid = event.get("event_sid")
        with lock:
            if sid and sid in seen:                          # retry of an event already handled
                return
            if sid:
                seen[sid] = None
                if len(seen) > 10000:
                    del seen[next(iter(seen))]  # drop the oldest, never the one just added
        # Log the event type and leg only: event data contains callers' phone numbers.
        print(event["event_name"], (event.get("data") or {}).get("leg_sid") if isinstance(event.get("data"), dict) else None)
        threading.Thread(target=handle, args=(event,), daemon=True).start()  # 2) decide 3) act

    def do_GET(self):
        self.reply(405)

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
