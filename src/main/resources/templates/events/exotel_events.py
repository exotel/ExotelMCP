"""Exotel real-time call event endpoint (HTTPS), standard library only.
Contract: POST application/json, reply 2xx within ~1 s, then act via the ExoML APIs.
Docs: https://docs.exotel.com/voice-apis/implementing-your-https-event-endpoint-for-real-time-call-events
Run:  EXOTEL_API_KEY=... EXOTEL_API_TOKEN=... EXOTEL_ACCOUNT_SID=... python3 exotel_events.py
"""
import base64, hmac, json, os, threading, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8080"))
BASIC_USER = os.environ.get("EXOTEL_EVENTS_USER", "")
BASIC_PASS = os.environ.get("EXOTEL_EVENTS_PASS", "")
CPAAS = "https://cpaas-api.in.exotel.com"
AUTH = "Basic " + base64.b64encode(f"{os.environ.get('EXOTEL_API_KEY')}:{os.environ.get('EXOTEL_API_TOKEN')}".encode()).decode()
seen = set()


def leg_action(leg_sid, exoml, param=""):
    url = f"{CPAAS}/v2/accounts/{os.environ['EXOTEL_ACCOUNT_SID']}/legs/{leg_sid}/actions"
    req = urllib.request.Request(url, data=json.dumps({"exoml": exoml, "action_custom_param": param}).encode(),
                                 headers={"Authorization": AUTH, "Content-Type": "application/json"}, method="POST")
    urllib.request.urlopen(req, timeout=10).read()


def handle(event):
    leg = (event.get("data") or {}).get("leg_sid")
    name = event.get("event_name")
    if name == "leg_incoming":  # inbound call on a DID registered to this endpoint: answer it
        leg_action(leg, "<Flow><Answer/></Flow>", "answer")
    elif name == "leg_answered":  # act only after answer; actions sent while ringing are lost
        leg_action(leg, "<Flow><Say>Hello from your Exotel event endpoint.</Say></Flow>", "greeting")
        if os.environ.get("BOT_WSS"):
            url = os.environ["BOT_WSS"].replace("&", "&amp;")
            leg_action(leg, f'<Flow><StartStream streamType="bidirectional" streamUrl="{url}"/></Flow>', "stream")
    elif name in ("leg_terminated", "leg_failed_to_create"):
        print("call ended", (event.get("data") or {}).get("terminal_status"), (event.get("event_data") or {}).get("error"))


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if BASIC_USER:
            want = "Basic " + base64.b64encode(f"{BASIC_USER}:{BASIC_PASS}".encode()).decode()
            if not hmac.compare_digest(self.headers.get("Authorization", ""), want):
                self.send_response(401); self.end_headers(); return
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        self.send_response(200); self.end_headers()          # 1) ack immediately
        try:
            event = json.loads(body)
        except ValueError:
            return
        if event.get("event_sid") in seen:                  # retry of an event already handled
            return
        seen.add(event.get("event_sid"))
        print(event.get("event_name"), json.dumps(event.get("data") or {}))
        threading.Thread(target=handle, args=(event,), daemon=True).start()  # 2) decide 3) act

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
