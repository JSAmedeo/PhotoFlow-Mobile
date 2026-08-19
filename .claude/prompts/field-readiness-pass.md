# PhotoFlow Mobile — Phase 2: Field Readiness Pass

**Repo:** https://github.com/JSAmedeo/PhotoFlow-Mobile
**Base branch:** `hardening/reliability-security-pass` (commit `63af4af`) — note this branch is **not yet merged to `master`**
**Working branch:** `phase2/field-readiness` — create this first, do all work on it
**Opened:** 2026-08-18
**Status:** All ten work items code complete and unit-tested (71 tests green). Group A verified
on device. Group B partly verified: FR-6 confirmed, FR-4/FR-5 failure paths never exercised.
Groups C and D not yet exercised on device.

## Group A device verification — 2026-08-18, Moto G 2025

Build `PhotoFlow-debug-20260818-2048.apk` from `2b745c9`. App launched clean, no `FATAL`.
The FR-3 settings migration was confirmed against real device data by decoding the DataStore
protobuf: `auto_retry_max_count` had been rewritten from `-1` to `3`, and `settings_schema_v`
was stamped `1` — the upgrade path exercised on an install that actually carried the old value.
Operator confirmed the FR-1/FR-2 session-activation checks pass.

## Build note

Android Studio holds a lock on `app/build/.../R.jar` while it is open, which fails
`:app:processDebugResources` from the command line with
`java.io.IOException: Couldn't delete ... R.jar`. This is an environment lock, not a code error.
Either close Android Studio before a CLI build, or verify in a throwaway worktree:

```bash
git worktree add --detach /tmp/verify-wt HEAD
cp local.properties /tmp/verify-wt/
cd /tmp/verify-wt && ./gradlew assembleDebug testDebugUnitTest
git worktree remove --force /tmp/verify-wt
```

```bash
git checkout hardening/reliability-security-pass
git checkout -b phase2/field-readiness
```

## Origin

This pass came out of a pre-deployment code review requested before the first real-workflow
field test. The build at `63af4af` was verified green (`./gradlew assembleDebug testDebugUnitTest`,
exit 0) — every item below is a **behavioural** defect, not a compile failure.

Ten work items (FR-1 … FR-10), ordered so the two groups that protect the field test land first.
Read `CLAUDE.md` before writing any code — it contains architectural rules and empirically-derived
hardware constraints that MUST NOT change.

---

## Product decisions (settled 2026-08-18)

These were open questions in the review; both are now decided and are binding on FR-1/FR-2.

1. **Re-scanning a barcode that already has a session re-activates that session.**
   Sequence numbers and capture codes continue from where it left off (`…_04`, `…_05`), so one
   customer's photos stay in one session. Re-scanning the card is therefore the natural way to
   "add a photo to a past session." Rejected alternative: always creating a new session, which
   would fragment a customer across duplicate sessions, restart sequence numbers at 01, and
   collide with filenames already uploaded for that barcode.

2. **Activating a past session from Session History happens immediately, with a persistent
   banner** — no confirmation dialog. Rejected alternatives: a confirm dialog (too much friction
   for a field console), and silent activation with no indicator (a mis-tap would send the next
   shots to the wrong customer with nothing on screen to catch it).

---

## GUARDS — Intentional behaviours that must survive this pass

Do not "fix" any of the following. They are deliberate, and several were paid for in lost
debugging hours.

1. **Splash screen:** the 1.4 s delay, `AndroidView` + `ImageView` icon loading (NOT
   `painterResource` — it crashes on adaptive icons), and `setOnExitAnimationListener { it.remove() }`.
2. **`PtpConnection` hardware workarounds:** `SET_INTERFACE(0)` control transfer,
   `Thread.sleep(800)` after `claimInterface`, `FIRST_CHUNK_SIZE = 512`, `CHUNK_SIZE = 16_384`,
   and the Canon "response-without-data-phase is legal" handling.
   **This pass DOES modify `PtpConnection.kt` and `MtpCameraManager.kt`** (FR-4, FR-5) — unlike
   the WI-1…WI-7 pass, which forbade it. The constants and sequencing above are still off limits;
   only the short-read validation and the poll-failure teardown change.
