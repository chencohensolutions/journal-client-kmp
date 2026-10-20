package com.chencohensolution.journal.backup

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class Rfc3339Test {
    private val israel = ZoneId.of("Asia/Jerusalem")

    @Test
    fun aWholeMinuteKeepsItsSeconds() {
        // OffsetDateTime.toString() gave "2026-10-16T22:48+03:00" here, which the server refused.
        assertEquals("2026-10-16T22:48:00.000+03:00", rfc3339(1_792_180_080_000, israel))
    }

    @Test
    fun millisAndOffsetsAreKept() {
        assertEquals("2026-10-16T22:48:28.123+03:00", rfc3339(1_792_180_108_123, israel))
        assertEquals("2026-10-16T19:48:28.123Z", rfc3339(1_792_180_108_123, ZoneId.of("UTC")))
    }
}
