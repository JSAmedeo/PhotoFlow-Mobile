# Testing logs

Machine-captured evidence from live test sessions, in native and tethered mode.

`TESTING.md` in the repo root holds the **procedures and pass criteria** — what to do and what a
correct run looks like. This directory holds **what actually happened**: a snapshot of the device
state after a session, plus a generated report.

## Capturing a session

Connect the device (**USB, not wireless** — see below) and run:

```bash
python testing-logs/tools/capture_session.py \
    --serial ZY32L9TX7B \
    --label motog-tethered \
    --date 2026-08-20 \
    --notes "Field test, Canon T7 over USB PTP, venue WiFi"
```

`--date` defaults to today; pass it explicitly when capturing a test from an earlier day.
Suggested labels: `motog-tethered`, `sam-native`, `sam-tethered`, `motog-native`.

Output:

| Path | Committed? | Contents |
|---|---|---|
| `sessions/<date>-<label>.md` | yes | generated report — sessions, images, integrity checks |
| `raw/<date>-<label>/` | **no** (gitignored) | `photoflow.db` + WAL, `settings.pb`, capture listing, device info, logcat |

Raw snapshots stay out of git: they are binary, they grow with every session, and they contain
venue session barcodes and FTP connection details. Keep them locally for re-analysis; commit the
report.

**The `-wal` and `-shm` files disappear after a capture — this is normal.** They are pulled, then
generating the report opens the database, and SQLite checkpoints the WAL into the main file and
removes them on a clean close. The remaining `photoflow.db` is self-contained and holds everything;
verify with `PRAGMA integrity_check` if in doubt. They must still be pulled, though — without the
WAL, recent writes would be missing from the snapshot entirely.

## Building a stakeholder handoff

After capturing every device for a test date, bundle them:

```bash
python testing-logs/tools/make_handoff.py --date 2026-08-20 \
    --notes "First real-workflow field test, Canon T7 tethered on the Moto, native on the Samsung"
```

That writes `testing-logs/handoff/<date>/`, self-contained and numbered in upload order:

```
00-START-HERE.md      what to do, what to upload, and the prompt to open with
01-brief.md           this session's figures (computed) + stable background + guidance
02-<label>.md         copy of each device's session report
03-<label>.md
```

**Upload every file in that folder** to a browser chat and open with the line in START-HERE. The
brief tells the assistant the audience, the register, and — importantly — what not to overstate.

Figures come from `sessions/<stem>.json`, written by `capture_session.py`. If that is missing they
are computed from the raw snapshot and the JSON is written then, so a bundle can always be rebuilt
later even though raw snapshots are gitignored.

`handoff/_background.md` is the stable narrative reused in every bundle: what the app is, what was
fixed before field testing, what is not yet proven, what happens next. **Its last two sections go
stale fastest** — revisit them after each session, because a brief claiming something is untested
after it has been tested is worse than no brief.

## What the report checks

Each check maps onto a specific fix, so a regression shows up as a number rather than a vibe:

- **FR-1 session attribution** — does each image's filename barcode match the session it is filed
  under? A mismatch is the signature of the pre-FR-1 bug, where a tethered shot landed in whichever
  session the operator was *reviewing*. Should always be 0.
- **FR-6 / `captureMutex`** — duplicate or gapped sequence numbers within a session. Two captures
  racing for the same DB count produce a duplicate seq *and* a gap, and the duplicate filename
  means one image overwrites the other on the server.
- **Rapid captures** — same-session images under 3 s apart. These are the ones that stress the
  mutex; a session with none did not really exercise it.
- **FR-3 retry bound** — max `retryCount`. `0` means every upload succeeded first try, so the
  bound was never tested, not that it works.
- **FTP ghost rows** — images still `PENDING`. Should be 0; a lingering `PENDING` was the bug
  where a missing local file left an undiagnosable row in the transfer queue forever.

## Logcat: capture it during the test, not after

**Logcat is a RAM ring buffer.** It does not survive a reboot, and the `logcat -G 16M` size bump
resets with it. The default on these devices is **256 KiB**, which the PTP poll loop churns
through in minutes because it logs every 500 ms.

The 2026-08-20 field test is the worked example: the Moto G rebooted overnight, so by the time it
was connected the buffer held about thirty seconds of history and nothing from the test. The
database still told the whole story — but only because the interesting facts are persisted.

To actually keep logs from a session:

