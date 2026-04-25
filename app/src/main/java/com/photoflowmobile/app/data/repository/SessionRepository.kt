package com.photoflowmobile.app.data.repository

import com.photoflowmobile.app.data.db.SessionDao
import com.photoflowmobile.app.data.db.SessionImageDao
import com.photoflowmobile.app.data.db.SessionWithCount
import com.photoflowmobile.app.data.model.Session
import com.photoflowmobile.app.data.model.SessionImage
import com.photoflowmobile.app.data.model.UploadState
import kotlinx.coroutines.flow.Flow

class SessionRepository(
    private val sessionDao: SessionDao,
    private val sessionImageDao: SessionImageDao
) {
    fun getAllSessions(): Flow<List<Session>> = sessionDao.getAllSessions()

    fun getSessionsWithImageCount(limit: Int): Flow<List<SessionWithCount>> =
        sessionDao.getSessionsWithImageCount(limit)

    suspend fun createSession(session: Session): Long = sessionDao.insert(session)

    suspend fun updateSession(session: Session) = sessionDao.update(session)

    fun getImagesForSession(sessionId: Long): Flow<List<SessionImage>> =
        sessionImageDao.getImagesForSession(sessionId)

    fun getTransferQueue(): Flow<List<SessionImage>> = sessionImageDao.getTransferQueue()

    fun getPendingUploads(): Flow<List<SessionImage>> =
        sessionImageDao.getImagesByState(listOf(UploadState.PENDING, UploadState.RETRY_REQUIRED))

    fun getFailedImages(): Flow<List<SessionImage>> = sessionImageDao.getFailedImages()

    suspend fun saveImage(image: SessionImage): Long = sessionImageDao.insert(image)

    suspend fun updateImageState(image: SessionImage) = sessionImageDao.update(image)

    suspend fun getImageById(id: Long): SessionImage? = sessionImageDao.getById(id)
}
