#!/usr/bin/env python3
"""
Exotel Voice AI Agent - WSS server for the Exotel Voicebot applet.

Modes (chosen by URL path, or custom parameter `mode`/`usecase`):
  wss://<host>/echo           -> echo bot, no AI key needed (proves the audio path)
  wss://<host>/receptionist   -> AI receptionist         (OpenAI Realtime)
  wss://<host>/booking        -> AI appointment booking  (OpenAI Realtime)
  wss://<host>/lead           -> AI lead qualification   (OpenAI Realtime)

If OPENAI_API_KEY is not set, every path falls back to echo.

Exotel protocol: JSON over WebSocket. Events in: connected, start, media, dtmf, mark, stop.
Events out: media, mark, clear. Audio: base64 PCM16 LE mono at start.media_format.sample_rate
(8000 default; add ?sample-rate=16000 in the applet URL for better quality).
Outbound chunks: multiple of 320 bytes, 3,200-100,000 bytes.
"""
import asyncio
import base64
import json
import logging
import os
import time
import urllib.request
from datetime import datetime, timezone
from urllib.parse import urlparse, parse_qs

import numpy as np
import websockets

from agents import USE_CASES

# ---------------------------------------------------------------- config
PORT = int(os.getenv("PORT", "8080"))
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "").strip()
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-realtime")
OPENAI_VOICE = os.getenv("OPENAI_VOICE", "marin")
OPENAI_URL = os.getenv("OPENAI_REALTIME_URL", f"wss://api.openai.com/v1/realtime?model={OPENAI_MODEL}")
WEBHOOK_URL = os.getenv("WEBHOOK_URL", "").strip()          # optional connector (CRM / Zapier / Sheets)
DEFAULT_USE_CASE = os.getenv("USE_CASE", "receptionist")
AI_RATE = 24000                                             # OpenAI Realtime PCM rate
# Caller-side transcript for the logs. Without a hint the model guesses the language per utterance and can
# log Hindi/Indian-English speech as Korean or Turkish (seen on a live call). Set TRANSCRIBE_LANGUAGE=en|hi to pin it.
TRANSCRIPTION = {"model": os.getenv("TRANSCRIBE_MODEL", "gpt-4o-transcribe"),
                 "prompt": os.getenv("TRANSCRIBE_PROMPT", "Phone call in Indian English, Hindi or Hinglish. "
                                                          "Indian names, dates and times.")}
if os.getenv("TRANSCRIBE_LANGUAGE"):
    TRANSCRIPTION["language"] = os.getenv("TRANSCRIBE_LANGUAGE")

os.makedirs("logs", exist_ok=True)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                    handlers=[logging.StreamHandler(), logging.FileHandler("logs/server.log")])
log = logging.getLogger("agent")


# ---------------------------------------------------------------- audio helpers
def resample(pcm: bytes, src: int, dst: int) -> bytes:
    """PCM16 mono resample with linear interpolation (+ box low-pass when downsampling)."""
    if src == dst or not pcm:
        return pcm
    x = np.frombuffer(pcm, dtype="<i2").astype(np.float32)
    if dst < src:
        k = max(1, round(src / dst))
        if k > 1:
            x = np.convolve(x, np.ones(k, dtype=np.float32) / k, mode="same")
    n_out = int(len(x) * dst / src)
    if n_out <= 0:
        return b""
    y = np.interp(np.linspace(0, len(x) - 1, n_out), np.arange(len(x)), x)
    return np.clip(y, -32768, 32767).astype("<i2").tobytes()


class OutboundAudio:
    """Buffers audio to Exotel and sends compliant chunks (multiple of 320, >= 3200 bytes)."""
    CHUNK = 3200

    def __init__(self, session):
        self.s = session
        self.buf = bytearray()

    async def push(self, pcm: bytes):
        self.buf.extend(pcm)
        while len(self.buf) >= self.CHUNK:
            chunk, self.buf = bytes(self.buf[:self.CHUNK]), self.buf[self.CHUNK:]
            await self.s.send_media(chunk)

    async def flush(self):
        if not self.buf:
            return
        pad = (-len(self.buf)) % 320
        data = bytes(self.buf) + b"\x00" * pad
        if len(data) < self.CHUNK:
            data += b"\x00" * (self.CHUNK - len(data))
        self.buf = bytearray()
        await self.s.send_media(data)

    def drop(self):
        self.buf = bytearray()


