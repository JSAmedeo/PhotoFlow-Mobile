package com.photoflowmobile.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ConnectionType(val label: String, val badge: String) {
    FTP("FTP Server", "FTP"),
    CLOUD_API("PhotoFlow Cloud API", "CLOUD")
}

@Entity(tableName = "connection_profiles")
data class ConnectionProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val connectionType: ConnectionType = ConnectionType.FTP,
    val host: String = "",
    val port: Int = 21,
    val username: String = "",
    val password: String = "",
    val remotePath: String = "/",
    val isActive: Boolean = false
)
