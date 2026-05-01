package com.photoflowmobile.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SessionKeyType(val apiValue: String) {
    BARCODE("barcode"),
    CUSTOM("custom"),
    MANUAL("manual")
}

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val barcode: String,                                              // legacy column — stores session_key value
    val startTime: Long,
    val endTime: Long? = null,
    val status: String = "active",
    val sessionKeyType: String = SessionKeyType.BARCODE.apiValue,    // "barcode" | "custom" | "manual"
    val displayLabel: String? = null,                                 // human-friendly label for display
    val cloudSessionId: Long? = null                                  // backend session id after creation
)

// Domain alias — use sessionKey in all new code; barcode is the legacy storage column name.
val Session.sessionKey: String get() = barcode

/**
 * Returns a canonical capture code for barcode-type sessions (e.g. "XYZ507665_01").
 * Returns null for custom/manual sessions — callers should use customLabel instead.
 */
fun buildCaptureCode(session: Session, sequenceNumber: Int): String? =
    if (session.sessionKeyType == SessionKeyType.BARCODE.apiValue)
        "${session.sessionKey}_${sequenceNumber.toString().padStart(2, '0')}"
    else null
