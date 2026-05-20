package com.photoflowmobile.app.data.db

import androidx.room.*
import com.photoflowmobile.app.data.model.SessionImage
import com.photoflowmobile.app.data.model.UploadState
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionImageDao {
    @Query("SELECT * FROM session_images WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    fun getImagesForSession(sessionId: Long): Flow<List<SessionImage>>

    @Query("SELECT * FROM session_images WHERE uploadState IN (:states)")
    fun getImagesByState(states: List<UploadState>): Flow<List<SessionImage>>

    @Query("SELECT * FROM session_images WHERE uploadState != 'UPLOADED' ORDER BY timestamp DESC")
    fun getTransferQueue(): Flow<List<SessionImage>>

    @Query("SELECT * FROM session_images WHERE uploadState = 'FAILED' ORDER BY timestamp DESC")
    fun getFailedImages(): Flow<List<SessionImage>>

    @Query("SELECT * FROM session_images WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SessionImage?

    @Query("SELECT * FROM session_images WHERE uploadState = 'UPLOADED' ORDER BY id DESC LIMIT 1")
    suspend fun getLastUploadedImage(): SessionImage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: SessionImage): Long

    @Update
    suspend fun update(image: SessionImage)
}
