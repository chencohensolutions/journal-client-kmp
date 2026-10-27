package com.chencohensolution.journal

import com.chencohensolution.journal.backup.BackupSettings
import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.backup.LedgerEntry
import com.chencohensolution.journal.backup.SoundFolder
import com.chencohensolution.journal.backup.SoundIds
import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.SoundRecording
import com.chencohensolution.journal.library.SourceCounts
import com.chencohensolution.journal.library.SourceTotal
import com.chencohensolution.journal.library.libraryTotals
import com.chencohensolution.journal.server.HiddenCollections
import com.chencohensolution.journal.server.PrivateKinds
import com.chencohensolution.journal.settings.KindsStatus
import com.apollographql.apollo.api.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Show in the app's status page (KindsStatus) and Private settings' list of kinds (PrivateKinds). */
class KindsTest {
    private fun collection(name: String, files: Int, hidden: Boolean = false) = HiddenCollections.Collection(name, files, hidden)

    private val hidden = HiddenCollections(
        apps = listOf(collection("WhatsApp", 19_700, hidden = true), collection("Telegram", 16), collection("Old", 0, hidden = true)),
        screenshotsWithoutApp = collection("", 128),
        screenshotApps = listOf(collection("Bumble", 1_458, hidden = true), collection("Chrome", 310)),
        nudity = HiddenCollections.Nudity(true, 50, 61, 0),
        recordings = collection("recordings", 168, hidden = false),
        calls = collection("calls", 6_395, hidden = true),
        voiceMessages = collection("voiceMessages", 0, hidden = true),
        sentToYou = collection("sentToYou", 312),
    )

    private fun sound(kind: AudioKind, n: Int) = SoundRecording("$kind$n".padEnd(64, '0'), kind, 0, LocalDay(2026, 10, 27), 1000)

    @Test
    fun eachKindHasItsTotalAndWhatIsNotBackedUpWithImportedFoldedIn() {
        val sources = mapOf(
            MediaSource.CAMERA to SourceTotal(40_590, 12),
            MediaSource.imported(MediaSource.CAMERA) to SourceTotal(38_410, 0),
            MediaSource.OTHER to SourceTotal(480, 0),
            MediaSource.imported(MediaSource.OTHER) to SourceTotal(20, 0),
            MediaSource.SCREENSHOTS to SourceTotal(1_730, 0),
            MediaSource.RECEIVED to SourceTotal(312, 0),
            MediaSource.folder("Petcube") to SourceTotal(10, 3),
            MediaSource.imported(MediaSource.folder("Petcube")) to SourceTotal(3, 0),
            MediaSource.folder("Empty") to SourceTotal(0, 0),
        )
        val sounds = listOf(sound(AudioKind.RECORDING, 1), sound(AudioKind.RECORDING, 2), sound(AudioKind.VOICE_MESSAGE, 1))
        val status = KindsStatus.of(sources, sounds, mapOf(AudioKind.CALL to 4), hidden = null)
        // The most files first, Other last, imported files in their kind; a kind without files isn't listed.
        assertEquals(
            listOf(
                KindsStatus.Kind(MediaSource.CAMERA, 79_000, 12),
                KindsStatus.Kind(MediaSource.SCREENSHOTS, 1_730, 0),
                KindsStatus.Kind(MediaSource.RECEIVED, 312, 0),
                KindsStatus.Kind(MediaSource.folder("Petcube"), 13, 3),
                KindsStatus.Kind(MediaSource.OTHER, 500, 0),
            ),
            status.kinds,
        )
        // Sound: the server's shown recordings plus the backed-up folders' files not on the server yet.
        assertEquals(
            listOf(
                KindsStatus.SoundKind(AudioKind.CALL, 4, 4),
                KindsStatus.SoundKind(AudioKind.RECORDING, 2, 0),
                KindsStatus.SoundKind(AudioKind.VOICE_MESSAGE, 1, 0),
            ),
            status.sounds,
        )
        assertEquals(79_000 + 1_730 + 312 + 13 + 500 + 7, status.files)
        assertEquals(12 + 3 + 4, status.notBackedUp)
        // Private settings not opened in this run: the line is left out.
        assertNull(status.hiddenEverywhere)
    }

    @Test
    fun kindsHiddenOnAllDevicesAreCountedNotNamed() {
        val sources = mapOf(
            MediaSource.CAMERA to SourceTotal(100, 0),
            // This phone's WhatsApp photos backup hasn't read yet still reach the library.
            MediaSource.WHATSAPP to SourceTotal(5, 5),
            MediaSource.RECEIVED to SourceTotal(312, 0),
        )
        val status = KindsStatus.of(sources, emptyList(), mapOf(AudioKind.CALL to 2), hidden.copy(sentToYou = collection("sentToYou", 312, hidden = true)))
        assertEquals(listOf(KindsStatus.Kind(MediaSource.CAMERA, 100, 0)), status.kinds)
        // Calls are hidden on all devices, so their files waiting here aren't named either.
        assertEquals(emptyList(), status.sounds)
        // WhatsApp, Bumble's screenshots, Calls and Sent to you; not Old (no files), voice messages (none) or nudity.
        assertEquals(4, status.hiddenEverywhere)
    }

