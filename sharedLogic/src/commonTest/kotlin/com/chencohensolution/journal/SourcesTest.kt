package com.chencohensolution.journal

import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.FolderNames
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.sourceOfAsset
import com.chencohensolution.journal.library.sourceOfFolder
import com.chencohensolution.journal.server.ServerMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cases the server's rule is tested over too (media.SourceOf's table test in journal-server-go): each a file's path
 * as the phone keeps it (its folder, then its name) and the source both must give it. A case added here is added there.
 */
internal val SOURCE_FIXTURES: List<Pair<String, String>> = listOf(
    // A camera's names, wherever the file is.
    "Pictures/DSC_000385.JPG" to "camera",
    "Pictures/20250928_211937_016.jpg" to "camera",
    "Pictures/IMG_20171012_213615.jpg" to "camera",
    "Pictures/IMG_20171012_213615_123.jpg" to "camera",
    "Pictures/IMG_20160516_081202_HDR.jpg" to "camera",
    // An app's name with a long number after the time (WorkersNet's empty files) isn't a camera's.
    "Pictures/img_20260927_115615_2430005126935688329.jpg" to "other",
    "Pictures/PXL_20240101_120000000.jpg" to "camera",
    "Download/VID_20240101_120000.mp4" to "camera",
    "DCIM/Camera/x.jpg" to "camera",
    "DCIM/x.jpg" to "camera",
    // Folders a camera makes, at any level, before a folder by its name (the server's rule only: sound recordings,
    // a phone's folder over camera tags, and the import's minimum, have no case here).
    "DCIM/100ANDRO/x.mp4" to "camera",
    "DCIM/100MEDIA/x.jpg" to "camera",
    "DCIM/101MSDCF/Sub/x.jpg" to "camera",
    "DCIM/SLOW_VIDEO/x.mp4" to "camera",
    "DCIM/EDITED_VIDEO/x.mp4" to "camera",
    "Pictures/Videocaptures/x.jpg" to "camera",
    "DCIM/DSC_20190928033805229/x.jpg" to "camera",
    "Pictures/2019trip/x.jpg" to "folder:2019trip",
    // WhatsApp's names and folders.
    "Pictures/IMG-20260101-WA0002.jpg" to "folder:whatsapp",
    "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/x.jpg" to "folder:whatsapp",
    "Pictures/WhatsApp/x.jpg" to "folder:whatsapp",
    // A screenshot's name wins over its folder, so a screenshot received in WhatsApp stays a screenshot.
    "Pictures/WhatsApp/Screenshot_20240301_030715_Bumble.jpg" to "screenshots",
    "DCIM/Screenshots/x.png" to "screenshots",
    "Movies/Screen recordings/x.mp4" to "screenshots",
    // Hebrew screenshot folders: צילומי מסך (screenshots), הקלטות מסך (screen recordings).
    "Pictures/צילומי מסך/x.png" to "screenshots",
    "Pictures/צילומי_מסך/x.png" to "screenshots",
    "Movies/הקלטות מסך/x.mp4" to "screenshots",
    // The messaging apps, by their names and their folders.
    "Download/photo_2023-01-15_10-00-00.jpg" to "folder:telegram",
    "Pictures/Telegram/x.jpg" to "folder:telegram",
    "Pictures/signal-2024-02-03-101112.jpg" to "folder:signal",
    "Pictures/FB_IMG_1700000000000.jpg" to "folder:facebook",
    "Pictures/received_1234567890.jpeg" to "folder:messenger",
    "Pictures/Snapchat-123456789.jpg" to "folder:snapchat",
    "Pictures/Snapchat/x.jpg" to "folder:snapchat",
    // Any other folder by its name; loose in a shared folder with nothing to say, Other.
    "Movies/Petcube/x.mp4" to "folder:petcube",
    "Pictures/1740850503547.png" to "other",
)

class SourcesTest {
    @Test
    fun thePhonesRuleGivesWhatTheServersDoes() {
        for ((path, expected) in SOURCE_FIXTURES) {
            val folder = path.substringBeforeLast('/', "") + "/"
            val name = path.substringAfterLast('/')
            assertEquals(expected, sourceOfFolder(folder, name).id, path)
        }
    }

