package com.chencohensolution.journal.ui.library

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import com.chencohensolution.journal.backup.JpegXl
import com.chencohensolution.journal.cast.CastItem
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.server.ServerPreviews
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

// The phone's own little web server for casting (docs/cast.md): the TV's receiver page fetches each original from it
// over the Wi-Fi, a photo whole and a video in ranges as it plays. Only what the viewer put up for the TV is served,
// each under a path with a random part, and only while casting.

/**
 * Serves the originals the viewer sends to the TV. [start] when a Cast session opens, [stop] when it closes; [offer]
 * gives an item its address for the TV.
 */
@OptIn(UnstableApi::class)
internal class CastServer(private val context: Context) {
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { Thread(it, "cast-server").apply { isDaemon = true } }
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** What is offered, by the number in its path. */
    private val offered = ConcurrentHashMap<String, Offer>()
    private val numbers = ConcurrentHashMap<String, String>()

    private class Offer(val item: MediaItem, val previews: ServerPreviews?) {
        /** Worked out once, on the first request (it may download the original from the server). */
        @Volatile var body: Body? = null
    }

    @Synchronized
    fun start() {
        if (socket != null) return
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(0))
        socket = server
        pool.execute {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                pool.execute { answer(client) }
            }
        }
    }

    /** Videos only on the server downloaded whole for the TV, by item id: the one shown and, in a slideshow, ahead. */
    private val downloads = ConcurrentHashMap<String, VideoDownload>()

    @Synchronized
    fun stop() {
        downloads.values.forEach { it.cancel() }
        downloads.clear()
        socket?.close()
        socket = null
        offered.clear()
        numbers.clear()
        File(context.cacheDir, CAST_DIR).deleteRecursively()
    }

    /**
     * [item] as the TV fetches it: its address here on the Wi-Fi. Null when the server isn't running or the phone isn't
     * on a network the TV can reach.
     */
    fun offer(item: MediaItem, previews: ServerPreviews?): CastItem? {
        val server = socket ?: return null
        val address = wifiAddress() ?: return null
        val number = numbers.getOrPut(item.id) { (numbers.size + 1).toString() }
        offered[number] = offered[number]?.takeIf { it.item == item } ?: Offer(item, previews)
        val name = item.fileName?.substringAfterLast('/')?.let { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: "original"
        return CastItem(
            id = item.id,
            isVideo = item.type == MediaType.VIDEO,
            url = "http://${address.hostAddress}:${server.localPort}/$token/$number/$name",
            mimeType = item.mimeType,
            fileName = item.fileName,
            width = item.width,
            height = item.height,
        )
    }

    /**
     * Gets [item] ready for the TV before it is shown (a slideshow does this for the items ahead, one at a time):
     * downloads its original from the server, or a video only there whole, and returns once it is here. Returns its
     * size in bytes, or 0 when it couldn't be had.
     */
    suspend fun prepare(item: MediaItem, previews: ServerPreviews?): Long = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
        offer(item, previews) ?: return@runInterruptible 0L
        val offer = numbers[item.id]?.let { offered[it] } ?: return@runInterruptible 0L
        val body = bodyOf(offer) ?: return@runInterruptible 0L
        (body as? VideoDownload)?.awaitDone()
        body.length
    }

    /**
     * Stops and deletes the videos downloaded for the TV other than [ids] (the one shown, and in a slideshow those
     * ahead): asked for again, they download again.
     */
    @Synchronized
    fun keepOnly(ids: Collection<String>) {
        val keep = ids.toSet()
        for ((id, download) in downloads) {
            if (id in keep) continue
            download.cancel()
            downloads.remove(id)
            numbers[id]?.let { offered[it] }?.let { offer -> if (offer.body === download) offer.body = null }
        }
    }

    /** The original's bytes whole, for a TV that couldn't fetch its address (they go over the Cast session). */
    fun bytesOf(itemId: String): ByteArray? {
        val offer = numbers[itemId]?.let { offered[it] } ?: return null
        val body = bodyOf(offer) ?: return null
        if (body.length > MAX_SENT_BYTES) return null
        return body.open(0).use { it.readBytes() }
    }

    /** This phone's address on its Wi-Fi (or Ethernet): IPv4, as the TV's page asks for it. */
    private fun wifiAddress(): Inet4Address? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val networks = listOfNotNull(connectivity.activeNetwork) + connectivity.allNetworks
        for (network in networks.distinct()) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val address = connectivity.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull { !it.isLoopbackAddress }
            if (address != null) return address
        }
        return null
    }

    private fun answer(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            try {
                val request = readHead(input) ?: return
                val (method, path) = request.line.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
                if (method == "OPTIONS") return reply(out, 204, "No Content", emptyMap())
                if (method != "GET" && method != "HEAD") return reply(out, 405, "Method Not Allowed", emptyMap())
                val parts = path.substringBefore('?').trim('/').split('/')
                val offer = if (parts.size >= 2 && parts[0] == token) offered[parts[1]] else null
                val body = offer?.let(::bodyOf) ?: return reply(out, 404, "Not Found", emptyMap())
                val range = request.headers["range"]?.let { parseRange(it, body.length) }
                if (range == null && request.headers["range"] != null) {
                    return reply(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */${body.length}"))
                }
                val (from, to) = range ?: (0L to body.length - 1)
                val headers = mutableMapOf(
                    "Content-Type" to body.mimeType,
                    "Content-Length" to (to - from + 1).toString(),
                    "Accept-Ranges" to "bytes",
                )
                if (range != null) headers["Content-Range"] = "bytes $from-$to/${body.length}"
                if (range != null) reply(out, 206, "Partial Content", headers) else reply(out, 200, "OK", headers)
                if (method == "GET" && body.length > 0) body.open(from).use { copy(it, out, to - from + 1) }
                out.flush()
            } catch (e: SocketException) {
                // The TV closed it (a video skipped ahead): nothing to do.
            } catch (e: Exception) {
                Log.w(TAG, "serving failed", e)
            }
        }
    }

    private fun bodyOf(offer: Offer): Body? {
        offer.body?.let { return it }
        synchronized(offer) {
            offer.body?.let { return it }
            return runCatching { runBlocking { find(offer.item, offer.previews) } }
                .onFailure { Log.w(TAG, "original not found", it) }
                .getOrNull()?.also { offer.body = it }
        }
    }

    /**
     * Where [item]'s bytes come from: its file on this phone (in the library, kept by the app, or downloaded from the
     * server now), or for a video only on the server, streamed from there through the video cache. A JPEG XL packed
     * from a JPEG goes as that JPEG, byte for byte, which the TV decodes itself; a photo the TV can't read (DNG) goes
     * as a JPEG made here at 4K.
     */
    private suspend fun find(item: MediaItem, previews: ServerPreviews?): Body? {
        val mime = item.mimeType ?: guessMime(item.fileName, item.type)
        if (item.type == MediaType.VIDEO) {
            item.playableUri()?.let { uri -> return uriBody(uri, mime) }
            val playable = ServerVideos.playable(context, item) ?: return null
            val uri = playable.item.localConfiguration?.uri ?: return null
            val key = playable.item.localConfiguration?.customCacheKey
            // Read from the video cache, but not written into it: the whole video goes into its own file below.
            (playable.dataSource as? CacheDataSource.Factory)?.setCacheWriteDataSinkFactory(null)
            val streamed = StreamedBody(playable.dataSource, uri, key, item.sizeBytes, mime)
            return downloadWhole(item, streamed) ?: streamed
        }
        if (!tvReads(mime, item.fileName)) {
            val bitmap = loadImage(context.contentResolver, previews, item, TV_JPEG_SIDE) ?: return null
            val file = File(File(context.cacheDir, CAST_DIR).apply { mkdirs() }, "${item.id.hashCode().toUInt()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            return FileBody(file, "image/jpeg")
        }
        val file = when {
            item.isOnDevice -> null
            item.keptOriginal != null -> File(item.keptOriginal!!)
            else -> item.sha256?.let { previews?.original(it, item.fileName ?: it) }?.let(::File)
        }
        if (isJxl(mime, item.fileName)) {
            val bytes = if (file != null) file.readBytes() else context.contentResolver.openInputStream(item.contentUri())?.use { it.readBytes() }
            JpegXl.unpack(bytes ?: return null)?.let { return MemoryBody(it, "image/jpeg") }
        }
        return if (file != null) FileBody(file, mime) else uriBody(item.contentUri(), mime)
    }

    /**
     * A video only on the server, downloaded whole into the cast folder, in order and as fast as the server sends it,
     * while the TV plays it: the TV reads what has arrived, waits briefly at the edge, and only a jump far ahead goes
     * to the server directly. Downloads go on until [keepOnly] lets them go (the viewer moved on, the slideshow
     * stopped). Null when there is no room.
     */
    @Synchronized
    private fun downloadWhole(item: MediaItem, streamed: StreamedBody): Body? {
        downloads[item.id]?.takeIf { it.usable }?.let { return it }
        val folder = File(context.cacheDir, CAST_DIR).apply { mkdirs() }
        val coming = downloads.values.sumOf { it.left }
        if (folder.usableSpace < streamed.length + coming + KEEP_FREE_BYTES) return null
        val file = File(folder, "${item.id.hashCode().toUInt()}.video")
        return VideoDownload(streamed, file).also {
            downloads[item.id] = it
            pool.execute(it::run)
        }
    }

    private fun uriBody(uri: android.net.Uri, mime: String): Body? {
        if (uri.scheme == "file") return FileBody(File(uri.path ?: return null), mime)
        // Read-only, as everything here (PhotoSafetyTest): an asset descriptor gives the length and a stream to seek in.
        val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
            fd.length.takeIf { it >= 0 } ?: fd.parcelFileDescriptor.statSize
        } ?: return null
        return object : Body {
            override val length = length
            override val mimeType = mime
            override fun open(from: Long): InputStream {
                val fd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: error("gone")
                // Closing the stream closes the descriptor.
                val stream = fd.createInputStream()
                stream.channel.position(fd.startOffset + from)
                return stream
            }
        }
    }

    private class Head(val line: String, val headers: Map<String, String>)

    private fun readHead(input: InputStream): Head? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (++total > 16_384) return null
            if (c == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if (text.isEmpty()) break
                lines += text
                line.clear()
            } else {
                line.append(c.toChar())
            }
        }
        if (lines.isEmpty()) return null
        val headers = lines.drop(1).mapNotNull { h ->
            val colon = h.indexOf(':')
            if (colon <= 0) null else h.substring(0, colon).trim().lowercase() to h.substring(colon + 1).trim()
        }.toMap()
        return Head(lines[0], headers)
    }

    private fun reply(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val all = headers + mapOf(
            // The receiver page is on GitHub Pages and fetches from here: allowed from any page, and from a public page
            // to this private address (Chrome's Private Network Access asks first, with OPTIONS).
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Private-Network" to "true",
            "Access-Control-Allow-Headers" to "Range",
            "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
            "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges",
            "Cache-Control" to "no-store",
            "Connection" to "close",
        ) + (if ("Content-Length" in headers) emptyMap() else mapOf("Content-Length" to "0"))
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            all.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    private fun copy(from: InputStream, to: OutputStream, count: Long) {
        val buffer = ByteArray(256 * 1024)
        var left = count
        while (left > 0) {
            val n = from.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            to.write(buffer, 0, n)
            left -= n
        }
    }

    companion object {
        private const val TAG = "CastServer"

        /** Where photos the TV can't read are made into JPEGs while casting; emptied when it stops. */
        private const val CAST_DIR = "cast"

        /** Room left on the phone after downloading a video whole for the TV; else it is streamed as the TV reads. */
        private const val KEEP_FREE_BYTES = 1L shl 30

        /** The longest side of a JPEG made for the TV from a photo it can't read: 4K. */
        private const val TV_JPEG_SIDE = 3840

        /** Larger originals don't go over the Cast session (a video would take minutes): only their address works. */
        private const val MAX_SENT_BYTES = 80L * 1024 * 1024
    }
}

