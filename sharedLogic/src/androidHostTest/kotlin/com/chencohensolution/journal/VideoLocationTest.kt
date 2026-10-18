package com.chencohensolution.journal

import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.places.parseIso6709
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoLocationTest {
    @Test
    fun readsTheLocationCamerasWriteInVideos() {
        assertEquals(GeoPoint(32.0853, 34.7818), parseIso6709("+32.0853+034.7818/"))
        // With an altitude, and west and south.
        assertEquals(GeoPoint(-33.8688, -151.2093), parseIso6709("-33.8688-151.2093+012.500/"))
        assertNull(parseIso6709(""))
        assertNull(parseIso6709("+95.0000+034.0000/"))
    }
}
