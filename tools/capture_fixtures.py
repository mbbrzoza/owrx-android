#!/usr/bin/env python3
"""Capture OpenWebRX+ WebSocket protocol fixtures for JVM unit tests.

Connects as a receiver client, performs the handshake, and records:
  - config.json          first full config message
  - profiles.json        profiles list
  - messages.jsonl       all text messages
  - fft_frame_N.bin      first 5 FFT payloads (type 1, without leading type byte)
  - audio_stream.bin     concatenated audio payloads (type 2, without type byte)

Usage: capture_fixtures.py [url] [seconds] [outdir]
"""
import asyncio
import json
import sys
from pathlib import Path

import websockets


async def capture(url: str, seconds: float, outdir: Path):
    outdir.mkdir(parents=True, exist_ok=True)
    fft_count = 0
    audio = bytearray()
    messages = []
    config = None
    profiles = None

    async with websockets.connect(url, max_size=2**24) as ws:
        await ws.send("SERVER DE CLIENT client=fixture-capture type=receiver")
        loop = asyncio.get_event_loop()
        deadline = loop.time() + seconds
        started_dsp = False
        while loop.time() < deadline:
            try:
                msg = await asyncio.wait_for(ws.recv(), timeout=max(0.1, deadline - loop.time()))
            except asyncio.TimeoutError:
                break
            if isinstance(msg, str):
                if msg.startswith("CLIENT DE SERVER"):
                    print("handshake:", msg)
                    await ws.send(json.dumps({"type": "connectionproperties",
                                              "params": {"output_rate": 12000, "hd_output_rate": 48000}}))
                    await ws.send(json.dumps({"type": "dspcontrol", "action": "start"}))
                    started_dsp = True
                    continue
                messages.append(msg)
                try:
                    obj = json.loads(msg)
                except json.JSONDecodeError:
                    continue
                if obj.get("type") == "config" and config is None:
                    config = obj
                    (outdir / "config.json").write_text(json.dumps(obj, indent=2))
                elif obj.get("type") == "profiles" and profiles is None:
                    profiles = obj
                    (outdir / "profiles.json").write_text(json.dumps(obj, indent=2))
            else:
                ftype = msg[0]
                payload = msg[1:]
                if ftype == 1 and fft_count < 5:
                    (outdir / f"fft_frame_{fft_count}.bin").write_bytes(payload)
                    fft_count += 1
                elif ftype == 2:
                    audio.extend(payload)

    (outdir / "messages.jsonl").write_text("\n".join(messages))
    (outdir / "audio_stream.bin").write_bytes(bytes(audio))
    print(f"captured: {fft_count} fft frames, {len(audio)} audio bytes, "
          f"{len(messages)} text messages, config={'yes' if config else 'NO'}")


if __name__ == "__main__":
    url = sys.argv[1] if len(sys.argv) > 1 else "wss://sdr.inter1.pl/ws/"
    seconds = float(sys.argv[2]) if len(sys.argv) > 2 else 15.0
    outdir = Path(sys.argv[3]) if len(sys.argv) > 3 else Path(__file__).parent.parent / "app/src/test/resources"
    asyncio.run(capture(url, seconds, outdir))
