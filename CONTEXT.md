# PhotoFlow Mobile — Project Context

## Current status
All three screens implemented and functional. **Tethered DSLR works end-to-end on the Canon EOS
Rebel T7** over USB PTP on the Samsung Galaxy test device: physical shutter stays active, shots
are picked up within 1–3 s, downloaded at ~140 Mbps, and written into the active session with
the same rename/save/upload pipeline as native capture.

**FTP upload is wired and attempts real transfers** (Apache Commons Net, 5 s connect / 15 s data
timeout, dynamic error messages). Workers always return `Result.failure()` — retry is managed by
`MainViewModel.startAutoRetryLoop()` on a configurable interval (default 4 s). The FTP server
must be configured in ConfigScreen → Connections for transfers to succeed.

Room DB is at schema version 5. Native camera capture has full pipeline logging
(`PhotoFlow/Pipeline` tag). WiFi chip recovers correctly after device sleep.

**Splash screen** is implemented: `core-splashscreen` library provides an instant dark-background
system splash before Compose renders; a `PhotoFlowSplash` composable then shows for 1.4 s with
the app icon, "PhotoFlow Mobile" title, and "FIELD OPERATIONS" tagline before handing off to
the NavGraph.

## Environment
- Development machine: Windows 10
- Primary coding environment: VS Code + Claude Code extension
- Android Studio: used for emulator/AVD management and Logcat only
- Project path: `C:\Users\John\AndroidStudioProjects\PhotoFlow-Mobile`
- Primary test device: Samsung Galaxy (serial `adb-RFCW101K9PA-PyKfsb._adb-tls-connect._tcp`) — physical device, wireless ADB
- Secondary test device: Motorola G 2025 (serial `adb-ZY32L9TX7B`) — physical device, USB ADB; exposes non-Samsung USB host quirks (no `USB_DEVICE_ATTACHED` dispatch, Room first-emit latency ~2.6 s)
- Emulator: Pixel 7 (AVD), Android 14 (tertiary, for UI work)

## Build & deploy
```bash
# Build debug APK
./gradlew assembleDebug

# Install to Samsung (wireless ADB)
/c/Users/John/AppData/Local/Android/Sdk/platform-tools/adb.exe \
  -s adb-RFCW101K9PA-PyKfsb._adb-tls-connect._tcp \
  install -r app/build/outputs/apk/debug/app-debug.apk

# Install to Motorola G 2025 (USB ADB)
/c/Users/John/AppData/Local/Android/Sdk/platform-tools/adb.exe \
  -s adb-ZY32L9TX7B \
  install -r app/build/outputs/apk/debug/app-debug.apk

# Watch PhotoFlow logs only — Samsung
/c/Users/John/AppData/Local/Android/Sdk/platform-tools/adb.exe \
  -s adb-RFCW101K9PA-PyKfsb._adb-tls-connect._tcp \
  logcat -d | grep -E "PhotoFlow|EOS|PTP"

# Watch PhotoFlow logs only — Motorola
/c/Users/John/AppData/Local/Android/Sdk/platform-tools/adb.exe \
  -s adb-ZY32L9TX7B \
  logcat -d | grep -E "PhotoFlow|EOS|PTP"

# Clear logcat buffer before a test — Samsung
/c/Users/John/AppData/Local/Android/Sdk/platform-tools/adb.exe \
  -s adb-RFCW101K9PA-PyKfsb._adb-tls-connect._tcp logcat -c

# Clear logcat buffer before a test — Motorola
/c/Users/John/AppData/Local/Android/Sdk/platform-tools/adb.exe \
  -s adb-ZY32L9TX7B logcat -c
```

