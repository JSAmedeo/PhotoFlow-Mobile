package com.photoflowmobile.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoflowmobile.app.PhotoFlowApplication
import com.photoflowmobile.app.data.model.Session
import com.photoflowmobile.app.data.model.SessionKeyType
import com.photoflowmobile.app.data.repository.SessionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ScanCardViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as PhotoFlowApplication).database
    private val repository = SessionRepository(
        db = db,
        sessionDao = db.sessionDao(),
        sessionImageDao = db.sessionImageDao()
    )

    /**
     * Starts or resumes the session for [barcode], then makes it the active session.
     *
     * Re-scanning a card that already has a session re-activates that session rather than
     * creating a second one, so a customer's photos stay together and sequence numbers and
     * capture codes continue from where they left off. Re-scanning is therefore the natural
     * way to add a shot to an earlier session; picking it in Session History does the same
     * thing through the same transaction.
     */
    fun startSession(barcode: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            val existing = repository.getAllSessions().first().firstOrNull { it.barcode == barcode }

            if (existing != null) {
                repository.activateSession(existing.id)
            } else {
                repository.createAndActivateSession(
                    Session(
                        barcode = barcode,
                        startTime = System.currentTimeMillis(),
                        status = "active",
                        sessionKeyType = SessionKeyType.BARCODE.apiValue
                    )
                )
            }
            onComplete()
        }
    }
}
