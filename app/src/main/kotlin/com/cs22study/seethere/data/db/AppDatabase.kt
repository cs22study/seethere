package com.cs22study.seethere.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.cs22study.seethere.core.CuratedDemoCatalog

@Database(
    entities = [CameraEntity::class, LocationAnchorEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cameraDao(): CameraDao

    suspend fun ensureSeeded() {
        val dao = cameraDao()
        // Insert missing demo cameras without overwriting user edits/favorites on upgrade
        dao.insertMissingCameras(CuratedDemoCatalog.demoCameras.map { CameraEntity.fromDomain(it) })
        if (dao.countAnchors() == 0) {
            dao.upsertAnchors(CuratedDemoCatalog.defaultAnchors.map { LocationAnchorEntity.fromDomain(it) })
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "seethere.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}
