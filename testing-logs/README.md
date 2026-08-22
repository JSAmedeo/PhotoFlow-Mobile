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
