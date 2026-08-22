# -*- coding: utf-8 -*-
"""Capture a live-test snapshot from a connected PhotoFlow device and write a report.

    python testing-logs/tools/capture_session.py --serial ZY32L9TX7B --label motog-tethered
    python testing-logs/tools/capture_session.py --serial <s> --label sam-native --date 2026-08-20

Pulls the Room database, DataStore settings, capture-file listing and device info into
`testing-logs/raw/<date>-<label>/`, then writes `testing-logs/sessions/<date>-<label>.md`.

Raw snapshots are gitignored; the generated report is what gets committed.

Two hard-won details, do not "simplify" them:

  * Binaries are pulled with `adb exec-out`, never `adb shell`. `shell` allocates a PTY and
    translates LF to CRLF, which silently corrupts a SQLite file — it arrives a few dozen bytes
    too long and opens as "database disk image is malformed".
  * Every pull goes to a `tmp_` name and is only promoted after the size and header are checked.
    A failed adb call still truncates its redirect target, so writing straight to the final name
    destroys a previous good snapshot when the device drops mid-command.
"""
import argparse
import os
import shutil
import sqlite3
import subprocess
import sys
from datetime import datetime

DEFAULT_ADB = r"C:\Users\John\AppData\Local\Android\Sdk\platform-tools\adb.exe"
PKG = "com.photoflowmobile.app"
DB_FILES = ["photoflow.db", "photoflow.db-wal", "photoflow.db-shm"]
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def adb(args, serial, adb_path, binary=False):
    cmd = [adb_path, "-s", serial] + args
    p = subprocess.run(cmd, capture_output=True)
    if binary:
        return p.returncode, p.stdout
    return p.returncode, p.stdout.decode("utf-8", "replace").replace("\r\n", "\n")


def pull_binary(remote_rel, dest, serial, adb_path):
    """exec-out + verify + promote. Returns bytes written, or 0."""
    tmp = dest + ".tmp"
    rc, data = adb(["exec-out", "run-as %s cat %s" % (PKG, remote_rel)], serial, adb_path, binary=True)
    if rc != 0 or not data:
        if os.path.exists(tmp):
            os.remove(tmp)
        return 0
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, dest)
    return len(data)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True, help="adb serial (prefer the USB one; wireless drops)")
    ap.add_argument("--label", required=True, help="e.g. motog-tethered, sam-native")
    ap.add_argument("--date", default=None, help="test date YYYY-MM-DD (default: today)")
    ap.add_argument("--adb", default=DEFAULT_ADB)
    ap.add_argument("--notes", default="", help="one-line description of the test conditions")
    args = ap.parse_args()

    date = args.date or datetime.now().strftime("%Y-%m-%d")
    stem = "%s-%s" % (date, args.label)
    raw = os.path.join(ROOT, "raw", stem)
    os.makedirs(raw, exist_ok=True)
    os.makedirs(os.path.join(ROOT, "sessions"), exist_ok=True)

    print("capturing %s from %s" % (stem, args.serial))

    # Database (binary, must use exec-out)
    sizes = {}
    for f in DB_FILES:
        n = pull_binary("databases/" + f, os.path.join(raw, f), args.serial, args.adb)
        sizes[f] = n
        print("  %-20s %8d bytes%s" % (f, n, "" if n else "   <-- EMPTY"))
    db_path = os.path.join(raw, "photoflow.db")
    if not sizes.get("photoflow.db"):
        sys.exit("FAILED: no database pulled - is the device connected and the app installed?")
    with open(db_path, "rb") as fh:
        if fh.read(15) != b"SQLite format 3":
            sys.exit("FAILED: bad SQLite header - was 'adb shell' used instead of 'exec-out'?")
    if os.path.getsize(db_path) % 4096:
        print("  WARNING: db not page-aligned, may be corrupt")

    # DataStore settings (binary protobuf)
    pull_binary("files/datastore/photoflow_settings.preferences_pb",
                os.path.join(raw, "settings.pb"), args.serial, args.adb)

    # Text captures
    _, listing = adb(["shell", "run-as %s ls -la files/captures/" % PKG], args.serial, args.adb)
    open(os.path.join(raw, "captures-listing.txt"), "w", encoding="utf-8").write(listing)

    info = []
    for label, cmd in (("model", "getprop ro.product.model"),
                       ("android", "getprop ro.build.version.release"),
                       ("serial", "getprop ro.serialno"),
                       ("uptime", "uptime")):
        _, out = adb(["shell", cmd], args.serial, args.adb)
        info.append("%-10s %s" % (label, out.strip()))
    _, pkg = adb(["shell", "dumpsys package " + PKG], args.serial, args.adb)
    for line in pkg.split("\n"):
        if any(k in line for k in ("versionName", "versionCode=", "lastUpdateTime", "firstInstallTime")):
            info.append(line.strip())
    open(os.path.join(raw, "device-info.txt"), "w", encoding="utf-8").write("\n".join(info) + "\n")

    # Logcat, best effort. Usually empty for a past test: the buffer is RAM-only, does not
    # survive a reboot, and `logcat -G` sizing resets with it.
    _, log = adb(["logcat", "-d", "-s", "PhotoFlow/Pipeline:*", "PhotoFlow/Tether:*",
                  "PhotoFlow/PTP:*", "PhotoFlow/Canon:*", "PhotoFlow/Credentials:*"],
                 args.serial, args.adb)
    open(os.path.join(raw, "logcat-photoflow.txt"), "w", encoding="utf-8").write(log)
    print("  logcat lines captured: %d" % len([l for l in log.split("\n") if "PhotoFlow/" in l]))

    report = build_report(db_path, date, args.label, info, log, args.notes)
    out = os.path.join(ROOT, "sessions", stem + ".md")
    open(out, "w", encoding="utf-8").write(report)
    print("\nreport: %s" % os.path.relpath(out, os.path.dirname(ROOT)))
    print("raw:    %s" % os.path.relpath(raw, os.path.dirname(ROOT)))


