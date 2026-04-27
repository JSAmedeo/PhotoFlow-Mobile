package com.photoflowmobile.app.data.db

import androidx.room.TypeConverter
import com.photoflowmobile.app.data.model.ConnectionType
import com.photoflowmobile.app.data.model.UploadState

class Converters {
    @TypeConverter
    fun fromUploadState(state: UploadState): String = state.name

    @TypeConverter
    fun toUploadState(value: String): UploadState =
        UploadState.entries.firstOrNull { it.name == value } ?: UploadState.FAILED

    @TypeConverter
    fun fromConnectionType(type: ConnectionType): String = type.name

    @TypeConverter
    fun toConnectionType(value: String): ConnectionType =
        ConnectionType.entries.firstOrNull { it.name == value } ?: ConnectionType.FTP
}
