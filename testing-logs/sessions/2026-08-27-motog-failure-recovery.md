# Live test - 2026-08-27 - motog-failure-recovery

**Conditions:** Bench observation, not a field session. Photo taken at desk while the active FTP profile still pointed at the on-site venue server (different subnet). Operator then switched profile via quick-connect and pressed RETRY in the file transfer window.

Captured 2026-08-27 14:52. Raw snapshot: `testing-logs/raw/2026-08-27-motog-failure-recovery/` (gitignored).

## Device

```
model      moto g - 2025
android    16
serial     ZY32L9TX7B
uptime     13:52:19 up 6 days, 12:00,  0 users,  load average: 12.48, 12.52, 12.71
versionCode=3 minSdk=26 targetSdk=34
versionName=1.2
lastUpdateTime=2026-08-27 13:41:54
firstInstallTime=2026-04-29 08:05:55
```

## Sessions touched

_none_

## Images captured (1)

| time | session | filename | seq | state | retries | error |
|---|---|---|---|---|---|---|
| 13:57:24 | TEST | TEST_01.jpg | 1 | UPLOADED | 0 |  |

## Integrity checks

- **FR-1 session attribution** - filename barcode vs filed session: **0 mismatch(es)** in 1 images
- **FR-6 / captureMutex** - duplicate sequence numbers: **0**, gaps: **0**
- **Rapid captures (<3s apart, same session)**: 0
- **Upload states**: {'UPLOADED': 1}
- **FR-3 retry bound** - max retryCount: **0** (bound is 3; 0 means never stressed)
- **FTP ghost rows** - still PENDING: **0**

## On-device log (files/logs)

14 lines for this date. Excerpt:

```
08-27 13:42:06.809 I PhotoFlow/Pipeline: [CAMERA] disconnected
08-27 13:42:07.238 I PhotoFlow/Pipeline: [APP] launched
08-27 13:42:07.240 I PhotoFlow/Pipeline: [CONFIG] connection loaded: "SAZ Test" (192.168.9.37:21)
08-27 13:42:07.241 I PhotoFlow/Pipeline: [SESSION] started: barcode=TEST
08-27 13:42:07.366 I PhotoFlow/Pipeline: [UPLOAD] started: TEST_01.jpg (id=149) attempt=4
08-27 13:42:12.390 I PhotoFlow/Pipeline: [UPLOAD] RETRYABLE (timeout): TEST_01.jpg (id=149)
08-27 13:43:32.471 I PhotoFlow/Pipeline: [UPLOAD] started: TEST_01.jpg (id=149) attempt=5
08-27 13:43:37.492 I PhotoFlow/Pipeline: [UPLOAD] RETRYABLE (timeout): TEST_01.jpg (id=149)
08-27 13:46:17.547 I PhotoFlow/Pipeline: [UPLOAD] started: TEST_01.jpg (id=149) attempt=6
08-27 13:46:22.567 I PhotoFlow/Pipeline: [UPLOAD] RETRYABLE (timeout): TEST_01.jpg (id=149)
08-27 13:46:30.599 I PhotoFlow/Pipeline: [CONFIG] connection loaded: "pgi-test" (192.168.1.147:21)
08-27 13:46:36.752 I PhotoFlow/Pipeline: [UPLOAD] queued: TEST_01.jpg (id=149) via FTP
08-27 13:46:36.855 I PhotoFlow/Pipeline: [UPLOAD] started: TEST_01.jpg (id=149) attempt=1
08-27 13:46:37.150 I PhotoFlow/Pipeline: [UPLOAD] succeeded: TEST_01.jpg (id=149)
```

## Logcat

6 PhotoFlow lines in the buffer at capture time. Logcat is a RAM ring buffer: it does not survive a reboot, and `logcat -G` sizing resets with it. For a test captured after the fact this is normally empty, and the database above is the authoritative record.

## Whole-database totals

- UPLOADED: 149
- sessions: 22
- images: 149

---

## Analysis (hand-added)

The first upload failure ever observed on either device, and the first complete
failure-and-recovery cycle. 192 photos of prior field use produced none.

### What the timings prove

Gaps between the failing attempts: **80 s** then **160 s**. That doubling is WorkManager's
exponential backoff from its 10 s base (10, 20, 40, 80, 160), which confirms WI-1's durable
retry is the mechanism actually driving retries — not the in-app loop.

`retryCount` stayed at **0** throughout. The in-app auto-retry loop correctly skipped the image
on every pass because WorkManager still had it queued, so **a transient failure consumed none of
FR-3's 3-attempt budget**. This was the predicted interaction when FR-3's bound was chosen, and
it had never been observed. It is the reason a bound of 3 is safe: the bound only engages once
WorkManager has genuinely given up, not while it is still backing off.

`attempt=1` after the operator pressed RETRY confirms `forceRetry` uses `ExistingWorkPolicy.REPLACE`
while the auto-loop uses `KEEP`. That distinction is deliberate and correct here — a new server
makes the old backoff schedule irrelevant, so the operator gets an immediate attempt instead of
waiting out a 320 s delay.

### WI-2 validated as a side effect

The image was *enqueued* while `SAZ Test` was the active profile and *executed* after the operator
switched to `pgi-test` — and it used the new one. That is the single `UploadWorker` resolving the
connection profile at execution time rather than enqueue time. Under the previous two-worker model
the job would have carried the stale connection and failed again.

### Operator-facing behaviour

The error text named both endpoints — `could not reach 192.168.9.37:21 from 192.168.1.248` — which
is what made a subnet mismatch diagnosable at a glance rather than looking like a server fault. The
transfer-queue RETRY button worked as intended.

### Still not proven

The **terminal** case: an image exhausting all 3 in-app attempts and stopping. That needs a failure
that never resolves, which this was not — the operator fixed the connection and recovery succeeded.
