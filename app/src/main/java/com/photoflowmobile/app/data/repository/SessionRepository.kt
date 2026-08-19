package com.photoflowmobile.app.data.repository

import androidx.room.withTransaction
import com.photoflowmobile.app.data.db.AppDatabase
import com.photoflowmobile.app.data.db.SessionDao
import com.photoflowmobile.app.data.db.SessionImageDao
import com.photoflowmobile.app.data.db.SessionWithCount
import com.photoflowmobile.app.data.model.Session
import com.photoflowmobile.app.data.model.SessionImage
import com.photoflowmobile.app.data.model.UploadState
import kotlinx.coroutines.flow.Flow

class SessionRepository(
    private val db: AppDatabase,
    private val sessionDao: SessionDao,
    private val sessionImageDao: SessionImageDao
) {
    fun getAllSessions(): Flow<List<Session>> = sessionDao.getAllSessions()

    fun getSessionsWithImageCount(limit: Int): Flow<List<SessionWithCount>> =
        sessionDao.getSessionsWithImageCount(limit)

    suspend fun createSession(session: Session): Long = sessionDao.insert(session)

    suspend fun updateSession(session: Session) = sessionDao.update(session)

    /**
     * Authoritative one-shot read of the active session, straight from Room.
     *
     * Background paths (tethered image arrival) must use this rather than sampling
     * `activeSession.value` — that StateFlow is `WhileSubscribed`, so with no UI subscribed
     * it can hold a stale value or its `null` initial value.
     */
    suspend fun getActiveSession(): Session? = sessionDao.getActiveSession()

    /**
     * Makes [sessionId] the one active session: closes every other active session and
     * re-opens the target.
     *
     * Selection is activation — the operator may need to add a shot to an earlier session,
     * so choosing one in Session History moves capture there. Runs in a single transaction
     * so no observer can ever see two active sessions (or none) mid-switch.
     */
    suspend fun activateSession(sessionId: Long, now: Long = System.currentTimeMillis()) =
        db.withTransaction {
            sessionDao.closeActiveSessionsExcept(sessionId, now)
            sessionDao.markActive(sessionId)
        }

    /** Closes any active session and inserts [session] as the new active one, atomically. */
    suspend fun createAndActivateSession(
        session: Session,
        now: Long = System.currentTimeMillis()
    ): Long = db.withTransaction {
        sessionDao.closeAllActiveSessions(now)
        sessionDao.insert(session)
    }

    fun getImagesForSession(sessionId: Long): Flow<List<SessionImage>> =
        sessionImageDao.getImagesForSession(sessionId)

    fun getTransferQueue(): Flow<List<SessionImage>> = sessionImageDao.getTransferQueue()

    fun getPendingUploads(): Flow<List<SessionImage>> =
        sessionImageDao.getImagesByState(listOf(UploadState.PENDING))

    fun getFailedImages(): Flow<List<SessionImage>> = sessionImageDao.getFailedImages()

    suspend fun saveImage(image: SessionImage): Long = sessionImageDao.insert(image)

    suspend fun updateImageState(image: SessionImage) = sessionImageDao.update(image)

    suspend fun getImageById(id: Long): SessionImage? = sessionImageDao.getById(id)
}
