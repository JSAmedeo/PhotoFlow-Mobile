# PhotoFlow Mobile — Reliability & Security Hardening Pass

**Repo:** https://github.com/JSAmedeo/PhotoFlow-Mobile
**Base branch:** `master` (commit `4f74091`, post Cloud API Phase 1 merge)
**Working branch:** `hardening/reliability-security-pass` — create this first, do all work on it

```bash
git checkout master && git pull
git checkout -b hardening/reliability-security-pass
```

You are performing a hardening pass on a working, field-deployed Android app. Read `CLAUDE.md` in the repo root before writing any code — it contains architectural rules and documented intentional behaviors that MUST NOT change. This document defines seven work items (WI-1 through WI-7), ordered by priority. Complete them as separate commits in order. Each has its own acceptance criteria.

---

## ⛔ GUARDS — Intentional behaviors that must survive this pass

Do not "fix" any of the following. They are deliberate:

1. **Splash screen:** the 1.4 s delay, the `AndroidView` + `ImageView` icon loading (NOT `painterResource` — it crashes on adaptive icons), and `setOnExitAnimationListener { it.remove() }`. Leave `SplashScreen.kt` and related `MainActivity` code untouched.
2. **PtpConnection.kt hardware workarounds:** `SET_INTERFACE(0)` control transfer, `Thread.sleep(800)` after interface claim, `FIRST_CHUNK_SIZE = 512` first-read limit, and the Canon "response-without-data-phase is legal" handling. Do not touch `PtpConnection.kt`, `PtpCore.kt`, `MtpCameraManager.kt`, or `VendorAdapter.kt` in this pass.
3. **`captureMutex`** in `MainViewModel` serializing count → sequence → write → insert. Keep it exactly as is.
4. **Cloud status-code semantics** in `CloudUploadWorker`: 409 = success (idempotency), 401/404/422 = terminal failure, `image.id` as `idempotency_key`. Preserve these mappings when refactoring retry behavior (WI-1 changes *how* retryable failures are signaled, not *which* codes are retryable).
5. **UPLOADING indicator gating:** workers only set `UPLOADING` when the current state is `PENDING`, so silent background retries don't flash the UI. Preserve.
6. **`debugRetryLastUpload()`** in `ConfigViewModel` intentionally reuses the same image id to test server idempotency. Keep its behavior; update only its work-request construction if WI-2 requires it.
7. **Startup recovery** in `MainViewModel.init`: resetting stuck `UPLOADING` → `PENDING` and re-enqueueing pending uploads must continue to happen.
8. **File naming, capture codes, sequence/sort-order logic** — no changes.
9. The app must keep working with **zero configuration changes** for existing installs: all Room migrations additive, no destructive DataStore changes, no new required permissions.

---

## WI-1 — Durable retry via WorkManager (HIGH)

**Problem:** `FtpUploadWorker` and `CloudUploadWorker` return `Result.failure()` for transient errors (connect/read timeouts, connection refused, HTTP 5xx). WorkManager considers the job complete. Actual retry depends on `MainViewModel.startAutoRetryLoop()`, a `while(true)` loop in `viewModelScope` that dies with the process. Result: uploads that fail while the app is backgrounded/killed are never retried until the user reopens the app. This contradicts the CLAUDE.md claim of "durable background FTP upload and retry."

**Required changes:**

1. In **both workers**, classify outcomes into three buckets:
   - **Success** → `Result.success()` (unchanged, including 409 in cloud worker).
   - **Transient/retryable** → `Result.retry()`. FTP: `SocketTimeoutException`, `ConnectException`, generic `IOException` during transfer. Cloud: `SocketTimeoutException`, `ConnectException`, HTTP >= 500.
   - **Terminal** → `Result.failure()`. FTP: login failure, missing file, no active profile, missing image row. Cloud: 401, 404, 422, missing file/profile/session/device registration.
2. On `Result.retry()`, still update the Room row to `FAILED` with the human-readable `errorMessage` exactly as today — the UI's transfer-queue error display must not regress. The Room state is the UI's source of truth; WorkManager state is the retry engine. These are intentionally decoupled.
3. Add to every upload work request (in `MainViewModel.buildUploadRequest` and `ConfigViewModel.debugRetryLastUpload`):
   - `setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())`
   - `setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)`
