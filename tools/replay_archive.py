#!/usr/bin/env python3
"""Replay local RHA telemetry into the debug Android emulator. Standard library only."""
import argparse
import json
import math
import os
from pathlib import Path
import shutil
import struct
import subprocess
import time
import zlib

PACKAGE = "at.themrcodes.ridershub"
COMPONENT = PACKAGE + "/.EmulatorTelemetryReceiver"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_archive(path):
    data = path.read_bytes()
    require(len(data) >= 56, "Truncated RHA header")
    magic, version, header, _ = struct.unpack_from(">4siii", data)
    _, original, compressed, entries, crc = struct.unpack_from(">qqqII", data, 24)
    require((magic, version, header) == (b"RHA1", 1, 56), "Unsupported RHA format")
    require(len(data) == 56 + compressed, "RHA length mismatch")
    payload = zlib.decompress(data[56:])
    require(len(payload) == original and zlib.crc32(payload) == crc, "RHA checksum mismatch")
    require(len(payload) >= 24 and payload[:4] == b"RHP1", "Invalid RHP header")
    offset = 24
    sessions = []
    while offset < len(payload):
        magic, version, header, entry_id, size, count, crc, _ = struct.unpack_from(
            ">4sHH16sqIIq", payload, offset)
        require((magic, version, header) == (b"RHE1", 1, 48) and size >= 0, "Invalid entry header")
        end = offset + header + size
        body = payload[offset + header:end]
        require(zlib.crc32(body) == crc, "Entry checksum mismatch")
        magic, commit_id, length, crc = struct.unpack_from(">4s16sqI", payload, end)
        require(magic == b"RHC1" and commit_id == entry_id and length == header + size
                and zlib.crc32(payload[offset:end]) == crc, "Invalid entry commit")
        rows = [json.loads(line) for line in body.splitlines()]
        require(len(rows) == count and rows and rows[-1]["type"] == "session_end",
                "Incomplete session")
        require(all(a["seq"] < b["seq"] for a, b in zip(rows, rows[1:])), "Invalid event sequence")
        sessions.append(rows)
        offset = end + 32
    require(len(sessions) == entries and offset == len(payload), "Entry count mismatch")
    return sessions


def frame_rows(session):
    return [row for row in session if row.get("type") == "telemetry_frame"]


