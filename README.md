# PhotoFlow Mobile

A landscape-only Android app for live photo operations in the field. Operators run barcode-based
sessions, capture either from the device camera or a tethered DSLR, and photos upload in the
background over FTP. It is an operations console for photographers working a venue — not a
consumer photo app.

**Status:** field-tested and in use. `1.2 (build 2)` on both test handsets. FTP is the live
transfer path; Cloud API upload is implemented but shelved pending a future project.

## Quick start

```bash
./gradlew assembleDebug

ADB=/c/Users/John/AppData/Local/Android/Sdk/platform-tools/adb.exe
$ADB -s <usb-serial> install -r app/build/outputs/apk/debug/PhotoFlow-debug-bNNN-<stamp>.apk
```

Each debug build carries an iterating number, so `versionName` identifies exactly which build a
handset runs. `app/build/outputs/apk/debug` holds only the current build — Gradle wipes stale
files from its own output directories — while `builds/` accumulates every iteration.

Android Studio sometimes holds a lock on `R.jar` that fails CLI builds with
`Couldn't delete ... R.jar`. That is an environment lock, not a code error; close Studio or build
in a throwaway worktree.

## Where things are

| Document | What it covers |
|---|---|
| **`CLAUDE.md`** | Architecture, hard rules, and the things you must not "clean up" — especially the USB/PTP tethering subsystem, where several constraints are empirical and load-bearing. **Read before changing code.** |
| **`CONTEXT.md`** | Current status, environment, source-file map, build and deploy commands, test history, and the reasoning behind key implementation decisions. |
| **`TESTING.md`** | Test procedures, pass criteria, the live-test checklist, and evidence-based priorities for what to test next. |
| **`testing-logs/`** | Machine-captured evidence from live sessions, plus tooling to snapshot a device and bundle a stakeholder handoff. See its own README. |
| **`.claude/prompts/`** | The engineering specifications for completed hardening passes (`WI-1…WI-7`, `FR-1…FR-10`), with acceptance criteria and deferred items. |

## A note on the tethering code

Everything under `data/usb/` talks directly to the USB stack and to vendor-specific PTP opcodes.
Several of its constants — a 512-byte first read, an 800 ms delay after claiming the interface —
are not style choices. They were arrived at by losing hours to silent failures on Samsung's USB
host driver, and changing them breaks tethering in ways that are hard to diagnose. The hard rules
in `CLAUDE.md` explain each one and why it exists.

## Testing

After any session worth keeping, with the device on **USB**:

```bash
python testing-logs/tools/capture_session.py --serial <usb-serial> --label motog-tethered --date YYYY-MM-DD
python testing-logs/tools/make_handoff.py    --date YYYY-MM-DD --notes "conditions"
```

The first snapshots the database, settings and on-device logs, then writes a report whose
integrity checks map onto specific fixes — session attribution, sequence numbering, retry
behaviour. The second bundles those reports for a stakeholder update.

The app writes its own rotating log to `filesDir/logs`, retrievable from the handset via
Settings → General → Logs → Export Logs. This matters because logcat is RAM-only and does not
survive a reboot — a field session's diagnostic record was lost that way once, which is why the
on-device log exists.
