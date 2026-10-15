#!/usr/bin/env python3
"""Desktop probe: can the shipped needle runner hold a loaded model behind a FIFO?

Answers four questions with timestamps and RSS samples:
  1. Does the runner load the model before it waits for audio?
  2. Does the ordinary --audio path accept a FIFO (no --audio-stream needed)?
  3. Does it emit anything before EOF, or read the whole WAV first?
  4. What does an idle prepared runner cost, and how long after EOF is the result?

Desktop only. It never calls adb and never touches a device; the phone counterpart
belongs to the main agent. See README.md in this directory for the protocol.

Example:
  python3 tests/whistle/probe_fifo.py --runner <needle> --model <whistle.cact> --clip <clip.wav>
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time

POLL_SECONDS = 0.1
def write_all(fd, data, deadline, note=None):
    """Non-blocking write loop: a full pipe returns a short write or EAGAIN."""
    offset = 0
    while offset < len(data):
        try:
            offset += os.write(fd, data[offset:])
        except OSError as error:
            if error.errno not in (EAGAIN, EWOULDBLOCK):
                if note is not None:
                    note.append("write failed with errno %s" % error.errno)
                return offset
            time.sleep(0.002)
        if time.monotonic() > deadline:
            if note is not None:
                note.append("write timed out after %s of %s bytes" % (offset, len(data)))
            return offset
    return offset


EAGAIN = 11
EWOULDBLOCK = 35

ENXIO = 6


def rss_kib(pid):
    """Resident set size in KiB for a live process, or None."""
    try:
        result = subprocess.run(["ps", "-o", "rss=", "-p", str(pid)],
                                capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.SubprocessError):
        return None
    text = result.stdout.strip()
    return int(text) if text.isdigit() else None


def wav_data_offset(data):
    """Offset of the first audio byte and the declared data size of a RIFF WAV."""
    position = 12
    while position + 8 <= len(data):
        chunk = data[position:position + 4]
        size = int.from_bytes(data[position + 4:position + 8], "little")
        if chunk == b"data":
            return position + 8, size
        position += 8 + size + (size & 1)
    return 44, max(0, len(data) - 44)


def drain(stream, sink):
    """Move whatever is readable right now from a non-blocking stream into sink."""
    try:
        chunk = stream.read()
    except (BlockingIOError, InterruptedError, OSError, ValueError, TypeError):
        return
    if chunk:
        sink.append(chunk)


def runner_arguments(args, audio_path):
    parts = [args.runner, "--model", args.model, "--audio", audio_path,
             "--audio-language", args.language]
    if args.depth:
        parts += ["--audio-depth", str(args.depth)]
    if args.threads:
        parts += ["--threads", str(args.threads)]
    if args.mode == "stream":
        parts += ["--audio-stream"]
    return parts


def telemetry(output):
    """Last JSON object the runner printed, plus the transcript it carries."""
    last = None
    for line in output.splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                candidate = json.loads(line)
            except ValueError:
                continue
            if "text" in candidate:
                last = candidate
    return {"json": last, "text": None if last is None else last.get("text")}


def probe(args):
    audio = open(args.clip, "rb").read()
    data_offset, declared = wav_data_offset(audio)
    split = min(data_offset + max(1, args.split_bytes), len(audio))

    workdir = tempfile.mkdtemp(prefix="whistle-fifo-probe-")
    fifo = os.path.join(workdir, "audio.fifo")
    os.mkfifo(fifo, 0o600)

    record = {"mode": args.mode, "depth": args.depth, "threads": args.threads,
              "runner": args.runner, "model": args.model, "clip": args.clip,
              "clip_bytes": len(audio), "declared_data_bytes": declared,
              "header_bytes": data_offset, "fifo": fifo, "samples": [],
              "notes": [], "output": ""}
    phases = record["phases"] = {}

    started = time.monotonic()
    process = subprocess.Popen(runner_arguments(args, fifo), stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, bufsize=0)
    os.set_blocking(process.stdout.fileno(), False)
    record["pid"] = process.pid
    phases["spawn"] = 0.0

    # Phase 1: does the model become resident while the FIFO is still untouched?
    peak = 0
    previous = None
    stable_at = None
    ready_at = None
    while time.monotonic() - started < args.max_wait:
        if process.poll() is not None:
            record["notes"].append("exited before any audio was supplied")
            break
        current = rss_kib(process.pid)
        elapsed = round(time.monotonic() - started, 3)
        record["samples"].append({"t": elapsed, "rss_kib": current})
        if current:
            peak = max(peak, current)
            if previous is not None and abs(current - previous) <= args.stable_kib:
                if stable_at is None:
                    stable_at = elapsed
                elif elapsed - stable_at >= 0.5:
                    ready_at = elapsed
                    break
            else:
                stable_at = None
            previous = current
        time.sleep(POLL_SECONDS)
    phases["model_resident"] = ready_at
    record["rss_ready_kib"] = previous
    record["model_loaded_before_audio"] = bool(
        ready_at is not None and previous and previous > args.loaded_kib)

    # Phase 2: open the writer. Until now the runner has seen no writer at all.
    writer = None
    while time.monotonic() - started < args.max_wait * 3:
        if process.poll() is not None:
            record["notes"].append("exited while waiting for a writer")
            break
        try:
            writer = os.open(fifo, os.O_WRONLY | os.O_NONBLOCK)
            break
        except OSError as error:
            if error.errno != ENXIO:
                record["notes"].append("open failed with errno %s" % error.errno)
                break
            time.sleep(POLL_SECONDS)
    phases["writer_open"] = round(time.monotonic() - started, 3) if writer is not None else None
    record["fifo_writer_opened"] = writer is not None

    if writer is None:
        finish(process, record, phases, started, peak, args)
        return record

    # Phase 3: header plus part of the audio, then watch for output before EOF.
    notes = record["notes"]
    first = write_all(writer, audio[:split], time.monotonic() + args.max_wait, notes)
    phases["partial_audio_written"] = round(time.monotonic() - started, 3)
    record["partial_bytes"] = first
    output = []
    first_before_eof = None
    deadline = time.monotonic() + args.pre_eof_window
    while time.monotonic() < deadline:
        drain(process.stdout, output)
        if output:
            first_before_eof = round(time.monotonic() - started, 3)
            break
        if process.poll() is not None:
            break
        time.sleep(POLL_SECONDS)
    phases["first_output_before_eof"] = first_before_eof
    record["output_before_eof"] = first_before_eof is not None

    # Phase 4: rest of the audio, then EOF.
    second = write_all(writer, audio[split:], time.monotonic() + args.max_wait, notes)
    record["final_bytes"] = second
    record["bytes_written"] = first + second
    phases["audio_written"] = round(time.monotonic() - started, 3)
    os.close(writer)
    phases["eof"] = round(time.monotonic() - started, 3)

    # Phase 5: result and exit after EOF.
    first_after_eof = None
    deadline = time.monotonic() + args.max_wait
    while time.monotonic() < deadline:
        drain(process.stdout, output)
        if output and first_after_eof is None:
            first_after_eof = round(time.monotonic() - started, 3)
        if process.poll() is not None:
            break
        current = rss_kib(process.pid)
        if current:
            peak = max(peak, current)
        time.sleep(POLL_SECONDS)
    phases["first_output_after_eof"] = first_after_eof
    finish(process, record, phases, started, peak, args, output)
    return record


def finish(process, record, phases, started, peak, args, output=None):
    if output is None:
        output = []
        deadline = time.monotonic() + args.max_wait
        while process.poll() is None and time.monotonic() < deadline:
            drain(process.stdout, output)
            time.sleep(POLL_SECONDS)
    drain(process.stdout, output)
    record["output"] = b"".join(output).decode("utf-8", "replace")
    phases["exit"] = round(time.monotonic() - started, 3)
    record["exit_code"] = process.poll()
    if record["exit_code"] is None:
        process.kill()
        process.wait(timeout=5)
        record["exit_code"] = process.poll()
        record["notes"].append("killed after the wait budget expired")
    if peak:
        record["peak_rss_kib"] = peak
    record["telemetry"] = telemetry(record["output"])


def full_file_transcript(args):
    result = subprocess.run(runner_arguments(args, args.clip), capture_output=True,
                            text=True, timeout=args.max_wait * 6)
    return telemetry(result.stdout + result.stderr)


def main():
    parser = argparse.ArgumentParser(description="Probe the warm Whistle runner on a FIFO (desktop).")
    parser.add_argument("--runner", required=True, help="path to the needle executable")
    parser.add_argument("--model", required=True, help="path to whistle.cact")
    parser.add_argument("--clip", required=True, help="WAV clip to feed through the FIFO")
    parser.add_argument("--mode", choices=("plain", "stream"), default="plain",
                        help="plain runs ordinary --audio (default); stream adds --audio-stream")
    parser.add_argument("--depth", type=int, default=0, help="0 = model default (full)")
    parser.add_argument("--threads", type=int, default=0, help="0 = engine default")
    parser.add_argument("--language", default="en")
    parser.add_argument("--split-bytes", type=int, default=64000,
                        help="audio bytes written before the EOF check")
    parser.add_argument("--pre-eof-window", type=float, default=0.75,
                        help="seconds spent watching for output before EOF")
    parser.add_argument("--max-wait", type=float, default=10.0, help="seconds per bounded wait")
    parser.add_argument("--stable-kib", type=int, default=1024, help="RSS movement counted as settled")
    parser.add_argument("--loaded-kib", type=int, default=8192, help="RSS above this counts as model resident")
    parser.add_argument("--out", help="write the full record as JSON here")
    parser.add_argument("--compare-full-file", action="store_true",
                        help="also run the clip as a normal file and keep that transcript")
    args = parser.parse_args()

    record = probe(args)
    if args.compare_full_file:
        record["full_file"] = full_file_transcript(args)

    phases = record["phases"]
    print("mode=%s depth=%s threads=%s clip=%s" % (args.mode, args.depth or "full",
                                                   args.threads or "auto", os.path.basename(args.clip)))
    print("model resident before audio : %s (t=%ss rss=%s KiB)" % (record.get("model_loaded_before_audio"),
          phases.get("model_resident"), record.get("rss_ready_kib")))
    print("fifo writer accepted        : %s (t=%ss)" % (record.get("fifo_writer_opened"),
          phases.get("writer_open")))
    print("output before EOF           : %s (t=%ss)" % (record.get("output_before_eof"),
          phases.get("first_output_before_eof")))
    print("eof                         : t=%ss" % phases.get("eof"))
    print("first output after EOF      : t=%ss" % phases.get("first_output_after_eof"))
    print("exit                        : t=%ss code=%s" % (phases.get("exit"), record.get("exit_code")))
    print("peak RSS                    : %s KiB" % record.get("peak_rss_kib"))
    print("transcript                  : %s" % record["telemetry"]["text"])
    if args.compare_full_file:
        print("plain-file transcript       : %s" % record["full_file"]["text"])
    if record["notes"]:
        print("notes                       : %s" % "; ".join(record["notes"]))

    if args.out:
        directory = os.path.dirname(os.path.abspath(args.out))
        if directory:
            os.makedirs(directory, exist_ok=True)
        with open(args.out, "w") as handle:
            json.dump(record, handle, indent=2, sort_keys=True)
        print("wrote %s" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())

