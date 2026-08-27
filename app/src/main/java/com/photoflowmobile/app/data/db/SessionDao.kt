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

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Session?

    /**
     * Authoritative one-shot read of the active session. Prefer this over sampling a
     * `WhileSubscribed` StateFlow on background paths (e.g. tethered image arrival), where
     * no UI may be subscribed and `.value` can be stale or still at its initial value.
     */
    @Query("SELECT * FROM sessions WHERE status = 'active' ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): Session?

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

    // ── Session activation ────────────────────────────────────────────────────
    // Only ever called from SessionRepository.activateSession / createAndActivateSession,
    // which wrap them in a transaction so no observer sees two active sessions (or none)
    // mid-switch.

    @Query("UPDATE sessions SET status = 'complete', endTime = :now WHERE status = 'active'")
    suspend fun closeAllActiveSessions(now: Long)

    @Query("UPDATE sessions SET status = 'complete', endTime = :now WHERE status = 'active' AND id != :sessionId")
    suspend fun closeActiveSessionsExcept(sessionId: Long, now: Long)

    @Query("UPDATE sessions SET status = 'active', endTime = NULL WHERE id = :sessionId")
    suspend fun markActive(sessionId: Long)

    @Query("DELETE FROM session_images WHERE sessionId IN (SELECT id FROM sessions WHERE status != 'active')")
    suspend fun deleteImagesForCompletedSessions()

    @Query("DELETE FROM sessions WHERE status != 'active'")
    suspend fun deleteCompletedSessions()
}