3. **USB permission subsystem contract** (see CLAUDE.md HARD RULES): `singleTop` launchMode,
   `viewModel(activity)` in `NavGraph`, `permissionDenied = true` on the exhausted-retry path,
   the `attempt > 0` guard preceding the `DISCONNECTED` check, all USB I/O pinned to
   `usbDispatcher`, `resetAndRescan()` and the `deviceMode` watcher.
4. **`captureMutex`** serializing count → sequence → write → insert. FR-6 moves blocking I/O
   onto `Dispatchers.IO` *inside* the lock; it does not narrow or remove the lock.
5. **Cloud status-code semantics:** 409 = success (idempotency), 401/404/422 = terminal,
   `image.id` as the stable `idempotency_key`.
6. **UPLOADING indicator gating:** workers set `UPLOADING` only when the current state is
   `PENDING`, so silent retries don't flash the UI.
7. **Startup recovery** in `MainViewModel.init`: stuck `UPLOADING` → `PENDING`, then re-enqueue
   pending uploads.
8. **File naming, capture codes, sequence/sort-order logic** — no changes.
9. Existing installs must keep working with **zero reconfiguration**: additive Room migrations
   only, no destructive DataStore changes, no new permissions.

---

## Status

| # | Work item | Group | Priority | Status |
|---|---|---|---|---|
| FR-1 | Selecting a session activates it | A | HIGH | **code complete** (`5374393`) — device test pending |
| FR-2 | Past-session banner | A | HIGH | **code complete** (`5374393`) — device test pending |
| FR-3 | Bounded auto-retry (default 3) | A | HIGH | **code complete** (`342c8c5`) — device test pending |
| FR-4 | Reject truncated PTP downloads | B | HIGH | **code complete** (`00c7ae5`) — device test pending |
| FR-5 | Recover from poll-loop death | B | MEDIUM | **code complete** (`00c7ae5`) — device test pending |
| FR-6 | Tethered file I/O off the USB thread | B | MEDIUM | **code complete** (`dfd3383`) — device test pending |
| FR-7 | Settings export never carries secrets | C | MEDIUM | **code complete** (`97e3b0b`) — device test pending |
| FR-8 | Single source of truth for the API key | C | MEDIUM | **code complete** (`97e3b0b`) — device test pending |
| FR-9 | Backup exclusions + crash-safe CredentialStore | C | MEDIUM | **code complete** (`97e3b0b`) — device test pending |
| FR-10 | Cleanup: FTP ghost rows, photoOp export, doc drift | D | LOW | **code complete** (`d29b4a4`; photo_op in `97e3b0b`) |

Groups are ordered by risk: **A and B protect the field test**; C and D are hardening.

---

# GROUP A — Session semantics & bounded retry

## FR-1 — Selecting a session activates it (HIGH)

**Problem.** `handleTetheredImage` reads `selectedSession.value` (`MainViewModel.kt:215`) while
native capture reads `activeSession.value` (`MainViewModel.kt:588`). Tapping a Session History row
sets `_overrideSessionId`, which persists until a *new* session starts. So: operator taps history
to review an earlier customer, the photographer fires the shutter, and the image is saved with the
old session's barcode in its filename, the old capture code, a sequence number continuing the old
session, and is uploaded into the old cloud session. Silent, with no warning and no log
distinction. Compounding it, `MainScreen.kt:985` labels the *reviewed* session "Active Session",
so the two capture paths disagree with each other and with the on-screen label.

**Approach.** Delete the override concept entirely — selection *is* activation. Both capture paths
then read one source of truth, and supporting "add a photo to a past session" falls out for free.

**Required changes.**

1. `SessionDao`: add `@Transaction suspend fun activateSession(sessionId: Long, now: Long)`
   wrapping two updates —
   `UPDATE sessions SET status='complete', endTime=:now WHERE status='active' AND id != :sessionId`
   then `UPDATE sessions SET status='active', endTime=NULL WHERE id=:sessionId`.
   Expose through `SessionRepository`.
2. `MainViewModel`: delete `_overrideSessionId`; `selectedSessionId` becomes
   `activeSession.map { it?.id }`; `selectSession()` calls `activateSession()`; remove the
   override-reset branch in the `activeSession` observer.
