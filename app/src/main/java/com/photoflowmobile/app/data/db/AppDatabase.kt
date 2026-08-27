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

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE session_images ADD COLUMN cloudPhotoUid TEXT")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // sessions — venue/session-key model fields
        db.execSQL("ALTER TABLE sessions ADD COLUMN sessionKeyType TEXT NOT NULL DEFAULT 'barcode'")
        db.execSQL("ALTER TABLE sessions ADD COLUMN displayLabel TEXT")
        db.execSQL("ALTER TABLE sessions ADD COLUMN cloudSessionId INTEGER")
        // session_images — cloud identifiers + capture metadata
        db.execSQL("ALTER TABLE session_images ADD COLUMN cloudPhotoId INTEGER")
        db.execSQL("ALTER TABLE session_images ADD COLUMN cloudUploadedAt INTEGER")
        db.execSQL("ALTER TABLE session_images ADD COLUMN captureCode TEXT")
        db.execSQL("ALTER TABLE session_images ADD COLUMN captureSequence INTEGER")
        db.execSQL("ALTER TABLE session_images ADD COLUMN sortOrder INTEGER")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE connection_profiles ADD COLUMN photoOp TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Schema unchanged — credential migration happens at app-level in PhotoFlowApplication:
        // FTP passwords are moved from the password column to EncryptedSharedPreferences and
        // the column is blanked. This migration just advances the version number.
    }
}

@Database(
    entities = [Session::class, SessionImage::class, ConnectionProfile::class],
    version = 9,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sessionImageDao(): SessionImageDao
    abstract fun connectionProfileDao(): ConnectionProfileDao
}
