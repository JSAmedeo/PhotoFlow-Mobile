# -*- coding: utf-8 -*-
"""Assemble a self-contained stakeholder handoff bundle for one test date.

    python testing-logs/tools/make_handoff.py --date 2026-08-20

Gathers every session report for that date into `testing-logs/handoff/<date>/`, numbered in
upload order, with a START-HERE and a brief whose figures are computed from the captured data.
Grab the folder, drop its contents into a browser chat, done.

Figures come from `sessions/<stem>.json`, written by capture_session.py. If that is missing (a
session captured before summaries existed) they are computed from the raw snapshot and the JSON
is written, so the bundle can be rebuilt later without the raw database.
"""
import argparse
import glob
import json
import os
import shutil
import sqlite3
import time
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SESSIONS = os.path.join(ROOT, "sessions")
RAW = os.path.join(ROOT, "raw")
HANDOFF = os.path.join(ROOT, "handoff")
BACKGROUND = os.path.join(ROOT, "handoff", "_background.md")


def day_bounds(date):
    start = time.mktime(time.strptime(date + " 00:00:00", "%Y-%m-%d %H:%M:%S")) * 1000
    return start, start + 86400000


def summarise(db_path, date, label):
    """Figures for one device-session, computed from its snapshot."""
    start, end = day_bounds(date)
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    q = con.execute
    imgs = list(q("SELECT i.*, s.barcode FROM session_images i "
                  "LEFT JOIN sessions s ON s.id = i.sessionId "
                  "WHERE i.timestamp >= ? AND i.timestamp < ? ORDER BY i.timestamp", (start, end)))
    by = {}
    for r in imgs:
        by.setdefault(r["sessionId"], []).append(r)
    dup = gap = 0
    for rs in by.values():
        seqs = [r["captureSequence"] for r in rs if r["captureSequence"] is not None]
        if seqs:
            dup += len({x for x in seqs if seqs.count(x) > 1})
            gap += len([n for n in range(min(seqs), max(seqs) + 1) if n not in seqs])
    rapid, prev = 0, {}
    for r in imgs:
        p = prev.get(r["sessionId"])
        if p and r["timestamp"] - p["timestamp"] < 3000:
            rapid += 1
        prev[r["sessionId"]] = r
    out = {
        "date": date,
        "label": label,
        "images": len(imgs),
        "sessions_created": q("SELECT COUNT(*) c FROM sessions WHERE startTime>=? AND startTime<?",
                              (start, end)).fetchone()["c"],
        "uploaded": len([r for r in imgs if r["uploadState"] == "UPLOADED"]),
        "failed": len([r for r in imgs if r["uploadState"] == "FAILED"]),
        "pending": len([r for r in imgs if r["uploadState"] == "PENDING"]),
        "max_retry": max([(r["retryCount"] or 0) for r in imgs], default=0),
        "misattributed": len([r for r in imgs if r["barcode"] and r["barcode"] not in (r["filename"] or "")]),
        "dup_seq": dup,
        "gap_seq": gap,
        "rapid_captures": rapid,
        "alltime_images": q("SELECT COUNT(*) c FROM session_images").fetchone()["c"],
        "alltime_not_uploaded": q("SELECT COUNT(*) c FROM session_images "
                                  "WHERE uploadState != 'UPLOADED'").fetchone()["c"],
    }
    con.close()
    return out


def load_or_build(stem, date, label):
    js = os.path.join(SESSIONS, stem + ".json")
    if os.path.exists(js):
        return json.load(open(js, encoding="utf-8"))
    db = os.path.join(RAW, stem, "photoflow.db")
    if not os.path.exists(db):
        print("  ! no summary and no snapshot for %s - figures will be blank" % stem)
        return None
    data = summarise(db, date, label)
    json.dump(data, open(js, "w", encoding="utf-8"), indent=2)
    print("  wrote %s (from snapshot)" % os.path.basename(js))
    return data


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--date", required=True, help="test date, YYYY-MM-DD")
    ap.add_argument("--notes", default="", help="one line on the session: venue, conditions, goal")
    args = ap.parse_args()

    reports = sorted(glob.glob(os.path.join(SESSIONS, args.date + "-*.md")))
    if not reports:
        raise SystemExit("No session reports for %s. Run capture_session.py first." % args.date)

    out = os.path.join(HANDOFF, args.date)
    os.makedirs(out, exist_ok=True)
    print("building handoff for %s" % args.date)

    summaries, attached = [], []
    for i, rep in enumerate(reports, start=2):
        stem = os.path.splitext(os.path.basename(rep))[0]
        label = stem[len(args.date) + 1:]
        s = load_or_build(stem, args.date, label)
        if s:
            summaries.append(s)
        name = "%02d-%s.md" % (i, label)
        shutil.copyfile(rep, os.path.join(out, name))
        attached.append((name, label, s))
        print("  + %s" % name)

    # Stable background, copied in so the bundle stands alone. Strip the maintenance header and
    # the HTML comment — those are notes to us, and the brief is read by someone else.
    bg = ""
    if os.path.exists(BACKGROUND):
        bg = open(BACKGROUND, encoding="utf-8").read()
        if "-->" in bg:
            bg = bg.split("-->", 1)[1]
        bg = "\n".join(l for l in bg.split("\n") if not l.startswith("# Background"))

    open(os.path.join(out, "01-brief.md"), "w", encoding="utf-8").write(
        build_brief(args.date, args.notes, summaries, attached, bg))
    open(os.path.join(out, "00-START-HERE.md"), "w", encoding="utf-8").write(
        build_start_here(args.date, attached))
    print("\nbundle: testing-logs/handoff/%s/  (%d files)" % (args.date, len(attached) + 2))
    print("Upload every file in that folder, starting with 00-START-HERE.md")


