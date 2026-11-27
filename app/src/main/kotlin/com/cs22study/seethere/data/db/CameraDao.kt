package com.cs22study.seethere.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CameraDao {

    @Query("SELECT * FROM cameras WHERE isDeleted = 0 ORDER BY isFavorite DESC, sortOrder ASC, title ASC")
    fun observeActiveCameras(): Flow<List<CameraEntity>>

    @Query("SELECT * FROM cameras WHERE isDeleted = 1 ORDER BY deletedAtIso DESC")
    fun observeDeletedCameras(): Flow<List<CameraEntity>>

    @Query("SELECT COUNT(*) FROM cameras")
    suspend fun countAllCameras(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CameraEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissingCameras(items: List<CameraEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CameraEntity)

    @Query("UPDATE cameras SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE cameras SET isDeleted = 1, deletedAtIso = :deletedAtIso WHERE id = :id")
    suspend fun softDelete(id: String, deletedAtIso: String)

    @Query("UPDATE cameras SET isDeleted = 0, deletedAtIso = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("DELETE FROM cameras WHERE id = :id AND isDeleted = 1")
    suspend fun purgeDeleted(id: String)

    @Query("SELECT * FROM location_anchors WHERE isDeleted = 0 ORDER BY isDefault DESC, name ASC")
    fun observeAnchors(): Flow<List<LocationAnchorEntity>>

    @Query("SELECT COUNT(*) FROM location_anchors")
    suspend fun countAnchors(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnchors(items: List<LocationAnchorEntity>)
}
