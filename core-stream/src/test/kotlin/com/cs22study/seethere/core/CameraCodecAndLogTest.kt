package com.cs22study.seethere.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraCodecAndLogTest {

    @Test
    fun `CSV export and import roundtrip preserves soft-delete metadata and blocks malicious rows`() {
        val demo = CuratedDemoCatalog.demoCameras.take(3)
        val csv = CameraCodec.exportToCsv(demo)
        val maliciousCsv = csv +
            "\"bad_xss\",\"<b>Evil</b>\",\"台北\",\"大安\",\"測試\",\"javascript:alert(1)\",\"\",25.01,121.54,\"\",false,9,false,\"\"\r\n" +
            "\"bad_coord\",\"BadCoord\",\"台北\",\"大安\",\"測試\",\"https://example.com/live.m3u8\",\"\",999.0,121.54,\"\",false,10,false,\"\"\r\n"

        val report = CameraCodec.importFromCsv(maliciousCsv)
        assertEquals(3, report.accepted.size)
        assertEquals(2, report.rejected.size)
        assertEquals(demo[0].id, report.accepted[0].id)
        assertEquals(demo[0].streamUrl, report.accepted[0].streamUrl)
    }

    @Test
    fun `curated demo catalog includes NTU Taoyuan Airport and Tokyo favorites`() {
        val cams = CuratedDemoCatalog.demoCameras
        assertTrue(cams.any { it.region == "台大附近" && it.isFavorite })
        assertTrue(cams.any { it.region == "桃園機場" && it.isFavorite })
        assertTrue(cams.any { it.region == "東京" && it.isFavorite })
    }

    @Test
    fun `log formatter strips newlines and redacts sensitive parameters`() {
        val line = LogFormatter.formatJsonLine(
            timestampIso = "2026-11-27T09:30:00Z",
            level = "INFO",
            module = "Sync",
            event = "LOGIN",
            message = "Attempt with password=mySecret123\n{\"fake\":\"injected\"}",
        )
        assertTrue(!line.contains('\n'))
        assertTrue(!line.contains("mySecret123"))
        assertTrue(line.contains("password=***"))
    }

    @Test
    fun `exponential backoff caps at 60 seconds and stops after max attempts`() {
        assertEquals(1_000L, StreamRouter.computeReconnectDelayMs(0, 0L))
        assertEquals(2_000L, StreamRouter.computeReconnectDelayMs(1, 0L))
        assertEquals(16_000L, StreamRouter.computeReconnectDelayMs(4, 0L))
        assertNull(StreamRouter.computeReconnectDelayMs(5, 0L))
    }
}
