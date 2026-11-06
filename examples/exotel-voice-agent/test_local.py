#!/usr/bin/env python3
"""Fake Exotel call against the local server (no phone needed).
Usage: BOT_TOKEN=<same as the server> python test_local.py [ws://localhost:8080/echo]
Sends connected/start, 2 s of a 440 Hz tone as 100 ms media chunks, then stop,
and checks that the server replies with compliant media chunks."""
import asyncio, base64, json, os, sys
from urllib.parse import quote
import numpy as np
import websockets

URI = sys.argv[1] if len(sys.argv) > 1 else "ws://localhost:8080/echo"
# The server only accepts connections that carry its BOT_TOKEN.
if os.getenv("BOT_TOKEN") and "token=" not in URI:
    URI += ("&" if "?" in URI else "?") + "token=" + quote(os.environ["BOT_TOKEN"])
RATE = 8000


async def main():
    tone = (np.sin(2 * np.pi * 440 * np.arange(RATE * 2) / RATE) * 8000).astype("<i2").tobytes()
    chunk = RATE * 2 // 10  # 100 ms
    got, sizes, events = 0, set(), []
    async with websockets.connect(URI) as ws:
        await ws.send(json.dumps({"event": "connected"}))
        await ws.send(json.dumps({"event": "start", "sequence_number": "1", "stream_sid": "MZtest",
            "start": {"stream_sid": "MZtest", "call_sid": "CAtest", "account_sid": "ACtest",
                      "from": "+919800000000", "to": "+918000000000", "custom_parameters": {},
                      "media_format": {"encoding": "audio/x-raw", "sample_rate": str(RATE), "bit_rate": "16"}}}))

        async def reader():
            nonlocal got
            try:
                async for raw in ws:
                    ev = json.loads(raw)
                    events.append(ev["event"])
                    if ev["event"] == "media":
                        assert ev["stream_sid"] == "MZtest"
                        n = len(base64.b64decode(ev["media"]["payload"]))
                        sizes.add(n); got += n
            except websockets.ConnectionClosed:
                pass
        r = asyncio.create_task(reader())
        for i in range(0, len(tone), chunk):
            await ws.send(json.dumps({"event": "media", "sequence_number": str(i), "stream_sid": "MZtest",
                "media": {"chunk": str(i // chunk), "timestamp": str(i // 16),
                          "payload": base64.b64encode(tone[i:i + chunk]).decode()}}))
            await asyncio.sleep(0.1)
        await asyncio.sleep(float(sys.argv[2]) if len(sys.argv) > 2 else 1.0)
        await ws.send(json.dumps({"event": "stop", "sequence_number": "99", "stream_sid": "MZtest",
                                  "stop": {"call_sid": "CAtest", "account_sid": "ACtest", "reason": "callended"}}))
        await asyncio.sleep(0.5)
        r.cancel()
    ok = got > 0 and all(s % 320 == 0 and 3200 <= s <= 100000 for s in sizes)
    print(f"events back: {sorted(set(events))}  audio bytes back: {got}  chunk sizes: {sorted(sizes)}")
    print("PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

asyncio.run(main())
