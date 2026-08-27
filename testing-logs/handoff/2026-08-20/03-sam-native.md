# Live test - 2026-08-20 - sam-native

**Conditions:** Field test, Native Camera mode (device camera capture), Samsung Galaxy S23.

Captured 2026-08-22 00:15. Raw snapshot: `testing-logs/raw/2026-08-20-sam-native/` (gitignored).

## Device

```
model      SM-S911U
android    16
serial     RFCW101K9PA
uptime     00:15:18 up 1 day, 15:16,  0 users,  load average: 0.07, 1.28, 2.17
versionCode=3 minSdk=26 targetSdk=34
versionName=1.2
lastUpdateTime=2026-08-18 23:44:18
firstInstallTime=2026-07-03 17:01:27
```

## Sessions touched

| id | barcode | status | start | end |
|---|---|---|---|---|
| 1 | XYZ297729 | complete | 07-04 13:57:00 | 08-20 13:15:07 |
| 2 | XYZ396035 | complete | 07-06 12:12:10 | 08-20 13:13:59 |
| 3 | SAZ121212 | complete | 08-20 12:20:47 | 08-20 13:45:58 |
| 4 | SAZ814792 | complete | 08-20 13:45:58 | 08-20 13:46:29 |
| 5 | SAZ814793 | complete | 08-20 13:46:29 | 08-20 13:49:27 |
| 6 | SAZ814795 | active | 08-20 13:49:27 | - |

## Images captured (9)

| time | session | filename | seq | state | retries | error |
|---|---|---|---|---|---|---|
| 12:20:54 | SAZ121212 | SAZ121212_01.jpg | 1 | UPLOADED | 0 |  |
| 13:14:28 | XYZ297729 | XYZ297729_31.jpg | 31 | UPLOADED | 0 |  |
| 13:15:00 | XYZ297729 | XYZ297729_32.jpg | 32 | UPLOADED | 0 |  |
| 13:15:11 | SAZ121212 | SAZ121212_02.jpg | 2 | UPLOADED | 0 |  |
| 13:45:27 | SAZ121212 | SAZ121212_03.jpg | 3 | UPLOADED | 0 |  |
| 13:46:10 | SAZ814792 | SAZ814792_01.jpg | 1 | UPLOADED | 0 |  |
| 13:46:13 | SAZ814792 | SAZ814792_02.jpg | 2 | UPLOADED | 0 |  |
| 13:49:07 | SAZ814793 | SAZ814793_01.jpg | 1 | UPLOADED | 0 |  |
| 13:49:53 | SAZ814795 | SAZ814795_01.jpg | 1 | UPLOADED | 0 |  |

## Integrity checks

- **FR-1 session attribution** - filename barcode vs filed session: **0 mismatch(es)** in 9 images
- **FR-6 / captureMutex** - duplicate sequence numbers: **0**, gaps: **0**
- **Rapid captures (<3s apart, same session)**: 1 -- SAZ814792_02.jpg at 2.5s
- **Upload states**: {'UPLOADED': 9}
- **FR-3 retry bound** - max retryCount: **0** (bound is 3; 0 means never stressed)
- **FTP ghost rows** - still PENDING: **0**

## On-device log (files/logs)

_No on-device log for this date._ Either the session predates the file logger, or logging was disabled in Settings.

## Logcat

0 PhotoFlow lines in the buffer at capture time. Logcat is a RAM ring buffer: it does not survive a reboot, and `logcat -G` sizing resets with it. For a test captured after the fact this is normally empty, and the database above is the authoritative record.

## Whole-database totals

- UPLOADED: 44
- sessions: 6
- images: 44