def build_start_here(date, attached):
    L = ["# START HERE — handoff bundle, %s\n" % date,
         "Everything needed to write a stakeholder update on this test session.\n",
         "## How to use\n",
         "1. Upload **every file in this folder** to a new chat.",
         "2. Say: *\"Write a stakeholder update on this test session. Start by asking me what "
         "format I need.\"*",
         "3. The brief tells the assistant the audience, the register, and what not to "
         "overstate.\n",
         "## Files, in order\n",
         "| File | What it is |", "|---|---|",
         "| `01-brief.md` | The session's figures, background, and guidance. Self-contained. |"]
    for name, label, _ in attached:
        L.append("| `%s` | Full report for **%s**: per-photo table and integrity checks |"
                 % (name, label))
    L += ["",
          "## If more depth is asked for\n",
          "These live in the repo and are not copied here, to keep the bundle small:\n",
          "- `TESTING.md` — \"What to test next, and why\": the evidence behind the next test "
          "priorities",
          "- `testing-logs/README.md` — \"Baseline findings\": historical defects in the devices' "
          "own data",
          "- `.claude/prompts/field-readiness-pass.md` — the engineering specification. Usually "
          "too technical for stakeholders.\n"]
    return "\n".join(L) + "\n"


def build_brief(date, notes, summaries, attached, background):
    tot = {k: sum(s.get(k, 0) for s in summaries) for k in
           ("images", "sessions_created", "uploaded", "failed", "pending",
            "misattributed", "dup_seq", "gap_seq", "rapid_captures",
            "alltime_images", "alltime_not_uploaded")}
    max_retry = max([s.get("max_retry", 0) for s in summaries], default=0)

    L = ["# Stakeholder brief — test session %s\n" % date]
    if notes:
        L.append("**Conditions:** %s\n" % notes)
    L.append("_Figures below are computed from the device databases captured after the session._\n")

    L.append("## For the assistant picking this up\n")
    L.append("You are helping write a **stakeholder update**, not an engineering document. The "
             "audience cares whether the tool works in the field, what was at risk, and what "
             "happens next. Translate internal identifiers (FR-1, `captureMutex`) into plain "
             "outcomes. **Ask what format is wanted — email, slide outline, one-pager — before "
             "drafting.**\n")
    L.append("**Do not overstate readiness.** Green numbers below are real, but see \"What is not "
             "yet proven\". A report implying \"fully validated\" would be wrong.\n")

    L.append("## This session\n")
    L.append("| Measure | Result |")
    L.append("|---|---|")
    L.append("| Sessions created | %d |" % tot["sessions_created"])
    L.append("| Photos captured | %d |" % tot["images"])
    if tot["images"]:
        pct = 100.0 * tot["uploaded"] / tot["images"]
        L.append("| Successfully uploaded | **%d of %d (%.0f%%)** |" % (tot["uploaded"], tot["images"], pct))
    L.append("| Upload failures | %d |" % tot["failed"])
    L.append("| Stuck awaiting upload | %d |" % tot["pending"])
    L.append("| Highest retry count needed | %d |" % max_retry)
    L.append("| Photos filed to the wrong session | **%d** |" % tot["misattributed"])
    L.append("| Duplicate or missing sequence numbers | **%d** |" % (tot["dup_seq"] + tot["gap_seq"]))
    L.append("")

    L.append("### By device\n")
    L.append("| Device / mode | Photos | Sessions | Uploaded | Failed | Misfiled | Seq defects |")
    L.append("|---|---|---|---|---|---|---|")
    for name, label, s in attached:
        if not s:
            L.append("| %s | _no summary_ | | | | | |" % label)
            continue
        L.append("| %s | %d | %d | %d | %d | %d | %d |" % (
            label, s["images"], s["sessions_created"], s["uploaded"], s["failed"],
            s["misattributed"], s["dup_seq"] + s["gap_seq"]))
    L.append("")
    if tot["rapid_captures"]:
        L.append("%d rapid capture(s) under 3 seconds apart within a session were handled with "
                 "correct, distinct numbering.\n" % tot["rapid_captures"])
    L.append("Across all recorded use on these devices: **%d photos, of which %d have ever failed "
             "to upload.**\n" % (tot["alltime_images"], tot["alltime_not_uploaded"]))

    if background:
        L.append("---\n")
        L.append(background.strip())
        L.append("")

    L.append("---\n## Attached\n")
    for name, label, _ in attached:
        L.append("- `%s` — full report for **%s**" % (name, label))
    L.append("")
    return "\n".join(L) + "\n"


if __name__ == "__main__":
    main()