4. **Keep `startAutoRetryLoop()`**, but demote it to a UI-facing supplement: it now exists to honor the user-configurable `autoRetryEnabled` / interval / max-count settings and to bump the visible `retryCount`. Add a guard so it does not double-enqueue: before enqueueing, check `WorkManager.getWorkInfosForUniqueWork("upload_${image.id}")` and skip if a work item is already ENQUEUED, RUNNING, or BLOCKED. Document this interplay in a comment block above the function and in CLAUDE.md.
5. Bound WorkManager retries: in each worker, if `runAttemptCount >= 10`, convert a would-be `Result.retry()` into `Result.failure()` (Room row already says FAILED; the in-app loop can still resurrect it per user settings).

**Acceptance:**
- [ ] Airplane-mode test: capture with Wi-Fi off → work sits ENQUEUED under the network constraint (does not spin-fail); Wi-Fi on → uploads without app interaction.
- [ ] Process-death test: fail an upload against an unreachable host, force-stop the app, restore the host, wait — WorkManager retries and the row reaches UPLOADED without the app being opened.
- [ ] 401 and 422 produce exactly one attempt (no WorkManager retry).
- [ ] 409 still resolves as UPLOADED.
- [ ] Transfer-queue dialog still shows error messages for failed items.
- [ ] No duplicate uploads when the auto-retry loop and WorkManager backoff overlap (verify via server logs or `[UPLOAD]`/`[CLOUD]` log lines: one attempt in flight per image id at a time).

---

## WI-2 — Fix worker-selection race at startup (HIGH)

**Problem:** `MainViewModel.buildUploadRequest()` branches on `activeConnection.value`, a Room-backed StateFlow with initial value `null`. The `init` block re-enqueues pending uploads immediately, typically before the flow's first emission, so `null` falls through to the `else` branch → `FtpUploadWorker` — even on a Cloud-configured device. Also, jobs queued under one connection type keep that type if the user switches connections while they're pending.

**Required change — dispatcher pattern:** Replace the two-worker dispatch with a single `UploadWorker` that resolves the connection type itself at execution time:

1. Create `data/worker/UploadWorker.kt`. In `doWork()`, read `profileDao.getActiveProfileOnce()` and branch on `profile.connectionType`, delegating to the existing FTP and Cloud upload logic. Recommended refactor: extract the body of each existing worker into `suspend fun uploadViaFtp(...): Result` and `suspend fun uploadViaCloud(...): Result` (new files `FtpUploadExecutor.kt` / `CloudUploadExecutor.kt`, or internal functions), and have `UploadWorker` call them. Delete the old worker classes once nothing references them.
2. Keep a **single** `uploadMutex` in `UploadWorker.companion` — this also fixes the latent issue that FTP and Cloud uploads currently serialize on *separate* mutexes.
3. Update `buildUploadRequest`, startup re-enqueue, auto-retry loop, `forceRetry`, and `ConfigViewModel.debugRetryLastUpload` to build `UploadWorker` requests. The unique-work name stays `upload_$imageId`.
4. `enqueueUpload`'s `via $via` log line may still read `activeConnection.value` for logging only — but the worker itself must never depend on ViewModel state.

**Acceptance:**
- [ ] Cold-start with a CLOUD_API active profile and pending images from a previous run → images upload via the cloud path (check `[CLOUD]` log lines, not `[UPLOAD] started` FTP lines).
- [ ] Switching the active connection while items are queued routes subsequent attempts through the new connection type.
- [ ] Only one upload in flight at a time across both transport types.
- [ ] Old `FtpUploadWorker`/`CloudUploadWorker` classes removed; project compiles with no references.

---

## WI-3 — Credentials at rest + backup exclusion (HIGH)

**Problem:** FTP passwords live plaintext in the Room `connection_profiles` table; the cloud API key lives plaintext in Preferences DataStore. `AndroidManifest.xml` sets `allowBackup="true"`, and both `backup_rules.xml` and `data_extraction_rules.xml` are unedited template stubs — so the database and DataStore (credentials included) are eligible for Google cloud backup and device-to-device transfer.

**Required changes:**

1. **Backup exclusion (do this regardless of anything else):**
   - `backup_rules.xml` (API ≤ 30 path): exclude the Room database (`domain="database"`, the app DB file and its `-wal`/`-shm` if pattern rules require) and the DataStore file (`domain="file"`, path `datastore/` — verify the actual filename under `files/datastore/`).
   - `data_extraction_rules.xml` (API 31+): mirror the same exclusions under BOTH `<cloud-backup>` and `<device-transfer>`.
   - Session/image metadata may also be excluded wholesale — a restored backup with stale upload state is worse than a clean start for this app. Excluding the entire database is acceptable and simpler.
