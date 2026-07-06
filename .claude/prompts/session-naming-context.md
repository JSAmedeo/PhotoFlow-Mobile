# PhotoFlow Mobile — Session & File Naming Context

## 1. What a Session Is

A **session** is a named container for a group of photos taken at a single event or station. Every photo captured or received by the app belongs to exactly one session.

### Room Entity: `sessions` table

| Column      | Type    | Notes                                      |
|-------------|---------|---------------------------------------------|
| `id`        | Long    | Auto-generated primary key                  |
| `barcode`   | String  | The session code — scanned or typed by user |
| `startTime` | Long    | Epoch milliseconds                          |
| `endTime`   | Long?   | Epoch milliseconds, null while active       |
| `status`    | String  | `"active"` or `"complete"`                  |

- Only one session can be `"active"` at a time. Starting a new session closes any existing active session by setting its `status = "complete"` and `endTime = now`.
- The `barcode` string is the raw value from the physical card — there is no normalization or formatting applied to it. Whatever the scanner reads (or the user types) is stored verbatim.
- The `id` is a database-internal surrogate key used internally for foreign key references. It is never shown in the UI.
- The `barcode` is the user-visible session identifier shown in the top bar and embedded in filenames.

---

## 2. Session Lifecycle

### Creation

Sessions are started from **ScanCardScreen** via two paths:

1. **Barcode scan** — ML Kit reads a QR/barcode from the camera viewfinder. The first successful decode fires `viewModel.startSession(barcode, onSessionStarted)`. An `AtomicBoolean` flag (`handled`) prevents duplicate triggers from the same scan.

2. **Manual entry** — a text field at the bottom of the screen accepts any string. The GO button (or keyboard Done action) calls the same `startSession()`. The keyboard is configured with `KeyboardCapitalization.Characters` so codes default to uppercase.

`ScanCardViewModel.startSession()` calls `SessionRepository`, which:
1. Finds any existing active sessions and updates them to `status = "complete"`, `endTime = now`
2. Inserts a new `Session(barcode = code, startTime = now, status = "active")`
3. Navigates to MainScreen, clearing the back stack

### Completion

A session is completed whenever a new session starts. There is no explicit "end session" button — the workflow assumes a new scan replaces the current session.

### Selection

`MainViewModel` tracks which session's images are currently displayed:
- `activeSession` — the Room-sourced active session (status = "active")
- `selectedSessionId` — either an explicit override (from tapping a session in history) or falls back to `activeSession.id`
- Tapping a past session in the Session History panel sets `_overrideSessionId`. This clears automatically when a new active session starts.

---

## 3. File Naming System

### Core Concept

When a photo arrives (from camera capture or tethered DSLR), the app discards the camera-assigned filename entirely and generates its own filename according to the configured naming fields. This renamed filename is stored in `SessionImage.filename` and used everywhere: thumbnails, transfer queue, FTP upload path, cloud upload filename, review pane.

### The `buildFilename()` Function

```kotlin
// AppSettings.kt
fun buildFilename(settings: AppSettings, barcode: String, sequenceNumber: Int): String {
    val parts = settings.namingFields.map { field ->
        when (field.type) {
            FieldType.BARCODE  -> barcode
            FieldType.SEQUENCE -> sequenceNumber.toString().padStart(2, '0')
            FieldType.CUSTOM   -> field.customValue.ifBlank { "custom" }
        }
    }.filter { it.isNotBlank() }
    return parts.joinToString(settings.namingSeparator) + ".${settings.namingExtension.lowercase()}"
}
```

**Inputs:**
- `settings` — current `AppSettings` (naming fields, separator, extension)
- `barcode` — the active session's barcode string (verbatim)
- `sequenceNumber` — the ordinal of this photo within the session (1-based)

**Output example:** `PHOTODAY_0006_01.jpg`

### Sequence Numbering

The sequence number is calculated at capture time as:

```kotlin
val existingCount = repository.getImagesForSession(session.id).first().size
val renamedFilename = buildFilename(settings, session.barcode, existingCount + 1)
```

- **1-based:** the first photo in a session gets sequence `1`, rendered as `01`
- **Zero-padded to 2 digits:** `01`, `02`, ... `09`, `10`, `11`, ... `99`, `100` (no cap — padding is minimum 2 digits, not maximum)
- **Per-session:** sequence resets when a new session starts. Two different sessions each start at `01`.
- **Gaps are possible:** if a file save fails after the count is read but before it's inserted into Room, the next photo would reuse the same sequence number. In practice this is rare because both reads are on the same coroutine.
- **Tethered vs Native:** both paths use the same `existingCount + 1` logic. For tethered images, `handleTetheredImage()` does the count query; for native captures, `capturePhoto()` does the same.