## Source file map
```
app/src/main/java/com/photoflowmobile/app/
├── PhotoFlowApplication.kt          — Application class; Room DB singleton
├── MainActivity.kt                  — Single activity; USB attach/detach receivers
├── navigation/
│   └── NavGraph.kt                  — 3-destination nav graph
├── ui/screens/
│   ├── SplashScreen.kt              — Branded cold-start splash (icon + app name + tagline)
│   ├── ScanCardScreen.kt            — Session entry (manual + future ML Kit scan)
│   ├── MainScreen.kt                — 4-panel operational dashboard
│   └── ConfigScreen.kt              — Settings (DataStore-backed)
├── ui/theme/
│   └── Color.kt / Theme.kt / Type.kt
├── viewmodel/
│   ├── MainViewModel.kt             — Session, images, capture, tethered camera, connections
│   ├── ConfigViewModel.kt           — AppSettings read/write
│   └── ScanCardViewModel.kt         — Session creation / resume
├── data/
│   ├── model/
│   │   ├── AppSettings.kt           — Settings data class + enums + buildFilename()
│   │   ├── Session.kt               — Room entity
│   │   ├── SessionImage.kt          — Room entity
│   │   ├── ConnectionProfile.kt     — Room entity
│   │   └── UploadState.kt           — Enum
│   ├── db/
│   │   ├── AppDatabase.kt           — Room database v5 (3 entities, migrations 1-5)
│   │   ├── SessionDao.kt            — incl. SessionWithCount join query with SQL LIMIT subquery
│   │   ├── SessionImageDao.kt       — incl. getTransferQueue(), getFailedImages(), getById()
│   │   ├── ConnectionProfileDao.kt  — CRUD + clearAllActive()
│   │   └── Converters.kt            — UploadState ↔ String
│   ├── repository/
│   │   └── SessionRepository.kt     — Sessions, images, transfer queue, getImageById()
│   ├── datastore/
│   │   └── SettingsDataStore.kt     — DataStore Preferences setup + serialization
│   ├── usb/
│   │   ├── PtpConnection.kt         — USB bulk-transfer PTP transport (command/data/response
│   │   │                              containers, interrupt-endpoint events, Samsung 512-byte
│   │   │                              first-read cap, 800 ms claim-settle delay)
│   │   ├── PtpCore.kt               — Vendor-neutral standard PTP (OpenSession, GetObjectHandles,
│   │   │                              GetObjectInfo, GetObject, CloseSession)
│   │   ├── VendorAdapter.kt         — Per-vendor hooks: GenericPtpAdapter, CanonEosAdapter
│   │   │                              (0x91xx + polled GetEvent 0x9127), NikonAdapter
│   │   └── MtpCameraManager.kt      — Tethering lifecycle: single-thread USB dispatcher,
│   │                                  connectionMutex, VendorAdapter selection, 500 ms poll
│   │                                  loop + 3 s GetObjectHandles diff-poll fallback;
│   │                                  permissionDenied flag, 8 s isConnecting timeout,
│   │                                  5 s foreground rescan loop, resetAndRescan()
│   └── worker/
│       └── FtpUploadWorker.kt       — WorkManager worker; real FTP via Apache Commons Net;
│                                      always Result.failure() (ViewModel retry loop owns retries);
│                                      shared Mutex serializes concurrent uploads
```

## What works end-to-end
1. **Splash screen** — Dark background + real app icon (160dp, `AndroidView` + `ImageView` +
   `ContextCompat.getDrawable`) + "PhotoFlow Mobile" title; 1.4 s then NavGraph. No tagline.
   `installSplashScreen().setOnExitAnimationListener { it.remove() }` suppresses the OS exit
   animation so the Android 12 system splash and Compose splash merge into one seamless screen.
2. **Session creation** — Enter barcode → GO writes Session to Room → navigates to MainScreen
3. **Session resume** — Scanning a barcode matching an existing session reopens it instead of
   creating a new one; other active sessions are closed first
4. **Camera preview** — Native Camera mode shows live CameraX PreviewView in center panel
5. **Image capture** — CAPTURE fires `ImageCapture.takePicture()`, renames file per naming
   config, saves to `filesDir/captures/`, inserts SessionImage (PENDING), enqueues FtpUploadWorker;
   logs `capture: firing / saved / FAILED` to `PhotoFlow/Pipeline`
6. **Scrollable thumbnails** — Left panel is a `LazyColumn`; thumbnails are clickable to select
   a review image (`selectReviewImage()`)
