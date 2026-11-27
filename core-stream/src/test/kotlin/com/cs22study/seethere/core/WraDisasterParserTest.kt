package com.cs22study.seethere.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WraDisasterParserTest {

    @Test
    fun `resolves daily CCTV_SN url for matching cameraid and rejects untrusted hosts`() {
        val payload = """
            [
              {"cameraid":"17457","imageurl":"https://fmg.wra.gov.tw/109wraweb/getImage.aspx?mode=getNewImageS\u0026CCTV_SN=ABC123"},
              {"cameraid":"99999","imageurl":"https://evil.example.com/fake.jpg"},
              {"cameraid":"88888","imageurl":"http://fmg.wra.gov.tw/plaintext.jpg"}
            ]
        """.trimIndent()

        assertEquals(
            "https://fmg.wra.gov.tw/109wraweb/getImage.aspx?mode=getNewImageS&CCTV_SN=ABC123",
            WraDisasterParser.resolveImageUrlFromJson(payload, "17457"),
        )
        assertNull(WraDisasterParser.resolveImageUrlFromJson(payload, "99999"))
        assertNull(WraDisasterParser.resolveImageUrlFromJson(payload, "88888"))
    }

    @Test
    fun `extracts cameraid from WRA stream url fragment`() {
        val url = "https://opendata.wra.gov.tw/api/v2/f71b74eb-cbe5-42c6-8be5-7500450e7db0?format=JSON#cameraid=17457"
        assertEquals("17457", WraDisasterParser.extractWraCameraId(url))
    }
}
