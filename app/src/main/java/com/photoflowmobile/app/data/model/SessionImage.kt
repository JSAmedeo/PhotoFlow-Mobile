package com.photoflowmobile.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_images",
    indices = [Index("sessionId")]
)
data class SessionImage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val filename: String,
    val localPath: String,
    val timestamp: Long,
    val uploadState: UploadState = UploadState.PENDING,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    // Cloud upload identifiers
    val cloudPhotoUid: String? = null,    // backend public photo file identifier (use for retrieval)
    val cloudPhotoId: Long? = null,       // backend internal numeric id (debug only)
    val cloudUploadedAt: Long? = null,    // epoch ms from backend uploaded_at timestamp
    // Capture metadata — stored at capture time, sent to cloud on upload
    val captureCode: String? = null,      // canonical code for barcode venues (e.g. "XYZ507665_01")
    val captureSequence: Int? = null,     // ordinal within the session (1-based)
    val sortOrder: Int? = null            // ordering value; defaults to captureSequence
)