    @Test
    fun theAppThatSavedAFileCountsOnlyWhenNothingElseSays() {
        assertEquals(MediaSource.folder("ChatGPT"), sourceOfFolder("Pictures/", "1740850503547.png", appName = "ChatGPT"))
        assertEquals(MediaSource.CAMERA, sourceOfFolder("Pictures/", "20250928_211937_016.jpg", appName = "ChatGPT"))
        // An app's label is spelled as the table spells it.
        assertEquals(MediaSource.folder("Telegram"), sourceOfFolder("Pictures/", "x.jpg", appName = "telegram"))
        assertEquals(MediaSource.WHATSAPP, sourceOfFolder(null, "IMG-20260101-WA0002.jpg"))
    }

    @Test
    fun anIphoneReadsTheSameNames() {
        assertEquals(MediaSource.WHATSAPP, sourceOfAsset(false, emptyList(), true, "image/jpeg", "IMG-20260101-WA0002.jpg"))
        assertEquals(MediaSource.folder("Telegram"), sourceOfAsset(false, listOf("Telegram"), true, "image/jpeg", "IMG_0001.JPG"))
        assertEquals(MediaSource.SCREENSHOTS, sourceOfAsset(false, listOf("WhatsApp"), true, "image/jpeg", "Screenshot_20240301_030715_Bumble.jpg"))
        assertEquals(MediaSource.CAMERA, sourceOfAsset(false, emptyList(), true, "image/heic", "IMG_0001.HEIC"))
    }

    @Test
    fun whatsAppIsAFolderAndItsOldIdIsReadAsIt() {
        assertEquals("folder:whatsapp", MediaSource.WHATSAPP.id)
        assertEquals("whatsapp", MediaSource.WHATSAPP.folder)
        assertEquals("WhatsApp", MediaSource.WHATSAPP.folderName)
        assertEquals(MediaSource.WHATSAPP, MediaSource.parse("folder:WhatsApp"))
        assertEquals(MediaSource.WHATSAPP, MediaSource.parse("whatsapp"))
        assertEquals(MediaSource.WHATSAPP, MediaSource.parse("WHATSAPP"))
        assertEquals(MediaSource.WHATSAPP, MediaSource.parse("folder:whatsapp"))
        assertEquals(MediaSource.WHATSAPP, MediaSource.folder("Whatsapp"))
        assertEquals(MediaSource.imported(MediaSource.WHATSAPP), MediaSource.parse("import:whatsapp"))
        assertEquals("import:folder:whatsapp", MediaSource.parse("import:whatsapp")?.id)
        assertEquals(false, MediaSource.WHATSAPP in MediaSource.FIXED)
    }

    @Test
    fun everyIdTheServerSendsIsKnown() {
        val telegram = MediaSource.folder("Telegram")
        val imported = MediaSource.parse("import:folder:Telegram")!!
        assertEquals(true, imported.isImported)
        assertEquals(telegram, imported.kind)
        assertEquals(MediaSource.imported(telegram), imported)
        assertNull(imported.folder)
        // Sent to you, a fixed source after Screenshots, and what was imported of it.
        assertEquals(MediaSource.RECEIVED, MediaSource.parse("received"))
        assertEquals(MediaSource.RECEIVED, MediaSource.parse("import:received")?.kind)
        assertEquals(
            listOf(MediaSource.CAMERA, MediaSource.SCREENSHOTS, MediaSource.RECEIVED, telegram, MediaSource.OTHER),
            listOf(MediaSource.OTHER, telegram, MediaSource.RECEIVED, MediaSource.SCREENSHOTS, MediaSource.CAMERA).sorted(),
        )
        // A folder named Received is a folder; Sent to you comes from the server only.
        assertEquals("folder:received", MediaSource.folder("Received").id)
        // Offered only once it has photos.
        assertEquals(false, MediaSource.RECEIVED in MediaSource.choices(emptySet()))
        assertEquals(true, MediaSource.RECEIVED in MediaSource.choices(setOf(MediaSource.RECEIVED)))
        // Nothing nests: "import:import:camera" names nothing.
        assertNull(MediaSource.parse("import:import:camera"))
    }

