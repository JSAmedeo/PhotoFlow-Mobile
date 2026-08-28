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
- Upload images in the background via FTP or Cloud API (both implemented; Cloud API is the active development path on branch `feature/cloud-api-upload`)
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

### SplashScreen (PhotoFlowSplash composable)
- Shown on cold start before the NavGraph loads; lives in `ui/screens/SplashScreen.kt`
- Dark background (`darkAppColors.background`), centered app icon at 160dp, app name
  "PhotoFlow Mobile" (Bold, 24sp, 2sp letter spacing). No tagline.
- App icon is loaded via `AndroidView` wrapping `ImageView` with
  `ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)` — `painterResource` cannot handle
  adaptive mipmap icons (XML-based) and throws `IllegalArgumentException`. 160dp matches the
  Android 12 OS splash screen icon size spec.
- Driven by `showSplash: Boolean` state in `MainActivity.setContent`; auto-dismisses after
  1.4 s via `LaunchedEffect` → `onComplete()` → sets `showSplash = false` → NavGraph loads
- System splash screen (`core-splashscreen`, `Theme.PhotoFlowMobile.Starting`) fires before
  Compose renders. `installSplashScreen().setOnExitAnimationListener { it.remove() }` in
  `MainActivity.onCreate()` suppresses the OS exit animation so the system splash vanishes
  instantly when Compose renders, eliminating the visible two-screen effect. Android 12+
  always shows an OS splash that cannot be fully removed; suppressing its exit animation
  makes the transition imperceptible.
- Do not use `painterResource(R.mipmap.ic_launcher)` for the splash icon — crashes with
  `IllegalArgumentException` on adaptive icons; use `AndroidView` + `ImageView` +
  `ContextCompat.getDrawable` instead
- Do not lengthen the 1.4 s delay — the splash exists only to display branding, not to gate
  on any loading work

### ScanCardScreen
- Entry point on first launch or when starting a new session
- Camera viewfinder (dominant) with corner-bracket reticle overlay
- Manual entry bar at bottom: text field + GO button
- GO creates a new Session in Room (closes any existing active session first), then navigates to MainScreen
- ML Kit barcode scanning is wired but not yet fully integrated — manual entry is the active path

### MainScreen
- Primary operational dashboard (4-panel layout described above)
- Top bar: app name + session barcode (centered, prominent) + WiFi SSID chip + File Transfers button + hamburger menu
- File Transfers button: green when all clean, orange when any FAILED. Opens a
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
- Two full-width banners sit directly under the top bar, in both orientations:
  - `PastSessionBanner` (amber `warning`) whenever `pastSessionActive` — the active session is
    not the most recently started one, so capture has moved to an earlier session. Carries a
    GO TO NEWEST action.
  - `MissedShotsBanner` (red `error`, dismissible) whenever `missedWhileDisconnected > 0` —
    shots appeared on the camera card while the cable was unplugged and were seeded as
    pre-existing, so they will never import. Warning only; it does not recover them.
  Both live at MainScreen top level rather than inside `TetheredPanel`, whose guidance overlay
  hides once images are showing — exactly when these matter. Their explanatory text wraps to
  two lines rather than truncating; the truncated half was the load-bearing one on a narrow
  screen ("still on the camera card", and the session code).
- `BackHandler { activity.moveTaskToBack(true) }` — back press backgrounds the app instead of
  destroying the Activity, preserving the USB camera session
- Camera capture: ImageCapture use case held in MainViewModel; fires CameraX takePicture()
  to a temp file (`tmp_<timestamp>.jpg`), then under `captureMutex` reads the DB count,
  assigns sequence number, renames the temp file to the final filename, and inserts
  SessionImage (PENDING) into Room. Enqueues `UploadWorker` (single unified worker; resolves
  FTP vs Cloud API at execution time). Logs `capture: firing`, `capture: saved`, or
  `capture: FAILED code=N` to `PhotoFlow/Pipeline` tag.
- **Sequence number race fix:** `captureMutex` (a `kotlinx.coroutines.sync.Mutex` in
  `MainViewModel`) serialises the count-read → filename-build → file-rename → DB-insert
  critical section for both native capture (`capturePhoto`) and tethered image receipt
  (`handleTetheredImage`). Without the mutex, two rapid captures can read the same DB count
  and receive the same sequence number. The temp-file approach allows CameraX's slow
  hardware capture to happen outside the mutex — only the metadata assignment step is locked.
- `reviewImage: StateFlow<SessionImage?>` in MainViewModel — resolves to the manually selected
  thumbnail (if set via `selectReviewImage()`) or `sessionImages.firstOrNull()`. Auto-clears
  to latest when the session changes or a new photo arrives (observer compares first-image ID)
- `noSessionsExist: StateFlow<Boolean>` in MainViewModel — `SharingStarted.Eagerly` so Room
  collection begins at ViewModel creation, before Compose subscribes. On MainScreen first
  composition, `LaunchedEffect(Unit)` collects this flow with `.filter { it }.first()` (suspending
  until `true`) then calls `viewModel.showNoSessionPrompt()`. Must collect the flow — never sample
  `.value` directly — because Room can take 2–3 s on first load (observed 2.6 s on Motorola G
  2025) and the initial `false` value would produce a false "has sessions" read.
