---
description: Diagnose DSLR USB tethering failures on connected PhotoFlow test devices
---

Diagnose DSLR USB tethering failures for the PhotoFlow Mobile app. Pull recent logcat from the connected Android test device(s), scan for known failure signatures, and give a specific diagnosis and remediation step.

## Known devices
- **Samsung Galaxy** — serial `adb-RFCW101K9PA-PyKfsb._adb-tls-connect._tcp` (primary test device, Samsung One UI)
- **Motorola G 2025** — serial `adb-ZY32L9TX7B-GrNvc4._adb-tls-connect._tcp` (secondary, non-Samsung USB host)

ADB binary: `/c/Users/John/AppData/Local/Android/Sdk/platform-tools/adb.exe`

## Steps

### 1. Resolve the target device

If `$ARGUMENTS` contains `samsung`, target the Samsung serial.
If `$ARGUMENTS` contains `moto`, target the Motorola serial.
Otherwise, run `adb devices` and target all devices that are `device` (not `offline`).

### 2. Pull logcat

For each target device, run:
```
adb -s <serial> logcat -d -t 600
```
Then filter the output for these tags (case-insensitive grep across the full dump):
- `PhotoFlow` — all app logs (tether, pipeline, cloud)
- `UsbDeviceManager` — Android USB attach/detach events
- `UsbUserSettingsManager` — USB permission grants/denials
- `UsbPermission` — permission dialog events

Also run:
```
adb -s <serial> shell dumpsys usb
```
and extract: `host_manager`, `devices=`, `vendor_id`, `product_id`, `class=`, `manufacturer_name`, `product_name`, any line containing `photoflow` or `permission`.

### 3. Diagnose against the known failure catalog

Match the filtered logs against these failure signatures in order. Report the **first match** (they are mutually exclusive in practice):

---

**Failure A — Samsung first-read buffer cap**
- Pattern: log line containing `first read=-1` appearing within 3 ms of a `SetRemoteMode` or `SetEventMode` command
- Meaning: Samsung's USB host driver silently rejects bulk-IN reads larger than ~512 bytes immediately after vendor extension commands. Every subsequent transfer on that `UsbDeviceConnection` is poisoned.
- Check: open `data/usb/PtpConnection.kt` and confirm `FIRST_CHUNK_SIZE = 512`. If it has been raised, that is the bug.
- Remediation: restore `FIRST_CHUNK_SIZE = 512`. Do not raise it without re-testing on Samsung One UI. This is a hardware constraint, not a code smell.

---

**Failure B — MTP daemon not releasing the interface**
- Pattern: `claimInterface` succeeded but `OpenSession` times out or never appears in logs; or `bulkTransfer` returns -1 immediately on the first command-phase write
- Meaning: Samsung's system MTP daemon hasn't fully backed off after the force-claim. The endpoint toggle bits are stale.
- Check: in `PtpConnection.init`, verify all three are present in this order: `SET_INTERFACE(0)` control transfer → `CLEAR_HALT` on bulk OUT → `CLEAR_HALT` on bulk IN → `Thread.sleep(800)`. If the sleep is shorter than 800 ms or missing, that is the bug.
- Remediation: restore the 800 ms sleep and the full SET_INTERFACE + CLEAR_HALT sequence. Do not lower the delay.

---

**Failure C — `isConnecting` stuck, no PTP session, no dialog visible**
- Pattern: app logs show `isConnecting=true` set but no `OpenSession` attempt and no USB permission dialog appeared; persists for more than 8 seconds
- Meaning: the 8-second `isConnecting` timeout did not fire, or `permissionDenied` was set from a previously swipe-dismissed dialog, blocking the rescan loop.
- Remediation (user action): in ConfigScreen, switch Device Mode from Tethered to Native, then back to Tethered. This calls `MtpCameraManager.resetAndRescan()` which atomically clears both `isConnecting` and `permissionDenied` and immediately rescans. Do NOT instruct the user to restart the app — mode-switch is the designed recovery path.
- If the bug is in code: check that the `deviceMode`-watching coroutine in `MainViewModel.init {}` still calls `resetAndRescan()` on every transition to `TETHERED_DSLR`.