    @Test
    fun anUnknownIdNeverBecomesThePhonesOther() {
        assertNull(MediaSource.parse("import:hologram"))
        assertEquals(MediaSource.imported(MediaSource.OTHER), MediaSource.of("import:hologram"))
        assertEquals(MediaSource.OTHER, MediaSource.of("hologram"))
        assertEquals(MediaSource.OTHER, MediaSource.of(null))
        val server = ServerMedia(sha256 = "a".repeat(64), isVideo = false, fileName = "x.jpg", mimeType = "image/jpeg", size = 1, uploadedAt = "2026-10-19T22:05:21Z", source = "hologram", uploadedBy = "import")
        assertEquals(MediaSource.imported(MediaSource.OTHER), server.mediaSource)
        assertEquals(MediaSource.imported(MediaSource.WHATSAPP), server.copy(source = "whatsapp").mediaSource)
        assertEquals(MediaSource.WHATSAPP, server.copy(source = "folder:WhatsApp", uploadedBy = "pixel").mediaSource)
    }

    @Test
    fun importedOffersEveryKindWithPhotos() {
        val telegram = MediaSource.imported(MediaSource.folder("Telegram"))
        val whatsApp = MediaSource.imported(MediaSource.WHATSAPP)
        val counts = mapOf(MediaSource.imported(MediaSource.CAMERA) to 5, whatsApp to 900, telegram to 12, MediaSource.CAMERA to 3)
        assertEquals(
            listOf(whatsApp, telegram, MediaSource.imported(MediaSource.CAMERA), MediaSource.imported(MediaSource.SCREENSHOTS), MediaSource.imported(MediaSource.OTHER)),
            MediaSource.importedChoices(counts),
        )
        assertEquals(emptyList(), MediaSource.importedChoices(mapOf(MediaSource.CAMERA to 3)))
    }

    @Test
    fun voiceMessagesAreKnownByEitherId() {
        val audio = ServerMedia(sha256 = "b".repeat(64), isVideo = false, fileName = "PTT-20250208-WA0004.opus", mimeType = "audio/ogg", size = 1, uploadedAt = "2026-10-19T22:05:21Z", isAudio = true)
        assertEquals(com.chencohensolution.journal.library.AudioKind.VOICE_MESSAGE, audio.copy(source = "whatsapp").toSoundRecording().kind)
        assertEquals(com.chencohensolution.journal.library.AudioKind.VOICE_MESSAGE, audio.copy(source = "folder:WhatsApp").toSoundRecording().kind)
        assertEquals(com.chencohensolution.journal.library.AudioKind.RECORDING, audio.copy(source = "folder:Voice Recorder").toSoundRecording().kind)
    }

    @Test
    fun folderIdsAreInLowerCaseAndFoldersDifferingByCaseAreOne() {
        assertEquals("folder:calls", MediaSource.folder("Calls").id)
        assertEquals(MediaSource.folder("Calls"), MediaSource.folder("calls"))
        assertEquals(MediaSource.folder("Calls"), MediaSource.parse("folder:CALLS"))
        assertEquals(MediaSource.folder("Calls"), MediaSource.of("FOLDER:Calls"))
        assertEquals("import:folder:calls", MediaSource.parse("import:folder:Calls")?.id)
        assertEquals(1, listOf("folder:Calls", "folder:calls", "folder:CALLS").map(MediaSource::of).distinct().size)
        // Fixed ids are read in any case too, and a folder named like one is it.
        assertEquals(MediaSource.CAMERA, MediaSource.folder("camera"))
    }

