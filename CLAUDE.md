# PhotoFlow Mobile

PhotoFlow Mobile is a landscape-only Android app for live photo operations in field environments.
It is designed as an operational dashboard for barcode/session-based image capture, live preview, background FTP transfer, and system health visibility.

## Package
com.photoflowmobile.app

## Platform
- Kotlin
- Jetpack Compose
- Min SDK: 26
- Target SDK: 34
- Landscape only

## Architecture
MVVM with:
- `PhotoFlowApplication` — Application subclass; holds the Room database singleton
- ViewModels (`AndroidViewModel`) for screen state, backed by StateFlow
- Repository pattern (`SessionRepository`) for data access
- Room for session/image/upload/connection-profile persistence
- DataStore Preferences for app settings (device mode, FTP config, file naming)
- WorkManager for durable background FTP upload and retry

## Architectural rules
- Save images locally before upload — never upload directly from UI callbacks
- Critical session state must persist across app restarts (Room + WorkManager)
- Upload logic must not live in Composables
- Composables should be as stateless as practical
- Screen logic belongs in ViewModels
- Prefer explicit UI state over scattered boolean flags
- Avoid coupling camera, scanning, upload, and navigation logic into one class
- Room database is accessed only via repositories or DAOs — never directly from Composables

## Product purpose
PhotoFlow Mobile is a professional field-operations app, not a consumer photo app.
Primary goals:
- Start/join barcode-based sessions
- Capture images with the device camera (Native Camera mode) or receive from tethered DSLR
- Display recent session images as thumbnails with renamed filenames
- Upload images in the background via FTP
- Expose transfer queue and retry state
- Keep critical operational/system status visible at all times

## Main UI model
The main operational screen is a 4-panel dashboard:
- Left panel: shot count, recent image thumbnails (with full filenames, clickable to review), + NEW SESSION button
- Center panel: live camera feed (left) + review/last-image pane (right) in Native Camera mode;
  full-width tethered image viewer in Tethered DSLR mode
- Right panel: session history (clickable to select session for review)
- Top bar: File Transfers button (green/orange) — opens full transfer queue dialog
- Bottom bar: Active Connection selector, CAPTURE button (Native Camera only), mode chip

The UI should feel like a professional field operations console, not a consumer gallery.

## Screen structure

### ScanCardScreen
- Entry point on first launch or when starting a new session
- Camera viewfinder (dominant) with corner-bracket reticle overlay
- Manual entry bar at bottom: text field + GO button
- GO creates a new Session in Room (closes any existing active session first), then navigates to MainScreen
- ML Kit barcode scanning is wired but not yet fully integrated — manual entry is the active path

### MainScreen
- Primary operational dashboard (4-panel layout described above)
- Top bar: app name + session barcode (centered, prominent) + WiFi SSID chip + File Transfers button + hamburger menu
- File Transfers button: green when all clean, orange when any FAILED/RETRY_REQUIRED. Opens a
  `Dialog` with the full non-UPLOADED image list. Each row shows filename, dynamic error message,
  state badge (PENDING / ↑ UP / ✓ / FAILED / AUTO-RETRY), and a RETRY button for failed items.
- Left panel: thumbnail rail driven by Room session images; shot count live from DB; thumbnails
  are clickable — tapping one sets it as the review image (`selectReviewImage(id)`)
- Center panel (Native Camera mode): left ~58% = live CameraX PreviewView with corner brackets
  and focus reticle; right ~42% = REVIEW pane showing `reviewImage` (selected thumbnail or
  latest capture) via Coil + filename + EXIF strip
- Center panel (Tethered DSLR mode): full-width panel showing `reviewImage` — selected thumbnail
  or latest tethered shot
- Right panel: Session History only (SessionWithCount from Room join query — clickable to select
  session for review; transfer queue moved to File Transfers dialog)
- Bottom bar: Active Connection dropdown (ConnectionProfile list from Room), CAPTURE button
  (Native Camera only), mode chip (Tethered Mode / Native Mode)
- `BackHandler { activity.moveTaskToBack(true) }` — back press backgrounds the app instead of
  destroying the Activity, preserving the USB camera session
