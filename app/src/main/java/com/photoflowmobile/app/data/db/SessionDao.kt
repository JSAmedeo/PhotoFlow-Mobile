package com.photoflowmobile.app.data.db

import androidx.room.*
import com.photoflowmobile.app.data.model.Session
import kotlinx.coroutines.flow.Flow

data class SessionWithCount(
    @Embedded val session: Session,
    @ColumnInfo(name = "imageCount") val imageCount: Int,
    @ColumnInfo(name = "uploadedCount") val uploadedCount: Int,
    @ColumnInfo(name = "failedCount") val failedCount: Int
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<Session>>

    @Query(
        "SELECT s.*, " +
        "COUNT(si.id) as imageCount, " +
        "COUNT(CASE WHEN si.uploadState = 'UPLOADED' THEN 1 END) as uploadedCount, " +
        "COUNT(CASE WHEN si.uploadState = 'FAILED' THEN 1 END) as failedCount " +
        "FROM (SELECT * FROM sessions ORDER BY startTime DESC LIMIT :limit) s " +
        "LEFT JOIN session_images si ON si.sessionId = s.id " +
        "GROUP BY s.id ORDER BY s.startTime DESC"
    )
    fun getSessionsWithImageCount(limit: Int): Flow<List<SessionWithCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: Session): Long

    @Update
    suspend fun update(session: Session)
}