7. **Review pane** — Driven by `reviewImage` StateFlow: shows selected thumbnail or latest
   capture. In Native mode: right 42% of center panel. In Tethered mode: full center panel.
   Auto-resets to latest when session changes or a new photo arrives.
8. **File Transfers panel** — Top bar button (green = clean, orange = errors) opens a full-screen
   dialog with all non-UPLOADED items. Each row shows filename, dynamic error description (includes
   phone IP + server IP:port), state badge, and RETRY button for FAILED items.
9. **Auto-retry** — `MainViewModel.startAutoRetryLoop()` finds FAILED images on configurable
   interval (default 4 s), increments `retryCount`, re-enqueues worker. State stays FAILED so
   error is visible. `forceRetry()` resets count and error message for manual retry.
10. **FTP upload** — `FtpUploadWorker` uses Apache Commons Net; 5 s connect / 15 s data timeout;
    always returns `Result.failure()` (WorkManager does not retry — ViewModel loop handles it);
    shared `Mutex` serializes concurrent workers so rapid-fire captures don't corrupt transfers
11. **Session history** — Right panel, clickable to select session for review; capped by
    `sessionHistoryMax` setting via SQL LIMIT in subquery (not Kotlin-side filtering)
12. **Settings persistence** — ConfigScreen saves all settings to DataStore
13. **File naming** — Custom/Barcode/Sequence fields with separator/extension, applied at capture
14. **Active Connection** — Bottom bar dropdown reads ConnectionProfile list from Room
15. **WiFi SSID chip** — Top bar shows connected WiFi SSID; uses `cm.allNetworks` + `onAvailable`
    callback so it recovers correctly after device sleep (fixed: was stuck on "WiFi not connected")
16. **Photo backup** — When enabled, each captured image is copied to `Pictures/PhotoFlow` via
    MediaStore (API 29+) or `Environment.getExternalStoragePublicDirectory` (API <29); optional
    auto-delete of backups older than N days runs at startup
17. **Storage info** — ConfigScreen GENERAL → STORAGE shows real available space via `StatFs`;
    Clear App Cache button wipes `filesDir/captures/` with a confirmation dialog
18. **Back button** — `BackHandler { activity.moveTaskToBack(true) }` backgrounds the app
    instead of destroying the Activity, preserving the USB tethered camera session
19. **Tethered DSLR** — Any USB Still Image class (0x06) device is detected; VID picks a
    `VendorAdapter` (Canon/Nikon/Generic). Canon EOS T7 confirmed working on Samsung and
    Motorola G 2025: `SetRemoteMode(1)` + `SetEventMode(1)` keep the physical shutter active,
    shots arrive via the 3-second GetObjectHandles diff-poll fallback (Canon `GetEvent` is
    silent on the T7) and flow through the same rename/save/upload pipeline as native capture.
    USB permission dialog reliability handled by `permissionDenied` flag, 8 s `isConnecting`
    timeout, 5 s foreground rescan loop, and `resetAndRescan()` mode-switch recovery.
20. **Clear session history** — ConfigScreen GENERAL → SESSION HISTORY → CLEAR SESSION HISTORY
    button; confirmation dialog; deletes all completed sessions and their `session_images` rows
    from Room (images deleted first — no `@ForeignKey` CASCADE defined on the entity); active
    session and physical image files on disk are preserved.
21. **No-session startup prompt** — `MainViewModel.noSessionsExist: StateFlow<Boolean>` starts
    with `SharingStarted.Eagerly` so Room collection begins at VM creation. `MainScreen`'s
    `LaunchedEffect(Unit)` collects the flow with `.filter { it }.first()` (suspending until
    Room confirms the table is empty) then calls `showNoSessionPrompt()`. Auto-clears when an
    active session starts. Uses flow collection rather than `.value` read because Room takes
    2–3 s on first load on Motorola G 2025 — sampling `.value` always read the `false` initial
    value and silently skipped the prompt.
22. **Tethered panel no-session warning** — When `TetheredState.CONNECTED` and no sessions
    exist, `TetheredPanel` overlays bold "START NEW SESSION / BEFORE TAKING PHOTO" text in
    warning color. Parameter `noSessionsExist: Boolean` is threaded MainScreen → CenterPanel /
    PortraitCenterArea → TetheredPanel. Disappears as soon as a session is created.