---

## 4. Naming Fields

### Field Types

There are three field types, defined in `FieldType` enum:

| Type       | Label in UI   | Value inserted into filename                                              |
|------------|---------------|---------------------------------------------------------------------------|
| `BARCODE`  | "Barcode"     | The session's barcode string verbatim                                     |
| `SEQUENCE` | "Seq Number"  | Zero-padded integer (min 2 digits): `01`, `02`, ... `10`, `11`           |
| `CUSTOM`   | "Custom"      | A user-entered text string; falls back to `"custom"` if left blank        |

### Default Configuration

```kotlin
fun defaultNamingFields(): List<NamingField> = listOf(
    NamingField(FieldType.CUSTOM),    // Field 1: blank custom text (user fills in event name etc.)
    NamingField(FieldType.BARCODE),   // Field 2: session barcode
    NamingField(FieldType.SEQUENCE)   // Field 3: sequence number
)
```

With an empty Custom field and default separator `_`, preview shows: `TEXT_0006_01`

### Separator Options

Configured in ConfigScreen → File Naming → Separator. Three choices, selected via toggle buttons:

| Value | Example result        |
|-------|-----------------------|
| `_`   | `EVENT_0006_01.jpg`   |
| `-`   | `EVENT-0006-01.jpg`   |
| `.`   | `EVENT.0006.01.jpg`   |

Default: `_`

### Extension Options

Configured in ConfigScreen → File Naming → Extension. Three choices:

| Value | Stored as (lowercase) |
|-------|-----------------------|
| `JPG` | `.jpg`                |
| `DNG` | `.dng`                |
| `RAW` | `.raw`                |

Default: `JPG` → `.jpg`

Note: the extension is stored in uppercase in DataStore and AppSettings (`namingExtension = "JPG"`), but `buildFilename()` calls `.lowercase()` when appending it, so the actual filename always has a lowercase extension.

---

## 5. Configuring Naming Fields — User Interaction

### Location

ConfigScreen → FILE NAMING section (right-hand scrollable content area).

### Field List

Each active naming field is shown as a row with:
- A "FIELD N" label (read-only ordinal)
- A dropdown button showing the current type (BARCODE / SEQ NUMBER / CUSTOM) — tap to change
- A free-text input (only visible when type = CUSTOM) for entering the custom string
- A × remove button (disabled if only one field remains)

### Adding Fields

A "+ ADD FIELD" button appends a new `NamingField(FieldType.CUSTOM)` to the list. There is no upper limit on field count enforced in code.

### Removing Fields

The × button calls:
```kotlin
onChange(settings.copy(namingFields = settings.namingFields.filterIndexed { i, _ -> i != index }))
```
The × is disabled (invisible) when only one field remains — `namingFields.size > 1` controls the enabled state.

### Reordering

There is no drag-to-reorder UI. Fields are displayed in list order and that order is the filename order. To reorder, the user must remove and re-add fields.

### Custom Field Text Input

Custom field text uses a local draft variable (`customDraft`) keyed on `(number, field.type)` to prevent the DataStore round-trip from clobbering the cursor:

```kotlin
var customDraft by remember(number, field.type) { mutableStateOf(field.customValue) }
```

Both the draft and DataStore are updated on every keystroke. The key resets the draft if the field position or type changes, but not on normal DataStore emissions caused by the same edits.

### Live Preview

The FILE NAMING section shows a PREVIEW box below the field list:
```kotlin
"${buildNamingPreview(settings.namingFields, settings.namingSeparator)}.${settings.namingExtension}"
```

`buildNamingPreview()` substitutes placeholder values:
- BARCODE → `0006`
- SEQUENCE → `01`
- CUSTOM → the actual `customValue`, or `"TEXT"` if blank

Preview updates immediately as fields are added, removed, or changed — no save required.

---

## 6. Naming Field Persistence (DataStore)

Naming fields are serialized to a single string stored in DataStore under key `"naming_fields"`.

### Serialization Format

```kotlin
fun serializeNamingFields(fields: List<NamingField>): String =
    fields.joinToString("|") { "${it.type.name}:${it.customValue}" }
```

Example for default fields with custom value `"EVENT"`:
```
CUSTOM:EVENT|BARCODE:|SEQUENCE:
```

- Fields are pipe-delimited (`|`)
- Each field is `TYPE_NAME:customValue` — the colon is always present even when customValue is empty
- `type.name` is the enum name: `CUSTOM`, `BARCODE`, or `SEQUENCE`

### Deserialization