    @Test
    fun aFolderIsShownByTheAppTableThenTheServerThenThePhone() {
        FolderNames.clear()
        try {
            // Known apps as the table spells them, whatever the id or the server say.
            FolderNames.setServer(mapOf("folder:whatsapp" to "Whatsapp", "folder:telegram" to "TELEGRAM"))
            assertEquals("WhatsApp", MediaSource.parse("folder:whatsapp")?.folderName)
            assertEquals("Telegram", MediaSource.parse("folder:telegram")?.folderName)
            assertEquals("Snapchat", MediaSource.parse("folder:snapchat")?.folderName)
            // With nothing known, the id's name with a capital: never "petcube".
            assertEquals("Petcube", MediaSource.parse("folder:petcube")?.folderName)
            assertEquals("ספירלה יום", MediaSource.parse("folder:ספירלה יום")?.folderName)
            // The phone's own spelling, from its folder or a kept id with capitals.
            val recorder = sourceOfFolder("Recordings/Voice Recorder/", "x.m4a")
            assertEquals("folder:voice recorder", recorder.id)
            assertEquals("Voice Recorder", recorder.folderName)
            assertEquals("folder:Voice Recorder", recorder.spelledId)
            MediaSource.of("folder:DineOut")
            assertEquals("DineOut", MediaSource.of("folder:dineout").folderName)
            // A later lower-case spelling doesn't replace it.
            MediaSource.folder("dineout")
            assertEquals("DineOut", MediaSource.of("folder:dineout").folderName)
            // The server's most common spelling wins over the phone's.
            MediaSource.folder("calls")
            MediaSource.folder("CALLS")
            assertEquals("CALLS", MediaSource.folder("calls").folderName)
            FolderNames.setServer(mapOf("folder:calls" to "Calls", "camera" to "Camera"))
            assertEquals("Calls", MediaSource.folder("CALLS").folderName)
            assertEquals(null, MediaSource.CAMERA.folderName)
        } finally {
            FolderNames.clear()
        }
    }

    @Test
    fun callsAndRecordingsAreSoundKindsNeverOfferedForPhotos() {
        assertEquals(MediaSource.CALL, MediaSource.parse("call"))
        assertEquals(MediaSource.RECORDING, MediaSource.parse("Recording"))
        assertEquals(MediaSource.imported(MediaSource.CALL), MediaSource.of("import:call"))
        assertTrue(MediaSource.CALL.isSound)
        assertEquals(false, MediaSource.folder("Call").isSound)
        assertEquals("folder:call", MediaSource.folder("Call").id)
        val counts = mapOf(MediaSource.CAMERA to 10, MediaSource.CALL to 1_623, MediaSource.RECORDING to 168, MediaSource.folder("Calls") to 3)
        val offered = MediaSource.byCount(counts, chosen = setOf(MediaSource.RECORDING))
        assertEquals(listOf(MediaSource.CAMERA, MediaSource.folder("Calls"), MediaSource.SCREENSHOTS, MediaSource.OTHER), offered)
        assertEquals(false, MediaSource.CALL in com.chencohensolution.journal.backup.BackupSettings().choices(counts))
    }

    @Test
    fun aSoundRecordingsKindIsItsSourceFirst() {
        val audio = ServerMedia(sha256 = "c".repeat(64), isVideo = false, fileName = "Call 054.m4a", mimeType = "audio/mp4", size = 1, uploadedAt = "2026-10-19T22:05:21Z", isAudio = true)
        assertEquals(AudioKind.CALL, audio.copy(source = "call").toSoundRecording().kind)
        assertEquals(AudioKind.RECORDING, audio.copy(source = "recording").toSoundRecording().kind)
        // The source wins over what the server read of a call; a WhatsApp voice message stays one.
        assertEquals(AudioKind.RECORDING, audio.copy(source = "recording", isCall = true).toSoundRecording().kind)
        assertEquals(AudioKind.VOICE_MESSAGE, audio.copy(source = "folder:whatsapp", isCall = true).toSoundRecording().kind)
        // Then today's rules: a call by what the server read, else a recording.
        assertEquals(AudioKind.CALL, audio.copy(source = "folder:calls", isCall = true).toSoundRecording().kind)
        assertEquals(AudioKind.RECORDING, audio.copy(source = "folder:calls").toSoundRecording().kind)
        assertEquals(AudioKind.CALL, AudioKind.of(MediaSource.imported(MediaSource.CALL), isCall = false))
        assertEquals(MediaSource.CALL, audio.copy(source = "call").toSoundRecording().source)
    }
}