- Camera capture: ImageCapture use case held in MainViewModel; fires CameraX takePicture(),
  computes renamed filename from AppSettings naming fields, saves to filesDir/captures/,
  inserts SessionImage (PENDING) into Room, enqueues FtpUploadWorker; logs
  `capture: firing`, `capture: saved`, or `capture: FAILED code=N` to PhotoFlow/Pipeline tag
- `reviewImage: StateFlow<SessionImage?>` in MainViewModel — resolves to the manually selected
  thumbnail (if set via `selectReviewImage()`) or `sessionImages.firstOrNull()`. Auto-clears
  to latest when the session changes or a new photo arrives (observer compares first-image ID)

### ConfigScreen
- Left nav rail + right scrollable content
- Sections (in order): GENERAL, DEVICE MODE, CONNECTIONS, FILE NAMING, ABOUT
- GENERAL section contains: FILE TRANSFER (auto-retry toggle/interval/max), SESSION HISTORY
  (max history dropdown: 10/25/50/100/200/Unlimited, default 50), PHOTO BACKUP (save to
  Pictures/PhotoFlow toggle; auto-delete backups toggle + age dropdown), STORAGE (real
  StatFs available space; Clear App Cache with confirmation dialog), DIAGNOSTICS, APP INFO
- All settings read/written via DataStore Preferences through ConfigViewModel
- SAVE CONFIGURATION button persists draft to DataStore; shows ✓ SAVED flash on success
- Device Mode selection (Tethered DSLR / Native Camera) propagates to MainScreen in real time

## Navigation
- Jetpack Navigation Compose
- Three top-level destinations: ScanCardScreen, MainScreen, ConfigScreen
- ScanCardScreen is the entry point (startDestination)
- After session creation: navigate to MainScreen, clearing back stack
- MainScreen hamburger → ConfigScreen
- MainScreen + NEW SESSION → ScanCardScreen

