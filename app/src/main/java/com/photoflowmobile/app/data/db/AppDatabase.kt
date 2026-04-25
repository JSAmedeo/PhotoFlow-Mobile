package com.photoflowmobile.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.photoflowmobile.app.data.model.ConnectionProfile
import com.photoflowmobile.app.data.model.Session
import com.photoflowmobile.app.data.model.SessionImage

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE connection_profiles ADD COLUMN connectionType TEXT NOT NULL DEFAULT 'FTP'"
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE session_images ADD COLUMN errorMessage TEXT")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE session_images ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_session_images_sessionId ON session_images(sessionId)")
    }
}

@Database(
    entities = [Session::class, SessionImage::class, ConnectionProfile::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sessionImageDao(): SessionImageDao
    abstract fun connectionProfileDao(): ConnectionProfileDao
}