3. `MainViewModel.handleTetheredImage`: read `activeSession.value`, matching `onCaptureRequested`.
4. `ScanCardViewModel.startSession`: route the existing-barcode branch through the same
   `activateSession` transaction, so scanning a known card and tapping its history row are
   literally the same operation. New barcode creates a session and activates it. (Per decision 1.)
5. `MainScreen`: `BottomBar` and `RightPanel` take `activeSession` directly instead of
   `selectedSession ?: activeSession`, making the "Active Session" label truthful.

**Acceptance.**
- [ ] Tap a past session in history — it becomes the active session; the top/bottom bars show it.
- [ ] With that past session active, a tethered shot lands in it with the *next* sequence number
      for that session, and a native capture lands in the same session.
- [ ] Scanning a barcode that already has a session re-activates it and continues its numbering.
- [ ] Scanning a new barcode creates a new session, closes the previous one, and activates it.
- [ ] No path remains where `selectedSession` and `activeSession` can disagree.

## FR-2 — Past-session banner (HIGH)

**Problem.** After FR-1, tapping a history row silently reassigns where new photos go. That is the
same hazard inverted, so it needs to be visible.

**Required changes.**
1. `MainViewModel`: add `pastSessionActive: StateFlow<Boolean>` — true when the active session is
   not the newest by `startTime`.
2. `MainScreen`: new `PastSessionBanner` composable placed directly under `TopBar` in **both**
   landscape and portrait. `LocalAppColors.current.warning`, text
   `VIEWING PAST SESSION — NEW PHOTOS GO HERE`, plus a `GO TO NEWEST` action that activates the
   newest session. Persistent while true.

**Acceptance.**
- [ ] Banner appears the moment a non-newest session becomes active, in both orientations.
- [ ] `GO TO NEWEST` activates the newest session and dismisses the banner.
- [ ] Banner never shows when the newest session is active, including right after a new scan.
- [ ] Colours come from `LocalAppColors` — correct in both light and dark mode.

## FR-3 — Bounded auto-retry, default 3 attempts (HIGH)

**Problem.** `startAutoRetryLoop` re-enqueues every `FAILED` image with
`ExistingWorkPolicy.REPLACE` (`MainViewModel.kt:447`), which resets WorkManager's
`runAttemptCount` to 0 — so the `runAttemptCount >= 10` cap in both executors never trips. With
the shipped defaults (`autoRetryEnabled = true`, interval 4 s, `autoRetryMaxCount = -1` =
continuous), a permanently-failing image — 401 bad API key, 422, "device not registered",
"local file not found" — is re-uploaded **in full every 4 seconds for the rest of the shoot**.
One wrong API key at a venue means continuous full-resolution uploads, battery and data burn, and
a transfer queue that never settles.

**Note:** the setting and its `1 / 3 / 5 / Continuous` dropdown already exist at
`ConfigScreen.kt:1550`. No new UI is required — this is a default change plus the underlying bug.

**Required changes.**
1. `AppSettings.autoRetryMaxCount` default `-1` becomes `3`. Same for the `settingsFromJson`
   fallback in `ConfigViewModel`.
2. One-time DataStore migration in `PhotoFlowApplication`, stamped with a `settings_schema_v` key:
   installs currently persisting `-1` are rewritten to `3` exactly once. A deliberate later choice
   of Continuous must stick.
3. `MainViewModel` auto-retry path: `ExistingWorkPolicy.REPLACE` becomes `KEEP`, so WorkManager's
   `runAttemptCount` and its `>= 10` cap survive. The existing `alreadyQueued` WorkInfo check
   already prevents duplicates; `KEEP` is the correctness backstop.
4. `forceRetry` (the manual RETRY button) keeps `REPLACE` + `retryCount = 0` — the operator's
   escape hatch, unchanged.

**Acceptance.**
- [ ] Fresh install shows "3" in Settings → General → File Transfer → Max retries.
- [ ] An install carrying `-1` from a previous build is migrated to `3` on first launch, once.
- [ ] With a deliberately wrong API key, a failed image is attempted 3 times and then stops;
      `[CLOUD]` log lines confirm no further attempts.
- [ ] The manual RETRY button still re-drives a stopped image.
- [ ] Selecting Continuous still works for operators who want it.