```kotlin
fun deserializeNamingFields(encoded: String): List<NamingField> {
    if (encoded.isBlank()) return defaultNamingFields()
    val result = encoded.split("|").mapNotNull { part ->
        val colon = part.indexOf(':')
        if (colon < 0) return@mapNotNull null
        val typeName = part.substring(0, colon)
        val customValue = part.substring(colon + 1)
        val type = FieldType.entries.firstOrNull { it.name == typeName } ?: return@mapNotNull null
        NamingField(type, customValue)
    }
    return result.ifEmpty { defaultNamingFields() }
}
```

Falls back to `defaultNamingFields()` if the stored string is blank or unparseable. Unknown type names are silently skipped (forward-compatibility for future field types).

### All Naming-Related DataStore Keys

| Key                | Type   | Default |
|--------------------|--------|---------|
| `"naming_fields"`  | String | serialized `defaultNamingFields()` |
| `"naming_separator"` | String | `"_"` |
| `"naming_extension"` | String | `"JPG"` |

All settings auto-save to DataStore on any change — there is no explicit Save button. Changes take effect immediately for the next photo captured.

---

## 7. SessionImage Entity

Every photo processed by the app creates one `SessionImage` record in the `session_images` Room table.

### Schema

| Column         | Type        | Notes                                                                 |
|----------------|-------------|-----------------------------------------------------------------------|
| `id`           | Long        | Auto-generated primary key                                            |
| `sessionId`    | Long        | Foreign key → `sessions.id` (indexed)                                |
| `filename`     | String      | The renamed filename (e.g. `EVENT_0006_01.jpg`) — used everywhere     |
| `localPath`    | String      | Absolute path to the file on device storage                           |
| `timestamp`    | Long        | Epoch milliseconds — when the image was received/captured             |
| `uploadState`  | UploadState | Current upload state (see below)                                      |
| `errorMessage` | String?     | Human-readable error from the last failed upload attempt              |
| `retryCount`   | Int         | Number of times the auto-retry loop has re-enqueued this upload       |
| `cloudPhotoUid`| String?     | UUID assigned by the cloud backend after successful upload            |

### UploadState Enum

| State            | Meaning                                                                  |
|------------------|--------------------------------------------------------------------------|
| `PENDING`        | Queued, not yet attempted                                                |
| `UPLOADING`      | Worker has started — set at the beginning of the upload attempt          |
| `UPLOADED`       | Successfully transferred (FTP or cloud)                                  |
| `FAILED`         | Last attempt failed — `errorMessage` contains the reason                 |
| `RETRY_REQUIRED` | Reserved state (not currently written by any worker)                     |

**State transitions:**
- On capture/receive: inserted as `PENDING`
- Worker starts: `PENDING` → `UPLOADING`
- Worker succeeds: `UPLOADING` → `UPLOADED`
- Worker fails: `UPLOADING` → `FAILED` (errorMessage set)
- App killed mid-upload: `UPLOADING` → reset to `PENDING` on next launch (startup recovery in `MainViewModel.init`)
- Auto-retry re-enqueues: stays `FAILED` (only `retryCount` increments) — the failed state and error message remain visible in the UI during retry attempts
- Manual force-retry: reset to `PENDING`, `retryCount = 0`, `errorMessage = null`

### Image Query Order

Images are always queried `ORDER BY timestamp DESC` — newest first. The thumbnail rail and review pane show the most recently captured image at the top/first position.

---

## 8. Local Storage

### Primary Storage

All captured/received images are written to:
```
context.filesDir/captures/<renamed_filename>
```

This is the app's private internal storage. It is:
- Not accessible to other apps
- Not visible in the Android file manager
- Not deleted when the user clears app cache (only `cacheDir` is cleared by the cache-clear button)
- Retained indefinitely unless the user uninstalls the app or the `session_images` record is explicitly deleted

The `localPath` column in `session_images` stores the absolute path, e.g.:
```
/data/user/0/com.photoflowmobile.app/files/captures/EVENT_0006_01.jpg
```

### Photo Backup (Optional)

If `saveBackupToPhone = true`, a copy is written to:
- **Android 10+ (API 29+):** `Pictures/PhotoFlow/` via MediaStore — visible in the gallery
- **Android 9 and below:** `Environment.DIRECTORY_PICTURES/PhotoFlow/` directly

The backup filename matches the renamed filename exactly. Backups are independent of the upload queue — they are always written at capture time, regardless of upload success or failure.

### Auto-Delete Backups

If `autoDeleteBackups = true`, `runBackupCleanupIfEnabled()` is called on every app launch. It deletes files in `Pictures/PhotoFlow/` older than `autoDeleteAfterDays` days. The primary copies in `filesDir/captures/` are never auto-deleted.

---

## 9. Photo Metadata