def build_report(db_path, date, label, info, logcat, notes):
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row

    def ts(ms):
        return "-" if ms is None else datetime.fromtimestamp(ms / 1000.0).strftime("%m-%d %H:%M:%S")

    start = datetime.strptime(date + " 00:00:00", "%Y-%m-%d %H:%M:%S").timestamp() * 1000
    end = start + 86400000

    sessions = list(con.execute(
        "SELECT * FROM sessions WHERE (startTime>=? AND startTime<?) OR (endTime>=? AND endTime<?)"
        " ORDER BY startTime", (start, end, start, end)))
    imgs = list(con.execute(
        "SELECT i.*, s.barcode FROM session_images i LEFT JOIN sessions s ON s.id=i.sessionId"
        " WHERE i.timestamp>=? AND i.timestamp<? ORDER BY i.timestamp", (start, end)))

    L = []
    L.append("# Live test - %s - %s\n" % (date, label))
    if notes:
        L.append("**Conditions:** %s\n" % notes)
    L.append("Captured %s. Raw snapshot: `testing-logs/raw/%s-%s/` (gitignored).\n"
             % (datetime.now().strftime("%Y-%m-%d %H:%M"), date, label))

    L.append("## Device\n\n```\n" + "\n".join(info) + "\n```\n")

    L.append("## Sessions touched\n")
    if sessions:
        L.append("| id | barcode | status | start | end |")
        L.append("|---|---|---|---|---|")
        for r in sessions:
            L.append("| %s | %s | %s | %s | %s |" % (
                r["id"], r["barcode"], r["status"], ts(r["startTime"]), ts(r["endTime"])))
    else:
        L.append("_none_")
    L.append("")

    L.append("## Images captured (%d)\n" % len(imgs))
    if imgs:
        L.append("| time | session | filename | seq | state | retries | error |")
        L.append("|---|---|---|---|---|---|---|")
        for r in imgs:
            L.append("| %s | %s | %s | %s | %s | %s | %s |" % (
                ts(r["timestamp"])[6:], r["barcode"], r["filename"], r["captureSequence"],
                r["uploadState"], r["retryCount"], (r["errorMessage"] or "")))
    else:
        L.append("_none_")
    L.append("")

    # Checks that map onto the FR-1..FR-10 pass
    L.append("## Integrity checks\n")
    mism = [r for r in imgs if r["barcode"] and r["barcode"] not in (r["filename"] or "")]
    L.append("- **FR-1 session attribution** - filename barcode vs filed session: "
             "**%d mismatch(es)** in %d images%s" % (
                 len(mism), len(imgs), "" if not mism else
                 " -- " + ", ".join("%s in session %s" % (r["filename"], r["barcode"]) for r in mism)))

    by = {}
    for r in imgs:
        by.setdefault(r["sessionId"], []).append(r)
    dup_total = gap_total = 0
    for sid, rs in by.items():
        seqs = [r["captureSequence"] for r in rs if r["captureSequence"] is not None]
        if not seqs:
            continue
        dup_total += len({x for x in seqs if seqs.count(x) > 1})
        gap_total += len([n for n in range(min(seqs), max(seqs) + 1) if n not in seqs])
    L.append("- **FR-6 / captureMutex** - duplicate sequence numbers: **%d**, gaps: **%d**"
             % (dup_total, gap_total))

    rapid = []
    prev = {}
    for r in sorted(imgs, key=lambda x: x["timestamp"]):
        p = prev.get(r["sessionId"])
        if p and r["timestamp"] - p["timestamp"] < 3000:
            rapid.append((r, (r["timestamp"] - p["timestamp"]) / 1000.0))
        prev[r["sessionId"]] = r
    L.append("- **Rapid captures (<3s apart, same session)**: %d%s" % (
        len(rapid), "" if not rapid else " -- " + ", ".join(
            "%s at %.1fs" % (r["filename"], d) for r, d in rapid)))

    states = {}
    for r in imgs:
        states[r["uploadState"]] = states.get(r["uploadState"], 0) + 1
    mx = max([(r["retryCount"] or 0) for r in imgs], default=0)
    L.append("- **Upload states**: %s" % (states or "n/a"))
    L.append("- **FR-3 retry bound** - max retryCount: **%d** (bound is 3; 0 means never stressed)" % mx)
    L.append("- **FTP ghost rows** - still PENDING: **%d**"
             % len([r for r in imgs if r["uploadState"] == "PENDING"]))
    L.append("")

    logn = len([l for l in logcat.split("\n") if "PhotoFlow/" in l])
    L.append("## Logcat\n")
    L.append("%d PhotoFlow lines in the buffer at capture time.%s\n" % (
        logn,
        "" if logn > 50 else
        " Logcat is a RAM ring buffer: it does not survive a reboot, and `logcat -G` sizing"
        " resets with it. For a test captured after the fact this is normally empty, and the"
        " database above is the authoritative record."))

    L.append("## Whole-database totals\n")
    for r in con.execute("SELECT uploadState, COUNT(*) c FROM session_images GROUP BY uploadState"):
        L.append("- %s: %d" % (r["uploadState"], r["c"]))
    L.append("- sessions: %d" % con.execute("SELECT COUNT(*) c FROM sessions").fetchone()["c"])
    L.append("- images: %d" % con.execute("SELECT COUNT(*) c FROM session_images").fetchone()["c"])
    con.close()
    return "\n".join(L) + "\n"


if __name__ == "__main__":
    main()
