package com.photoflowmobile.app.data.db

import androidx.room.*
import com.photoflowmobile.app.data.model.ConnectionProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionProfileDao {
    @Query("SELECT * FROM connection_profiles ORDER BY name ASC")
    fun getAllProfiles(): Flow<List<ConnectionProfile>>

    @Query("SELECT * FROM connection_profiles WHERE isActive = 1 LIMIT 1")
    fun getActiveProfile(): Flow<ConnectionProfile?>

    @Query("SELECT * FROM connection_profiles WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveProfileOnce(): ConnectionProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ConnectionProfile): Long

    @Update
    suspend fun update(profile: ConnectionProfile)

    @Delete
    suspend fun delete(profile: ConnectionProfile)

    @Query("UPDATE connection_profiles SET isActive = 0")
    suspend fun clearAllActive()

    @Query("DELETE FROM connection_profiles")
    suspend fun deleteAll()
}