/** An original's bytes as served: its length, its type, and a stream from any place in it. */
internal interface Body {
    val length: Long
    val mimeType: String
    fun open(from: Long): InputStream
}

private class FileBody(private val file: File, override val mimeType: String) : Body {
    override val length: Long = file.length()
    override fun open(from: Long): InputStream = FileInputStream(file).also { it.channel.position(from) }
}

private class MemoryBody(private val bytes: ByteArray, override val mimeType: String) : Body {
    override val length: Long = bytes.size.toLong()
    override fun open(from: Long): InputStream = bytes.inputStream(from.toInt(), bytes.size - from.toInt())
}

/** A video only on the server, read through the video cache (what was played on the phone isn't fetched again). */
@OptIn(UnstableApi::class)
private class StreamedBody(
    private val factory: DataSource.Factory,
    private val uri: android.net.Uri,
    private val key: String?,
    knownLength: Long?,
    override val mimeType: String,
) : Body {
    override val length: Long = knownLength ?: run {
        val source = factory.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(uri).setKey(key).build())
        } finally {
            source.close()
        }
    }

    override fun open(from: Long): InputStream {
        val source = factory.createDataSource()
        source.open(DataSpec.Builder().setUri(uri).setKey(key).setPosition(from).build())
        return object : InputStream() {
            private val one = ByteArray(1)
            override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = source.read(b, off, len)
                return if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) -1 else n
            }
            override fun close() = source.close()
        }
    }
}