def prepare(session, offset=0, duration=None, speed=1):
    """Preserve byte order/cadence; compress long disconnected gaps to at most 3 s."""
    require(math.isfinite(speed) and 0.1 <= speed <= 20, "Speed must be 0.1..20")
    require(math.isfinite(offset) and offset >= 0, "Offset must be nonnegative")
    require(duration is None or math.isfinite(duration) and duration > 0, "Duration must be positive")
    frames = frame_rows(session)
    require(frames, "Session has no telemetry")
    previous = None
    result = []
    # Wall time is used for selection across possible device reboots; elapsed time sets cadence.
    from datetime import datetime
    start = datetime.fromisoformat(frames[0]["wall_time"].replace("Z", "+00:00"))
    for frame in frames:
        at = (datetime.fromisoformat(frame["wall_time"].replace("Z", "+00:00")) - start).total_seconds()
        if at < offset or duration is not None and at >= offset + duration:
            continue
        raw = bytes.fromhex(frame["raw_hex"])
        require(23 <= len(raw) <= 64 and raw[0] == 0xAC and raw[2] == len(raw),
                "Malformed archived frame")
        now = frame["elapsed_realtime_ns"]
        delta = (now - previous) / 1e6 if previous is not None else 0
        # Preserve a discontinuity instead of connecting samples across a reconnect/reboot.
        delay = 0 if previous is None else (
            3000 if delta <= 0 or delta > 2000 else max(1, round(delta / speed)))
        result.append({"delay_ms": delay, "hex": raw.hex()})
        previous = now
    require(result and len(result) <= 100_000, "Select between 1 and 100000 frames")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", nargs="?", type=Path, help="Local .rha file or archive directory")
    parser.add_argument("--list", action="store_true", help="List anonymous session indices without deploying")
    parser.add_argument("--session", type=int, help="1-based session index; default: most telemetry frames")
    parser.add_argument("--offset", type=float, default=0, help="Start offset in recorded seconds")
    parser.add_argument("--duration", type=float, help="Recorded seconds to replay")
    parser.add_argument("--speed", type=float, default=1, help="Playback multiplier, 0.1..20 (default 1)")
    parser.add_argument("--serial", help="Explicit emulator serial")
    parser.add_argument("--board", type=int, default=3, choices=range(1, 240), metavar="1..239",
                        help="Synthetic board slot; default 3, slots 1/2 reserved for smoke tests")
    parser.add_argument("--adb", default=shutil.which("adb") or str(
        Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk"))) / "platform-tools/adb"))
    parser.add_argument("--wait", action="store_true", help="Wait for completion; Ctrl-C stops replay")
    parser.add_argument("--stop", action="store_true", help="Stop and finalize the active emulator replay")
    args = parser.parse_args()

    sessions = []
    if not args.stop:
        require(args.archive is not None, "Provide an archive path")
        paths = sorted(args.archive.glob("*.rha")) if args.archive.is_dir() else [args.archive]
        require(paths, "No RHA archives found")
        for path in paths:
            sessions.extend(read_archive(path))
        sessions.sort(key=lambda s: s[0]["wall_time"])
        sessions = [s for s in sessions if frame_rows(s)]
        require(sessions, "No archived telemetry sessions")
        if args.list:
            for i, session in enumerate(sessions, 1):
                rows = frame_rows(session)
                # No source identifiers, absolute activity times, or odometers in console output.
                print(f"Session {i}: {len(rows)} frames")
            return
        index = args.session - 1 if args.session is not None else max(
            range(len(sessions)), key=lambda i: len(frame_rows(sessions[i])))
        require(0 <= index < len(sessions), "Session index out of range")
        replay = prepare(sessions[index], args.offset, args.duration, args.speed)

    require(args.serial is not None and args.serial.startswith("emulator-"), "Specify --serial emulator-N")
    def adb(*parts, data=None):
        return subprocess.run([args.adb, "-s", args.serial, *parts], input=data,
                              capture_output=True, check=True).stdout
    require(adb("shell", "getprop", "ro.kernel.qemu").strip() == b"1", "Target is not an emulator")
    require(adb("shell", "getprop", "ro.hardware").strip() in (b"ranchu", b"goldfish"),
            "Unsupported emulator hardware")
    adb("shell", "run-as", PACKAGE, "pwd")  # Debuggable installed application required.
    def command(name):
        response = adb("shell", "am", "broadcast", "-n", COMPONENT,
                       "--es", "command", name, "--ei", "board", str(args.board))
        require(b"Broadcast completed" in response, "Debug receiver broadcast failed")

    command("finalize")
    if args.stop:
        print("Replay stopped and ride finalized.")
        return
    encoded = "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in replay).encode()
    require(len(encoded) <= 16_000_000, "Replay file too large")
    adb("shell", "run-as", PACKAGE, "tee", "files/emulator-replay.jsonl", data=encoded)
    adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
    command("replay")
    time.sleep(0.5)
    def status():
        return json.loads(adb("shell", "run-as", PACKAGE, "cat", "files/emulator-telemetry-status.json"))
    current = status()
    require(current["status"] in ("replaying", "replay_complete"), "Receiver did not start replay")
    seconds = sum(row["delay_ms"] for row in replay) / 1000
    print(f"Replaying session {index + 1}: {len(replay)} frames, about {seconds:.1f}s on emulator.", flush=True)
    print("Uses current app state; speed changes affect time-based estimators. Source archives stay local.", flush=True)
    if args.wait:
        deadline = time.monotonic() + seconds * 2 + 60
        try:
            while current["status"] == "replaying":
                require(time.monotonic() < deadline, "Replay timed out")
                time.sleep(1)
                current = status()
            require(current["status"] == "replay_complete" and current["emitted"] == len(replay),
                    "Replay interrupted or frame count mismatch")
            print(f"Completed: {current['emitted']} frames; ride finalized.")
        except (KeyboardInterrupt, ValueError):
            command("finalize")
            raise


if __name__ == "__main__":
    main()
