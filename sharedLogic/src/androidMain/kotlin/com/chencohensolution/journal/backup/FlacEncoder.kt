package com.chencohensolution.journal.backup

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes a finished WAV recording as FLAC with Android's own encoder (MediaCodec `audio/flac`): the same sound in
 * about half the bytes. Takes integer PCM of 16 bits (sent to the encoder as it is) or 24 bits (sent as float, which
 * holds 24 bits exactly). Writes a plain `.flac`: the `fLaC` marker and STREAMINFO from the encoder, then its
 * frames; STREAMINFO then gets the number of samples and the MD5 of the sound, which the encoder can't know when it
 * starts. False for what it doesn't take (8- or 32-bit or float WAVs, a damaged header): such a WAV is sent as it is, and the
 * server keeps it as FLAC.
 */
internal object FlacEncoder {
    /** Encodes [wav] into [out]; false (and [out] deleted) when the WAV isn't one it takes. */
    fun encode(wav: InputStream, out: File): Boolean {
        val stream = wav.buffered(1 shl 16)
        val format = readHeader(stream) ?: return false
        if (format.bits !in setOf(16, 24) || format.channels !in 1..8 || format.rate !in 1..655_350) return false
        return try {
            RandomAccessFile(out, "rw").use { file ->
                file.setLength(0)
                encode(stream, format, file)
            }
        } catch (e: IllegalStateException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: MediaCodec.CodecException) {
            false
        }.also { if (!it) out.delete() }
    }

    private class WavFormat(val channels: Int, val rate: Int, val bits: Int, val dataSize: Long)

