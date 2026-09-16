"""Synthetic fixtures only: never embed archived rides in public tests."""
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

from replay_archive import prepare, read_archive


def frame(seconds, raw=None):
    return {
        "type": "telemetry_frame", "seq": seconds + 1,
        "wall_time": f"2030-01-01T00:00:{seconds:02d}Z",
        "elapsed_realtime_ns": seconds * 1_000_000_000,
        "raw_hex": raw or (bytes([0xAC, 6, 25]) + bytes(22)).hex(),
    }


def archive(rows):
    body = b"\n".join(json.dumps(row).encode() for row in rows)
    header = struct.pack(">4sHH16sqIIq", b"RHE1", 1, 48, bytes(16),
                         len(body), len(rows), zlib.crc32(body), 0)
    entry = header + body
    commit = struct.pack(">4s16sqI", b"RHC1", bytes(16), len(entry), zlib.crc32(entry))
    payload = b"RHP1" + bytes(20) + entry + commit
    compressed = zlib.compress(payload)
    header = struct.pack(">4siii", b"RHA1", 1, 56, 1) + bytes(8)
    header += struct.pack(">qqqII", 0, len(payload), len(compressed), 1, zlib.crc32(payload))
    return header + compressed


class ReplayTest(unittest.TestCase):
    def test_archive_integrity_and_session_read(self):
        rows = [frame(0), frame(1), {"type": "session_end", "seq": 3}]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.rha"
            path.write_bytes(archive(rows))
            self.assertEqual([rows], read_archive(path))
            damaged = bytearray(path.read_bytes())
            damaged[55] ^= 1  # Outer checksum, with valid compression.
            path.write_bytes(damaged)
            with self.assertRaisesRegex(ValueError, "checksum"):
                read_archive(path)

    def test_bytes_selection_speed_and_gap(self):
        rows = [frame(0), frame(1), frame(2), frame(10)]
        result = prepare(rows, offset=1, speed=2)
        self.assertEqual([0, 500, 3000], [r["delay_ms"] for r in result])
        self.assertEqual(rows[1]["raw_hex"], result[0]["hex"])
        self.assertEqual(2, len(prepare(rows, offset=1, duration=2)))

    def test_reboot_is_discontinuity(self):
        rows = [frame(1), frame(2)]
        rows[1]["elapsed_realtime_ns"] = 0
        self.assertEqual(3000, prepare(rows)[1]["delay_ms"])

    def test_reject_bad_input_before_adb(self):
        for speed in (0, float("nan"), float("inf"), 21):
            with self.assertRaises(ValueError):
                prepare([frame(0)], speed=speed)
        with self.assertRaises(ValueError):
            prepare([frame(0)], offset=2)
        with self.assertRaises(ValueError):
            prepare([frame(0, "00" * 25)])


if __name__ == "__main__":
    unittest.main()