**Optional, deferred by default:** a `terminal` column (Room migration v10) so 401/422/
not-registered never consume even the 3 attempts. The bound alone eliminates the storm; this is
polish and adds schema risk before a field test. Do not implement without an explicit decision.

---

# GROUP B — Tether reliability

## FR-4 — Reject truncated PTP downloads (HIGH)

**Problem.** `PtpConnection.readDataAndResponse` (`PtpConnection.kt:177-186`) breaks its read loop
on `n <= 0` and returns whatever it has, never comparing `received` against `expectedPayload`.
`downloadAndDeliver` only rejects null/empty. A mid-transfer USB hiccup therefore yields a partial
JPEG that is saved, renamed, shown in the review pane, and uploaded — silently corrupt. On a
fragile bus over a full day this is the failure mode you would least want to find out about
afterwards.

**Required changes.**
1. After the read loop, if `received < expectedPayload`, log both byte counts and return `null`
   instead of a partial payload. `PtpCore.getObject` already handles `null`, so it propagates
   cleanly with no call-site changes.
2. The unknown-length sentinel (`containerLen == 0xFFFFFFFF`, which coerces `expectedPayload` to 0)
   must stay benign — verify the strict check does not reject it.
3. `MtpCameraManager.downloadAndDeliver`: on a failed download, remove the handle from
   `knownHandles` so the 3-second diff-poll retries it. Today a failed shot is lost permanently.
   Bound this at 3 attempts per handle so a genuinely bad object cannot loop forever.

**Acceptance.**
- [ ] A short read produces a log line with expected vs received bytes and **no** file on disk,
      no Room row, and no upload.
- [ ] The handle is retried on the next diff-poll and succeeds if the bus recovers.
- [ ] After 3 failed attempts on one handle, it is logged and abandoned without looping.
- [ ] Normal Canon T7 transfer is unaffected — verify import latency stays in the 1–3 s band and
      throughput near the ~140 Mbps baseline.

## FR-5 — Recover from poll-loop death (MEDIUM)

**Problem.** When the poll loop throws (`MtpCameraManager.kt:474`), it sets status ERROR and
breaks, but leaves `core`/`ptpConn` non-null and `isConnecting` true. Both
`rescanForAttachedCamera()` and `connectOrRequestPermission()` bail out when `core != null`, so the
5-second rescan can never re-establish. Only a physical detach (`onDeviceDetached` then
`closeConnection`) recovers. If the camera sleeps, browns out, or the bus glitches without a
DETACHED broadcast, tethering is dead for the rest of the session and the operator's only remedy
is unplugging the cable — which nothing on screen tells them to do.

**Required changes.**
1. Add `teardownAfterPollFailure()`: shut down the adapter, close the session, release the
   connection, null `core`/`adapter`/`ptpConn`, clear `isConnecting`, and set `pollingJob = null`.
   It must **not** call `closeConnection()`, which cancels `pollingJob` — the very job it would be
   running on. Leave `permissionDenied` false so the existing 5-second rescan reconnects.
2. Run the teardown on `usbDispatcher` (the loop is already there).
3. Add a consecutive-failure counter: after 3 poll-loop failures with no successful image in
   between, stop auto-reconnecting and surface `Camera lost — unplug and replug` rather than
   thrashing the USB stack.

**Acceptance.**
- [ ] Force a poll-loop exception (power the camera off without unplugging) — the app tears down
      and reconnects on its own within ~5 s once the camera returns.
- [ ] Three consecutive failures stop the retry loop with the unplug-and-replug message.
- [ ] A genuine cable detach still routes through `onDeviceDetached` and `closeConnection`
      unchanged.
- [ ] No zombie sessions: `[APP] launched` count and `sent=-1` spam do not appear in logcat.

## FR-6 — Tethered file I/O off the USB thread (MEDIUM)

**Problem.** `handleTetheredImage` never switches dispatchers, so the full `file.writeBytes(data)`
(inside `captureMutex`) and the optional MediaStore backup copy both execute on the single
`PhotoFlow-USB` thread, stalling the poll loop for the duration of a 25 MB write.

**Required change.** Wrap `file.writeBytes(data)` and `saveBackupIfEnabled` in
`withContext(Dispatchers.IO)`. The write stays **inside** `captureMutex` — the filename depends on
the sequence number — only the blocking work leaves the USB thread.

