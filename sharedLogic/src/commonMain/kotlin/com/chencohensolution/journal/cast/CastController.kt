package com.chencohensolution.journal.cast

import kotlin.io.encoding.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Casting to a TV with Google Cast (docs/cast.md). The TV runs the Journal's own receiver page (receiver/ at the repo's
// root, on GitHub Pages); the phone tells it what to show over one custom namespace, in the JSON messages listed at the
// top of receiver/receiver.js. The platform finds the TV and opens the session (Android: JournalCast.kt) and serves the
// originals on the Wi-Fi; this is what is said over the session.

/** The receiver's namespace: the same string as NAMESPACE in receiver/receiver.js. */
const val CAST_NAMESPACE = "urn:x-cast:com.chencohensolution.journal"

/** A photo or video for the TV: its original at [url] on the phone's own little web server. */
data class CastItem(
    val id: String,
    val isVideo: Boolean,
    val url: String,
    val mimeType: String? = null,
    val fileName: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)

/** The video the TV plays, as it last told: where it is and whether it plays. */
data class CastVideo(val id: String, val positionMillis: Long, val durationMillis: Long, val playing: Boolean, val ended: Boolean)

/**
 * What the TV may show over a photo or video ([CastController.info]): its note, when it was taken and where (as the
 * phone words them), and the place's coordinates for the map. Empty strings and nulls are left out.
 */
data class CastInfo(
    val text: String = "",
    val time: String = "",
    val place: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val hasPlace: Boolean get() = latitude != null && longitude != null
}

/** Something the TV couldn't show: the photo or video's id, and why (for the log). */
data class CastError(val id: String, val message: String)

data class CastState(
    /** A session with a TV is open: the viewer shows the frame and sends what is shown. */
    val connected: Boolean = false,
    /** The TV's name, as Cast tells it ("Living Room TV"). */
    val device: String? = null,
    /** The TV screen's width over its height: the frame's shape. 16:9 until the TV says. */
    val tvRatio: Float = TvZoom.DEFAULT_TV_RATIO,
    /** The photo the TV last put on screen. */
    val shownId: String? = null,
    val video: CastVideo? = null,
    /** The last thing the TV couldn't show; [CastController.errorSeen] clears it. */
    val error: CastError? = null,
    /** The TV shows the info ([CastInfo]) over what is shown: the phone's toggle, off with each new session. */
    val infoOnTv: Boolean = false,
    /** The TV shows the place of what is shown on a map. */
    val mapOnTv: Boolean = false,
)

/** The open session's way to the TV: sends one message on [CAST_NAMESPACE]; false when it didn't go. */
fun interface CastChannel {
    suspend fun send(message: String): Boolean
}

/**
 * What the phone says to the TV and what it hears back. The platform calls [connected] and [disconnected] as the
 * session opens and closes, and [received] with each message from the TV; the viewer calls [show], [frame] and the
 * video controls. Messages go out one at a time, in order; frames at most every [FRAME_MILLIS], the latest winning.
 */
