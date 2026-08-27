# PhotoFlow Mobile — Test Log

This document records manual test sessions, pass/fail criteria, and the canonical log
signatures used to verify correct behaviour. Update it whenever a test session produces
new findings or a regression is caught and fixed.

---

## Test environment

| Device | Serial (wireless ADB) | OS | Role |
|--------|----------------------|----|------|
| Samsung Galaxy S-series | `adb-RFCW101K9PA-PyKfsb._adb-tls-connect._tcp` | One UI / Android 14 | Primary tethering test device |
| Motorola G 2025 | `adb-ZY32L9TX7B-GrNvc4._adb-tls-connect._tcp` | Stock Android 14 | Secondary; exposes non-Samsung USB quirks |
| Canon EOS Rebel T7 | VID=0x04A9 PID=0x32E1 | — | Only verified DSLR |

### ADB log commands
```bash
# Stream PhotoFlow logs only — Samsung
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe \
  -s adb-RFCW101K9PA-PyKfsb._adb-tls-connect._tcp \
  logcat --pid=$(adb shell pidof com.photoflowmobile.app) \
  | grep -E "PhotoFlow|usb|USB|Tether|PTP|ptp"

# Clear buffer before a test
adb logcat -c

# Dump last N lines after a test
adb logcat -t 500 --pid=$(adb shell pidof com.photoflowmobile.app)
```

Prefer the **USB serial** (`ZY32L9TX7B`, `RFCW101K9PA`) over the wireless one for anything that
must not be interrupted. Wireless ADB dropped roughly every ten minutes during 2026-08-18/21
testing; USB held throughout.

---

## What to test next, and why

Derived from the device snapshots of 2026-08-21/22 (192 images across both handsets, back to
April). Recorded here because it contradicts some of what we had been testing by instinct.

### 1. Upload failure and recovery — the largest untested risk

**No image on either device has ever been recorded as `FAILED`.** All 192 are `UPLOADED`, and not
one carries an `errorMessage`. So the failure UI — the orange File Transfers button, the per-row
error text, the RETRY button — has never been seen under real conditions.

That would be comforting if outages did not happen. They do, and the data proves it:

| Device | Date | Images | retryCount |
|---|---|---|---|
| Moto G | 05-02 | 4 | 705–719 |
| Samsung | 07-04 | 3 | 604–605 |
| Samsung | 07-04 | 3 | 517–522 |

At the 4-second loop interval, **719 retries is roughly 48 minutes** of continuously re-uploading
full-size files to an unreachable server. Every one of them eventually succeeded — the old
unbounded loop did get there, at that cost. This is the FR-3 retry storm, in production data.

**Since WI-1 landed (~2026-07-05): zero retries on either device across 73 images.** WorkManager
now absorbs transient failures with exponential backoff, and the in-app loop skips anything
WorkManager already has queued, so `retryCount` only moves once WorkManager has given up — which
has not happened yet.

The consequence: **FR-3's bound of 3 has never been exercised**, and it is the one change in the
pass that could make things *worse* than before. Arithmetic says it is safe — WorkManager's ten
exponential attempts from a 10 s base span about 2.8 hours, comfortably covering a 48-minute
outage, before the loop's 3 attempts even begin — but that is a prediction, not a result.

**Test it deliberately.** Point the active FTP profile at an unreachable host (or stop the server)
mid-session, shoot several frames, then restore it. What to confirm:

- images reach `FAILED` with a readable error, and the File Transfers button turns orange
- once the server returns, they upload without operator intervention
- `retryCount` settles at or below 3, and nothing is stuck in `PENDING`
- an image that has genuinely exhausted its attempts still recovers via the manual RETRY button

### 2. Burst capture matters on the tethered path, not the native one

The two devices work very differently, and it is not what we assumed:

| | Samsung (native) | Moto G (tethered) |
|---|---|---|
| median gap between shots in a session | 13.8 s | **1.8 s** |
| intervals under 3 s | 2 of 38 | **72 of 128** |
| intervals under 1 s | 1 | **13** |
| median images per session | 3 | 1 |

Rapid capture is a **tethered** phenomenon — a photographer working a DSLR shutter — while native
capture is paced by tapping a button and glancing at the review pane. So `captureMutex` stress
testing belongs on the tethered path, where it was already aimed. Native mode does not need it.

### 3. Sessions are small and numerous, and switching between them is normal

Median session size is 1–3 images. Both devices show capture **returning to an earlier session**
twice in ordinary use, without anyone setting out to test it. FR-1 is therefore exercised
constantly rather than exceptionally, which is the best argument that its zero mismatches across
192 images is meaningful.

Test shape should follow: many small sessions with switching between them, rather than one long
session with a burst in it.

### 4. Session hygiene

Both devices were found holding a **stale active session** (`TEST` with 0 images, `SAZ814795` with
1). Harmless, but it means the next capture lands somewhere unintended. Covered by the checklist
below.

---

## Live test checklist

Follow this for any field or bench session whose result is worth keeping. It exists because the
2026-08-20 field test produced a good outcome that was **only half recoverable**: the database had
the results, but every log line was gone by the time a cable was connected.

### Before

- [ ] **Start a fresh session.** Do not shoot into whatever session is left active from last time —
      both devices were found holding a stale one (`TEST`, `SAZ814795`).
- [ ] **Settings → General → LOGS → Enable logging** is on. For a tethering fault add
      **Verbose (DEBUG detail)**; leave it off otherwise, it records every 500 ms PTP poll.
- [ ] Confirm the intended **Device Mode** (Tethered DSLR / Native Camera) and **Active Connection**.
- [ ] Note the conditions you will want later: venue, network, camera, what you are trying to prove.

### During

- [ ] If a laptop is attached, raise the logcat buffer and stream to a file — logcat is RAM-only
      and 256 KiB by default, which the PTP poll loop churns through in minutes:
      ```bash
      adb -s <serial> logcat -G 16M
      adb -s <serial> logcat -s "PhotoFlow/Pipeline:*" "PhotoFlow/Tether:*" "PhotoFlow/PTP:*" > live.txt
      ```
- [ ] Note anything unexpected as it happens, with the wall-clock time — it makes the log
      searchable afterwards.

### After

- [ ] **Do not reboot the device before capturing.** A reboot clears logcat *and* resets the
      `logcat -G` sizing. This is exactly how the 2026-08-20 logs were lost.
- [ ] Connect over USB and capture:
      ```bash
      python testing-logs/tools/capture_session.py \
          --serial <usb-serial> --label <motog-tethered|sam-native|...> \
          --date YYYY-MM-DD --notes "conditions"
      ```
- [ ] Read the generated report's **Integrity checks** — session attribution, duplicate/gapped
      sequence numbers, retry counts and lingering `PENDING` rows should all be 0.
- [ ] Commit the report under `testing-logs/sessions/`. Raw snapshots stay gitignored.

If ADB is unavailable, the operator can still retrieve logs on the handset:
**Settings → General → LOGS → EXPORT LOGS**, which writes them to Downloads.

---

## Tethering reliability test

### Purpose
Verify that connect/disconnect/reconnect cycles produce a single clean PTP session with
no zombie poll loops, no `sent=-1` cascades, and no ERROR state requiring an app restart.

### Procedure
1. Build and install a debug APK.
2. Launch the app in Tethered DSLR mode with the camera **already plugged in**.
3. Confirm the status chip shows **Connected**.
4. Pull the cable. Confirm status shows **Disconnected**.
5. Plug the cable back in. Confirm status returns to **Connected** within ~2 s.
6. Repeat steps 4–5 at least three times, some times quickly (< 3 s between unplug/replug),
   some times with a longer pause.