/**
 * A video downloaded whole from the server into [file], from the start and in order, while the TV reads it: a read
 * within what has arrived comes from the file; at the edge it waits for the download; a read far past the edge (the TV
 * jumped ahead) or after the download failed goes to the server through [streamed].
 */
private class VideoDownload(private val streamed: StreamedBody, private val file: File) : Body {
    override val length: Long = streamed.length
    override val mimeType: String = streamed.mimeType

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = java.lang.Object()
    @Volatile private var written = 0L
    @Volatile private var failed = false
    @Volatile private var cancelled = false

    fun run() {
        val started = System.nanoTime()
        try {
            streamed.open(0).use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 20)
                    while (!cancelled) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        synchronized(lock) {
                            written += n
                            lock.notifyAll()
                        }
                    }
                }
            }
            if (written < length && !cancelled) error("the video ended at $written of $length bytes")
            val seconds = (System.nanoTime() - started) / 1e9
            if (!cancelled) Log.i(TAG, "video downloaded: ${written shr 20} MB in ${"%.1f".format(seconds)} s")
        } catch (e: Exception) {
            if (!cancelled) Log.w(TAG, "video download stopped at ${written shr 20} MB", e)
            failed = true
        } finally {
            synchronized(lock) { lock.notifyAll() }
            if (cancelled) file.delete()
        }
    }

    fun cancel() {
        cancelled = true
        synchronized(lock) { lock.notifyAll() }
    }

    /** Not stopped nor failed: what is downloaded (or still coming) can be read. */
    val usable: Boolean get() = !cancelled && !failed

    /** Bytes still to come. */
    val left: Long get() = if (usable) (length - written).coerceAtLeast(0) else 0

    /** Waits until the whole video is here, or the download stopped. */
    fun awaitDone() {
        synchronized(lock) {
            while (written < length && !failed && !cancelled) lock.wait(500)
        }
    }

    override fun open(from: Long): InputStream = object : InputStream() {
        private var position = from
        private var reader: RandomAccessFile? = null
        private var direct: InputStream? = null
        private val one = ByteArray(1)

        override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= length) return -1
            direct?.let { stream -> return stream.read(b, off, len).also { if (it > 0) position += it } }
            synchronized(lock) {
                while (position >= written && !failed && !cancelled && position - written < WAIT_AHEAD_BYTES && written < length) {
                    lock.wait(500)
                }
            }
            if (position >= written || cancelled) {
                // Far past the download, or it stopped (or another video took its place and its file went): straight
                // from the server from here on.
                val stream = streamed.open(position).also { direct = it }
                return stream.read(b, off, len).also { if (it > 0) position += it }
            }
            val file = reader ?: RandomAccessFile(this@VideoDownload.file, "r").also { reader = it }
            file.seek(position)
            val n = file.read(b, off, minOf(len.toLong(), written - position).toInt())
            if (n > 0) position += n
            return n
        }

        override fun close() {
            reader?.close()
            direct?.close()
        }
    }

    companion object {
        private const val TAG = "CastServer"

        /** How far past the download the TV may ask and still wait for it rather than go to the server: 64 MB. */
        private const val WAIT_AHEAD_BYTES = 64L shl 20
    }
}