```bash
# before the test, on the connected device
adb -s <serial> logcat -G 16M

# during the test, if a laptop is present — survives nothing if ADB drops, so prefer USB
adb -s <serial> logcat -s "PhotoFlow/Pipeline:*" "PhotoFlow/Tether:*" "PhotoFlow/PTP:*" \
    > testing-logs/raw/<date>-<label>/live-logcat.txt
```

For a real field test with no laptop attached, neither is practical. **The app's LOGS toggle only
gates whether lines are emitted to logcat — it does not write to a file**, so there is currently no
durable on-device log. That is a real gap for field testing and a small feature to close it; see
the deferred list in `.claude/prompts/field-readiness-pass.md`.

## Pitfalls the tool already handles

Both cost real time on 2026-08-21; do not undo them.

- **`adb exec-out`, never `adb shell`, for binaries.** `shell` allocates a PTY and translates LF to
  CRLF, silently corrupting a SQLite file. It arrives a few dozen bytes too long and opens as
  `database disk image is malformed`. The tool verifies the `SQLite format 3` header and page
  alignment after every pull.
- **Pull to a temp name, promote on success.** A failed adb call still truncates its redirect
  target, so writing straight to the final name destroys a previous good snapshot when the device
  drops mid-command.
- **Prefer the USB serial.** Wireless ADB on the Moto G dropped roughly every ten minutes during
  testing. USB is stable, and the camera only needs the port in tethered mode.

## Baseline findings

**Duplicate sequence number, 2026-05-01, session `MOBILEV1TEST001`.** Two captures 1 s apart both
received `seq=2` and wrote `MOBILEV1TEST001_02_abc.jpg` twice (image ids 24 and 25), skipping
`seq=3`. This is the race `captureMutex` was introduced to fix, visible in real data.

Across the 148 images since, in 19 other sessions including a 58-image session and the 2026-08-20
field test: **zero duplicates, zero gaps**. Worth re-running that check after any change to the
capture path.

**First observed upload failure and recovery — Motorola, 2026-08-27.** After 192 photos of field
use at a 100% upload rate, the first failure was finally seen — and the on-device log, installed
that same day, captured the whole cycle. It would previously have been lost within minutes.

```
13:42:07  attempt=4  RETRYABLE (timeout)   profile "SAZ Test" 192.168.9.37, wrong subnet
13:43:32  attempt=5  RETRYABLE (timeout)   <- 80s later
13:46:17  attempt=6  RETRYABLE (timeout)   <- 160s later
13:46:30  [CONFIG] connection loaded: "pgi-test" 192.168.1.147
13:46:36  attempt=1  <- manual RETRY resets the schedule
13:46:37  succeeded  (295ms)
```

The 80/160 doubling is WorkManager's exponential backoff, and `retryCount` stayed **0** throughout
— a transient failure consumes none of FR-3's 3-attempt budget, which is exactly why a bound of 3
is safe. Full analysis in `sessions/2026-08-27-motog-failure-recovery.md`.

**The FR-3 retry storm, in production data — both devices, pre-WI-1.** Seven images on each
handset carry very high retry counts: 705–719 on the Moto (2026-05-02), 604–605 and 517–522 on
the Samsung (2026-07-04). At the 4-second loop interval, 719 retries is about **48 minutes** of
continuously re-uploading full-size files to an unreachable server. All of them eventually
reached `UPLOADED`, so the unbounded loop did work — at that cost, and with no way to stop it.

Since WI-1 (~2026-07-05) there are **zero retries across 73 images**, because WorkManager absorbs
transient failures and the in-app loop skips anything already queued. `retryCount` now only moves
once WorkManager has given up. See "What to test next" in `TESTING.md` — this is why upload
failure recovery is the highest-value untested path.

**FR-1 session switching, used for real — Samsung, 2026-08-20 native camera.** The clearest
evidence in either capture that "add a photo to an earlier session" works end to end:

```
12:20:54  SAZ121212_01.jpg   seq 1
13:14:28  XYZ297729_31.jpg   seq 31   <- switched to a session opened 07-04
13:15:00  XYZ297729_32.jpg   seq 32
13:15:11  SAZ121212_02.jpg   seq 2    <- switched back, continued where it left off
13:45:27  SAZ121212_03.jpg   seq 3
```

Two sessions interleaved, each continuing its own numbering — `XYZ297729` resumed at 31 from the
30 images it already had in July, `SAZ121212` picked up at 2 after the detour. Zero filename or
attribution mismatches. Before FR-1 this workflow did not exist: tapping a history row changed
only what was displayed, while capture always went to the newest session.