## Tethered DSLR — architecture, fragility, and what you must not break

**This is the most fragile subsystem in the app.** It works now, but several constraints
are empirical and load-bearing. If someone changes `data/usb/*`, `MainViewModel`'s USB
wiring, or `NavGraph.kt`'s `MainScreen` composable without understanding the constraints
below, tethering will silently break.

### Architecture
```
UsbDevice (Still Image class 6)
  └─ MtpCameraManager                    — lifecycle, mutex, single-thread dispatcher
       ├─ PtpConnection                  — USB bulk transport (command/data/response)
       ├─ PtpCore                        — standard PTP ops (OpenSession, GetObject, …)
       └─ VendorAdapter                  — Canon / Nikon / Generic per VID
            └─ initialize() / pollNewImageHandles() / shutdown()
```
Everything vendor-specific lives in `VendorAdapter.kt`. Adding Sony/Fuji/Panasonic support
is a new adapter class + a branch in `VendorAdapter.forVendor()` — no transport changes
needed.

### Critical constraints (do not cleanup casually)

1. **`FIRST_CHUNK_SIZE = 512` in `PtpConnection`** — Samsung's USB host driver returns -1
   instantly on first-read bulk-IN requests larger than ~512 bytes following vendor
   extension commands. Symptom: `readDataAndResponse: first read=-1` in ~3 ms and every
   subsequent transfer on that connection fails. Continuation reads at `CHUNK_SIZE = 16_384`
   are fine. Do NOT raise either without re-testing on Samsung One UI.

2. **800 ms `Thread.sleep` after `claimInterface`** in `PtpConnection.init`. The Samsung
   system MTP daemon needs this long to release the USB interface after we force-claim it.
   Shorter delays = contention = failed `OpenSession`. Keep SET_INTERFACE(0) + CLEAR_HALT
   too — they reset endpoint toggle bits the daemon may leave stale.

3. **`NavGraph.kt` must Activity-scope MainViewModel.** The `Screen.Main` composable uses:
   ```kotlin
   val activity = LocalContext.current as ComponentActivity
   MainScreen(viewModel = viewModel(activity), …)
   ```
   Default `viewModel()` inside a `composable { }` resolves to the NavBackStackEntry's
   store — different from `MainActivity`'s `by viewModels()`. Two stores = two
   `MainViewModel`s = two `MtpCameraManager`s = two coroutines racing to
   `claimInterface(force=true)` on the same interface. Never revert.

4. **Single-threaded USB dispatcher.** `MtpCameraManager.usbDispatcher` is backed by a
   dedicated `Executors.newSingleThreadExecutor { Thread("PhotoFlow-USB") }`. Every USB
   I/O call (open, init, poll, download, close) runs on that thread. `bulkTransfer` calls
   on the same `UsbDeviceConnection` crossing threads break silently on Samsung — symptom
   identical to the 512-byte cap, which led to a multi-hour misdiagnosis. Keep all
   `scope.launch` USB work on `usbDispatcher`.

5. **Duplicate-claim guards.** `connectOrRequestPermission` sets `isConnecting` under
   `synchronized(this)`. `openConnection` wraps its body in `connectionMutex.withLock`
   with an early `if (core != null) return@withLock` guard. All three are required — they
   cover the attach/deviceList-scan/permission-grant race window.