2. **Encrypt secrets:** create a `CredentialStore` class backed by Android Keystore.
   - Use `androidx.security:security-crypto` (`EncryptedSharedPreferences` with `MasterKey`, AES256-GCM) — add to `libs.versions.toml` + gradle. It is deprecated-but-functional; if you prefer, implement a minimal Keystore AES-GCM wrapper instead. Either is acceptable; pick one and note the choice in the commit message.
   - Store: FTP password per profile (key: `ftp_password_<profileId>`), and `cloud_api_key`.
3. **Migration path (must be seamless for existing installs):**
   - Room: add `MIGRATION_8_9` that leaves the schema in place but is paired with app-level logic: on `PhotoFlowApplication` startup, for every profile with a non-empty `password` column, move the value into `CredentialStore` and blank the column. Same one-time sweep for `CLOUD_API_KEY` in DataStore. Idempotent: safe to run every launch (it only migrates non-empty values).
   - Simpler alternative if profile ids make key management awkward: keep the `password` column as the storage location but encrypt its *contents* via `CredentialStore` (encrypt-on-write, decrypt-on-read in the DAO/repository layer). Choose whichever is cleaner; the invariant is **no plaintext secret persisted anywhere** after first launch post-update.
4. Reads: `UploadWorker` (FTP path) and `CloudApiClient` construction get secrets from `CredentialStore` (or via the decrypting repository), never raw from Room/DataStore.
5. Do not log secrets. Audit existing `pipelineLog`/`workerLog` lines while you're in there — the current `registering device uuid=$uuid setupCode=$setupCode` line in `MainViewModel` leaks the setup code into logcat; redact it (e.g. log only its length or last 2 chars).

**Acceptance:**
- [ ] Fresh install: create FTP profile + cloud key, verify via Device Explorer / `adb shell run-as` that the DB `password` column and DataStore file contain no plaintext secrets.
- [ ] Upgrade path: install current master build, configure credentials, then install this branch's build over it — uploads still work with no re-entry of credentials, and plaintext values are gone after first launch.
- [ ] `adb backup` / bugreport-visible files contain no credentials; backup rules exclude DB + DataStore on both API paths.
- [ ] Setup code no longer appears in logcat.

---

## WI-4 — Scope cleartext networking (MEDIUM)

**Problem:** `network_security_config.xml` sets `cleartextTrafficPermitted="true"` globally. Plain HTTP to a LAN FTP-adjacent backend is a deliberate tradeoff; plain HTTP carrying the `X-PhotoFlow-Api-Key` header to an internet-hosted cloud API is not.

**Required change:** Replace the global base-config with:
- `<base-config cleartextTrafficPermitted="false">`
- A `<domain-config cleartextTrafficPermitted="true">` — but domain-config requires literal domains, and hosts here are user-entered IPs. So instead implement the policy in `CloudApiClient`: if `baseUrl` scheme is `http://` AND the host is **not** an RFC-1918/loopback address (`10.*`, `172.16–31.*`, `192.168.*`, `127.*`), refuse the connection with a clear error surfaced as the image `errorMessage` ("Cloud API over plain HTTP is only allowed for LAN addresses — use https:// for internet hosts"). Keep the network-security-config permitting cleartext (Android's config can't express "private ranges only"), and let the client-side check be the enforcement point. Add a unit test for the RFC-1918 classifier.
- FTP is out of scope (protocol is inherently plaintext; venue-LAN usage is accepted and already documented).

**Acceptance:**
- [ ] `http://192.168.x.x` cloud profile works unchanged.
- [ ] `http://<public-host>` cloud profile fails fast with the friendly error on the transfer queue.
- [ ] `https://` hosts work unchanged.
- [ ] Unit tests cover the private-address classifier (10/8, 172.16/12 boundaries, 192.168/16, 127/8, public addresses, hostnames → treated as public).

---

## WI-5 — Stream multipart upload instead of readBytes() (MEDIUM)

**Problem:** `CloudUploadWorker` calls `file.readBytes()` — the full image is held in memory, and `HttpURLConnection` additionally buffers the whole request body unless streaming mode is set. Tethered DSLR files can be 25–60 MB; burst queues on low-RAM devices risk OOM.

