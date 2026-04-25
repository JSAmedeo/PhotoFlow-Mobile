package com.photoflowmobile.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoflowmobile.app.PhotoFlowApplication
import com.photoflowmobile.app.data.model.Session
import com.photoflowmobile.app.data.repository.SessionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ScanCardViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as PhotoFlowApplication).database
    private val repository = SessionRepository(
        sessionDao = db.sessionDao(),
        sessionImageDao = db.sessionImageDao()
    )

    fun startSession(barcode: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            val allSessions = repository.getAllSessions().first()
            val existing = allSessions.firstOrNull { it.barcode == barcode }

            if (existing != null) {
                // Close any other active sessions, then re-activate the matching one
                allSessions.filter { it.status == "active" && it.id != existing.id }
                    .forEach { repository.updateSession(it.copy(status = "complete", endTime = System.currentTimeMillis())) }
                repository.updateSession(existing.copy(status = "active", endTime = null))
            } else {
                // Close all active sessions and create a new one
                allSessions.filter { it.status == "active" }
                    .forEach { repository.updateSession(it.copy(status = "complete", endTime = System.currentTimeMillis())) }
                repository.createSession(Session(barcode = barcode, startTime = System.currentTimeMillis(), status = "active"))
            }
            onComplete()
        }
    }
}