The app does not write or read EXIF metadata from captured images. What it tracks per image:

| Field           | Source                                                         |
|-----------------|----------------------------------------------------------------|
| Filename        | Generated by `buildFilename()` at capture time                 |
| Timestamp       | `System.currentTimeMillis()` at capture/receive time          |
| Session barcode | Embedded in the filename via BARCODE field; also via `sessionId` FK |
| Sequence number | Embedded in the filename via SEQUENCE field                    |
| Upload state    | Tracked in Room, updated as the upload progresses              |
| Error message   | Set by FtpUploadWorker or CloudUploadWorker on failure         |
| Retry count     | Incremented by the auto-retry loop                             |
| Cloud photo UID | UUID returned by the cloud backend after upload (`cloudPhotoUid`) |

There is no EXIF strip, GPS tagging, camera model embedding, or image resizing. The image bytes are written to disk exactly as received from CameraX or the tethered DSLR.

---

## 10. Filename Generation by Capture Mode

### Native Camera (CameraX)

```kotlin
// capturePhoto() in MainViewModel
val existingCount = repository.getImagesForSession(sessionId).first().size
val renamedFilename = buildFilename(settings, session.barcode, existingCount + 1)
val outputFile = File(outputDir, renamedFilename)
val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
imageCapture.takePicture(outputOptions, executor, callback)
```

CameraX writes the JPEG directly to `outputFile` — the renamed filename is used as the output path from the start. There is no intermediate camera-assigned filename.

### Tethered DSLR (PTP/USB)

```kotlin
// handleTetheredImage() in MainViewModel
// cameraFilename = e.g. "IMG_1234.JPG" (from camera's GetObjectInfo)
val existingCount = repository.getImagesForSession(session.id).first().size
val renamedFilename = buildFilename(settings, session.barcode, existingCount + 1)
outputFile.writeBytes(data)  // data = raw bytes from PTP GetObject
```

The camera's original filename (`IMG_1234.JPG`, `_DSC1234.ARW`, etc.) is used only for logging:
```
[IMAGE] renamed: IMG_1234.JPG → EVENT_0006_01.jpg
```
It is never stored in the database. Only the renamed filename persists.

---

## 11. The Transfer Queue

The transfer queue is all `session_images` rows where `uploadState != 'UPLOADED'`, ordered newest first:

```kotlin
@Query("SELECT * FROM session_images WHERE uploadState != 'UPLOADED' ORDER BY timestamp DESC")
fun getTransferQueue(): Flow<List<SessionImage>>
```

This is what the File Transfers dialog shows. The dialog renders:
- **Filename** from `SessionImage.filename`
- **Dynamic error message** from `SessionImage.errorMessage`
- **State badge** from `SessionImage.uploadState`
- **RETRY button** for FAILED items (calls `forceRetry(imageId)`)

The File Transfers button in the top bar is green when the queue is empty or all uploaded, orange when any item is FAILED.

---

## 12. Settings Export / Import for Naming

When settings are exported to JSON, naming fields are included:

```json
{
  "version": 1,
  "exported_at": "2026-04-30T16:15:00",
  "settings": {
    "naming_fields": "CUSTOM:EVENT|BARCODE:|SEQUENCE:",
    "naming_separator": "_",
    "naming_extension": "JPG",
    ...
  }
}
```

On import, the naming fields string is deserialized by `deserializeNamingFields()` and takes effect immediately. Connection profiles are also imported/replaced atomically in the same operation.

---

## 13. Session History

The session history panel (right side of MainScreen) shows sessions from Room, limited by `sessionHistoryMax`:

```kotlin
// -1 means unlimited; otherwise used as LIMIT in the SQL subquery
@Query(
    "SELECT s.*, COUNT(si.id) as imageCount, ... " +
    "FROM (SELECT * FROM sessions ORDER BY startTime DESC LIMIT :limit) s " +
    "LEFT JOIN session_images si ON si.sessionId = s.id " +
    "GROUP BY s.id ORDER BY s.startTime DESC"
)
fun getSessionsWithImageCount(limit: Int): Flow<List<SessionWithCount>>
```

Each row in the panel shows:
- Session barcode
- Image count, uploaded count, failed count (`SessionWithCount`)
- Active indicator for the current active session

Tapping a past session sets `selectedSessionId` to that session's ID, switching the thumbnail rail and review pane to show that session's images. This does not change the active session — uploads continue to target the true active session.

`sessionHistoryMax` options: 10, 25, 50 (default), 100, 200, Unlimited (-1).

Clear Session History deletes all `status = 'complete'` sessions and their `session_images` rows from Room. Physical files in `filesDir/captures/` are not deleted. The active session is always preserved.
