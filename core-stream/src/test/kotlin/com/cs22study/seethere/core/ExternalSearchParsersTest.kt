package com.cs22study.seethere.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExternalSearchParsersTest {

    @Test
    fun `parses tw_live nearby ajax HTML safely and strips query cache busters`() {
        val sampleHtml = """
            <div class='cctv-stack'><a class='nearby-link' data-deviceid='BOT079' data-distance='0' href='/cam/?id=BOT079'><img src='https://assets.tw.live/images/taipei/BOT079.jpg?t=123' alt='079-復興辛亥'></a></div>
            <div class='cctv-stack'><a class='nearby-link' data-deviceid='BOT322' data-distance='376' href='/cam/?id=BOT322'><img src='https://assets.tw.live/images/taipei/BOT322.jpg?t=123' alt='322-復興南路<script>bad</script>'></a></div>
        """.trimIndent()

        val parsed = ExternalSearchParsers.parseTwLiveNearbyHtml(sampleHtml)
        assertEquals(2, parsed.size)
        assertEquals("BOT079", parsed[0].deviceId)
        assertEquals(0.0, parsed[0].distanceMeters)
        assertEquals("https://assets.tw.live/images/taipei/BOT079.jpg", parsed[0].thumbnailUrl)
        assertEquals("322-復興南路", parsed[1].title)
        assertEquals(376.0, parsed[1].distanceMeters)
    }

    @Test
    fun `extracts HLS or YouTube stream from tw_live cam page`() {
        val hlsHtml = """
            <title>079-復興辛亥即時影像-台北市大安區 - 即時影像監視器</title>
            <script type="application/ld+json">
            {"thumbnailUrl": "https://assets.tw.live/images/taipei/BOT079.jpg", "contentUrl": "https://jtmctrafficcctv5.gov.taipei/NVR/9e1db4ca-c5f8-4c6c-87ac-07abfe5ffb1a/live.m3u8"}
            </script>
            <a href="/pbs/nearby/?lat=25.021568&amp;lng=121.543298">more</a>
        """.trimIndent()

        val cam = ExternalSearchParsers.extractStreamFromTwLiveCamHtml("BOT079", hlsHtml)
        assertNotNull(cam)
        assertEquals("https://jtmctrafficcctv5.gov.taipei/NVR/9e1db4ca-c5f8-4c6c-87ac-07abfe5ffb1a/live.m3u8", cam!!.streamUrl)
        assertEquals(25.021568, cam.latitude)
        assertEquals(121.543298, cam.longitude)

        assertEquals("BOT079", ExternalSearchParsers.extractTwLiveDeviceIdFromUrl("https://tw.live/cam/?id=BOT079"))
    }

    @Test
    fun `parses YouTube live search ytInitialData safely`() {
        val sampleHtml = """
            <script>var ytInitialData = {"contents":{"videoRenderer":{"videoId":"DjdUEyjx8GM","thumbnail":{},"title":{"runs":[{"text":"東京新宿 歌舞伎町 Live"}]},"ownerText":{"runs":[{"text":"Kabukicho Channel"}]}}}};
            </script>
        """.trimIndent()

        val parsed = ExternalSearchParsers.parseYouTubeSearchFromHtml(sampleHtml)
        assertEquals(1, parsed.size)
        assertEquals("DjdUEyjx8GM", parsed[0].videoId)
        assertEquals("東京新宿 歌舞伎町 Live", parsed[0].title)
        assertTrue(parsed[0].embedUrl.startsWith("https://www.youtube-nocookie.com/embed/DjdUEyjx8GM"))
    }
}