# ---------------------------------------------------------------- connector
async def save_record(kind: str, call: dict, data: dict):
    rec = {"type": kind, "at": datetime.now(timezone.utc).isoformat(), **call, "data": data}
    with open("records.jsonl", "a") as f:
        f.write(json.dumps(rec) + "\n")
    log.info("💾 saved %s: %s", kind, data)
    if WEBHOOK_URL:
        def _post():
            req = urllib.request.Request(WEBHOOK_URL, data=json.dumps(rec).encode(),
                                         headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status
        try:
            status = await asyncio.to_thread(_post)
            log.info("🔗 webhook %s -> %s", WEBHOOK_URL, status)
        except Exception as e:
            log.warning("webhook failed: %s", e)
    return {"ok": True}


# ---------------------------------------------------------------- call session
class CallSession:
    def __init__(self, ws, path: str):
        self.ws = ws
        parsed = urlparse(path or "/")
        self.route = parsed.path.strip("/").lower() or DEFAULT_USE_CASE
        self.query = {k: v[0] for k, v in parse_qs(parsed.query).items()}
        self.stream_sid = None
        self.call = {}
        self.rate = 8000
        self.out = OutboundAudio(self)
        self.ai = None
        self.ai_task = None
        self.transcript = []
        self.hangup_after_audio = False
        self.closed = False
        self.playing_until = 0.0
        self.started_at = None
        self.first_audio_at = None
        self.bytes_in = 0
        self.bytes_out = 0
        self.ai_open_task = None

    # ---- outbound to Exotel
    async def send(self, msg: dict):
        if not self.closed:
            await self.ws.send(json.dumps(msg))

    async def send_media(self, pcm: bytes):
        await self.send({"event": "media", "stream_sid": self.stream_sid,
                         "media": {"payload": base64.b64encode(pcm).decode()}})
        # Exotel queues what we send; estimate when it will have finished playing (barge-in needs this).
        now = time.monotonic()
        self.playing_until = max(self.playing_until, now) + len(pcm) / (2 * self.rate)
        self.bytes_out += len(pcm)
        if self.first_audio_at is None and self.started_at is not None:
            self.first_audio_at = now
            log.info("🔊 first audio to caller %.0f ms after start", (now - self.started_at) * 1000)

    async def send_mark(self, name: str):
        await self.send({"event": "mark", "stream_sid": self.stream_sid, "mark": {"name": name}})

    async def clear(self):
        self.out.drop()
        self.playing_until = 0.0
        await self.send({"event": "clear", "stream_sid": self.stream_sid})

    def bot_audible(self) -> bool:
        return time.monotonic() < self.playing_until or bool(self.out.buf)

    def note(self, who: str, text: str):
        if text:
            self.transcript.append(f"{who}: {text}")
            log.info("🗣  %s: %s", who, text)

    # ---- main loop
    async def run(self):
        async for raw in self.ws:
            try:
                ev = json.loads(raw)
            except Exception:
                continue
            kind = ev.get("event")
            if kind == "connected":
                log.info("🔌 connected (route=/%s)", self.route)
                if self.route in USE_CASES and OPENAI_API_KEY and not self.ai_open_task:
                    # Open the OpenAI session while Exotel is still sending `start`: saves ~0.5 s on the greeting.
                    self.ai_open_task = asyncio.create_task(self.ai_open(self.route))
            elif kind == "start":
                await self.on_start(ev)
            elif kind == "media":
                await self.on_media(ev)
            elif kind == "dtmf":
                digit = (ev.get("dtmf") or {}).get("digit")
                log.info("☎️  DTMF %s", digit)
                if self.ai:
                    await self.ai_send_text(f"(The caller pressed the {digit} key on their keypad.)")
            elif kind == "mark":
                name = (ev.get("mark") or {}).get("name")
                if name == "hangup":
                    log.info("👋 goodbye played, ending stream")
                    break
            elif kind == "stop":
                log.info("⏹  stop: %s", (ev.get("stop") or {}).get("reason"))
                break
        await self.close()

    async def on_start(self, ev):
        self.started_at = time.monotonic()
        st = ev.get("start") or {}
        self.stream_sid = ev.get("stream_sid") or st.get("stream_sid")
        fmt = st.get("media_format") or {}
        self.rate = int(fmt.get("sample_rate") or 8000)
        cp = st.get("custom_parameters") or {}
        self.call = {"call_sid": st.get("call_sid"), "from": st.get("from"), "to": st.get("to"),
                     "stream_sid": self.stream_sid}
        # custom params can override the route
        route = (cp.get("usecase") or cp.get("mode") or self.query.get("usecase") or self.route).lower()
        if route not in USE_CASES and route != "echo":
            route = DEFAULT_USE_CASE
        if route != "echo" and not OPENAI_API_KEY:
            log.warning("OPENAI_API_KEY not set -> falling back to echo")
            route = "echo"
        self.route = route
        log.info("📞 start call=%s from=%s to=%s rate=%s mode=%s", self.call["call_sid"],
                 self.call["from"], self.call["to"], self.rate, self.route)
        if self.route != "echo":
            try:
                await self.ai_connect()
            except Exception as e:
                # 6. never leave the caller in silence: log clearly and end the stream so the flow moves on.
                log.error("❌ OpenAI connection failed (%s). Check OPENAI_API_KEY / OPENAI_MODEL. Ending stream.", e)
                await self.close()

    async def on_media(self, ev):
        payload = (ev.get("media") or {}).get("payload")
        if not payload:
            return
        pcm = base64.b64decode(payload)
        self.bytes_in += len(pcm)
        if self.route == "echo":
            await self.out.push(pcm)
            return
        if self.ai:
            await self.ai.send(json.dumps({"type": "input_audio_buffer.append",
                                           "audio": base64.b64encode(resample(pcm, self.rate, AI_RATE)).decode()}))

    # ---- OpenAI Realtime
    async def ai_open(self, route):
        """Connect to OpenAI Realtime and configure the session for `route`. Returns (route, ws)."""
        uc = USE_CASES[route]
        ws = await websockets.connect(
            OPENAI_URL, additional_headers={"Authorization": f"Bearer {OPENAI_API_KEY}"},
            max_size=None, open_timeout=10)
        await ws.send(json.dumps({"type": "session.update", "session": {
            "type": "realtime",
            "instructions": uc["instructions"],
            "audio": {
                "input": {"format": {"type": "audio/pcm", "rate": AI_RATE},
                          "turn_detection": {"type": "server_vad", "silence_duration_ms": 500},
                          "transcription": TRANSCRIPTION},
                "output": {"format": {"type": "audio/pcm", "rate": AI_RATE}, "voice": OPENAI_VOICE},
            },
            "tools": uc["tools"] + [END_CALL_TOOL],
            "tool_choice": "auto",
        }}))
        return route, ws

    async def ai_connect(self):
        pre = None
        if self.ai_open_task:
            try:
                pre = await self.ai_open_task
            except Exception as e:
                log.warning("pre-connect failed (%s); retrying", e)
        if pre and pre[0] == self.route:
            self.ai = pre[1]
        else:
            if pre:  # custom parameter chose a different use case than the path
                await pre[1].close()
            self.ai = (await self.ai_open(self.route))[1]
        # greet first
        await self.ai.send(json.dumps({"type": "response.create",
                                       "response": {"instructions": USE_CASES[self.route]["greeting"]}}))
        self.ai_task = asyncio.create_task(self.ai_loop())

    async def ai_send_text(self, text: str):
        await self.ai.send(json.dumps({"type": "conversation.item.create", "item": {
            "type": "message", "role": "user", "content": [{"type": "input_text", "text": text}]}}))
        await self.ai.send(json.dumps({"type": "response.create"}))

    async def ai_loop(self):
        responding = False
        try:
            async for raw in self.ai:
                ev = json.loads(raw)
                t = ev.get("type", "")
                if t in ("response.output_audio.delta", "response.audio.delta"):
                    responding = True
                    pcm = base64.b64decode(ev["delta"])
                    await self.out.push(resample(pcm, AI_RATE, self.rate))
                elif t in ("response.output_audio.done", "response.audio.done"):
                    await self.out.flush()
                elif t == "input_audio_buffer.speech_started":
                    # Barge-in: the caller talks while the bot is generating OR while Exotel is still
                    # playing audio we already sent. Drop queued audio at Exotel; cancel generation if any.
                    if responding or self.bot_audible():
                        log.info("✋ barge-in (generating=%s)", responding)
                        await self.clear()
                        if responding:
                            await self.ai.send(json.dumps({"type": "response.cancel"}))
                        responding = False
                elif t in ("response.output_audio_transcript.done", "response.audio_transcript.done"):
                    self.note("AGENT", ev.get("transcript", ""))
                elif t == "conversation.item.input_audio_transcription.completed":
                    self.note("CALLER", ev.get("transcript", ""))
                elif t == "response.function_call_arguments.done":
                    await self.on_tool(ev)
                elif t == "response.done":
                    responding = False
                    await self.out.flush()
                    if self.hangup_after_audio:
                        self.hangup_after_audio = False
                        await self.send_mark("hangup")
                elif t == "error":
                    err = ev.get("error", {})
                    if err.get("code") != "response_cancel_not_active":
                        log.error("OpenAI error: %s", err)
        except websockets.ConnectionClosed:
            pass
        except Exception as e:
            log.exception("ai loop crashed: %s", e)

    async def on_tool(self, ev):
        name, call_id = ev.get("name"), ev.get("call_id")
        try:
            args = json.loads(ev.get("arguments") or "{}")
        except Exception:
            args = {}
        log.info("🛠  tool %s %s", name, args)
        if name == "end_call":
            self.hangup_after_audio = True
            result = {"ok": True, "note": "Say a short goodbye now; the call ends after it plays."}
        else:
            result = await save_record(name, self.call, args)
        await self.ai.send(json.dumps({"type": "conversation.item.create", "item": {
            "type": "function_call_output", "call_id": call_id, "output": json.dumps(result)}}))
        await self.ai.send(json.dumps({"type": "response.create"}))

    async def close(self):
        if self.closed:
            return
        if self.route == "echo":
            try:
                await self.out.flush()
            except Exception:
                pass
        self.closed = True
        log.info("📊 call=%s mode=%s audio in=%d B out=%d B first_audio_ms=%s", self.call.get("call_sid"), self.route,
                 self.bytes_in, self.bytes_out,
                 None if self.first_audio_at is None or self.started_at is None else round((self.first_audio_at - self.started_at) * 1000))
        if self.ai_open_task:
            if not self.ai_open_task.done():
                self.ai_open_task.cancel()
            elif not self.ai_open_task.cancelled() and self.ai_open_task.exception() is None:
                pre_ws = self.ai_open_task.result()[1]
                if pre_ws is not self.ai:  # pre-connected but unused (e.g. custom param chose echo)
                    await pre_ws.close()
        if self.ai:
            await self.ai.close()
        if self.ai_task:
            self.ai_task.cancel()
        if self.transcript:
            sid = self.call.get("call_sid") or f"call-{int(time.time())}"
            with open(f"logs/{sid}.txt", "w") as f:
                f.write("\n".join(self.transcript) + "\n")
        try:
            await self.ws.close()
        except Exception:
            pass
        log.info("✅ call closed (%s)", self.call.get("call_sid"))


END_CALL_TOOL = {
    "type": "function", "name": "end_call",
    "description": "End the phone call once the conversation is complete and you have said goodbye.",
    "parameters": {"type": "object", "properties": {}, "required": []},
}


async def handler(ws):
    path = getattr(getattr(ws, "request", None), "path", None) or getattr(ws, "path", "/")
    session = CallSession(ws, path)
    try:
        await session.run()
    except websockets.ConnectionClosed:
        await session.close()
    except Exception as e:
        log.exception("session error: %s", e)
        await session.close()


async def main():
    mode = "OpenAI Realtime + echo" if OPENAI_API_KEY else "echo only (set OPENAI_API_KEY for AI)"
    log.info("🚀 Exotel voice agent on ws://0.0.0.0:%s  [%s]", PORT, mode)
    log.info("   paths: /echo /receptionist /booking /lead   webhook=%s", WEBHOOK_URL or "off")
    async with websockets.serve(handler, "0.0.0.0", PORT, max_size=None, ping_interval=20):
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