class CastController(
    private val scope: CoroutineScope,
    /**
     * The original's bytes, for a TV that couldn't fetch its URL (the TV's browser may refuse a plain-http address on
     * the Wi-Fi): they then go over the session in pieces. Null when it can't be had.
     */
    private val bytesOf: suspend (id: String) -> ByteArray? = { null },
) {
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()

    private var outbox: Channel<String>? = null
    private var jobs: List<Job> = emptyList()
    private val frames = MutableStateFlow<Pair<String, CastFrame>?>(null)
    private var shownItem: String? = null
    private val infosSent = mutableMapOf<String, CastInfo>()

    /** The item the TV last couldn't show, kept after [errorSeen] (which the viewer calls at once) for [videoDone]. */
    private val failedId = MutableStateFlow<String?>(null)

    /** A session opened with the TV called [device]: says hello, and from now on sends through [channel]. */
    fun connected(channel: CastChannel, device: String?) {
        stopJobs()
        val box = Channel<String>(Channel.UNLIMITED)
        outbox = box
        frames.value = null
        shownItem = null
        infosSent.clear()
        _state.value = CastState(connected = true, device = device)
        jobs = listOf(
            scope.launch { for (message in box) channel.send(message) },
            scope.launch {
                frames.filterNotNull().collect { (id, frame) ->
                    box.trySend(message("frame") { put("id", id); putFrame(frame) })
                    delay(FRAME_MILLIS)
                }
            },
        )
        box.trySend(message("hi") {})
    }

    /** The session closed (by the phone, the TV or the Wi-Fi). */
    fun disconnected() {
        stopJobs()
        outbox = null
        shownItem = null
        _state.value = CastState()
    }

    private fun stopJobs() {
        outbox?.close()
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /**
     * Shows [item] on the TV now, at [frame] (null: the whole photo), and fetches [ahead] there (the photos likely
     * next). A video starts playing ([autoplay]) at [positionMillis], [muted] or not. Sent again for the same item, only
     * [ahead] goes.
     */
    fun show(
        item: CastItem,
        ahead: List<CastItem> = emptyList(),
        frame: CastFrame? = null,
        autoplay: Boolean = true,
        muted: Boolean = false,
        positionMillis: Long = 0,
    ) {
        val box = outbox ?: return
        if (shownItem != item.id) {
            shownItem = item.id
            frames.value = null
            failedId.value = null
            _state.update { it.copy(video = null) }
            box.trySend(message("show") {
                put("item", item.json())
                frame?.let { put("frame", buildJsonObject { putFrame(it) }) }
                put("autoplay", autoplay)
                put("muted", muted)
                put("position", positionMillis)
            })
        }
        val photos = ahead.filter { !it.isVideo }
        if (photos.isNotEmpty()) box.trySend(message("preload") { put("items", buildJsonArray { photos.forEach { add(it.json()) } }) })
    }

    /** The photo [id]'s frame moved (a pinch or drag on the phone): the TV follows. */
    fun frame(id: String, frame: CastFrame) {
        if (outbox == null) return
        frames.value = id to frame
    }

    fun play() = send(message("video") { put("action", "play") })

    fun pause() = send(message("video") { put("action", "pause") })

    fun seek(positionMillis: Long) = send(message("video") { put("action", "seek"); put("position", positionMillis) })

    /** What the TV may show over the item [id] (sent again only when it changed). */
    fun info(id: String, info: CastInfo) {
        val box = outbox ?: return
        if (infosSent[id] == info) return
        infosSent[id] = info
        box.trySend(message("info") {
            put("id", id)
            if (info.text.isNotBlank()) put("text", info.text)
            if (info.time.isNotBlank()) put("time", info.time)
            if (info.place.isNotBlank()) put("place", info.place)
            info.latitude?.let { put("latitude", it) }
            info.longitude?.let { put("longitude", it) }
        })
    }

    /** The info over the photo on the TV, on or off. */
    fun showInfo(on: Boolean) = overlay(on, _state.value.mapOnTv)

    /** The place on a map on the TV, on or off. */
    fun showMap(on: Boolean) = overlay(_state.value.infoOnTv, on)

    private fun overlay(info: Boolean, map: Boolean) {
        if (outbox == null) return
        _state.update { it.copy(infoOnTv = info, mapOnTv = map) }
        send(message("overlay") { put("info", info); put("map", map) })
    }

    fun mute(muted: Boolean) = send(message("mute") { put("muted", muted) })

    /** The TV back to its quiet screen (the viewer closed, casting goes on). */
    fun clear() {
        shownItem = null
        send(message("stop") {})
    }

    /**
     * Returns once the video [id] on the TV is done, for a slideshow to go on: it ended (or stopped within
     * [VIDEO_END_SLACK_MILLIS] of its end), the TV couldn't play it, or the session closed. A video that stops moving
     * while it should play (its stream broke, the TV's last word was lost) counts as done after [VIDEO_STALL_MILLIS],
     * and one the TV never starts after [VIDEO_START_MILLIS]; one paused with the controls waits for as long as it is.
     */
    suspend fun videoDone(id: String) {
        var waited = 0L
        var last: CastVideo? = null
        while (true) {
            val news = withTimeoutOrNull(VIDEO_STALL_MILLIS) {
                combine(_state, failedId) { s, f -> s to f }
                    .first { (s, f) -> !s.connected || f == id || s.video?.takeIf { it.id == id } != last }
            }
            if (news == null) {
                waited += VIDEO_STALL_MILLIS
                val mine = last ?: if (waited >= VIDEO_START_MILLIS) return else continue
                if (mine.playing) return
                continue
            }
            val (s, f) = news
            if (!s.connected || f == id) return
            val v = s.video?.takeIf { it.id == id } ?: continue
            if (v.ended || (!v.playing && v.durationMillis > 0 && v.positionMillis >= v.durationMillis - VIDEO_END_SLACK_MILLIS)) return
            last = v
        }
    }

    /** The error was shown to the user. */
    fun errorSeen() = _state.update { it.copy(error = null) }

    private fun send(message: String) {
        outbox?.trySend(message)
    }

    /** One message from the TV. Anything unreadable is ignored. */
    fun received(text: String) {
        val m = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (m.string("type")) {
            "hello" -> {
                val w = m.int("width") ?: 0
                val h = m.int("height") ?: 0
                if (w > 0 && h > 0) _state.update { it.copy(tvRatio = w.toFloat() / h) }
            }
            "shown" -> m.string("id")?.let { id -> _state.update { it.copy(shownId = id) } }
            "video" -> {
                val id = m.string("id") ?: return
                _state.update {
                    it.copy(video = CastVideo(id, m.long("position") ?: 0, m.long("duration") ?: 0, m.bool("playing") ?: false, m.bool("ended") ?: false))
                }
            }
            "error" -> {
                val id = m.string("id") ?: return
                failedId.value = id
                _state.update { it.copy(error = CastError(id, m.string("message").orEmpty())) }
            }
            "need" -> m.string("id")?.let(::sendBytes)
        }
    }

    /** The original of [id] over the session, in pieces small enough for a Cast message. */
    private fun sendBytes(id: String) {
        val box = outbox ?: return
        scope.launch {
            val bytes = bytesOf(id)
            if (bytes == null) {
                _state.update { it.copy(error = CastError(id, "the original couldn't be read")) }
                return@launch
            }
            chunks(id, bytes).forEach { box.trySend(it) }
        }
    }

    companion object {
        /** Frames go at most this often: about 25 a second, smooth on the TV without flooding the Wi-Fi. */
        const val FRAME_MILLIS = 40L

        /**
         * Bytes in one piece of an original: 45 KB, about 60 KB once in base64 and JSON, under Cast's 64 KB message.
         * A multiple of 3, so each piece is base64 on its own.
         */
        const val CHUNK_BYTES = 45_000

        /** A playing video on the TV that says nothing new for this long has stalled: the slideshow goes on. */
        const val VIDEO_STALL_MILLIS = 15_000L

        /** A video the TV says nothing about for this long never started: the slideshow goes on. */
        const val VIDEO_START_MILLIS = 30_000L

        /** A video stopped this close to its end has ended (a stream a little shorter than its stated duration). */
        const val VIDEO_END_SLACK_MILLIS = 1_000L

        /** [bytes] as the "chunk" messages the receiver puts back together. */
        fun chunks(id: String, bytes: ByteArray): List<String> {
            val count = maxOf(1, (bytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES)
            return (0 until count).map { index ->
                val from = index * CHUNK_BYTES
                val to = minOf(bytes.size, from + CHUNK_BYTES)
                message("chunk") {
                    put("id", id)
                    put("index", index)
                    put("count", count)
                    put("data", Base64.encode(bytes, from, to))
                }
            }
        }

        private fun message(type: String, body: JsonObjectBuilder.() -> Unit): String =
            buildJsonObject { put("type", type); body() }.toString()
    }
}

/**
 * The photos to fetch on the TV ahead of [index] in [items]: the next three, then the one before, the way swipes and
 * the slideshow go.
 */
fun <T> castAhead(items: List<T>, index: Int): List<T> =
    listOfNotNull(items.getOrNull(index + 1), items.getOrNull(index + 2), items.getOrNull(index + 3), items.getOrNull(index - 1))

private fun CastItem.json(): JsonObject = buildJsonObject {
    put("id", id)
    put("kind", if (isVideo) "video" else "image")
    put("url", url)
    mimeType?.let { put("mime", it) }
    fileName?.let { put("name", it) }
    if (width > 0 && height > 0) {
        put("width", width)
        put("height", height)
    }
}

private fun JsonObjectBuilder.putFrame(frame: CastFrame) {
    put("x", frame.x)
    put("y", frame.y)
    put("w", frame.w)
    put("h", frame.h)
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