7. Fire 3–5 shots on the camera. Confirm each appears in the thumbnail rail and uploads.
8. Pull the ADB log and verify the pass criteria below.

### Pass criteria (log signatures)

**Single ViewModel init:**
```
[APP] launched   ← appears exactly ONCE per process start
```

**Clean connect on attempt 1:**
```
Opening PTP session with Canon Digital Camera VID=0x4a9 PID=0x32e1 adapter=Canon EOS
[CAMERA] connecting
PtpConnection ready — in=0x81 out=0x2 intr=0x83
openSession response=0x2001
canon-init: SetRemoteMode=0x2001 SetEventMode=0x2001 (shutter unlocked)
Connected: Canon Digital Camera (adapter=Canon EOS) on attempt 1
[CAMERA] connected: Canon Digital Camera
Poll loop started (adapter=Canon EOS)
seeded: NNN pre-existing objects on card (only new shots will be imported)
```

**Clean disconnect (cable pulled):**
```
usb detached: 'Canon Digital Camera' VID=0x4a9 PID=0x32e1
sendCommand 0x9115 txn=N: sent=-1 expected=16    ← SetEventMode(0) fails — expected, cable gone
sendCommand 0x9114 txn=N: sent=-1 expected=16    ← SetRemoteMode(0) fails — expected
sendCommand 0x1003 txn=N: sent=-1 expected=12    ← CloseSession fails — expected
[CAMERA] disconnected
Disconnected
```
These three `sent=-1` lines on shutdown are **expected and correct** — the cable is gone.
They should appear only once per disconnect, not as a repeating cascade.

**Single heartbeat (no zombie loops):**
```
heartbeat: adapter=Canon EOS polls=N delivered=M known=K
```
Only ONE heartbeat line per interval. Multiple heartbeats with different `polls` counters
indicate zombie poll loops that must be fixed before shipping.

**Image pipeline (per shot):**
```
fallback-poll: 1 new handle(s) missed by Canon EOS: 0xXXXXXXXX
info: handle=0x... name='IMG_NNNN.JPG' format=0x3801 size=NNNNB (Nms)
downloaded: 'IMG_NNNN.JPG' NNNNNNNBin NNms (NNNNNNkbps)
[IMAGE] detected: IMG_NNNN.JPG (NNNNkB)
[IMAGE] renamed: IMG_NNNN.JPG → SESSION_NN.jpg (session=... seq=N captureCode=...)
[UPLOAD] queued: SESSION_NN.jpg (id=N) via FTP
[UPLOAD] started: SESSION_NN.jpg (id=N) attempt=1
[UPLOAD] succeeded: SESSION_NN.jpg (id=N)
Worker result SUCCESS for Work [...]
```

### Fail signatures (stop — investigate before merging)

| Symptom | Log pattern | Likely cause |
|---------|-------------|--------------|
| Multiple VM inits | `[APP] launched` appears 2+ times in same PID | `launchMode` not `singleTop`, or second ViewModel scope path |
| Zombie poll loops | Multiple `heartbeat:` lines with different `polls` counters; `sent=-1` spam without a preceding `usb detached` | Concurrent MtpCameraManagers or racing `openConnection` calls |
| Cascading reconnect | `openConnection: retry N/3` repeating across several minutes; `isConnecting` never clearing | `permissionDenied` not set on failure, or rescan loop not blocked |
| Camera not detected | No `Opening PTP session` within 10 s of plug-in | `permissionDenied` stuck true, or `isConnecting` stuck true past 8 s timeout |
| Abort on attempt 0 | `openConnection: device disconnected — aborting retry loop` immediately after `Opening PTP session` | DISCONNECTED check placed before `attempt > 0` guard (fatal regression) |
| OpenSession refused | `openSession: null response` / `Camera refused PTP session` on every attempt | Stale PTP session from a prior run; try unplugging camera power cycle |
| USB endpoint poison | `sent=-1` on every command in the poll loop (not just on disconnect) | Two `openConnection` calls ran concurrently and one issued `SET_INTERFACE + CLEAR_HALT` on the other's active session |