**Acceptance.**
- [ ] Burst shooting shows no growth in import latency across a 10-shot burst.
- [ ] Sequence numbers remain gap-free and unique under burst (the mutex still covers
      count-read through insert).
- [ ] Poll heartbeat lines continue during a large file write.

---

# GROUP C — Security

## FR-7 — Settings export never carries secrets (MEDIUM)

**Problem.** `profilesToJson` (`ConfigViewModel.kt:430`) reads every FTP password out of
`CredentialStore` and writes it into the export JSON; `settingsToJson` includes `cloud_api_key` and
`cloud_setup_code`. The file lands in `Downloads/` — shared storage, readable by any app with
storage access and swept up by cloud backup. This directly undoes the WI-3 credential-at-rest work.

**Industry-standard fix.** Config export and credential provisioning are separate concerns.
Browsers, VPN clients, and MDM configuration profiles all export configuration *without* secrets;
where secrets are portable at all, they are wrapped with a user-supplied passphrase, never
plaintext.

**Required changes.**
1. Drop `password` from `profilesToJson`; drop `cloud_api_key` and `cloud_setup_code` from
   `settingsToJson`. Add `"secrets_included": false` at the JSON root for forward compatibility.
2. `profileFromJson` / `settingsFromJson` tolerate the absent fields and never write a password.
3. Export result dialog gains: "Passwords and API keys were not included."
4. Import result names what still needs entering, e.g. "Settings imported. Re-enter the FTP
   password for P-SERVER-ALPHA, and the Cloud API key in Settings → Cloud API."

**Acceptance.**
- [ ] An exported file contains no password, API key, or setup code — verify by opening the JSON.
- [ ] Import restores every non-secret setting and profile, and tells the operator what to re-enter.
- [ ] Importing a file exported by an older build (which *does* contain a password) does not
      persist that password.
- [ ] Unit test asserts the export JSON has no secret-bearing keys.

**Future option (not in scope):** passphrase-encrypted export (PBKDF2WithHmacSHA256 + AES-256-GCM)
for cloning a fully configured device.

## FR-8 — Single source of truth for the Cloud API key (MEDIUM)

**Problem.** `ConfigViewModel.save()` mirrors the key to `CredentialStore` *and* calls
`toPreferences`, which writes `CLOUD_API_KEY` to DataStore (`SettingsDataStore.kt:88`). The
migration in `PhotoFlowApplication` only blanks DataStore when the credential store is still empty
— true on the very first run only — so after the first successful migration the plaintext copy is
written back on every settings save and never cleared again.

**Industry-standard fix.** One authoritative store for a secret. The secret lives only in the
Keystore-backed store, and the plaintext settings model cannot carry it even by accident.

**Required changes.**
1. Remove `cloudApiKey` from `AppSettings` and its write from `AppSettings.toPreferences()`. Keep
   the `CLOUD_API_KEY` key constant for migration reads only.
2. `ConfigViewModel` exposes `cloudApiKey: StateFlow<String>` backed by `CredentialStore`, with
   `setCloudApiKey()` writing straight through. `ConfigScreen`'s API Key field binds to it.
3. Every read site drops the `fallback = settings.cloudApiKey` argument: `CloudUploadExecutor`,
   `MainViewModel.registerCloudDeviceIfNeeded`, and `ConfigViewModel`'s `testConnection`,
   `registerDevice`, `fetchManifest`.
4. The migration blanks the DataStore entry **unconditionally** whenever it is present, rather
   than only when the credential store is empty.
5. `cloudSetupCode` stays in DataStore — it is a single-use venue provisioning code, useless after
   registration — but is excluded from export by FR-7.

**Acceptance.**
- [ ] After entering an API key and changing an unrelated setting, `adb shell run-as` shows no
      plaintext key in the DataStore file.
- [ ] An install carrying a plaintext key from a previous build has it moved and blanked on first
      launch, and uploads keep working with no re-entry.
- [ ] Uploads, registration, manifest fetch, and TEST CONNECTION all authenticate correctly.
- [ ] `grep -rn "cloudApiKey" app/src` shows no remaining path that persists it outside
      `CredentialStore`.

## FR-9 — Backup exclusions + crash-safe CredentialStore (MEDIUM)

