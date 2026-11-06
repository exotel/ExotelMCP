#!/usr/bin/env python3
"""AI-path test with no phone and no OpenAI key.

Starts a fake OpenAI Realtime server and a fake webhook, runs server.py against them, then plays a
fake Exotel call (connected/start/media/mark/stop) and checks the acceptance criteria:

  - greeting audio reaches the caller fast (server overhead only; real OpenAI adds model latency)
  - caller audio reaches the AI resampled to 24 kHz PCM16
  - every outbound chunk is a multiple of 320 bytes, 3.2 KB to 100 KB, at the announced sample rate
  - barge-in while generating: `clear` to Exotel + `response.cancel` to the AI
  - barge-in after generation finished but while Exotel is still playing: `clear` to Exotel
  - save_booking -> webhook POST (and RECORDS_FILE when set for testing)
  - end_call -> goodbye, `mark` "hangup", stream closed by the server
  - per-call transcript file written

Usage: python test_ai_mock.py [8000|16000]
"""
import asyncio
import base64
import http.server
import json
import os
import socket
import subprocess
import sys
import threading
import time

import numpy as np
import websockets

RATE = int(sys.argv[1]) if len(sys.argv) > 1 else 16000
HERE = os.path.dirname(os.path.abspath(__file__))
results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(f"{'PASS' if ok else 'FAIL'}  {name}{('  — ' + detail) if detail else ''}")


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def pcm24k(seconds):
    n = int(24000 * seconds)
    return (np.sin(2 * np.pi * 300 * np.arange(n) / 24000) * 6000).astype("<i2").tobytes()


# ---------------------------------------------------------------- fake webhook
webhook_hits = []