    @Test
    fun theCountsPerSourceAreTheTotalsSplit() {
        val counts = listOf(
            SourceCounts(MediaSource.CAMERA, items = 10, onDevice = 8, onDeviceBytes = 80, waiting = 3, waitingBytes = 30),
            SourceCounts(MediaSource.SCREENSHOTS, items = 5, onDevice = 5, onDeviceBytes = 50, waiting = 5, waitingBytes = 50),
            SourceCounts(MediaSource.imported(MediaSource.CAMERA), items = 7, onDevice = 0, onDeviceBytes = 0, waiting = 0, waitingBytes = 0),
        )
        // Backup takes the camera only: Screenshots' files aren't counted as waiting; one camera file on its way counts.
        val on = BackupState(settings = BackupSettings(enabled = true, sources = setOf(MediaSource.CAMERA)))
        val totals = libraryTotals(counts, listOf(MediaSource.CAMERA to 10L), on)
        assertEquals(
            mapOf(
                MediaSource.CAMERA to SourceTotal(10, 4),
                MediaSource.SCREENSHOTS to SourceTotal(5, 0),
                MediaSource.imported(MediaSource.CAMERA) to SourceTotal(7, 0),
            ),
            totals.bySource,
        )
        assertEquals(totals.notBackedUp, totals.bySource.values.sumOf { it.notBackedUp })
        // Without backup every phone file waits.
        val off = libraryTotals(counts, emptyList(), null)
        assertEquals(SourceTotal(5, 5), off.bySource[MediaSource.SCREENSHOTS])
        assertEquals(off.notBackedUp, off.bySource.values.sumOf { it.notBackedUp })
    }

    @Test
    fun soundFoldersNotBackedUpAreTheirFilesLessTheUploaded() {
        val calls = SoundFolder("tree:call", "Call", "Recordings › Call", AudioKind.CALL)
        val voice = SoundFolder("tree:voice", "Voice", "Recordings › Voice", AudioKind.RECORDING)
        val off = SoundFolder("tree:off", "Off", "Off", AudioKind.RECORDING, enabled = false)
        val entries = listOf(
            LedgerEntry(SoundIds.of(calls, "a"), 1, 1, "a", uploaded = true),
            LedgerEntry(SoundIds.of(calls, "b"), 1, 1, "b", uploaded = true),
            LedgerEntry(SoundIds.of(calls, "c"), 1, 1, "c", uploaded = false),
            LedgerEntry(SoundIds.of(voice, "d"), 1, 1, "d", uploaded = true),
        ).associateBy { it.id }
        val state = BackupState(
            settings = BackupSettings(enabled = true, soundFolders = listOf(calls, voice, off)),
            entries = entries,
            soundCounts = mapOf("tree:call" to 5, "tree:voice" to 1, "tree:off" to 9),
        )
        assertEquals(mapOf(AudioKind.CALL to 3), state.soundsNotBackedUp())
    }

    @Test
    fun privateSettingsListsEveryKindOnceWithItsShowSwitch() {
        val kinds = PrivateKinds.of(hidden)
        assertEquals(
            listOf(
                PrivateKinds.Type.FOLDER to "WhatsApp",
                PrivateKinds.Type.CALLS to "",
                PrivateKinds.Type.SCREENSHOTS to "",
                PrivateKinds.Type.SENT_TO_YOU to "",
                PrivateKinds.Type.VOICE_RECORDINGS to "",
                PrivateKinds.Type.FOLDER to "Telegram",
                PrivateKinds.Type.FOLDER to "Old",
            ),
            kinds.kinds.map { it.type to it.name },
        )
        // On = shown; voice messages without files aren't listed.
        assertEquals(listOf(false, false, true, true, true, true, false), kinds.kinds.map { it.shown })
        val screenshots = kinds.kinds.single { it.type == PrivateKinds.Type.SCREENSHOTS }
        assertEquals(1_458 + 310 + 128, screenshots.files)
        assertNull(screenshots.collection)
        // Screenshots' chips: by app, the most first, without an app among them (an empty name).
        assertEquals(listOf("Bumble" to false, "Chrome" to true, "" to true), kinds.screenshotApps.map { it.name to it.shown })
    }

    @Test
    fun eachSwitchGoesBackAsTheServersField() {
        val kinds = PrivateKinds.of(hidden)
        fun kind(type: PrivateKinds.Type, name: String = "") = kinds.kinds.single { it.type == type && it.name == name }
        fun show(kind: PrivateKinds.Kind, shown: Boolean) = hidden.with(kind.collection!!, hidden = !shown).toInput()

        // Old stays hidden: it is listed, hidden, with no files.
        assertEquals(Optional.present(listOf("Old")), show(kind(PrivateKinds.Type.FOLDER, "WhatsApp"), true).apps)
        assertEquals(Optional.present(listOf("WhatsApp", "Telegram", "Old")), show(kind(PrivateKinds.Type.FOLDER, "Telegram"), false).apps)
        val chrome = kinds.screenshotApps.single { it.name == "Chrome" }
        assertEquals(listOf("Bumble", "Chrome"), show(chrome, false).screenshotApps)
        val withoutApp = kinds.screenshotApps.single { it.name == "" }
        assertEquals(true, show(withoutApp, false).screenshotsWithoutApp)
        val calls = show(kind(PrivateKinds.Type.CALLS), true).audio.getOrNull()!!
        assertEquals(listOf(false, false, true), listOf(calls.recordings, calls.calls, calls.voiceMessages))
        val recordings = show(kind(PrivateKinds.Type.VOICE_RECORDINGS), false).audio.getOrNull()!!
        assertEquals(listOf(true, true, true), listOf(recordings.recordings, recordings.calls, recordings.voiceMessages))
        assertEquals(Optional.present(true), show(kind(PrivateKinds.Type.SENT_TO_YOU), false).sentToYou)
        // A server without Sent to you is never told about it.
        assertEquals(Optional.absent(), hidden.copy(sentToYou = null).toInput().sentToYou)
    }
}
