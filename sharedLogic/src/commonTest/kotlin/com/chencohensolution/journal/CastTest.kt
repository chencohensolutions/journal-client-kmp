package com.chencohensolution.journal

import com.chencohensolution.journal.cast.CastController
import com.chencohensolution.journal.cast.CastError
import com.chencohensolution.journal.cast.CastItem
import com.chencohensolution.journal.cast.PhoneRect
import com.chencohensolution.journal.cast.TvZoom
import com.chencohensolution.journal.cast.castAhead
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Casting to a TV (docs/cast.md): the frame the phone moves, and what is said to the TV. */
class CastTest {
    private fun near(expected: Float, actual: Float) = assertTrue(abs(expected - actual) < 1e-4f * maxOf(1f, abs(expected)), "expected $expected, was $actual")

    @Test
    fun aWidePhotoFillsTheTvsWidthWithBlackAboveAndBelow() {
        // 3:1 on a 16:9 TV: the frame is the photo's width and taller than it.
        val frame = TvZoom(photoRatio = 3f).frame
        near(0f, frame.x)
        near(1f, frame.w)
        near(3f / (16f / 9f), frame.h)
        near((1f - frame.h) / 2, frame.y)
    }

    @Test
    fun aPortraitPhotoFillsTheTvsHeightWithBlackBeside() {
        val frame = TvZoom(photoRatio = 3f / 4f).frame
        near(0f, frame.y)
        near(1f, frame.h)
        near((16f / 9f) / (3f / 4f), frame.w)
        near(0.5f, frame.x + frame.w / 2)
    }

    @Test
    fun aDoubleTapFillsTheTvAndAnotherShowsTheWholePhoto() {
        val portrait = TvZoom(photoRatio = 3f / 4f)
        val filled = portrait.toggled()
        assertTrue(filled.isZoomed)
        // The photo's whole width across the TV, no black bars, its middle shown.
        near(0f, filled.frame.x)
        near(1f, filled.frame.w)
        near(0.5f, filled.frame.y + filled.frame.h / 2)
        assertTrue(filled.frame.h < 1f)
        assertFalse(filled.toggled().isZoomed)
        // A photo of the TV's own shape fills it already.
        assertFalse(TvZoom(photoRatio = 16f / 9f).filled().isZoomed)
    }

    @Test
    fun thePhoneShowsWhatTheTvShowsInTheLargestTvRectangle() {
        // A portrait phone, 1000 × 2000: the TV's 16:9 is the full width, in the middle.
        val tv = PhoneRect.fitted(1000f, 2000f, 16f / 9f)
        near(0f, tv.left)
        near(1000f, tv.width)
        near(562.5f, tv.height)
        near((2000f - 562.5f) / 2, tv.top)
        // The whole of a 16:9 photo is the rectangle itself.
        val whole = TvZoom(photoRatio = 16f / 9f).photoOn(tv)
        near(tv.left, whole.left)
        near(tv.width, whole.width)
        // Twice as close on its middle: the photo is twice as large, around the rectangle.
        val close = TvZoom(photoRatio = 16f / 9f).zoomBy(2f, 0.5f, 0.5f).photoOn(tv)
        near(2000f, close.width)
        near(-500f, close.left)
        near(tv.top - tv.height / 2, close.top)
    }

    @Test
    fun pinchingKeepsThePointUnderTheFingersAndStaysOnThePhoto() {
        val start = TvZoom(photoRatio = 16f / 9f)
        assertFalse(start.isZoomed)
        // Twice as close on the top left quarter's middle: that point stays where it was in the frame.
        val zoomed = start.zoomBy(2f, 0.25f, 0.25f)
        assertTrue(zoomed.isZoomed)
        near(0.5f, zoomed.frame.w)
        near(0.5f, zoomed.frame.h)
        near(0.375f, zoomed.centerX)
        near(0.375f, zoomed.centerY)
        // Dragged far past the left edge: stops at it.
        val dragged = zoomed.panBy(-5f, 0f)
        near(0f, dragged.frame.x)
        // Never further out than the whole photo, nor closer than the most.
        assertEquals(1f, zoomed.zoomBy(0.1f, 0.5f, 0.5f).scale)
        assertEquals(TvZoom.MAX_SCALE, zoomed.zoomBy(100f, 0.5f, 0.5f).scale)
        assertFalse(dragged.whole().isZoomed)
    }

    @Test
    fun alongASideWiderThanThePhotoTheFrameStaysCentred() {
        // A portrait photo zoomed a little: the frame is still wider than the photo, so it can't move sideways.
        val zoomed = TvZoom(photoRatio = 3f / 4f).zoomBy(1.5f, 0.1f, 0.5f).panBy(0.3f, 0f)
        assertTrue(zoomed.frame.w > 1f)
        near(0.5f, zoomed.centerX)
    }

    @Test
    fun aheadAreTheNextThreeThenTheOneBefore() {
        val items = (0..9).toList()
        assertEquals(listOf(5, 6, 7, 3), castAhead(items, 4))
        assertEquals(listOf(9), castAhead(items, 8).take(1))
        assertEquals(listOf(1, 2, 3), castAhead(items, 0))
    }

