package com.chencohensolution.journal

import com.chencohensolution.journal.backup.BackupEngine
import com.chencohensolution.journal.backup.BackupSettings
import com.chencohensolution.journal.backup.EncodedFlac
import com.chencohensolution.journal.backup.KeepOnPhone
import com.chencohensolution.journal.backup.SoundFolder
import com.chencohensolution.journal.backup.SoundIds
import com.chencohensolution.journal.backup.UploadLedger
import com.chencohensolution.journal.backup.finished
import com.chencohensolution.journal.backup.guessKind
import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.storage.ServerCheck
import com.chencohensolution.journal.storage.ServerVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Files from the sound folders the user picked: calls, recordings, WAVs sent as FLAC. */
class SoundBackupTest {
    private val fs = FakeFileSystem()
    private val server = FakeServer()
    private val calls = SoundFolder("content://tree/primary%3ARecordings%2FCall", "Call", "Recordings › Call", AudioKind.CALL)
    private val recorder = SoundFolder("content://tree/primary%3ARecordings%2FVoice", "Voice Recorder", "Recordings › Voice Recorder", AudioKind.RECORDING)

    /** Encodes a WAV by reversing its bytes, and remembers what happened to each FLAC. */
    private class SoundFiles(private val canEncode: Boolean = true) : FakeFiles() {
        val discarded = mutableListOf<String>()
        val replaced = mutableListOf<String>()

        override suspend fun encodeFlac(item: MediaItem): EncodedFlac? {
            if (!canEncode) return null
            val flac = bytes.getValue(item.id).reversedArray()
            val name = item.fileName!!.substringBeforeLast('.') + ".flac"
            return EncodedFlac(name, flac.size.toLong(), flac.toByteString().sha256().hex(), { Buffer().write(flac.copyOfRange(it.toInt(), flac.size)) }, { discarded += name })
        }

        override suspend fun replaceWithFlac(item: MediaItem, flac: EncodedFlac): Boolean {
            replaced += item.id
            return true
        }
    }

    private val database = testLibraryDatabase()
    private fun ledger() = UploadLedger(fs, "/backup".toPath(), database, Dispatchers.Unconfined)

    private fun TestScope.engine(files: SoundFiles, ledger: UploadLedger = ledger(), check: ServerCheck? = null) =
        BackupEngine(files, ledger, server, check = check, io = StandardTestDispatcher(testScheduler))

    private fun sound(files: SoundFiles, folder: SoundFolder, name: String, seed: Int, mime: String? = null): MediaItem {
        val bytes = ByteArray(1_000 + seed) { ((it * 7 + seed) % 251).toByte() }
        val item = MediaItem(
            SoundIds.of(folder, "doc/$name"), MediaType.AUDIO, 1_000L * seed, LocalDay(2026, 10, 25), null, 0, 0,
            bytes.size.toLong(), name, source = folder.source, modifiedMillis = 1, mimeType = mime,
        )
        files.bytes[item.id] = bytes
        return item
    }