    /** Reads the RIFF header up to the start of the sound; null for anything but integer PCM. */
    private fun readHeader(stream: InputStream): WavFormat? {
        val riff = stream.readBytes(12) ?: return null
        if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" || String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var fmt: WavFormat? = null
        while (true) {
            val header = stream.readBytes(8) ?: return null
            val id = String(header, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            when (id) {
                "fmt " -> {
                    val body = stream.readBytes(size.toInt()) ?: return null
                    val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                    val tag = b.getShort(0).toInt() and 0xFFFF
                    // 1: PCM; 0xFFFE: extensible, whose sub-format's first two bytes say PCM (1).
                    val pcm = tag == 1 || (tag == 0xFFFE && body.size >= 26 && (b.getShort(24).toInt() and 0xFFFF) == 1)
                    if (!pcm) return null
                    fmt = WavFormat(channels = b.getShort(2).toInt(), rate = b.getInt(4), bits = b.getShort(14).toInt(), dataSize = 0)
                    if (size % 2 == 1L) stream.skipFully(1)
                }
                "data" -> {
                    val f = fmt ?: return null
                    // Recorders writing as they go leave 0 or 0xFFFFFFFF here: then the sound runs to the end.
                    val known = size.takeIf { it != 0L && it != 0xFFFFFFFFL } ?: Long.MAX_VALUE
                    return WavFormat(f.channels, f.rate, f.bits, known)
                }
                else -> stream.skipFully(size + size % 2)
            }
        }
    }

    private fun encode(wav: InputStream, format: WavFormat, out: RandomAccessFile): Boolean {
        val sampleBytes = format.bits / 8
        val frameBytes = sampleBytes * format.channels
        val asFloat = format.bits != 16
        val media = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, format.rate, format.channels).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, if (asFloat) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_FRAMES * format.channels * (if (asFloat) 4 else 2))
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        val md5 = HashingSink.md5(blackholeSink())
        val md5Sink = md5.buffer()
        var header: ByteArray? = null
        var wroteHeader = false
        var samples = 0L
        var remaining = format.dataSize
        try {
            codec.configure(media, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val raw = ByteArray(INPUT_FRAMES * frameBytes)
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
                        input.clear()
                        val room = minOf(raw.size.toLong(), input.capacity() / (if (asFloat) 4 else 2) / format.channels * frameBytes.toLong(), remaining)
                        val want = (room - room % frameBytes).toInt()
                        val read = wav.readUpTo(raw, want)
                        val frames = read / frameBytes
                        val used = frames * frameBytes
                        md5Sink.write(raw, 0, used)
                        remaining -= used
                        if (asFloat) {
                            val floats = input.asFloatBuffer()
                            for (i in 0 until frames * format.channels) floats.put(sampleAt(raw, i * sampleBytes, format.bits))
                            input.position(frames * format.channels * 4)
                        } else {
                            input.put(raw, 0, used)
                        }
                        val time = samples * 1_000_000L / format.rate
                        samples += frames
                        inputDone = frames == 0 || read < want || remaining <= 0
                        codec.queueInputBuffer(index, 0, input.position(), time, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        if (header == null) header = codec.outputFormat.getByteBuffer("csd-0")?.let { it.toBytes() }
                    index >= 0 -> {
                        val output = codec.getOutputBuffer(index)!!
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val bytes = output.toBytes()
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            if (!wroteHeader) header = bytes
                        } else if (bytes.isNotEmpty()) {
                            if (!wroteHeader) {
                                out.write(streamInfo(header ?: return false))
                                wroteHeader = true
                            }
                            out.write(bytes)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        if (!wroteHeader || samples == 0L) return false
        md5Sink.flush()
        finishStreamInfo(out, samples, md5.hash.toByteArray(), format.bits)
        return true
    }

    /** The encoder's header as `fLaC` and a STREAMINFO block, whichever of them it gave. */
    private fun streamInfo(csd: ByteArray): ByteArray = when {
        csd.size >= 42 && String(csd, 0, 4, Charsets.US_ASCII) == "fLaC" -> csd
        csd.size == 38 -> "fLaC".toByteArray(Charsets.US_ASCII) + csd
        csd.size == 34 -> "fLaC".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x80.toByte(), 0, 0, 34) + csd
        else -> throw IllegalStateException("Unexpected FLAC header of ${csd.size} bytes")
    }

    /**
     * Sets STREAMINFO's number of samples (36 bits after the rate, channels and bits) and, when the encoder kept the
     * WAV's bits, the MD5 of the sound (FLAC's MD5 is of the samples as little-endian integers: the WAV's data).
     */
    private fun finishStreamInfo(out: RandomAccessFile, samples: Long, md5: ByteArray, wavBits: Int) {
        val at = 8L + 10 // fLaC, the block's header, then 10 bytes of block and frame sizes.
        out.seek(at)
        val packed = ByteArray(8).also { out.readFully(it) }
        var word = ByteBuffer.wrap(packed).long
        val bits = ((word ushr 36) and 0x1F).toInt() + 1
        if ((word and 0xFFFFFFFFFL) == 0L) {
            word = (word and 0xFFFFFFFFFL.inv()) or (samples and 0xFFFFFFFFFL)
            out.seek(at)
            out.write(ByteBuffer.allocate(8).putLong(word).array())
        }
        if (bits == wavBits) {
            out.seek(at + 8)
            out.write(md5)
        }
    }

    /** A sample of [bits] bits at [offset] of little-endian PCM, as float in -1..1. */
    private fun sampleAt(raw: ByteArray, offset: Int, bits: Int): Float {
        var value = 0
        for (i in 0 until bits / 8) value = value or ((raw[offset + i].toInt() and 0xFF) shl (8 * i))
        if (bits == 24) value = (value shl 8) shr 8 // Sign-extended.
        return value / 8_388_608f
    }

    private fun ByteBuffer.toBytes(): ByteArray = ByteArray(remaining()).also { get(it) }

    private fun InputStream.readBytes(count: Int): ByteArray? {
        val bytes = ByteArray(count)
        return if (readUpTo(bytes, count) == count) bytes else null
    }

    private fun InputStream.readUpTo(into: ByteArray, count: Int): Int {
        var total = 0
        while (total < count) {
            val n = read(into, total, count - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val n = skip(left)
            if (n <= 0) {
                if (read() < 0) return
                left--
            } else {
                left -= n
            }
        }
    }

    /** Frames read and given to the encoder at a time. */
    private const val INPUT_FRAMES = 4096
    private const val TIMEOUT_US = 10_000L
}
