package com.cs22study.seethere

import com.cs22study.seethere.core.CameraItem
import com.cs22study.seethere.core.CuratedDemoCatalog
import com.cs22study.seethere.core.StreamSecuritySanitizer
import com.cs22study.seethere.data.db.CameraEntity
import com.cs22study.seethere.server.SeeThereLanWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

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

    @Test
    fun lanWebServerAddsYouTubeCameraOverHttpPost() {
        val addedTitles = mutableListOf<String>()
        val listener = object : SeeThereLanWebServer.Listener {
            override fun getActiveCameras(): List<CameraItem> = CuratedDemoCatalog.demoCameras
            override fun onLanAddCamera(
                title: String,
                url: String,
                region: String,
                city: String,
                favorite: Boolean,
            ): Pair<Boolean, String> {
                val check = StreamSecuritySanitizer.sanitizeStreamUrl(url, allowPrivateLanHttp = false)
                if (!check.isAllowed) return false to (check.blockReason ?: "blocked")
                addedTitles += title
                return true to "已新增並播放：$title"
            }

            override fun onLanImportCsv(csv: String): Pair<Int, Int> = 0 to 0
            override fun onLanPlayCamera(cameraId: String): Boolean = true
        }

        val startedUrl = SeeThereLanWebServer.start(18089, listener).getOrThrow()
        assertTrue(startedUrl.endsWith(":18089"))
        try {
            val html = URL("http://127.0.0.1:18089/").readText()
            assertTrue(html.contains("btnAdd"))
            assertTrue(html.contains("addMsg"))

            val camsJson = URL("http://127.0.0.1:18089/api/cameras").readText()
            assertTrue(camsJson.startsWith("["))

            val conn = (URL("http://127.0.0.1:18089/api/camera/add").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val payload = """{"url":"https://www.youtube.com/watch?v=ipbHUjTeUIw","title":"富士山急行線河口湖駅前","region":"區網新增","favorite":true}"""
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            assertEquals(200, conn.responseCode)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            assertTrue(body.contains("\"ok\":true"))
            assertTrue(body.contains("富士山急行線河口湖駅前"))
            assertEquals(listOf("富士山急行線河口湖駅前"), addedTitles)

            val badConn = (URL("http://127.0.0.1:18089/api/camera/add").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            badConn.outputStream.use { it.write("""{"url":"javascript:alert(1)","title":"xss"}""".toByteArray(Charsets.UTF_8)) }
            assertEquals(400, badConn.responseCode)
            val errBody = badConn.errorStream.bufferedReader(Charsets.UTF_8).readText()
            assertTrue(errBody.contains("\"ok\":false"))
        } finally {
            SeeThereLanWebServer.stop()
        }
    }
}