- `TetheredPanel` shows a bold "START NEW SESSION\nBEFORE TAKING PHOTO" warning in
  `LocalAppColors.current.warning` color when `status.state == TetheredState.CONNECTED &&
  noSessionsExist`. The `noSessionsExist: Boolean` parameter is threaded from MainScreen →
  `CenterPanel`/`PortraitCenterArea` → `TetheredPanel`. Warning disappears as soon as a session
  exists. Native Camera mode does not need this — the CAPTURE button already triggers the prompt.
- `showNoSessionPrompt()` is a public function in MainViewModel that sets
  `_showNoSessionPrompt.value = true`. It auto-clears in the `activeSession.collect` observer when
  a non-null session starts. It is called only from `MainScreen`'s `LaunchedEffect(Unit)` — not
  from `handleTetheredImage()` (which just silently drops the image and logs it).

### ConfigScreen
- Left nav rail + right scrollable content
- Sections (in order): DEVICE MODE, GENERAL, CONNECTIONS, FILE NAMING, ABOUT
- GENERAL section contains: FILE TRANSFER (auto-retry toggle/interval/max), SESSION HISTORY
  (max history dropdown: 10/25/50/100/200/Unlimited, default 50; CLEAR SESSION HISTORY button
  with confirmation dialog — deletes all completed sessions and their `session_images` rows
  from Room via `ConfigViewModel.clearSessionHistory()`; preserves the active session and
  physical image files on disk), PHOTO BACKUP (save to Pictures/PhotoFlow toggle; auto-delete
  backups toggle + age dropdown), STORAGE (real StatFs available space; Clear App Cache with
  confirmation dialog), LOGS (enable/disable logging toggle), IMPORT / EXPORT (export all
  settings + connection profiles to a timestamped JSON in Downloads; import via file picker
  — replaces DataStore settings and all Room ConnectionProfile rows atomically), APP INFO
- All settings are auto-saved to DataStore on change (no explicit SAVE button)
- Device Mode selection (Tethered DSLR / Native Camera) propagates to MainScreen in real time
- DEVICE MODE section contains three groups: device mode cards (Tethered/Native), DISPLAY MODE
  (dark mode toggle — immediately reflected system-wide via `MainViewModel.darkMode` StateFlow),
  and ORIENTATION LOCK (enabled toggle + lock-to dropdown: Landscape / Landscape 180 / Portrait
  / Portrait 180)
- `SettingsTransferResult` sealed class in `ConfigViewModel.kt` drives result dialog shown at
  `ConfigScreen` level: `ExportSuccess(displayPath)`, `ImportSuccess`, `Error(message)`
- `ConfigViewModel.exportSettings(context)` and `importSettings(context, uri)` run on
  `Dispatchers.IO`; use `org.json.JSONObject` (built-in Android, no new dependency)
- GENERAL section also contains a CLOUD API subsection with: Setup Code text field (sent to
  `POST /devices/register-with-setup-code`), API Key field (masked by default; SHOW/HIDE toggle),
  Station Name field, Device Display Name field, read-only Device UUID and Device ID rows,
  read-only Venue ID and Venue Slug (shown only when non-blank/non-zero — returned by backend
  after registration), REGISTER DEVICE button (calls `ConfigViewModel.registerDevice()`), and
  FETCH MANIFEST button (shown only when an active session exists; calls `fetchManifest()`).
  Registration state and manifest result are shown as inline status text below the buttons.
  Error messages per contract: 401 → "check API key", 404 → "invalid setup code", blank
  setup code → "Enter a Setup Code first".

## Navigation
- Jetpack Navigation Compose
- Three top-level destinations: ScanCardScreen, MainScreen, ConfigScreen
- MainScreen is the `startDestination`. ScanCardScreen is reached from the no-session
  prompt or the + NEW SESSION button — it is not the start destination, despite being the
  conceptual entry point
- After session creation: navigate to MainScreen, clearing back stack
- MainScreen hamburger → ConfigScreen
- MainScreen + NEW SESSION → ScanCardScreen

## Session selection — selection IS activation

- There is no separate "viewing" session. `selectedSessionId` is derived from `activeSession`,
  so thumbnails, review pane, shot count, and **both capture paths** always agree.
- Tapping a row in Session History calls `MainViewModel.selectSession()`, which **activates**
  that session via `SessionRepository.activateSession()` — a single transaction that closes
  every other active session and reopens the target. The operator can therefore add a shot to
  an earlier session deliberately.
- Re-scanning a barcode that already has a session re-activates it through the same
  transaction, so its sequence numbers and capture codes continue rather than restarting.
- `pastSessionActive` is true whenever the active session is not the most recently started one;
  MainScreen shows a persistent `PastSessionBanner` while it is, with a GO TO NEWEST action.

**Why:** an earlier model had `_overrideSessionId` decouple "viewing" from "active".
`handleTetheredImage` read the *selected* session while native capture read the *active* one, so
reviewing an earlier customer mid-shoot silently filed subsequent tethered shots into that old
session — old barcode in the filename, old capture code, uploaded to the old cloud session —
while the bottom bar labelled the reviewed session "Active Session". Do not reintroduce an
override; if a "look without switching" mode is ever wanted, it must not feed either capture path.

