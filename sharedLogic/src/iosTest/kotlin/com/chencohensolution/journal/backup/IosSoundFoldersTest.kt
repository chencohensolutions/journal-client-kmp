@file:OptIn(ExperimentalForeignApi::class)

package com.chencohensolution.journal.backup

import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.MediaType
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sound folders on iOS (Oct 26, 2026) on a real folder: a folder's lasting reference, its files (subfolders too, a
 * dot file left out), reading one for upload, a WAV sent as FLAC and then kept as FLAC, and deleting what Free up space
 * may.
 */
class IosSoundFoldersTest {
    private val fs = FileSystem.SYSTEM
    private val root = (NSTemporaryDirectory() + "soundfolders-" + NSUUID().UUIDString).toPath()
    private val picked = root / "Recordings"
    private val folders = IosSoundFolders((root / "bookmarks").toString())

    init {
        fs.createDirectories(picked / "Call")
        fs.write(picked / "Call" / "Call recording Noa_261015_093000.m4a") { write(ByteArray(1_000) { it.toByte() }) }
        fs.write(picked / "20261015_101500.wav") { write(wav(seconds = 1)) }
        fs.write(picked / ".pending") { writeUtf8("not a recording") }
        fs.write(picked / "notes.txt") { writeUtf8("not a recording") }
    }

    @AfterTest
    fun cleanUp() {
        fs.deleteRecursively(root)
    }

    @Test
    fun aPickedFolderListsItsSoundFiles() = runTest {
        val folder = assertNotNull(folders.add(NSURL.fileURLWithPath(picked.toString())))
        assertEquals("Recordings", folder.name)
        assertEquals(AudioKind.RECORDING, folder.kind)
        val items = assertNotNull(folders.list(folder)).sortedBy { it.fileName }
        assertEquals(listOf("20261015_101500.wav", "Call recording Noa_261015_093000.m4a"), items.map { it.fileName })
        assertTrue(items.all { it.type == MediaType.AUDIO && SoundIds.treeOf(it.id) == folder.treeUri })
        assertEquals("Call/Call recording Noa_261015_093000.m4a", SoundIds.documentOf(items[1].id))
        assertEquals(1_000L, items[1].sizeBytes)
        assertEquals("audio/wav", items[0].mimeType)
        assertEquals(FolderLook(files = 2, bytes = items.sumOf { it.sizeBytes ?: 0 }, wavs = 1, guess = AudioKind.RECORDING), folders.look(folder))
    }

    @Test
    fun aFolderGoneIsNotListed() = runTest {
        val folder = assertNotNull(folders.add(NSURL.fileURLWithPath(picked.toString())))
        assertNull(folders.list(folder.copy(treeUri = "folder:unknown")))
        fs.deleteRecursively(picked)
        assertNull(folders.list(folder))
    }

    @Test
    fun backupReadsTheFileAndSendsAWavAsFlac() = runTest {
        val folder = assertNotNull(folders.add(NSURL.fileURLWithPath(picked.toString())))
        val items = assertNotNull(folders.list(folder))
        val files = IosMediaFiles(root / "cache", sounds = folders)
        val call = items.first { it.fileName!!.endsWith(".m4a") }
        val read = files.open(call, offset = 10).buffer().use { it.readByteArray() }
        assertEquals(ByteArray(1_000) { it.toByte() }.drop(10), read.toList())

        val wav = items.first { it.isWav }
        val flac = assertNotNull(files.encodeFlac(wav))
        assertEquals("20261015_101500.flac", flac.fileName)
        val bytes = flac.open(0).buffer().use { it.readByteArray() }
        assertEquals(flac.size, bytes.size.toLong())
        assertEquals("fLaC", bytes.copyOfRange(0, 4).decodeToString())
        assertEquals(Buffer().write(bytes).sha256().hex(), flac.sha256)

        // Keep on this phone: FLAC, once the server has it.
        assertTrue(files.replaceWithFlac(wav, flac))
        assertTrue(fs.exists(picked / "20261015_101500.flac"))
        assertTrue(!fs.exists(picked / "20261015_101500.wav"))
        files.runEnded()
    }

    @Test
    fun freeUpSpaceDeletesOnlyWhatItIsGiven() = runTest {
        val folder = assertNotNull(folders.add(NSURL.fileURLWithPath(picked.toString())))
        val items = assertNotNull(folders.list(folder))
        val call = items.first { it.fileName!!.endsWith(".m4a") }
        assertEquals(setOf(call.id), folders.delete(listOf(call)))
        assertEquals(listOf("20261015_101500.wav"), folders.list(folder)?.map { it.fileName })
    }

    /** A mono 16-bit WAV of a tone, 16 kHz. */
    private fun wav(seconds: Int): ByteArray {
        val rate = 16_000
        val samples = rate * seconds
        val data = Buffer()
        repeat(samples) { i -> data.writeShortLe((3_000 * kotlin.math.sin(2 * kotlin.math.PI * 440 * i / rate)).toInt()) }
        val out = Buffer()
        out.writeUtf8("RIFF").writeIntLe(36 + samples * 2).writeUtf8("WAVE")
        out.writeUtf8("fmt ").writeIntLe(16).writeShortLe(1).writeShortLe(1).writeIntLe(rate).writeIntLe(rate * 2).writeShortLe(2).writeShortLe(16)
        out.writeUtf8("data").writeIntLe(samples * 2).writeAll(data)
        return out.readByteArray()
    }
}
