# -*- coding: utf-8 -*-
"""Usage profile of a device snapshot: what real operation actually looks like.

    python testing-logs/tools/profile_usage.py testing-logs/raw/<snapshot>/photoflow.db "label"

Reports integrity across the whole history, images per session, capture cadence, session
interleaving and leftover active sessions. Written to answer "what should we actually be
testing?" rather than "did this session pass" -- see the evidence-based priorities in
TESTING.md, which came out of running this on both handsets.
"""
import sqlite3
import sys
from datetime import datetime

db = sys.argv[1]
name = sys.argv[2] if len(sys.argv) > 2 else db
con = sqlite3.connect(db)
con.row_factory = sqlite3.Row


def ts(ms):
    return "-" if ms is None else datetime.fromtimestamp(ms / 1000.0).strftime("%m-%d %H:%M:%S")


print("############ %s ############" % name)

sess = {r["id"]: r for r in con.execute("SELECT * FROM sessions")}
imgs = list(con.execute("SELECT * FROM session_images ORDER BY timestamp"))
print("sessions=%d images=%d" % (len(sess), len(imgs)))
if imgs:
    print("span: %s .. %s" % (ts(imgs[0]["timestamp"]), ts(imgs[-1]["timestamp"])))

# ---- integrity across the whole history -------------------------------------
by = {}
for r in imgs:
    by.setdefault(r["sessionId"], []).append(r)

dup_sess, gap_sess, mismatch = [], [], []
for sid, rs in by.items():
    seqs = [r["captureSequence"] for r in rs if r["captureSequence"] is not None]
    if seqs:
        if len({x for x in seqs if seqs.count(x) > 1}):
            dup_sess.append(sid)
        if [n for n in range(min(seqs), max(seqs) + 1) if n not in seqs]:
            gap_sess.append(sid)
    bc = sess.get(sid, {})
    bc = bc["barcode"] if bc else None
    for r in rs:
        if bc and bc not in (r["filename"] or ""):
            mismatch.append(r)
print("\n-- integrity (all time) --")
print("  sessions with duplicate seq: %s" % ([sess[s]['barcode'] for s in dup_sess] or "none"))
print("  sessions with gapped seq:    %s" % ([sess[s]['barcode'] for s in gap_sess] or "none"))
print("  filename/session mismatches: %d" % len(mismatch))
states = {}
for r in imgs:
    states[r["uploadState"]] = states.get(r["uploadState"], 0) + 1
print("  upload states ever seen:     %s" % states)
print("  max retryCount ever:         %d" % max([(r["retryCount"] or 0) for r in imgs], default=0))
errs = [r for r in imgs if r["errorMessage"]]
print("  rows with an errorMessage:   %d" % len(errs))
for r in errs[:5]:
    print("      %s  %s" % (r["filename"], r["errorMessage"][:60]))

# ---- what real sessions look like -------------------------------------------
sizes = sorted(len(v) for v in by.values())
print("\n-- images per session (n=%d) --" % len(sizes))
print("  min=%d  median=%d  max=%d" % (sizes[0], sizes[len(sizes) // 2], sizes[-1]))
buckets = {"1": 0, "2-3": 0, "4-10": 0, "11+": 0}
for n in sizes:
    if n == 1: buckets["1"] += 1
    elif n <= 3: buckets["2-3"] += 1
    elif n <= 10: buckets["4-10"] += 1
    else: buckets["11+"] += 1
print("  distribution: %s" % buckets)

# ---- capture cadence --------------------------------------------------------
gaps = []
prev = {}
for r in imgs:
    p = prev.get(r["sessionId"])
    if p:
        gaps.append((r["timestamp"] - p["timestamp"]) / 1000.0)
    prev[r["sessionId"]] = r
if gaps:
    gaps_s = sorted(gaps)
    print("\n-- interval between consecutive shots in a session (n=%d) --" % len(gaps))
    print("  min=%.1fs  median=%.1fs  max=%.0fs" % (
        gaps_s[0], gaps_s[len(gaps_s) // 2], gaps_s[-1]))
    print("  under 1s: %d    under 3s: %d    under 10s: %d" % (
        len([g for g in gaps if g < 1]), len([g for g in gaps if g < 3]),
        len([g for g in gaps if g < 10])))

# ---- session switching: did capture interleave between sessions? ------------
print("\n-- session interleaving (capture returning to an earlier session) --")
order = []
for r in imgs:
    if not order or order[-1] != r["sessionId"]:
        order.append(r["sessionId"])
revisits = [s for i, s in enumerate(order) if s in order[:i]]
print("  capture switched session %d times; %d were a RETURN to an earlier session"
      % (max(0, len(order) - 1), len(revisits)))
for s in dict.fromkeys(revisits):
    print("      returned to: %s" % (sess[s]["barcode"] if s in sess else s))

# ---- leftover state ---------------------------------------------------------
act = [r for r in sess.values() if r["status"] == "active"]
print("\n-- active sessions right now: %d --" % len(act))
for r in act:
    n = len(by.get(r["id"], []))
    print("      %s  started %s  images=%d" % (r["barcode"], ts(r["startTime"]), n))
con.close()