    private val photo = CastItem("p1", isVideo = false, url = "http://192.168.1.5:5000/t/p1", mimeType = "image/heic", fileName = "IMG_1.HEIC", width = 4032, height = 3024)
    private val next = CastItem("p2", isVideo = false, url = "http://192.168.1.5:5000/t/p2")
    private val clip = CastItem("v1", isVideo = true, url = "http://192.168.1.5:5000/t/v1")

    private fun read(message: String): JsonObject = Json.parseToJsonElement(message).jsonObject
    private fun JsonObject.type() = this["type"]!!.jsonPrimitive.content

    @Test
    fun showSaysHelloThenShowsThePhotoAndFetchesTheNextAhead() = runTest {
        val sent = mutableListOf<String>()
        val cast = CastController(backgroundScope)
        cast.connected({ sent += it; true }, "Living Room TV")
        cast.show(photo, ahead = listOf(next, clip))
        runCurrent()
        assertEquals(listOf("hi", "show", "preload"), sent.map { read(it).type() })
        val item = read(sent[1])["item"]!!.jsonObject
        assertEquals("p1", item["id"]!!.jsonPrimitive.content)
        assertEquals("image", item["kind"]!!.jsonPrimitive.content)
        assertEquals(photo.url, item["url"]!!.jsonPrimitive.content)
        // Videos aren't fetched ahead.
        assertEquals(listOf("p2"), read(sent[2])["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertTrue(cast.state.value.connected)
        assertEquals("Living Room TV", cast.state.value.device)

        // The same photo again (a recomposition) isn't shown twice.
        sent.clear()
        cast.show(photo)
        runCurrent()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun framesGoAtMostEveryFortyMillisecondsTheLatestWinning() = runTest {
        val sent = mutableListOf<String>()
        val cast = CastController(backgroundScope)
        cast.connected({ sent += it; true }, null)
        cast.show(photo)
        runCurrent()
        sent.clear()
        val zoom = TvZoom(4f / 3f)
        cast.frame("p1", zoom.zoomBy(2f, 0.5f, 0.5f).frame)
        runCurrent()
        cast.frame("p1", zoom.zoomBy(3f, 0.5f, 0.5f).frame)
        cast.frame("p1", zoom.zoomBy(4f, 0.5f, 0.5f).frame)
        runCurrent()
        assertEquals(1, sent.size)
        advanceTimeBy(CastController.FRAME_MILLIS + 1)
        runCurrent()
        assertEquals(2, sent.size)
        val last = read(sent.last())
        assertEquals("frame", last.type())
        // The latest: four times as close, a quarter of the photo high.
        near(0.25f, last["h"]!!.jsonPrimitive.content.toFloat())
    }

    @Test
    fun whatTheTvSaysUpdatesTheState() = runTest {
        val cast = CastController(backgroundScope)
        cast.connected({ true }, "TV")
        cast.received("""{"type":"hello","width":3840,"height":2160}""")
        near(16f / 9f, cast.state.value.tvRatio)
        cast.received("""{"type":"hello","width":1080,"height":1920}""")
        near(1080f / 1920f, cast.state.value.tvRatio)
        cast.received("""{"type":"video","id":"v1","position":1500,"duration":60000,"playing":true,"ended":false}""")
        assertEquals(1500L, cast.state.value.video?.positionMillis)
        assertTrue(cast.state.value.video?.playing == true)
        cast.received("""{"type":"error","id":"v1","message":"media error 4"}""")
        assertEquals(CastError("v1", "media error 4"), cast.state.value.error)
        cast.errorSeen()
        assertNull(cast.state.value.error)
        cast.received("not json")
        cast.received("""{"type":"something new"}""")
        cast.disconnected()
        assertFalse(cast.state.value.connected)
    }

    @Test
    fun aTvThatCantFetchGetsTheOriginalInPieces() = runTest {
        val sent = mutableListOf<String>()
        val original = ByteArray(CastController.CHUNK_BYTES * 2 + 1234) { (it % 251).toByte() }
        val cast = CastController(backgroundScope) { id -> if (id == "p1") original else null }
        cast.connected({ sent += it; true }, null)
        runCurrent()
        sent.clear()
        cast.received("""{"type":"need","id":"p1"}""")
        runCurrent()
        val pieces = sent.map(::read)
        assertEquals(3, pieces.size)
        assertTrue(pieces.all { it.type() == "chunk" && it["count"]!!.jsonPrimitive.content == "3" })
        // Each piece is base64 on its own, under Cast's 64 KB message.
        assertTrue(sent.all { it.length < 64_000 })
        val back = pieces.flatMap { Base64.decode(it["data"]!!.jsonPrimitive.content).toList() }.toByteArray()
        assertContentEquals(original, back)

        cast.received("""{"type":"need","id":"gone"}""")
        runCurrent()
        assertEquals("gone", cast.state.value.error?.id)
    }
}
