# Live test - 2026-08-20 - motog-tethered

**Conditions:** Field test, tethered DSLR mode, Canon EOS Rebel T7 over USB PTP. Positive observational result reported by operator.

Captured 2026-08-21 23:53. Raw snapshot: `testing-logs/raw/2026-08-20-motog-tethered/` (gitignored).

## Device

```
model      moto g - 2025
android    16
serial     ZY32L9TX7B
uptime     22:53:06 up 21:01,  0 users,  load average: 12.08, 12.09, 12.21
versionCode=3 minSdk=26 targetSdk=34
versionName=1.2
lastUpdateTime=2026-08-18 21:54:08
firstInstallTime=2026-04-29 08:05:55
```

## Sessions touched

| id | barcode | status | start | end |
|---|---|---|---|---|
| 5 | XYZ968213 | complete | 05-04 15:26:09 | 08-20 10:46:25 |
| 7 | XYZ815024 | complete | 05-15 14:33:27 | 08-20 13:28:19 |
| 8 | XYZ297729 | complete | 05-15 22:47:59 | 08-20 13:27:57 |
| 9 | TST123456 | complete | 08-20 10:49:25 | 08-20 13:28:08 |
| 10 | SAZ813503 | complete | 08-20 13:10:00 | 08-20 13:27:06 |
| 11 | SAZ813455 | complete | 08-20 13:24:16 | 08-20 13:28:05 |
| 12 | SAZ813456 | complete | 08-20 13:26:03 | 08-20 13:40:57 |
| 13 | SAZ813457 | complete | 08-20 13:27:40 | 08-20 13:35:41 |
| 14 | SAZ813458 | complete | 08-20 13:35:41 | 08-20 13:37:49 |
| 15 | SAZ813459 | complete | 08-20 13:37:49 | 08-20 13:40:51 |
| 16 | SAZ813460 | complete | 08-20 13:39:19 | 08-20 13:41:04 |
| 17 | SAZ814781 | complete | 08-20 13:41:04 | 08-20 13:43:19 |
| 18 | SAZ814742 | complete | 08-20 13:43:19 | 08-20 14:15:34 |
| 19 | SAZ814797 | complete | 08-20 14:15:34 | 08-20 14:17:13 |
| 20 | SAZ814798 | complete | 08-20 14:17:13 | 08-20 14:17:52 |
| 21 | SAZ814799 | complete | 08-20 14:17:52 | 08-20 14:46:28 |
| 22 | TEST | active | 08-20 14:22:57 | - |

## Images captured (17)

| time | session | filename | seq | state | retries | error |
|---|---|---|---|---|---|---|
| 10:49:38 | TST123456 | TST123456_01.jpg | 1 | UPLOADED | 0 |  |
| 13:10:08 | SAZ813503 | SAZ813503_01.jpg | 1 | UPLOADED | 0 |  |
| 13:10:53 | SAZ813503 | SAZ813503_02.jpg | 2 | UPLOADED | 0 |  |
| 13:11:58 | SAZ813503 | SAZ813503_03.jpg | 3 | UPLOADED | 0 |  |
| 13:12:31 | SAZ813503 | SAZ813503_04.jpg | 4 | UPLOADED | 0 |  |
| 13:19:52 | SAZ813503 | SAZ813503_05.jpg | 5 | UPLOADED | 0 |  |
| 13:25:28 | SAZ813455 | SAZ813455_01.jpg | 1 | UPLOADED | 0 |  |
| 13:25:29 | SAZ813455 | SAZ813455_02.jpg | 2 | UPLOADED | 0 |  |
| 13:27:21 | SAZ813456 | SAZ813456_01.jpg | 1 | UPLOADED | 0 |  |
| 13:35:13 | SAZ813457 | SAZ813457_01.jpg | 1 | UPLOADED | 0 |  |
| 13:37:22 | SAZ813458 | SAZ813458_01.jpg | 1 | UPLOADED | 0 |  |
| 13:38:59 | SAZ813459 | SAZ813459_01.jpg | 1 | UPLOADED | 0 |  |
| 13:40:36 | SAZ813460 | SAZ813460_01.jpg | 1 | UPLOADED | 0 |  |
| 13:43:08 | SAZ814781 | SAZ814781_01.jpg | 1 | UPLOADED | 0 |  |
| 13:43:50 | SAZ814742 | SAZ814742_01.jpg | 1 | UPLOADED | 0 |  |
| 14:16:07 | SAZ814797 | SAZ814797_01.jpg | 1 | UPLOADED | 0 |  |
| 14:17:36 | SAZ814798 | SAZ814798_01.jpg | 1 | UPLOADED | 0 |  |

## Integrity checks

- **FR-1 session attribution** - filename barcode vs filed session: **0 mismatch(es)** in 17 images
- **FR-6 / captureMutex** - duplicate sequence numbers: **0**, gaps: **0**
- **Rapid captures (<3s apart, same session)**: 1 -- SAZ813455_02.jpg at 0.3s
- **Upload states**: {'UPLOADED': 17}
- **FR-3 retry bound** - max retryCount: **0** (bound is 3; 0 means never stressed)
- **FTP ghost rows** - still PENDING: **0**

## Logcat

0 PhotoFlow lines in the buffer at capture time. Logcat is a RAM ring buffer: it does not survive a reboot, and `logcat -G` sizing resets with it. For a test captured after the fact this is normally empty, and the database above is the authoritative record.

## Whole-database totals

- UPLOADED: 148
- sessions: 22
- images: 148
