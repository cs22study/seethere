package com.cs22study.seethere

import com.cs22study.seethere.core.CuratedDemoCatalog
import com.cs22study.seethere.data.db.CameraEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeeThereAppSmokeTest {

    @Test
    fun entityDomainMappingPreservesSoftDeleteAndFavorites() {
        val domain = CuratedDemoCatalog.demoCameras.first().copy(
            isDeleted = true,
            deletedAtIso = "2026-11-27T09:40:00Z",
        )
        val roundtrip = CameraEntity.fromDomain(domain).toDomain()
        assertEquals(domain, roundtrip)
        assertTrue(roundtrip.isDeleted)
    }
}
