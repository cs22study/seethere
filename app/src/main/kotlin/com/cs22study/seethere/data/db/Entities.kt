package com.cs22study.seethere.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cs22study.seethere.core.CameraItem
import com.cs22study.seethere.core.LocationAnchor

@Entity(tableName = "cameras")
data class CameraEntity(
    @PrimaryKey val id: String,
    val title: String,
    val region: String,
    val city: String,
    val category: String,
    val streamUrl: String,
    val thumbnailUrl: String,
    val latitude: Double,
    val longitude: Double,
    val sourceName: String,
    val isFavorite: Boolean,
    val sortOrder: Int,
    val isDeleted: Boolean = false,
    val deletedAtIso: String? = null,
    val allowLocalHttp: Boolean = false,
) {
    fun toDomain(): CameraItem = CameraItem(
        id = id,
        title = title,
        region = region,
        city = city,
        category = category,
        streamUrl = streamUrl,
        thumbnailUrl = thumbnailUrl,
        latitude = latitude,
        longitude = longitude,
        sourceName = sourceName,
        isFavorite = isFavorite,
        sortOrder = sortOrder,
        isDeleted = isDeleted,
        deletedAtIso = deletedAtIso,
        allowLocalHttp = allowLocalHttp,
    )

    companion object {
        fun fromDomain(item: CameraItem): CameraEntity = CameraEntity(
            id = item.id,
            title = item.title,
            region = item.region,
            city = item.city,
            category = item.category,
            streamUrl = item.streamUrl,
            thumbnailUrl = item.thumbnailUrl,
            latitude = item.latitude,
            longitude = item.longitude,
            sourceName = item.sourceName,
            isFavorite = item.isFavorite,
            sortOrder = item.sortOrder,
            isDeleted = item.isDeleted,
            deletedAtIso = item.deletedAtIso,
            allowLocalHttp = item.allowLocalHttp,
        )
    }
}

@Entity(tableName = "location_anchors")
data class LocationAnchorEntity(
    @PrimaryKey val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val isDefault: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAtIso: String? = null,
) {
    fun toDomain(): LocationAnchor = LocationAnchor(
        id = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
        isDefault = isDefault,
        isDeleted = isDeleted,
        deletedAtIso = deletedAtIso,
    )

    companion object {
        fun fromDomain(anchor: LocationAnchor): LocationAnchorEntity = LocationAnchorEntity(
            id = anchor.id,
            name = anchor.name,
            latitude = anchor.latitude,
            longitude = anchor.longitude,
            isDefault = anchor.isDefault,
            isDeleted = anchor.isDeleted,
            deletedAtIso = anchor.deletedAtIso,
        )
    }
}