**Problem.** `backup_rules.xml` claims Android excludes `shared_prefs` automatically. It does not —
private `shared_prefs` is exactly what auto-backup captures, including `photoflow_credentials.xml`.
The Keystore master key cannot be backed up, so a restore yields ciphertext with no key.
`CredentialStore.prefs` is an uncaught `by lazy` reached from `PhotoFlowApplication.onCreate`'s
coroutine, so that restore can hard-crash the app at startup.

**Required changes.**
1. Add `<exclude domain="sharedpref" path="photoflow_credentials.xml" />` to **both**
   `backup_rules.xml` and `data_extraction_rules.xml` (the latter under both `<cloud-backup>` and
   `<device-transfer>`). Correct the misleading comment.
2. `CredentialStore`: catch failures from `EncryptedSharedPreferences.create()`, delete the corrupt
   prefs file and master-key alias, recreate once, and fall back to an in-memory map if that still
   fails — uploads then fail with a clear message instead of the app crashing at launch.
3. Wrap `migrateSecretsToCredentialStore()` in try/catch for the same reason.

**Acceptance.**
- [ ] `adb backup` / bugreport output contains no `photoflow_credentials.xml`.
- [ ] Simulated corruption (overwrite the prefs file with garbage) — app launches, logs the
      recovery, and prompts for credentials rather than crashing.
- [ ] Normal launch path is unchanged — no extra Keystore work on the happy path.

---

# GROUP D — Cleanup

## FR-10 — FTP ghost rows, photoOp export, documentation drift (LOW)

1. **FTP ghost rows** — `FtpUploadExecutor.kt:48` returns `failure()` without touching the row, so
   it stays `PENDING` with no error message: re-enqueued at every app start, permanently visible
   in the transfer queue, and undiagnosable. Set `FAILED` + "Local file not found" first, matching
   the Cloud path.
2. **Export/import drops `photoOp`** — the schema-v8 field is missing from `profilesToJson` and
   `profileFromJson`, so a settings round-trip silently loses the FTP subfolder. Add it. While
   there: wrap the profile replace in a Room transaction, and call
   `credentialStore.removeFtpPassword(id)` for each profile `deleteAll()` removes so orphaned
   credentials stop accumulating.
3. **CLAUDE.md drift** —
   - `UploadState` has 4 values, not 5; `RETRY_REQUIRED` exists only in the docs.
   - `NavGraph.startDestination` is `Screen.Main`, not `ScanCard`.
   - The "disk warning chip" listed under UX priorities does not exist on MainScreen; `StatFs` is
     ConfigScreen-only.
   - Document the new session-activation semantics (FR-1), the past-session banner (FR-2), and the
     retry default (FR-3).
   - Add the FR-4/FR-5 tethering contract to the HARD RULES block, since a future agent could
     plausibly undo either one while "cleaning up":
     - `readDataAndResponse` must reject a short data phase rather than return a partial payload,
       and `expectedPayloadBytes` must keep mapping the 0xFFFFFFFF unknown-length sentinel to 0 —
       returning a real expectation there would reject every unknown-length transfer as truncated.
     - `teardownAfterPollFailure()` must not be "simplified" into a `closeConnection()` call: it
       runs on `pollingJob`, which `closeConnection()` cancels, so the teardown would cancel
       itself partway through.
     - The poll loop must keep rethrowing `CancellationException` ahead of the general `catch`,
       or a normal disconnect will run the recovery path and overwrite the DISCONNECTED status.

**Acceptance.**
- [ ] A missing local file shows `FAILED` + a readable message and stops being re-enqueued.
- [ ] Export then import round-trips `photoOp` intact.
- [ ] CLAUDE.md matches the shipped code; a fresh agent reading it would not reintroduce the
      override-session or unbounded-retry patterns.

---

## Deferred — deliberately not in this pass

| Item | Reason |
|---|---|
| `GenericPtpAdapter.pollNewImageHandles` uses `return@repeat` where it should break — burns up to 800 ms per poll on non-Canon/non-Nikon bodies | Revisit later; does not affect the Canon T7 field test |
| Sequence numbers restart after CLEAR SESSION HISTORY, regenerating filenames already on the FTP server | Accepted for now |
| `terminal` column + Room migration v10 for retry classification (FR-3 optional) | Schema risk before a field test; the retry bound already removes the storm |
| `debugRetryLastUpload()` shipping in release builds | Intentional per WI-3 guard 6 |
| **Recover shots taken while the cable was disconnected** — see design notes below | Feature, not a fix: schema + fragile subsystem + UI, with an unresolved prerequisite |

