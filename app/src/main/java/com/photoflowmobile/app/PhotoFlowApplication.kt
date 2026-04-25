package com.photoflowmobile.app

import android.app.Application
import androidx.room.Room
import com.photoflowmobile.app.data.db.AppDatabase
import com.photoflowmobile.app.data.db.MIGRATION_1_2
import com.photoflowmobile.app.data.db.MIGRATION_2_3
import com.photoflowmobile.app.data.db.MIGRATION_3_4
import com.photoflowmobile.app.data.db.MIGRATION_4_5

class PhotoFlowApplication : Application() {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "photoflow.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }
}