6. **USB permission subsystem (Motorola / non-Samsung hosts).** `USB_DEVICE_ATTACHED` is NOT
   dispatched to PhotoFlow on Motorola G 2025 — competing system apps claim it first. Four
   compensating mechanisms:
   - **`onResume` rescan:** `MainActivity.onResume()` → `rescanForAttachedCamera()` catches
     cameras plugged in while the app was backgrounded.
   - **5 s foreground rescan loop:** `MtpCameraManager.init {}` polls `rescanForAttachedCamera()`
     every `FOREGROUND_RESCAN_MS = 5_000L` so cameras plugged while the app is already on-screen
     are detected without requiring the user to background/foreground the app.
   - **`permissionDenied` flag:** Set when the user explicitly denies the USB dialog; prevents
     the rescan loop from re-prompting every 5 s. Cleared in `onDeviceDetached()` so re-plugging
     the cable re-enables asking.
   - **8 s `isConnecting` timeout:** On first launch, the Android CAMERA permission dialog
     (triggered by ScanCardScreen's barcode viewfinder) can block the USB permission dialog.
     `isConnecting` resets to `false` after 8 s if `core` is still null, so the loop retries
     instead of staying stuck forever.
   - **`resetAndRescan()`:** Atomically clears both `permissionDenied` and `isConnecting` under
     `synchronized(this)`, then immediately rescans. Called by a `deviceMode`-watching coroutine
     in `MainViewModel.init {}` on every `TETHERED_DSLR` transition — switching Native → Tethered
     in ConfigScreen gives a guaranteed clean retry without requiring an app restart or cable
     re-plug. This is the primary recovery path for a swipe-dismissed dialog.

### Samsung-specific discoveries
See memory: `project_samsung_usb_quirks`. Both quirks above (512-byte first read, NavGraph
VM scoping) are documented there with reproduction conditions.

### Canon T7 behavior
- VID=0x04A9, PID=0x32E1, USB Still Image class
- No camera menu setting to change — connects as-is
- No live view (`liveViewFrame` always null)
- `EOS GetEvent` (0x9127) is silent on the T7 — the adapter fast path reports zero new
  handles. Shots are picked up by the 3-second `FALLBACK_POLL_MS` `GetObjectHandles`
  diff-poll instead. Typical post-shot import latency: 1–3 s. Working throughput: ~140
  Mbps download, 12–28 ms end-to-end pipeline per image.
- Poll loop seeds `knownHandles` with what's already on the SD card at connect, so only
  shots taken after connection are imported.

### Known rough edges (acceptable for now)
- Detach cleanup: the poll loop can spam failing commands for up to ~15 s after the
  device is unplugged before the session tears down cleanly. Not data-damaging, just
  noisy in logcat.
- Canon GetEvent silence on T7 is camera-side behavior — not fixable from the Android
  side. The diff-poll fallback is the designed safety net for exactly this case.

## Known gaps / next steps
- **FTP server config** — Worker is wired and functional; needs a ConnectionProfile with valid
  host/port/credentials configured in ConfigScreen → Connections for transfers to actually succeed.
- **ML Kit barcode scanning** — Dependency in place; camera analysis use case not yet bound.
  Manual entry is the active path.
- **Connection profile creation** — ConfigScreen FTP section writes flat DataStore fields.
  ConnectionProfile Room entities must be created via ConfigScreen but the two are not yet unified.
- **EXIF strip** — ISO/shutter/aperture values in review pane are hardcoded placeholders.
- **Tethered detach cleanup** — Poll loop logs 10–15 s of failing commands after device unplug
  before teardown completes. Cosmetic; not data-damaging.
- **Non-Canon DSLR validation** — `GenericPtpAdapter` + `NikonAdapter` are written to standard
  PTP spec but untested on actual hardware. Only Canon T7 has been physically verified.

## Key implementation decisions
- `ImageCapture` use case owned by `MainViewModel`, not the Composable.
- `selectedSessionId` in MainViewModel defaults to active session; overridden by tapping history;
  resets automatically when a new session starts.
- `reviewImage: StateFlow<SessionImage?>` — the single source of truth for the review pane and
  tethered center panel. Resolves to the manually selected thumbnail (`_reviewImageId`) or
  `sessionImages.firstOrNull()`. Auto-clears to latest on session switch or new photo arrival.
- `SessionImage.filename` always holds the renamed filename. Raw camera filename is never stored.
- `FtpUploadWorker` always returns `Result.failure()` — WorkManager retry is disabled.
  `MainViewModel.startAutoRetryLoop()` owns the retry cadence; it increments `retryCount` but
  does NOT change `uploadState`, so FAILED + error message stays visible during background retry.
- `sessionHistoryMax` is applied as a SQL LIMIT inside a subquery (`FROM (SELECT * FROM sessions
  ORDER BY startTime DESC LIMIT :limit) s`) — not Kotlin-side `.take()`. This keeps Room from
  fetching unbounded rows before filtering.
- `MtpCameraManager` (despite the name) uses raw PTP via `PtpConnection` + `PtpCore` +
  `VendorAdapter`, NOT `android.mtp.MtpDevice`. The name is kept to avoid ViewModel churn.
  `CanonEosPtp.kt` no longer exists — merged into `VendorAdapter.kt`.
- `TetheredStatus` / `TetheredState` are defined at the bottom of `MtpCameraManager.kt`.
- USB I/O is pinned to a dedicated single-thread executor (`PhotoFlow-USB`) exposed as
  `usbDispatcher`. All `scope.launch` sites that touch USB must use it, not `Dispatchers.IO`.
- `NavGraph.kt` explicitly Activity-scopes `MainViewModel` via
  `viewModel(activity = LocalContext.current as ComponentActivity)`. Do not revert to the
  default `viewModel()` — it creates a second VM via the NavBackStackEntry store, which
  produces two USB-claim races.
- `FtpUploadWorker.uploadMutex` is a `companion object`-level `Mutex` — shared across all worker
  instances so FTP connections are serialized.
- `PhotoFlowApplication` is the single source of the Room database instance.
- `SessionWithCount` is a Room query result class defined in `SessionDao.kt`.
- `ContextCompat.registerReceiver()` (core-ktx ≥ 1.9.0) is used for all BroadcastReceivers —
  handles Android 12 (API 31-32) explicit flag requirement transparently.
- `WifiSsidChip` uses `cm.allNetworks` (not `cm.activeNetwork`) and handles `onAvailable` in
  the NetworkCallback — prevents the chip sticking on "WiFi not connected" after device sleep.
- Splash icon uses `AndroidView` + `ImageView` + `ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)`
  at 160dp. `painterResource` cannot load adaptive mipmap icons (XML) and throws
  `IllegalArgumentException` at runtime. 160dp matches the Android 12 OS splash screen spec.
- `installSplashScreen().setOnExitAnimationListener { it.remove() }` — Android 12+ always
  shows an OS splash before the app draws; suppressing its exit animation makes it vanish
  instantly when Compose renders, so users see only the Compose splash (one screen, not two).
- `SessionDao.deleteImagesForCompletedSessions()` must be called before `deleteCompletedSessions()`
  — there is no `@ForeignKey` CASCADE on `SessionImage`, so images must be deleted explicitly
  first to avoid orphaned rows in `session_images`.
- `noSessionsExist: StateFlow<Boolean>` in `MainViewModel` uses `SharingStarted.Eagerly` so Room
  starts collecting before any Compose subscriber exists. `MainScreen`'s `LaunchedEffect(Unit)`
  collects it with `.filter { it }.first()` — suspending until Room emits `true` — then calls
  `showNoSessionPrompt()`. Must collect; never sample `.value`: on Motorola G 2025, Room takes
  ~2.6 s on first load and the initial value is `false`, so a direct `.value` read silently misses
  the empty-DB case. `showNoSessionPrompt()` sets `_showNoSessionPrompt.value = true`; auto-clears
  in the `activeSession.collect` observer when a non-null session arrives.
- `handleTetheredImage()` silently drops images and logs when no session is active — it does NOT
  call `showNoSessionPrompt()`. The prompt is driven entirely by the `LaunchedEffect` startup
  check. Tethered image arrival is not a safe trigger because it can fire when the user is not on
  MainScreen.

## Visual conventions
- Primary accent: `DarkPrimary` (cyan-ish, from Color.kt)
- Surface: `DarkSurface`, `DarkSurfaceVariant`
- Warning: `DarkWarning`, Success: `DarkSuccess`
- Panel borders: 1dp `DarkPrimary` for active/center panels, 0.5dp `DarkBorder` elsewhere
- Font sizes: 15sp ExtraBold for session barcode; 11sp Bold for app title; 8–9sp for labels;
  6–7sp for metadata/status
- All screens use `WindowInsets.safeDrawing` padding to avoid display cutout overlap