## File naming
- Configured in ConfigScreen → File Naming section
- Three default fields: Custom, Barcode, Sequence Number (user can add/remove/reorder)
- `buildFilename(settings, barcode, seqNum)` in AppSettings.kt generates the actual filename
- Renamed filename is stored in `SessionImage.filename` and shown everywhere (thumbnails,
  transfer queue, review pane)

## Key libraries
- CameraX 1.3.4 — camera preview (`PreviewView`) and capture (`ImageCapture`)
- ML Kit 17.3.0 — barcode scanning (wired, not yet active in UI)
- Room 2.6.1 — Session, SessionImage, ConnectionProfile tables; current schema version 9
- WorkManager 2.9.0 — single `UploadWorker` (replaces old `FtpUploadWorker`/`CloudUploadWorker`);
  resolves FTP vs Cloud API at execution time from the active `ConnectionProfile`; uses
  `EXPONENTIAL` back-off + `NetworkType.CONNECTED` constraint; `uploadMutex` in companion
  prevents concurrent uploads; `runAttemptCount >= 10` caps WorkManager retries and falls
  back to `Result.failure()`; ViewModel `startAutoRetryLoop()` drives FAILED→re-enqueue on
  configurable interval. `FtpUploadExecutor` and `CloudUploadExecutor` are internal objects
  called by `UploadWorker`, not registered as workers.
- `CredentialStore` (`data/security/CredentialStore.kt`) — **the single authoritative store
  for every secret**: FTP passwords and the Cloud API key are never written to Room or
  DataStore. Opens defensively: an unreadable store (restored ciphertext with no Keystore key,
  or a Keystore reset) is discarded and recreated once, falling back to an in-memory map if
  even that fails — it must not throw, because it is reached from `Application.onCreate` and an
  uncaught failure there took the whole app down at launch. Wraps `EncryptedSharedPreferences`
  (AES256-SIV keys, AES256-GCM values) for FTP passwords keyed by profile ID and the Cloud
  API key. `ConnectionProfile.password` is stored blank in Room; passwords live only in
  `CredentialStore`. Migration from DataStore/Room plaintext runs once in
  `PhotoFlowApplication.onCreate()` via `migrateSecretsToCredentialStore()`.
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
USB interface. Five layers of protection, in the order they were needed:

1. **Single MainViewModel instance.** `NavGraph.kt` scopes `MainViewModel` to the
   Activity via `viewModel(activity)` with
   `activity = LocalContext.current as ComponentActivity`. The default `viewModel()`
   inside a `composable { ... }` block would resolve to the `NavBackStackEntry`'s
   ViewModelStore — a *different* store than the Activity's. That produced two
   `MainViewModel` instances, two `MtpCameraManager`s, and two coroutines racing to
   `claimInterface(force = true)` on the same USB interface. Never revert to the default
   here, and apply the same pattern to any future Activity-scoped VM.
2. **`android:launchMode="singleTop"` on `MainActivity`.** Without this, every
   `USB_DEVICE_ATTACHED` broadcast delivered while the app is running creates a *new*
   `MainActivity` instance — a new `ViewModelStore`, a new `MainViewModel`, and a new
   `MtpCameraManager`. Observed in production: three `[APP] launched` log entries within
   15 s in the same PID, three racing PTP connections, three zombie poll loops, all
   commands returning `sent=-1`. `singleTop` routes the intent to `onNewIntent()` on the
   existing instance instead. `MainActivity.onNewIntent()` already handles the attach
   event correctly. Do not change the launchMode.
3. **`synchronized(this)` flag** in `connectOrRequestPermission` — atomic check-and-set
   of `isConnecting` so multiple attach-path callers collapse to one launch.
4. **`connectionMutex.withLock`** wraps all of `openConnection`. If a second coroutine
   does slip past the flag, it waits here, and the early `if (core != null) return`
   guard stops it from claiming a second time.
5. **Single-threaded USB dispatcher.** `usbDispatcher` is backed by a dedicated
   `Executors.newSingleThreadExecutor { Thread("PhotoFlow-USB") }`. Every USB I/O
   operation (open, init, poll, download, close) runs on that one thread. Samsung's USB
   stack appears to break if `bulkTransfer` calls on the same `UsbDeviceConnection` cross
   threads, even though the API doesn't document this.

### Why NOT android.mtp.MtpDevice
`android.mtp.MtpDevice` uses standard PTP/MTP. When it holds a session, Canon EOS
cameras lock their physical controls (shutter button, menus). Canon's EOS extension
opcodes (`SetRemoteMode` + `SetEventMode`) are required to keep the camera responsive
while connected — and `android.mtp.MtpDevice` gives no way to send them.

### Non-Samsung hosts and USB permission

Tested on Motorola G 2025 (serial `adb-ZY32L9TX7B`). Key difference from Samsung:
`USB_DEVICE_ATTACHED` is **not dispatched** to PhotoFlow even though a matching USB device filter
is declared in the manifest. Several system apps and potentially third-party tether apps
(`com.android.mtp`, `com.google.android.apps.photos`, any installed camera tether app) are also
registered for PTP class-6 devices and compete for the attach intent.

Six fixes are in place:

1. **`onResume` rescan.** `MainActivity.onResume()` calls `mainViewModel.rescanUsbDevices()` →
   `MtpCameraManager.rescanForAttachedCamera()`, which re-scans `usbManager.deviceList` and
   calls `connectOrRequestPermission()` for any PTP device not yet claimed. This fires on every
   app foreground, catching cameras plugged in while the app was backgrounded.

2. **Explicit PendingIntent.** On API 26+, implicit broadcasts are not delivered to
   `RECEIVER_NOT_EXPORTED` receivers. The USB permission PendingIntent must use
   `Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)` (explicit package) plus
   `FLAG_UPDATE_CURRENT` (discard stale intents from prior calls with the same request code).

3. **8-second `isConnecting` timeout.** If the USB permission dialog is suppressed (e.g. the
   Android CAMERA permission dialog appears simultaneously on first launch and blocks it),
   `isConnecting` would stay `true` forever and block the foreground rescan loop. A coroutine
   resets `isConnecting = false` after 8 s if `core` is still null, so the 5 s rescan loop
   retries quickly rather than waiting the original 30 s.

4. **Competing default app.** If another USB tether app was previously set as the default handler
   for PTP devices, Android routes `USB_DEVICE_ATTACHED` to it without showing the app-chooser.
   Our `requestPermission()` path still works — the permission dialog fires from the rescan
   regardless of which app got the attach intent — but the other app may claim the USB interface
   first and block our `openDevice()`. User must clear the other app's USB defaults:
   Settings → Apps → [other app] → Open by default → Clear defaults.

5. **Foreground hot-plug rescan loop.** `onResume()` only fires when the app is re-foregrounded,
   so cameras plugged in while PhotoFlow is already on-screen are never detected on Motorola.
   `MtpCameraManager.init {}` starts a coroutine that calls `rescanForAttachedCamera()` every
   5 s (`FOREGROUND_RESCAN_MS`). `permissionDenied` flag prevents re-prompting after an explicit
   denial; it resets to `false` in `onDeviceDetached()` so re-plugging the cable re-enables asking.