**Required changes in `CloudApiClient.postMultipart`:**
1. Change the signature to accept a `File` (or an `InputStream` provider) instead of `fileBytes: ByteArray`.
2. Compute the exact multipart body length (fields + boundaries + headers + `file.length()`) and call `conn.setFixedLengthStreamingMode(totalLength)` before writing. If exact-length computation proves brittle, `setChunkedStreamingMode(16384)` is an acceptable fallback — verify the backend accepts chunked transfer encoding first; note which mode was chosen in the commit message.
3. Stream the file with a 16 KB buffer (`file.inputStream().use { it.copyTo(out, 16 * 1024) }`).
4. `mimeType`: derive from the filename extension (`.jpg/.jpeg → image/jpeg`, `.png → image/png`, `.heic → image/heic`, `.cr2/.cr3/.nef/.arw → application/octet-stream`, default `application/octet-stream`). Small utility function + unit test.

**Acceptance:**
- [ ] Upload of a large (≥40 MB) file succeeds without heap spike (spot-check Android Studio profiler or just verify success on a mid-range device).
- [ ] Normal JPEG uploads unchanged; server still receives correct `Content-Type` per part.
- [ ] Unit test for the extension→MIME mapping.

---

## WI-6 — Remove dead legacy package and .idea (LOW)

1. Delete `app/src/main/java/com/photoflow/mobile/` entirely (legacy `MainActivity`, `ui/theme/*` — verify nothing imports from `com.photoflow.mobile` first: `grep -rn "com.photoflow.mobile" app/src`).
2. Delete the two template test stubs' package if it's also under `com.photoflow.mobile` — the test source dirs currently use that package (`app/src/test/java/com/photoflow/mobile/`, `app/src/androidTest/...`). Move/rename them to `com.photoflowmobile.app` as living homes for WI-4/WI-5/WI-7 unit tests rather than deleting outright.
3. Remove `.idea/` from version control: `git rm -r --cached .idea` and add `.idea/` to the root `.gitignore`.
4. Reorder `MIGRATION_7_8` after `MIGRATION_6_7` in `AppDatabase.kt` (cosmetic; behavior unchanged).

**Acceptance:**
- [ ] Clean build; APK contains no `com.photoflow.mobile` classes (check with `apkanalyzer` or dex dump grep).
- [ ] `.idea/` untracked; repo clones without IDE state.

---

## WI-7 — Unit tests + permission documentation (LOW)

1. **Document ACCESS_FINE_LOCATION** — it IS used (Wi-Fi SSID chip in `MainScreen.kt`, `WifiSsidChip()`; Android requires fine location to read SSID). Do not remove it. Add: (a) an XML comment above the permission in the manifest explaining why, (b) a note in CLAUDE.md under a new "Permissions" section, (c) confirm the chip degrades gracefully when permission is denied (it already checks `checkSelfPermission` — verify no crash path).
2. **Unit tests** (JVM, no Robolectric needed if you extract pure functions):
   - Cloud HTTP status → outcome classification (success / retry / terminal) — extract to a pure `classifyCloudResponse(status: Int): UploadOutcome` function used by the executor, then test all branches incl. 200/201/409/401/404/422/500/503/418.
   - RFC-1918 classifier (WI-4).
   - Extension→MIME mapping (WI-5).
   - `parseIso8601Millis` — with/without `Z`, with offset, garbage input.
3. Update **CLAUDE.md**: new retry architecture (WorkManager `Result.retry()` + constraints as the durable engine; in-app loop as user-visible supplement), the single `UploadWorker` dispatcher, `CredentialStore`, backup exclusions, cleartext policy, and the Permissions section.

**Acceptance:**
- [ ] `./gradlew test` green with the new tests.
- [ ] CLAUDE.md accurately reflects the post-pass architecture (a fresh agent reading it would not reintroduce the old two-worker or failure()-based retry patterns).

---

## Commit & delivery conventions

- One commit per work item, prefixed: `WI-1: durable WorkManager retry with network constraints`, etc. WI-3 may be two commits (backup rules; credential encryption) if cleaner.
- Bump `versionCode` to 3 and `versionName` to `1.2` in the final commit.
- After each WI: `./gradlew assembleDebug` must succeed before moving on. Run `./gradlew test` after WI-4 onward.
- Do NOT merge to master. Finish with the branch pushed and a summary of: what changed per WI, any deviations from this spec and why, and the manual test steps still requiring a physical device (airplane-mode test, process-death test, tethered large-file upload, credential upgrade-path test).
- If any instruction here conflicts with observed code behavior, stop and flag it in the summary rather than guessing — especially around the Room migration in WI-3.