---

## Test session log

### 2026-07-06 — Tethering reliability pass (Samsung Galaxy, Canon T7)
**Build:** `PhotoFlow-debug-20260706-1132.apk`
**Branch:** `hardening/reliability-security-pass`
**Commit:** `f55abf5`

**What was tested:**
- Cold start with camera already attached
- Three cable pull/replug cycles (mix of fast < 3 s and slow > 10 s intervals)
- Five shots fired during a connected session

**Results — PASS:**

Single `[APP] launched` per process (PID 811). All three reconnect cycles succeeded on
attempt 1, reconnect latency ~800 ms (dominated by Samsung 800 ms daemon back-off sleep).
Single heartbeat line throughout. Images IMG_3546–3552 all detected, renamed, and
FTP-uploaded with `Worker result SUCCESS`.

Key log excerpt (three clean cycles, condensed):
```
11:58:16  [APP] launched                                      ← once, PID 811
11:58:17  Connected: Canon Digital Camera on attempt 1
11:58:20  usb detached → Disconnected                         ← clean teardown
11:58:22  usb attached → Connected on attempt 1               ← ~800 ms reconnect
11:58:39  usb detached → Disconnected
11:58:43  usb attached → Connected on attempt 1
11:58:52  usb detached → Disconnected
11:58:55  usb attached → Connected on attempt 1
11:59:26  heartbeat: adapter=Canon EOS polls=60 delivered=2 known=292  ← single loop
```

**Previous broken behaviour (pre-fix, same branch, commit `dd19d9b`):**
```
11:12:40  [APP] launched  (PID 26028, instance 1)
11:12:47  [APP] launched  (PID 26028, instance 2) ← new Activity on USB_DEVICE_ATTACHED
11:13:02  [APP] launched  (PID 26028, instance 3) ← another new Activity
11:14:xx  heartbeat: polls=61 delivered=0 known=285  ← zombie loop A
11:14:xx  heartbeat: polls=61 delivered=0 known=285  ← zombie loop B (txn=69)
11:14:xx  heartbeat: polls=61 delivered=0 known=285  ← zombie loop C (txn=100)
          [all commands returning sent=-1 — required app restart]
```

**Fixes applied that produced this result:** see commit `f55abf5` message for full detail.

---

## Upload pipeline test

### Pass criteria
- `[UPLOAD] queued` appears within 500 ms of `[IMAGE] renamed`
- `[UPLOAD] succeeded` appears within 2 s of `[UPLOAD] started` on a good LAN connection
- `Worker result SUCCESS` follows each succeeded upload
- No images stuck in PENDING or FAILED state after a clean session

### Auto-retry test
1. Disconnect FTP server (or misconfigure host) while images are queued.
2. Confirm images show FAILED in the File Transfers panel with a specific error message
   (includes phone IP + server IP:port).
3. Restore FTP server.
4. Within `autoRetryIntervalSeconds` (default 4 s), confirm images retry and reach UPLOADED.
5. Verify `retryCount` incremented but `uploadState` stayed FAILED during retry
   (error message should stay visible until success).

---

## Known-good throughput baseline (Canon T7, Samsung Galaxy, LAN FTP)

| Metric | Observed value |
|--------|----------------|
| Post-shot import latency | 1–3 s (3 s diff-poll cadence — expected; GetEvent silent on T7) |
| PTP download speed | ~120–140 Mbps |
| End-to-end pipeline (shot → thumbnail) | 12–28 ms after download |
| FTP upload (1–2 MB JPEG, LAN) | ~600 ms |
| Reconnect latency (cable replug) | ~800 ms (Samsung 800 ms daemon sleep) |

Values outside 2× these ranges on the same hardware indicate a regression.