## Deferred feature — disconnected-shot recovery

Observed 2026-08-18 during FR-4/FR-5 testing: a frame shot while the cable was unseated is
**silently lost**. On reconnect the poll loop seeds `knownHandles` with everything on the card
(`seeded: 320` before the disconnect, `seeded: 321` after), so the new image counts as
pre-existing and is never imported. Nothing in the UI indicates it happened. If cables get
bumped in the field, an operator can finish a session short without knowing.

**Cheap interim — DONE (`286c991`), device-verified 2026-08-18.** One shot taken while connected
imported normally (`XYZ297729_25.jpg`), then three taken while unplugged were correctly reported:
`missed-while-disconnected: 3 shot(s) … (was 322, now 325)`. Note the baseline was 322, not the
seeded 321 — the per-poll refresh excluded the shot that had imported successfully. Had the
baseline been the seed count, it would have reported 4. Banner text truncated on the Moto G's
width and was fixed in `773d703`.
 `MtpCameraManager.lastKnownHandleCount` is refreshed on each
poll so it reflects where the previous session actually *ended*, not where it started, and the next
connect compares its seed against it. A positive delta logs
`missed-while-disconnected: N shot(s)…` and raises `missedWhileDisconnected`, which `MainScreen`
renders as a dismissible `MissedShotsBanner` at top level — not inside `TetheredPanel`, whose
guidance overlay hides once images are showing, which is exactly when the warning matters.
Recovers nothing; makes the loss visible.

Its limits are deliberate and must not be treated as a foundation for the full feature: it is a
count comparison, so deleting images on the camera between connections can mask a missed shot, a
swapped card reports a bogus number, and the baseline is in memory so a disconnect spanning an app
restart reports nothing.

**Full feature sketch:**
1. Add `sourceFilename` to `SessionImage` (Room migration v10) — the camera's own name, e.g.
   `IMG_1234.JPG`. `handleTetheredImage` already receives it and currently discards it.
2. On reconnect, diff the card against what Room already holds and offer the difference in a
   recovery dialog (Import / Dismiss).
3. Import selected shots through the normal rename/save/upload pipeline.

**Open question that must be settled first:** are PTP object handles stable across sessions on the
Canon T7? The spec does not guarantee a camera reuses a handle for the same file after
CloseSession/OpenSession. If Canon re-enumerates, handle diffing re-imports duplicates or misses
files, and identity must come from `GetObjectInfo` filenames instead — cheap when handles are
stable (one call per genuinely new shot), expensive when they are not (one call per card object,
321 round trips in the observed state). Test this before committing to a design.

**Second open question:** recovered shots belong to the session that was active when they were
*taken*, which may no longer be the active session. Importing into whatever is active now would
misfile them — the same class of bug FR-1 just fixed.

---

## Commit & delivery conventions

- One commit per work item, prefixed `FR-1: selecting a session activates it`, etc.
  FR-1 and FR-2 may share a commit if the banner is inseparable from the activation change.
- `./gradlew assembleDebug` must pass before moving to the next item;
  `./gradlew testDebugUnitTest` after any item that adds or touches tests (FR-3, FR-7, FR-8, FR-10).
- New unit tests belong in `app/src/test/java/com/photoflowmobile/app/`: export-omits-secrets,
  `photoOp` round-trip, retry-bound evaluation. Session activation needs Room, so it goes in
  `androidTest`.
- Add a device checklist to `TESTING.md` covering: tap a past session, confirm the banner appears
  and a tethered shot lands in *that* session; scan a known card and confirm the sequence
  continues; set a wrong API key and confirm the queue stops after 3 attempts; yank the USB cable
  mid-transfer and confirm no corrupt file appears.
- Update the Status table in this document as items complete.
- Do NOT merge to `master`. Note that `hardening/reliability-security-pass` is still unmerged —
  decide the merge order before either branch lands.
- If any instruction here conflicts with observed code behaviour, stop and flag it rather than
  guessing — especially around FR-1's session-state transitions and FR-8's migration path.
