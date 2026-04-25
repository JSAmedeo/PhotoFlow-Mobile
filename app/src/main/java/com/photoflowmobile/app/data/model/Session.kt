package com.photoflowmobile.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val barcode: String,
    val startTime: Long,
    val endTime: Long? = null,
    val status: String = "active"
)