---

**Failure D — Camera not detected, no USB attach events**
- Pattern: no `USB_DEVICE_ATTACHED` in logs, no `rescanForAttachedCamera` log, `dumpsys usb` shows a PTP-class device present but PhotoFlow never received the attach intent
- Meaning: common on Motorola and other non-Samsung hosts. Competing apps (`com.android.mtp`, Google Photos, installed tether apps) are registered for PTP class-6 devices and claim the attach intent first.
- Remediation (user action, in order):
  1. Background and re-foreground PhotoFlow — `MainActivity.onResume()` calls `rescanForAttachedCamera()` which re-scans `usbManager.deviceList` directly.
  2. If still not detected: unplug and replug the USB cable.
  3. If still not detected: check Settings → Apps for any tether app with USB defaults set → Open by default → Clear defaults. Then replug.
- If the foreground rescan loop is not running, check that `MtpCameraManager.init {}` starts the 5-second `FOREGROUND_RESCAN_MS` loop.

---

**Failure E — Two `MtpCameraManager` instances / double `claimInterface`**
- Pattern: log shows `claimInterface` called twice within a second, or two `OpenSession` sequences interleaved, or two `connectOrRequestPermission` calls with different coroutine contexts
- Meaning: `MainViewModel` is being instantiated twice — once by the Activity's `by viewModels()` and once by the `NavBackStackEntry`'s ViewModelStore inside `NavGraph.kt`. Two VMs = two `MtpCameraManager`s = two coroutines racing to `claimInterface(force=true)`.
- Check: in `NavGraph.kt`, find the `Screen.Main` composable block. The `MainScreen` call must pass `viewModel = viewModel(activity)` where `activity = LocalContext.current as ComponentActivity`. If it uses the default `viewModel()` with no arguments, that is the bug.
- Remediation: restore `viewModel(activity = LocalContext.current as ComponentActivity)`. Never revert to the default.

---

**Failure F — USB I/O crossing threads**
- Pattern: `bulkTransfer` returns -1 after a successful `OpenSession`; no vendor extension commands nearby; symptoms look identical to Failure A but timing doesn't match
- Meaning: `bulkTransfer` calls on the same `UsbDeviceConnection` are being made from different threads. Samsung's USB stack breaks silently in this case.
- Check: in `MtpCameraManager`, verify every `scope.launch` block that performs USB I/O (open, poll, download, close) uses `withContext(usbDispatcher)`. Any `launch(Dispatchers.IO)` doing USB work is the bug.
- Remediation: move the USB work onto `usbDispatcher`. The dispatcher is a `newSingleThreadExecutor { Thread("PhotoFlow-USB") }` — all USB I/O must run on that single thread.

---

**Failure G — Competing app holds USB interface as default handler**
- Pattern: `dumpsys usb` shows the camera device with a non-PhotoFlow package as the granted permission holder; or USB permission dialog routes to another app; PhotoFlow's `requestPermission()` call does not produce a visible dialog
- Meaning: another USB tether app (EOS Utility Android, Lightroom mobile, Imaging Edge, etc.) was previously set as the system default handler for PTP devices. Android routes the attach intent to it without showing the app chooser.
- Remediation (user action): Settings → Apps → [the other tether app] → Open by default → Clear defaults. Then unplug and replug the cable. PhotoFlow's `requestPermission()` path will then fire via the rescan loop.

---

**No failure detected**
- If none of the above patterns match: report which log lines were found, confirm that PhotoFlow tethering logs exist at all, and note whether logging is enabled (check for `loggingEnabled=true` in the DataStore — if logging is off, the user should enable it in ConfigScreen → Logs and reproduce the issue).
- If no PhotoFlow logs exist at all: the app may not have been run yet on this device, or the process was killed. Instruct the user to launch PhotoFlow, switch to Tethered mode, plug in the camera, and then run `/dslr-debug` again.

### 4. Report

Produce a short report with:
- Which device(s) were checked
- The 10–20 most relevant log lines (timestamps included)
- **Diagnosis**: which failure letter (A–G), or "no failure detected"
- **Remediation**: the exact action to take (one specific step — no menus of options)