## Session selection
- `MainViewModel` tracks `selectedSessionId` (which session's images are shown in thumbnails/review)
- Defaults to the active session; can be overridden by clicking a row in Session History
- When a new session starts, the override clears and the view auto-follows the new session

## File naming
- Configured in ConfigScreen → File Naming section
- Three default fields: Custom, Barcode, Sequence Number (user can add/remove/reorder)
- `buildFilename(settings, barcode, seqNum)` in AppSettings.kt generates the actual filename
- Renamed filename is stored in `SessionImage.filename` and shown everywhere (thumbnails,
  transfer queue, review pane)

## Key libraries
- CameraX 1.3.4 — camera preview (`PreviewView`) and capture (`ImageCapture`)
- ML Kit 17.3.0 — barcode scanning (wired, not yet active in UI)
- Room 2.6.1 — Session, SessionImage, ConnectionProfile tables; current schema version 5
- WorkManager 2.9.0 — FtpUploadWorker; always returns `Result.failure()` (no WorkManager
  retry); ViewModel `startAutoRetryLoop()` handles background retry on configurable interval
- Apache Commons Net 3.10.0 — FTP transfers; `connectTimeout = 5 s`, `dataTimeout = 15 s`;
  dynamic error messages include phone IP and server IP/port
- DataStore Preferences 1.1.1 — AppSettings persistence
- Coil 2.7.0 — image loading from local file paths in thumbnails and review pane
- Jetpack Navigation Compose 2.7.7
- AndroidX Core KTX 1.13.1 — required for `ContextCompat.registerReceiver` (Android 12+ flag handling)

## Tethered DSLR (USB/PTP)

**This is the most fragile subsystem in the app.** It talks directly to the USB stack,
to vendor-extension PTP opcodes, and around Android's system MTP daemon. Several of its
constraints are empirical — they were discovered by losing hours of debugging and must
not be "cleaned up" casually. Read this whole section before touching any file under
`data/usb/` or the `MainViewModel` / `NavGraph` USB wiring.

Tethered shooting uses **raw PTP/USB** over Android USB Host (`android.hardware.usb.*`),
NOT `android.mtp.MtpDevice`. The transport is vendor-neutral (standard PTP from CIPA
DC-X005); per-vendor behavior lives behind a `VendorAdapter` so Canon, Nikon, Sony, Fuji,
etc. can be added without touching the transport. Canon specifically requires the EOS
extension opcodes (`SetRemoteMode` + `SetEventMode`) to keep the physical shutter button
active while connected — this is the same protocol EOS Utility and gPhoto2 use.

### Files
- `data/usb/PtpConnection.kt` — USB bulk-transfer transport. Handles the three PTP
  container phases (command / data / response), the standard PTP interrupt-endpoint event
  channel, and the Samsung-specific first-read buffer cap (see "Samsung One UI
  constraints" below).
- `data/usb/PtpCore.kt` — Vendor-neutral standard PTP operations: OpenSession /
  CloseSession, GetStorageIds, GetObjectHandles (0x1007), GetObjectInfo, GetObject
  (0x1009). Used by every vendor adapter.
- `data/usb/VendorAdapter.kt` — Per-vendor hook points: `initialize` /
  `pollNewImageHandles` / `shutdown`. Contains `GenericPtpAdapter` (standard PTP +
  interrupt endpoint events), `CanonEosAdapter` (0x91xx opcodes + polled GetEvent 0x9127),
  and `NikonAdapter`. New vendors plug in via `VendorAdapter.forVendor(vendorId)`.
  `CanonEosPtp.kt` is gone — it was merged into this file when the adapter pattern was
  introduced.
- `data/usb/MtpCameraManager.kt` — Connection lifecycle manager. Owns the single-threaded
  USB dispatcher, the connection mutex, VendorAdapter selection, the 500 ms adapter poll
  loop, and the 3-second GetObjectHandles diff-poll fallback. Exposes
  `status: StateFlow<TetheredStatus>` and `liveViewFrame: StateFlow<ByteArray?>` (always
  null — T7 and most DSLRs do not expose live view through this pipeline). The name is
  kept for historical reasons — the implementation uses PTP, not `android.mtp`.

### Standard connection sequence
Driven by `MtpCameraManager.openConnection` + `PtpConnection.init` + the selected
`VendorAdapter`:

1. `usbManager.openDevice(device)` — get `UsbDeviceConnection`
2. Find the interface with `interfaceClass == USB_CLASS_STILL_IMAGE` (class 6)
3. `conn.claimInterface(iface, force = true)` — force-claim from the system MTP daemon
4. `SET_INTERFACE(0)` via `controlTransfer(0x01, 0x0B, 0, iface.id, null, 0, 1000)` —
   resets the alternate-setting and endpoint toggle bits; stronger than CLEAR_HALT alone
5. `CLEAR_HALT` on bulk OUT, then bulk IN
6. `Thread.sleep(800)` — Samsung's system MTP daemon needs this long to fully back off
   (see Samsung constraints below)
7. Standard PTP `OpenSession(1)` via `PtpCore`
8. `VendorAdapter.initialize(core, conn)` — for Canon this sends `SetRemoteMode(1)` +
   `SetEventMode(1)`; generic vendors are a no-op
9. Poll loop starts: `adapter.pollNewImageHandles` every 500 ms (Canon GetEvent or
   standard interrupt endpoint); `GetObjectHandles` diff-poll every 3 s as a safety net
10. On each new handle: `GetObjectInfo` → skip non-images → `GetObject` → call
    `onImageReceived(filename, bytes)` which `MainViewModel` renames and writes to
    `filesDir/captures/`

Poll loop seeds `knownHandles` with whatever's already on the card at connect time so
only shots taken after connection are imported — otherwise every reconnect would re-pull
every file on the SD card.

### Samsung One UI constraints (critical)
All testing is done on a Samsung Galaxy (serial `adb-RFCW101K9PA`) running One UI. Two
non-obvious quirks MUST be respected — both produce the same silent failure mode:
`bulkTransfer` returns -1 in ~3 ms, the endpoint is poisoned, and every subsequent
transfer on that `UsbDeviceConnection` fails until it's torn down and rebuilt.

1. **First-read buffer cap of 512 bytes on data-phase reads.**
   `PtpConnection.readDataAndResponse` reads the first chunk into a
   `FIRST_CHUNK_SIZE = 512` buffer, then continues with `CHUNK_SIZE = 16_384`. Asking for
   65 536 bytes on the first bulk-IN read after vendor-extension commands (Canon
   `SetRemoteMode` / `SetEventMode`) instantly returns -1. Do NOT raise either constant
   without re-testing on Samsung One UI.
2. **800 ms sleep after `claimInterface`.**
   Samsung's system MTP daemon needs this long to fully release the USB interface after
   we force-claim it. Shorter delays leave the daemon contending for ownership and the
   PTP session never opens cleanly. The SET_INTERFACE(0) + CLEAR_HALT sequence is also
   required to clear endpoint toggle bits the daemon may have left stale.

Both of these fixes are load-bearing. Treat them as hardware constraints, not code
style.

### Preventing duplicate USB claims
A single `MtpCameraManager` instance must exist, and only one coroutine may claim the
USB interface. Four layers of protection, in the order they were needed:

1. **Single MainViewModel instance.** `NavGraph.kt` scopes `MainViewModel` to the
   Activity via `viewModel(activity)` with
   `activity = LocalContext.current as ComponentActivity`. The default `viewModel()`
   inside a `composable { ... }` block would resolve to the `NavBackStackEntry`'s
   ViewModelStore — a *different* store than the Activity's. That produced two
   `MainViewModel` instances, two `MtpCameraManager`s, and two coroutines racing to
   `claimInterface(force = true)` on the same USB interface. Never revert to the default
   here, and apply the same pattern to any future Activity-scoped VM.
2. **`synchronized(this)` flag** in `connectOrRequestPermission` — atomic check-and-set
   of `isConnecting` so multiple attach-path callers collapse to one launch.
3. **`connectionMutex.withLock`** wraps all of `openConnection`. If a second coroutine
   does slip past the flag, it waits here, and the early `if (core != null) return`
   guard stops it from claiming a second time.
4. **Single-threaded USB dispatcher.** `usbDispatcher` is backed by a dedicated
   `Executors.newSingleThreadExecutor { Thread("PhotoFlow-USB") }`. Every USB I/O
   operation (open, init, poll, download, close) runs on that one thread. Samsung's USB
   stack appears to break if `bulkTransfer` calls on the same `UsbDeviceConnection` cross
   threads, even though the API doesn't document this.

### Why NOT android.mtp.MtpDevice
`android.mtp.MtpDevice` uses standard PTP/MTP. When it holds a session, Canon EOS
cameras lock their physical controls (shutter button, menus). Canon's EOS extension
opcodes (`SetRemoteMode` + `SetEventMode`) are required to keep the camera responsive
while connected — and `android.mtp.MtpDevice` gives no way to send them.

### Canon T7 notes
- VID=0x04A9, PID=0x32E1
- No "PC Connection mode" setting exists on the T7 — it connects with no camera menu
  changes
- Connects as USB Still Image class (PTP), not MTP
- No live view support on T7 — `liveViewFrame` is always null
- Physical shutter fires shots; images arrive as `EOS_EV_OBJECT_ADDED` events in
  principle, but on the T7 `GetEvent` (0x9127) is silent in practice. Images are
  actually picked up by the 3-second `FALLBACK_POLL_MS` `GetObjectHandles` diff-poll.
  Typical post-shot import latency is 1–3 s. This is expected — not a bug to "fix" by
  tightening the poll cadence.
- Working transfer throughput observed: ~140 Mbps, ~12–28 ms end-to-end pipeline per
  image once downloaded.

## Visual design
- Landscape-only
- Material3 dark theme
- High-contrast dark UI
- Professional operations-console feel
- Clear bordered panels and compact status labels
- Large touch targets
- Minimal clutter
- Prioritize active session, live preview, queue state, and warnings
- Avoid consumer-gallery or social-media styling

## Core entities

### Session
- id (autoGenerate)
- barcode — the scanned/entered session code
- startTime — epoch ms
- endTime — epoch ms, nullable
- status — "active" | "complete"

### SessionImage
- id (autoGenerate)
- sessionId — foreign key to Session (indexed: `@Index("sessionId")`)
- filename — renamed filename (per naming config)
- localPath — absolute path on device
- timestamp — epoch ms
- uploadState — UploadState enum
- errorMessage — nullable; set by FtpUploadWorker with human-readable error including IPs
- retryCount — Int, default 0; incremented by auto-retry loop (state stays FAILED during retry)

### UploadState (enum)
- PENDING, UPLOADING, UPLOADED, FAILED, RETRY_REQUIRED

### ConnectionProfile
- id (autoGenerate)
- name — display name (e.g. "P-SERVER-ALPHA")
- host, port, username, password, remotePath
- isActive — only one profile should be active at a time

### AppSettings (DataStore, not Room)
- deviceMode (DeviceMode enum)
- autoReconnect, previewQuality, sessionTimeout
- ftpHost, ftpPort, ftpUsername, ftpPassword, ftpRemotePath
- namingFields (List<NamingField>, pipe-delimited in DataStore)
- namingSeparator, namingExtension
- verboseLogging, saveCrashReports
- autoRetryEnabled (Boolean, default true)
- autoRetryIntervalSeconds (Int, default 4)
- autoRetryMaxCount (Int, default -1 = continuous)
- sessionHistoryMax (Int, default 50; -1 = unlimited) — applied as SQL LIMIT in subquery
- saveBackupToPhone (Boolean, default false) — copies to Pictures/PhotoFlow via MediaStore
- autoDeleteBackups (Boolean, default false)
- autoDeleteAfterDays (Int, default 7)

## UX priorities
At a glance, the operator should always be able to tell:
- The active session barcode (centered, large in top bar)
- Whether live preview/capture is active (mode chip)
- Whether uploads are succeeding (transfer queue panel)
- Whether any files need retry (transfer queue shows FAILED/RETRY state)
- Whether storage/system health is in a warning state (disk warning chip)

## Avoid
- Do not treat MainScreen as a generic gallery
- Do not hide critical transfer/system state in menus
- Do not upload directly from UI callbacks without persistence
- Do not keep critical session state only in memory
- Do not put business logic in Composables
- Do not redesign away from the 4-panel dashboard unless explicitly requested
- Do not introduce ActiveSessionScreen — capture lives in MainScreen
- Do not invent FTP client libraries — use Apache Commons Net
- Do not access the Room database directly from Composables
- Do not create new named connection profiles from MainScreen — that belongs in ConfigScreen
- Do not use `android.mtp.MtpDevice` for DSLR tethering — it locks Canon camera controls and blocks vendor-extension opcodes; use raw PTP via `PtpConnection` + `PtpCore` + `VendorAdapter` instead
- Do not call `openConnection` directly — go through `connectOrRequestPermission`. The `connectionMutex.withLock` guard lives inside `openConnection`, but the `isConnecting` flag must be set first, and all USB I/O must dispatch onto `usbDispatcher`
- Do not add delays shorter than 800 ms after `claimInterface` on Samsung devices — the system MTP daemon needs that time to release the USB interface
- Do not raise `FIRST_CHUNK_SIZE` or `CHUNK_SIZE` in `PtpConnection` without re-testing on Samsung One UI — oversize first-read requests break the endpoint silently
- Do not use the default `viewModel()` for `MainScreen` in `NavGraph.kt` — it must be `viewModel(activity)` with `activity = LocalContext.current as ComponentActivity`, otherwise a second `MtpCameraManager` will be created and race for the USB interface
- Do not move USB I/O off `usbDispatcher` (the `PhotoFlow-USB` single-thread executor) — `bulkTransfer` calls crossing threads on the same `UsbDeviceConnection` break silently on Samsung
- Do not "fix" the 1–3 s post-shot import latency on the Canon T7 by tightening the poll cadence — Canon `GetEvent` is silent on the T7 and the diff-poll is the actual delivery path