6. **`resetAndRescan()` — mode-switch recovery path.** The Android CAMERA permission dialog
   (triggered by ScanCardScreen's barcode viewfinder on first launch) can suppress the USB
   permission dialog, leaving `isConnecting = true` and `permissionDenied` potentially set with
   no dialog ever shown. If the user accidentally swipes the USB dialog away, `permissionDenied`
   also permanently blocks the rescan loop until the cable is re-plugged.
   `MtpCameraManager.resetAndRescan()` atomically clears both flags under `synchronized(this)`,
   then immediately calls `rescanForAttachedCamera()`. It is called automatically by a
   `deviceMode`-watching coroutine in `MainViewModel.init {}` on every transition to
   `TETHERED_DSLR` — so switching from Native → Tethered in ConfigScreen gives a guaranteed clean
   retry without requiring an app restart.

**HARD RULES for the USB permission subsystem — do not violate without re-testing on both devices:**

- **Do not change `android:launchMode` away from `singleTop` on `MainActivity`.** This is the
  root fix for the multi-MtpCameraManager zombie problem. With `standard` launchMode, plugging
  the camera in while the app is running creates a new Activity instance, a new ViewModel, and
  a new MtpCameraManager that races the existing one for the USB interface. The result is zombie
  poll loops that spam `sent=-1` indefinitely and require an app restart. `singleTop` routes
  `USB_DEVICE_ATTACHED` to `onNewIntent()` on the existing Activity instead.

- **Do not remove `permissionDenied = true` from the exhausted-retry path in `openConnection`.**
  After all retry attempts fail (e.g. camera in bad state from a prior competing connection),
  this flag prevents the 5-second foreground rescan from immediately queueing another attempt.
  Without it, each failure triggers a new rescan → new `openConnection` (holds `connectionMutex`
  for up to ~9 s across 3 retries) → another failure → another rescan, compounding zombie
  sessions that poison the USB endpoint. Recovery: physical cable re-plug (`onDeviceDetached`
  resets `permissionDenied`) or mode switch (`resetAndRescan` resets it). Critically,
  `connectOrRequestPermission()` does NOT check `permissionDenied` — so
  `USB_DEVICE_ATTACHED` → `onDeviceAttached()` still retries correctly on replug.

- **Do not place the `DISCONNECTED`-status check before the `attempt > 0` guard in the
  `openConnection` retry loop.** The initial value of `_status` is `DISCONNECTED`. Checking
  `_status.value.state == DISCONNECTED` on attempt 0 aborts every connection attempt before
  it ever starts. The check is valid only for attempt > 0, where `closeConnection()` may
  have set DISCONNECTED while the coroutine was suspended in `delay(CONNECT_RETRY_DELAY_MS)`.

- **Do not remove `resetAndRescan()`.** It is the only recovery path when `permissionDenied` is
  set from an accidental dialog swipe or a suppressed-dialog first launch. Without it, the user
  must unplug and replug the camera or restart the app.
- **Do not remove or weaken the `deviceMode` watcher** in `MainViewModel.init {}` that calls
  `resetAndRescan()`. This is what makes mode-switch recovery reliable. Removing it means the
  user must physically disconnect the camera to clear a stuck state.
- **Do not lower `isConnecting` timeout below 8 s.** On Motorola, the CAMERA permission dialog
  can take several seconds to appear and be dismissed. The timeout must be long enough to avoid
  a false retry that shows the USB dialog while the camera dialog is still on screen.
- **Do not change `rescanForAttachedCamera()` to ignore `permissionDenied`.** If the user
  deliberately denies USB access, the loop must stop re-asking. Only a cable re-plug or mode
  switch (via `resetAndRescan()`) should clear that state.
- **Do not add `_showNoSessionPrompt.value = true` to `handleTetheredImage()`.** The no-session
  prompt is now driven by a startup `LaunchedEffect` in `MainScreen` collecting `noSessionsExist`
  — not by tethered image arrival. The tethered path just silently drops the image and logs it.
  Adding a prompt there would fire when the user may not be on MainScreen at all.

- **Do not let `readDataAndResponse` return a short payload.** It compares `received` against
  the declared container length and returns null on a shortfall. Without that check a
  mid-transfer USB hiccup yields a partial JPEG that is saved, renamed, shown in the review
  pane and uploaded, with nothing indicating it is incomplete.

- **Do not change `expectedPayloadBytes` to return a real size for the unknown-length
  sentinel.** Cameras streaming without a declared length send 0xFFFFFFFF, which reads back as
  -1 and must map to 0 — meaning "not declared, nothing to enforce". Returning a large
  expectation would reject every such transfer as truncated and break tethering on those
  bodies. Pinned by `PtpConnectionPayloadTest`.

- **Do not simplify `teardownAfterPollFailure()` into a `closeConnection()` call.** It runs on
  `pollingJob`, which `closeConnection()` cancels — the teardown would cancel itself partway
  through. It also deliberately leaves `permissionDenied` alone so the caller can decide
  whether the rescan may reconnect.

- **Do not remove the `CancellationException` rethrow ahead of the poll loop's general
  `catch`.** Without it a normal disconnect is treated as a fault: it runs the recovery path
  and overwrites the DISCONNECTED status with a "Camera lost" error.

- **Do not drop the failed handle from `knownHandles` bookkeeping.** A failed download removes
  its handle so the 3 s diff-poll re-offers it; otherwise a shot lost to a transient bus error
  is lost permanently. Capped at `MAX_DOWNLOAD_ATTEMPTS` so one unreadable object cannot loop.

- **Do not treat `missedWhileDisconnected` as a basis for importing missed shots.** It is a
  count comparison, not per-file identity: deleting images on the camera can mask a missed
  shot, a swapped card reports a bogus number, and the baseline is in memory so a disconnect
  spanning an app restart reports nothing. Fine for a warning; wrong for deciding what to
  import. See "disconnected-shot recovery" in `.claude/prompts/field-readiness-pass.md`.

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
- Material3 with full light/dark mode support — `PhotoFlowMobileTheme(darkMode: Boolean)` drives
  both the Material `colorScheme` and the custom `LocalAppColors` CompositionLocal
- `AppColorScheme` data class (`Color.kt`) — `darkAppColors` / `lightAppColors`; access via
  `LocalAppColors.current.xxx` in all Composables. Never use hardcoded `Color(0xFF...)` for UI
  colors — always go through `LocalAppColors.current`
- Canvas DrawScope limitation: `LocalAppColors.current` cannot be called inside a `Canvas { }`
  block (not a composable context). Capture colors as local `val`s before the Canvas block and
  reference them by closure inside the DrawScope.
- Default is dark mode; toggled via ConfigScreen → DEVICE MODE → DISPLAY MODE
- High-contrast dark UI; light mode also high-contrast
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
- sessionKeyType — "barcode" | "manual" | "custom" (added schema v7)
- displayLabel — human-readable label sent to cloud backend (added schema v7)
- cloudSessionId — backend session id returned from POST /sessions/upsert (added schema v7)

### SessionImage
- id (autoGenerate)
- sessionId — foreign key to Session (indexed: `@Index("sessionId")`)
- filename — renamed filename (per naming config)
- localPath — absolute path on device
- timestamp — epoch ms
- uploadState — UploadState enum
- errorMessage — nullable; set by upload worker with human-readable error
- retryCount — Int, default 0; incremented by auto-retry loop (state stays FAILED during retry)
- cloudPhotoUid — backend public photo identifier returned on upload (added schema v6/v7)
- cloudPhotoId — backend internal numeric id, debug only (added schema v7)
- cloudUploadedAt — epoch ms from backend `uploaded_at` timestamp (added schema v7)
- captureCode — canonical code for barcode venues, e.g. "XYZ507665_01" (added schema v7)
- captureSequence — ordinal within the session, 1-based (added schema v7)
- sortOrder — ordering value; defaults to captureSequence (added schema v7)

### UploadState (enum)
- PENDING, UPLOADING, UPLOADED, FAILED

### ConnectionProfile
- id (autoGenerate)
- name — display name (e.g. "P-SERVER-ALPHA")
- connectionType — FTP | CLOUD_API
- host, port, username, password, remotePath
- photoOp — optional operation label (added schema v8, default "")
- isActive — only one profile should be active at a time

### AppSettings (DataStore, not Room)
- deviceMode (DeviceMode enum)
- darkMode (Boolean, default true) — drives `PhotoFlowMobileTheme` and status bar appearance
- orientationLockEnabled (Boolean, default false)
- orientationLock (OrientationLock enum — LANDSCAPE / LANDSCAPE_180 / PORTRAIT / PORTRAIT_180)
- namingFields (List<NamingField>, pipe-delimited in DataStore)
- namingSeparator, namingExtension
- loggingEnabled (Boolean, default true) — gates both logcat and the on-device log file
- verboseLogging (Boolean, default false) — also writes DEBUG to the file. Off by default:
  the PTP poll loop logs at DEBUG every 500 ms, which is unusable in a file over a session
- autoRetryEnabled (Boolean, default true)
- autoRetryIntervalSeconds (Int, default 4)
- autoRetryMaxCount (Int, default 3; -1 = continuous) — bounded by default. Continuous
  retries terminal failures (bad API key, 422, unregistered device) forever, re-uploading
  the full file every interval. A one-time DataStore migration (`settings_schema_v` = 1)
  rewrites a persisted -1 to 3 exactly once, so a later deliberate choice of Continuous sticks
- sessionHistoryMax (Int, default 50; -1 = unlimited) — applied as SQL LIMIT in subquery
- saveBackupToPhone (Boolean, default false) — copies to Pictures/PhotoFlow via MediaStore
- autoDeleteBackups (Boolean, default false)
- autoDeleteAfterDays (Int, default 30)
- cloudSetupCode (String, default "") — venue setup code sent on `POST /devices/register-with-setup-code`
- cloudVenueSlug (String, default "") — returned by backend after registration; persisted; not sent in requests
- cloudVenueId (Int, default 0) — returned by backend after registration; 0 means not yet registered
- cloudDeviceDisplayName (String, default "") — friendly name sent on registration
- cloudDeviceUuid (String, default "") — stable UUID generated once, persisted before first API call
- cloudDeviceId (Int, default 0) — backend device id returned after registration; 0 means not registered
- **cloudApiKey is NOT an AppSettings field.** AppSettings is serialised verbatim into
  DataStore in plaintext, so a field here would rewrite the key in the clear on every save.
  It lives only in `CredentialStore`; `ConfigViewModel.cloudApiKey` / `setCloudApiKey()`
  expose it to the UI. `SettingsKeys.CLOUD_API_KEY` survives for migration reads only
- cloudStationName (String, default "") — optional station label sent with registration and photo uploads

FTP credentials are stored in `ConnectionProfile` (Room), NOT in AppSettings.
Cloud API key is stored in AppSettings (DataStore), NOT in ConnectionProfile.

## UX priorities
At a glance, the operator should always be able to tell:
- The active session barcode (centered, large in top bar)
- Whether live preview/capture is active (mode chip)
- Whether uploads are succeeding (transfer queue panel)
- Whether any files need retry (transfer queue shows FAILED/RETRY state)
- Whether any shots were missed while the camera was unplugged (red MissedShotsBanner)
- Whether capture has moved to an earlier session (amber PastSessionBanner)

(There is no disk/storage warning chip on MainScreen — real `StatFs` reporting lives only
in ConfigScreen → GENERAL → STORAGE.)

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
- Do not change `android:launchMode` away from `singleTop` on `MainActivity` — `standard` launchMode causes Android to create a new Activity instance on every `USB_DEVICE_ATTACHED`, producing a second MtpCameraManager that races the existing one; observed as 3× `[APP] launched` in the same PID, zombie poll loops, all USB commands returning `sent=-1`
- Do not remove `permissionDenied = true` from the exhausted-retry path in `openConnection` — without it, each failure causes the foreground rescan to immediately retry, cascading into compounding zombie PTP sessions; see HARD RULES above for the full recovery contract
- Do not place the `_status == DISCONNECTED` check before the `attempt > 0` guard in `openConnection` — `_status` starts as `DISCONNECTED`, so checking it unconditionally aborts every connection before it starts; check only on retries (attempt > 0) where `closeConnection()` may have set it while suspended
- Do not move USB I/O off `usbDispatcher` (the `PhotoFlow-USB` single-thread executor) — `bulkTransfer` calls crossing threads on the same `UsbDeviceConnection` break silently on Samsung
- Do not "fix" the 1–3 s post-shot import latency on the Canon T7 by tightening the poll cadence — Canon `GetEvent` is silent on the T7 and the diff-poll is the actual delivery path
- Do not use an implicit `Intent(ACTION)` inside a USB permission PendingIntent — always add `.setPackage(context.packageName)` to make it explicit; implicit broadcasts are silently dropped by `RECEIVER_NOT_EXPORTED` receivers on API 26+
- Do not use `PendingIntent.FLAG_MUTABLE` alone for USB permissions — also add `FLAG_UPDATE_CURRENT` to discard stale intents from prior calls with the same request code
- Do not rely solely on `USB_DEVICE_ATTACHED` intent delivery for tethering — Motorola and other non-Samsung hosts may not dispatch it to the app; the `onResume` rescan path in `MainActivity` is the primary trigger on those devices
- Do not hardcode `Color(0xFF...)` values for UI colors — always use `LocalAppColors.current.xxx` so light/dark mode works correctly
- Do not use `painterResource(R.mipmap.ic_launcher)` to display the app icon in Compose — adaptive mipmap icons are XML and `painterResource` throws `IllegalArgumentException`; use `AndroidView` wrapping `ImageView` with `ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)` instead
- Do not read `noSessionsExist.value` directly in a `LaunchedEffect` — on Motorola G 2025, Room
  takes 2–3 s to emit the first DB result; the StateFlow initial value is `false` (appears to have
  sessions) and the prompt will silently not fire. Always collect the flow with
  `.filter { it }.first()` to suspend until Room actually confirms the table is empty.
- Do not bind a `BasicTextField` directly to a DataStore-backed StateFlow value — every keystroke
  triggers a DataStore write → StateFlow emission → recomposition that overwrites the field value
  and cursor position, causing skipped characters and cursor jumps. Always keep a local `var draft
  by remember(key) { mutableStateOf(externalValue) }` as the source of truth for text inputs, and
  call the persistence callback alongside the local update.
- Do not construct `CloudApiClient(host)` without passing the API key — use
  `CloudApiClient(host, settings.cloudApiKey)`. The key is blank when not configured
  (header is omitted), but the pattern must be consistent so staging auth works everywhere.
- Do not send `cloudVenueId` to the backend when it is 0 — it means the device is not yet
  registered. `fetchManifest()` guards on this with an early return.
- Do not use `POST /devices/register` — the Phase 1 endpoint is `POST /devices/register-with-setup-code`
  with a `setup_code` field (not `venue_slug`). `venue_slug` is returned in the response and persisted.
- Do not reference `cloudVenueSlug` as the value sent for registration — it is persisted FROM the
  response. The user-entered value sent TO the registration request is `cloudSetupCode`.
- Do not use `photo_id` (numeric) in new code — always use `photo_uid` (the opaque public identifier
  returned in upload responses) for photo retrieval via `GET /photos/{photo_uid}/file`.
- Do not use `session_code` in new code — prefer `session_key` everywhere; `session_code` is a
  compatibility alias only for reading backend response fields.
- Do not assign sequence numbers outside `captureMutex` — the count-read and DB-insert must
  be atomic or two rapid captures will receive the same sequence number.
- Do not use `domain-config` in `network_security_config.xml` to allow cleartext HTTP to
  IP addresses by subnet — Android does not support subnet notation there. The app uses
  `<base-config cleartextTrafficPermitted="true">` since the backend URL is user-configured
  and the LAN IP can change. This is a staging-only app.
- Do not send a new `idempotency_key` on retry — always use `image.id.toString()` as the
  stable key so the backend can deduplicate retries and return HTTP 200 with the same
  `photo_uid` instead of creating a duplicate record.
- Do not register `FtpUploadWorker` or `CloudUploadWorker` — both are deleted; the single
  `UploadWorker` handles both transport types by reading the active `ConnectionProfile` at
  execution time. Registering the old class names will throw `ClassNotFoundException`.
- Do not store FTP passwords in `ConnectionProfile.password` (Room) — always write blank to
  Room and persist via `credentialStore.storeFtpPassword(profileId, password)`. Likewise, do
  not write the Cloud API key to DataStore directly from new code; use
  `credentialStore.storeCloudApiKey(key)` (the DataStore field is a migration fallback only).
- Do not pass plain HTTP URLs that resolve to non-LAN hosts to `CloudApiClient` — the client
  throws `IllegalArgumentException` in `openConnection()` for `http://` URLs whose host is
  not classified as private by `PrivateAddressChecker`. Use `https://` for internet hosts.
- Do not reintroduce `_overrideSessionId` or any "viewing session" separate from the active
  session — the two capture paths must read one source of truth, or tethered shots silently
  land in whichever session the operator happens to be reviewing
- Do not read the active session from `activeSession.value` on the tethered path — that flow is
  `WhileSubscribed`, and the poll loop runs with no UI subscribed after `moveTaskToBack`. Use
  `repository.getActiveSession()`, which reads Room directly
- Do not use `ExistingWorkPolicy.REPLACE` in the auto-retry loop — REPLACE resets WorkManager's
  `runAttemptCount` to 0, so the executors' `runAttemptCount >= 10` cap can never trip. Use
  `KEEP`. The manual RETRY button is the one place REPLACE is correct
- Do not add `cloudApiKey` (or any secret) to `AppSettings` — it is serialised verbatim into
  plaintext DataStore, so a field there rewrites the secret in the clear on every save
- Do not add credentials to the settings export — it is written to shared Downloads storage,
  readable by other apps and swept up by cloud backup. `SettingsExportTest` fails if a
  secret-bearing key or value reappears
- Do not pass `fallback = settings.cloudApiKey` to `getCloudApiKey()` — the fallback parameter
  exists solely for the one-time DataStore migration; a fallback at a call site resurrects the
  plaintext copy the migration removes
- Do not assume Android excludes `shared_prefs` from backup — it does not. The encrypted
  credential file needs an explicit `<exclude domain="sharedpref" …>` in both rule files
- Do not perform blocking file I/O on the tethered path without `withContext(Dispatchers.IO)` —
  `handleTetheredImage` runs on the single `PhotoFlow-USB` thread, so a full-size write there
  stalls image polling for its duration
- Do not call `android.util.Log` directly in app code — import
  `com.photoflowmobile.app.data.logging.PhotoFlowLog as Log` so the line also reaches the
  on-device file. A direct call still works but is invisible after a reboot, which is exactly the
  gap the file logger closes
- Do not make `PhotoFlowLog`'s file write synchronous — it is called from the single
  `PhotoFlow-USB` thread, and a blocking write there stalls image polling
- Do not route DEBUG to the file unconditionally — the PTP poll loop logs every 500 ms, which is
  unusable over a session. That is what the verbose toggle gates
- Do not treat `app/build/outputs/apk/debug` as an archive — Gradle wipes stale files from its own
  output directories on every build. Previous iterations live in `builds/`
- Do not add database or DataStore files to the Android backup set — `backup_rules.xml` and
  `data_extraction_rules.xml` exclude `photoflow.db*` and `datastore/`. Restoring a backup
  across devices would inject a stale Room schema and stale credentials into a fresh install.

## Logging

`PhotoFlowLog` (`data/logging/PhotoFlowLog.kt`) is a drop-in replacement for `android.util.Log`
that also writes to a rotating file at `filesDir/logs/photoflow-YYYY-MM-DD.log`.

**Call sites are unchanged.** Each file does
`import com.photoflowmobile.app.data.logging.PhotoFlowLog as Log`, so every existing
`Log.i(TAG, msg)` keeps working. Signatures mirror `android.util.Log` exactly, including the Int
return. The alias means the fragile PTP files carry a one-line change rather than 53 edits.

**Why it exists.** Logcat is a RAM ring buffer — 256 KiB by default on these handsets, reset by a
reboot, and `logcat -G` sizing resets with it. After the 2026-08-20 field test the device had
rebooted, so nothing from the session survived. A field test runs with no laptop attached, so
post-hoc capture is impossible by construction.

**Two constraints shape the design — do not undo either:**

1. **Never block the caller.** Logging happens on the single `PhotoFlow-USB` thread, where a
   synchronous file write would stall image polling — the problem FR-6 fixed. Lines go to a
   bounded `Channel` with `DROP_OLDEST`, drained by one writer coroutine on `Dispatchers.IO`.
   Under flood the oldest lines are lost rather than the poll loop stalling.
2. **Filter by level.** DEBUG reaches the file only when `verbose` is set. INFO and above always
   do (when enabled). Logcat output is never gated, so `adb logcat` behaves as always.

Rotation is by day, keeping 7 days or 20 MB, whichever binds first. `logCrashSync` writes an
uncaught exception synchronously — bypassing the queue, because the process is about to die and
the writer coroutine will never drain. Retrievable without ADB via
ConfigScreen → GENERAL → LOGS → EXPORT LOGS.

## Debug build identity

Every debug build gets an iterating number from `app/build-number.properties` (gitignored):
`versionName` reads `"1.2 (build N)"`, the APK is `PhotoFlow-debug-bNNN-<timestamp>.apk`, and
`BuildConfig.BUILD_NUMBER` exposes it. Before this, every build reported `"1.2"` and an installed
build could not be identified from `dumpsys package` — which bit when a stale APK from a failed
build sat in the output directory looking current.

The counter advances only when an APK is actually produced, not on IDE syncs or test runs.
`assembleDebug` also copies the APK to `builds/`, outside the directory Gradle wipes:
`app/build/outputs/apk/debug` holds **only the current build**, because Gradle removes stale files
from its own output directories. That directory is never an archive.

## Testing records

`testing-logs/` holds machine-captured evidence from live sessions and the tooling to produce it —
`capture_session.py` snapshots a device after a session and writes a report with integrity checks;
`make_handoff.py` bundles reports for a stakeholder update. Reports are committed, raw snapshots
are gitignored. `TESTING.md` holds procedures, pass criteria and evidence-based test priorities.

## Permissions

| Permission | Why required | Graceful degradation |
|---|---|---|
| `CAMERA` | CameraX live preview and capture in Native Camera mode | No preview or CAPTURE button without it |
| `INTERNET` | FTP and Cloud API uploads | Uploads fail; app still usable for capture |
| `WRITE_EXTERNAL_STORAGE` (maxSdk=28) | Save images on Android ≤8 | Not needed on Android 9+ |
| `ACCESS_WIFI_STATE` | Detect Wi-Fi connectivity for the SSID chip | Chip shows "WiFi not connected" |
| `ACCESS_FINE_LOCATION` | Read Wi-Fi SSID via `WifiManager.connectionInfo` (required Android 8.1+) | Chip shows "WiFi Connected" without SSID |
| `ACCESS_NETWORK_STATE` | Monitor network changes in `WifiSsidChip` | Chip may not update on network change |
| `USB_PERMISSION` (dynamic) | Claim the USB interface for PTP/tethered DSLR | Tethered mode unavailable |

`ACCESS_FINE_LOCATION` is never used for geolocation. It is required solely because Android
8.1 (API 27) restricted SSID reads behind the location permission gate. The `WifiSsidChip`
composable checks the permission at runtime and shows a degraded label when denied.
