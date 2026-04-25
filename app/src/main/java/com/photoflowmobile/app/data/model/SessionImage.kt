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
    val retryCount: Int = 0
)