    @Test
    fun aWavGoesAsFlacWithItsShaAndIsNeverSentAgain() = runTest {
        val files = SoundFiles()
        val wav = sound(files, calls, "Call recording 0501234567_251025_101500.wav", seed = 1)
        val settings = BackupSettings(enabled = true, soundFolders = listOf(calls))
        val ledger = ledger()

        val result = engine(files, ledger).run(listOf(wav), settings) {}

        assertEquals(1, result.uploaded)
        val wavSha = files.bytes.getValue(wav.id).toByteString().sha256().hex()
        val request = server.requests.values.single()
        assertEquals("Call recording 0501234567_251025_101500.flac", request.fileName)
        assertEquals("audio/flac", request.mimeType)
        assertEquals(MediaType.AUDIO, request.kind)
        assertTrue(request.isCall, "a file of a calls folder is sent as a call")
        assertEquals(wavSha, request.encodedFrom?.sha256)
        assertEquals(listOf("Call recording 0501234567_251025_101500.flac"), files.discarded)
        assertTrue(files.replaced.isEmpty(), "the WAV stays: keep WAV is the default")
        assertTrue(ledger.isUploaded(wav))

        // The same WAV under another name (copied to another folder): the server answers it through its FLAC.
        val copy = sound(files, recorder, "copy.wav", seed = 1)
        val again = engine(files, ledger).run(listOf(copy), settings.copy(soundFolders = listOf(calls, recorder))) {}
        assertEquals(1, again.alreadyOnServer)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun keepingFlacReplacesTheWavOnlyAfterTheServerConfirmsIt() = runTest {
        val files = SoundFiles()
        val settings = BackupSettings(enabled = true, soundFolders = listOf(recorder), keepOnPhone = KeepOnPhone.FLAC)
        val confirmed = sound(files, recorder, "20251025_101500.wav", seed = 1)
        engine(files, check = { ServerVerdict.OK }).run(listOf(confirmed), settings) {}
        assertEquals(listOf(confirmed.id), files.replaced)

        val unconfirmed = sound(files, recorder, "20251025_111500.wav", seed = 2)
        engine(files, check = { ServerVerdict.MISMATCH }).run(listOf(unconfirmed), settings) {}
        val unreachable = sound(files, recorder, "20251025_121500.wav", seed = 3)
        engine(files, check = { error("offline") }).run(listOf(unreachable), settings) {}
        assertEquals(listOf(confirmed.id), files.replaced)
        assertEquals(3, files.discarded.size)
    }

    @Test
    fun aWavThePhoneCantEncodeGoesAsItIs() = runTest {
        val files = SoundFiles(canEncode = false)
        val wav = sound(files, recorder, "odd.wav", seed = 1, mime = "audio/x-wav")
        engine(files).run(listOf(wav), BackupSettings(enabled = true, soundFolders = listOf(recorder))) {}
        val request = server.requests.values.single()
        assertEquals("odd.wav", request.fileName)
        assertEquals("audio/x-wav", request.mimeType)
        assertNull(request.encodedFrom)
        assertTrue(!request.isCall)
    }

    @Test
    fun folderFilesGoWhateverPhotoSourcesAreChosen() = runTest {
        val files = SoundFiles()
        val voice = SoundFolder("content://tree/voice", "WhatsApp Voice Notes", "WhatsApp › Voice Notes", AudioKind.VOICE_MESSAGE)
        val note = sound(files, voice, "PTT-20251025-WA0001.opus", seed = 1)
        val recording = sound(files, recorder, "20251025_101500.m4a", seed = 2)
        // Only Camera is chosen for photos: WhatsApp photos stay, but the voice notes folder was picked.
        val result = engine(files).run(listOf(note, recording), BackupSettings(enabled = true, soundFolders = listOf(voice, recorder))) {}
        assertEquals(2, result.uploaded)
        val bySource = server.requests.values.associate { it.fileName to it.source }
        assertEquals("whatsapp", bySource["PTT-20251025-WA0001.opus"])
        assertEquals("folder:Voice Recorder", bySource["20251025_101500.m4a"])
        assertEquals("audio/ogg", server.requests.values.first { it.fileName.endsWith(".opus") }.mimeType)
    }

    @Test
    fun aFileStillBeingWrittenWaits() {
        val now = 10L * 60 * 1000
        val item = { id: String, modified: Long ->
            MediaItem(id, MediaType.AUDIO, 0, LocalDay(2026, 10, 25), null, 0, 0, 1, "$id.m4a", modifiedMillis = modified)
        }
        val done = item("done", now - 3 * 60 * 1000)
        val recording = item("recording", now - 30 * 1000)
        assertEquals(listOf(done), finished(listOf(done, recording), now))
    }

    @Test
    fun theNamesSuggestWhatAFolderHolds() {
        assertEquals(AudioKind.CALL, guessKind(listOf("Call recording אבא_250206_142813.m4a", "הקלטת שיחה 0501234567_211115_200618.m4a", "x.m4a")))
        assertEquals(AudioKind.VOICE_MESSAGE, guessKind(listOf("PTT-20251025-WA0001.opus", "AUD-20251025-WA0002.opus")))
        assertEquals(AudioKind.RECORDING, guessKind(listOf("20251025_101500.m4a", "Voice 001.m4a")))
        assertEquals(AudioKind.RECORDING, guessKind(emptyList()))
    }

    @Test
    fun soundFoldersAreKeptInSettings() {
        val store = object : KeyValueStore {
            val values = mutableMapOf<String, String>()
            override fun getString(key: String) = values[key]
            override fun putString(key: String, value: String?) {
                if (value == null) values.remove(key) else values[key] = value
            }
        }
        val settings = JournalSettings(store)
        settings.backup = BackupSettings(soundFolders = listOf(calls, recorder.copy(enabled = false)), keepOnPhone = KeepOnPhone.FLAC)
        val read = JournalSettings(store).backup
        assertEquals(listOf(calls, recorder.copy(enabled = false)), read.soundFolders)
        assertEquals(KeepOnPhone.FLAC, read.keepOnPhone)
        assertTrue(store.values.getValue("backup_sound_folders").startsWith("{\"v\":1"))
        assertEquals(MediaSource.folder("Call"), calls.source)
        assertNotNull(SoundIds.treeOf(SoundIds.of(calls, "doc")))
        assertEquals(calls.treeUri, SoundIds.treeOf(SoundIds.of(calls, "content://doc/a%7Cb")))
    }
}