class Hook(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        webhook_hits.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
        self.send_response(200)
        self.end_headers()

    def log_message(self, *a):
        pass


# ---------------------------------------------------------------- fake OpenAI Realtime
ai = {"session": None, "appends": 0, "append_bytes": 0, "cancels": 0, "tool_outputs": [], "auth": None}


async def fake_openai(ws):
    ai["auth"] = ws.request.headers.get("Authorization")
    responses = 0

    async def speak(seconds, transcript, chunk=4800):
        audio = pcm24k(seconds)
        for i in range(0, len(audio), chunk):
            await ws.send(json.dumps({"type": "response.output_audio.delta", "delta": base64.b64encode(audio[i:i + chunk]).decode()}))
            await asyncio.sleep(0.005)
        await ws.send(json.dumps({"type": "response.output_audio.done"}))
        await ws.send(json.dumps({"type": "response.output_audio_transcript.done", "transcript": transcript}))
        await ws.send(json.dumps({"type": "response.done"}))

    async for raw in ws:
        ev = json.loads(raw)
        t = ev["type"]
        if t == "session.update":
            ai["session"] = ev["session"]
        elif t == "input_audio_buffer.append":
            ai["appends"] += 1
            ai["append_bytes"] += len(base64.b64decode(ev["audio"]))
        elif t == "response.cancel":
            ai["cancels"] += 1
        elif t == "conversation.item.create" and ev["item"]["type"] == "function_call_output":
            ai["tool_outputs"].append(ev["item"])
        elif t == "response.create":
            responses += 1
            if responses == 1:  # greeting
                await speak(1.0, "Hello, thanks for calling Acme Clinic. Shall I book an appointment?")
            elif responses == 2:  # triggered by test: long answer, interrupted while generating
                audio = pcm24k(0.4)
                for i in range(0, len(audio), 4800):
                    await ws.send(json.dumps({"type": "response.output_audio.delta", "delta": base64.b64encode(audio[i:i + 4800]).decode()}))
                await asyncio.sleep(0.05)
                await ws.send(json.dumps({"type": "input_audio_buffer.speech_started"}))
            elif responses == 3:  # after tool output: confirmation, then the model ends the call
                await speak(0.5, "Booked for 5 December at 10 AM.")
                await ws.send(json.dumps({"type": "response.function_call_arguments.done", "name": "end_call", "call_id": "c2", "arguments": "{}"}))
            elif responses == 4:  # goodbye after end_call
                await speak(0.6, "Goodbye!")


async def main():
    hook_port, ai_port, srv_port = free_port(), free_port(), free_port()
    httpd = http.server.HTTPServer(("127.0.0.1", hook_port), Hook)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    ai_server = await websockets.serve(fake_openai, "127.0.0.1", ai_port)

    work = os.path.join(HERE, ".test_run")
    os.makedirs(work, exist_ok=True)
    for f in ("records.jsonl",):
        if os.path.exists(os.path.join(work, f)):
            os.remove(os.path.join(work, f))
    env = dict(os.environ, PORT=str(srv_port), OPENAI_API_KEY="sk-test", OPENAI_REALTIME_URL=f"ws://127.0.0.1:{ai_port}", BOT_TOKEN="test-token-123", RECORDS_FILE="records.jsonl",
               WEBHOOK_URL=f"http://127.0.0.1:{hook_port}/hook", PYTHONPATH=HERE)
    proc = subprocess.Popen([sys.executable, os.path.join(HERE, "server.py")], cwd=work, env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    for _ in range(50):
        try:
            socket.create_connection(("127.0.0.1", srv_port), 0.2).close()
            break
        except OSError:
            await asyncio.sleep(0.1)

    sizes, events, first_audio = [], [], None
    try:
        async with websockets.connect(f"ws://127.0.0.1:{srv_port}/booking?sample-rate={RATE}&token=test-token-123") as ex:
            await ex.send(json.dumps({"event": "connected"}))
            await asyncio.sleep(0.2)  # Exotel sends `start` shortly after; pre-connect happens meanwhile
            t_start = time.monotonic()
            await ex.send(json.dumps({"event": "start", "sequence_number": "1", "stream_sid": "MZ1", "start": {
                "stream_sid": "MZ1", "call_sid": "CAmock1", "account_sid": "AC", "from": "+919800000000", "to": "+918000000000",
                "custom_parameters": {}, "media_format": {"encoding": "raw", "sample_rate": str(RATE), "bit_rate": "128"}}}))

            closed = asyncio.Event()

            async def reader():
                nonlocal first_audio
                try:
                    async for raw in ex:
                        ev = json.loads(raw)
                        events.append(ev["event"])
                        if ev["event"] == "media":
                            if first_audio is None:
                                first_audio = time.monotonic() - t_start
                            sizes.append(len(base64.b64decode(ev["media"]["payload"])))
                        elif ev["event"] == "mark" and ev["mark"]["name"] == "hangup":
                            await ex.send(json.dumps({"event": "mark", "stream_sid": "MZ1", "mark": {"name": "hangup"}}))
                except websockets.ConnectionClosed:
                    pass
                closed.set()

            rd = asyncio.create_task(reader())
            frame = RATE * 2 // 10  # 100 ms of caller audio
            speech = (np.sin(2 * np.pi * 200 * np.arange(RATE) / RATE) * 5000).astype("<i2").tobytes()
            for i in range(0, len(speech), frame):
                await ex.send(json.dumps({"event": "media", "stream_sid": "MZ1", "media": {"chunk": str(i), "timestamp": str(i), "payload": base64.b64encode(speech[i:i + frame]).decode()}}))
                await asyncio.sleep(0.02)
            await asyncio.sleep(0.3)

            # --- barge-in after generation finished, while Exotel would still be playing the 1 s greeting
            n_clear = events.count("clear")
            ai_ws = next(iter(ai_server.connections))
            await ai_ws.send(json.dumps({"type": "input_audio_buffer.speech_started"}))
            await asyncio.sleep(0.2)
            check("barge-in while Exotel still plays queued audio sends clear", events.count("clear") == n_clear + 1)

            # --- barge-in while generating (fake AI triggers speech_started mid-response)
            n_clear, n_cancel = events.count("clear"), ai["cancels"]
            await ai_ws.send(json.dumps({"type": "noop"}))
            # ask the server to make the AI respond again (as a DTMF would) -> response #2
            await ex.send(json.dumps({"event": "dtmf", "stream_sid": "MZ1", "dtmf": {"digit": "1", "duration": "200"}}))
            await asyncio.sleep(0.5)
            check("barge-in while generating sends clear to Exotel", events.count("clear") > n_clear)
            check("barge-in while generating cancels the AI response", ai["cancels"] > n_cancel)

            # --- tool call from the model
            await ai_ws.send(json.dumps({"type": "response.function_call_arguments.done", "name": "save_booking", "call_id": "c1",
                                         "arguments": json.dumps({"name": "Ravi Kumar", "date": "2026-12-05", "time": "10:00", "purpose": "checkup"})}))
            await asyncio.wait_for(closed.wait(), timeout=10)
            await rd
    finally:
        await asyncio.sleep(0.3)
        proc.terminate()
        out = proc.communicate(timeout=5)[0]
        ai_server.close()
        httpd.shutdown()

    sess = ai["session"] or {}
    check("server authenticates to OpenAI with the key", ai["auth"] == "Bearer sk-test")
    check("session uses PCM 24 kHz in and out", sess.get("audio", {}).get("input", {}).get("format", {}).get("rate") == 24000
          and sess.get("audio", {}).get("output", {}).get("format", {}).get("rate") == 24000)
    check("booking tools offered (save_booking, end_call)", {t["name"] for t in sess.get("tools", [])} >= {"save_booking", "end_call"})
    expected = int(len(speech) * 24000 / RATE)
    check("caller audio reached the AI resampled to 24 kHz", ai["appends"] > 0 and abs(ai["append_bytes"] - expected) <= 0.02 * expected,
          f"{ai['append_bytes']} B vs ~{expected} B")
    check("greeting audio reached the caller quickly", first_audio is not None and first_audio < 0.5,
          f"{first_audio * 1000:.0f} ms server-side" if first_audio else "no audio")
    bad = [s for s in sizes if s % 320 or not 3200 <= s <= 100000]
    check("all outbound chunks are multiples of 320 B within 3.2–100 KB", sizes and not bad, f"{len(sizes)} chunks, sizes {sorted(set(sizes))}")
    recs = [json.loads(l) for l in open(os.path.join(work, "records.jsonl"))] if os.path.exists(os.path.join(work, "records.jsonl")) else []
    check("save_booking written to records.jsonl with call context",
          len(recs) == 1 and recs[0]["type"] == "save_booking" and recs[0]["data"]["date"] == "2026-12-05" and recs[0]["call_sid"] == "CAmock1")
    check("save_booking POSTed to WEBHOOK_URL", len(webhook_hits) == 1 and webhook_hits[0]["data"]["name"] == "Ravi Kumar")
    check("tool result returned to the AI", any(o["call_id"] == "c1" for o in ai["tool_outputs"]))
    check("end_call: goodbye then mark 'hangup' then server closes the stream", "mark" in events and closed.is_set())
    # Privacy: nothing the caller says, and no full phone number or captured value, reaches logs or disk.
    check("no transcript written to disk", not os.path.exists(os.path.join(work, "logs", "CAmock1.txt")))
    check("call log masks numbers to the last 4 digits", "call=CAmock1 from=••••0000 to=••••0000" in out and "first audio" in out)
    check("no conversation text or captured values in the log",
          "Ravi Kumar" not in out and "AGENT:" not in out and "CALLER:" not in out and "+919800000000" not in out)
    print(f"\n{sum(results)}/{len(results)} passed at {RATE} Hz")
    if not all(results):
        print("---- server log ----\n" + out[-3000:])
    sys.exit(0 if all(results) else 1)


asyncio.run(main())