/** "bytes=0-", "bytes=100-199", "bytes=-500" within [length]: first and last byte, or null when it can't be served. */
internal fun parseRange(header: String, length: Long): Pair<Long, Long>? {
    val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
    val dash = spec.indexOf('-')
    if (dash < 0 || length <= 0) return null
    val start = spec.substring(0, dash).trim()
    val end = spec.substring(dash + 1).trim()
    return if (start.isEmpty()) {
        val suffix = end.toLongOrNull() ?: return null
        if (suffix <= 0) null else maxOf(0, length - suffix) to length - 1
    } else {
        val from = start.toLongOrNull() ?: return null
        val to = (end.toLongOrNull() ?: (length - 1)).coerceAtMost(length - 1)
        if (from >= length || to < from) null else from to to
    }
}

/** The types a TV's browser reads, with the receiver's own HEIC and JPEG XL decoders. */
private val TV_TYPES = setOf(
    "image/jpeg", "image/png", "image/webp", "image/gif", "image/avif", "image/bmp", "image/heic", "image/heif",
    "image/heic-sequence", "image/heif-sequence", "image/jxl",
)
private val TV_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "heic", "heif", "hif", "jxl")

private fun tvReads(mime: String, fileName: String?): Boolean {
    val ext = fileName?.substringAfterLast('.', "")?.lowercase()
    return if (ext != null && ext.isNotEmpty()) ext in TV_EXTENSIONS else mime.lowercase() in TV_TYPES
}

private fun isJxl(mime: String, fileName: String?) =
    mime.equals("image/jxl", ignoreCase = true) || fileName?.endsWith(".jxl", ignoreCase = true) == true

private fun guessMime(fileName: String?, type: MediaType): String {
    val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: when (ext) {
            "heic", "heif", "hif" -> "image/heic"
            "jxl" -> "image/jxl"
            else -> if (type == MediaType.VIDEO) "video/mp4" else "image/jpeg"
        }
}
