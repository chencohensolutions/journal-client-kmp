package com.chencohensolution.journal

import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.sourceOfAsset
import com.chencohensolution.journal.library.sourceOfFolder
import com.chencohensolution.journal.server.ServerMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The cases the server's rule is tested over too (media.SourceOf's table test in journal-server-go): each a file's path
 * as the phone keeps it (its folder, then its name) and the source both must give it. A case added here is added there.
 */
internal val SOURCE_FIXTURES: List<Pair<String, String>> = listOf(
    // A camera's names, wherever the file is.
    "Pictures/DSC_000385.JPG" to "camera",
    "Pictures/20250928_211937_016.jpg" to "camera",
    "Pictures/PXL_20240101_120000000.jpg" to "camera",
    "Download/VID_20240101_120000.mp4" to "camera",
    "DCIM/Camera/x.jpg" to "camera",
    "DCIM/x.jpg" to "camera",
    // WhatsApp's names and folders.
    "Pictures/IMG-20260101-WA0002.jpg" to "folder:WhatsApp",
    "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/x.jpg" to "folder:WhatsApp",
    "Pictures/WhatsApp/x.jpg" to "folder:WhatsApp",
    // A screenshot's name wins over its folder, so a screenshot received in WhatsApp stays a screenshot.
    "Pictures/WhatsApp/Screenshot_20240301_030715_Bumble.jpg" to "screenshots",
    "DCIM/Screenshots/x.png" to "screenshots",
    "Movies/Screen recordings/x.mp4" to "screenshots",
    // The messaging apps, by their names and their folders.
    "Download/photo_2023-01-15_10-00-00.jpg" to "folder:Telegram",
    "Pictures/Telegram/x.jpg" to "folder:Telegram",
    "Pictures/signal-2024-02-03-101112.jpg" to "folder:Signal",
    "Pictures/FB_IMG_1700000000000.jpg" to "folder:Facebook",
    "Pictures/received_1234567890.jpeg" to "folder:Messenger",
    "Pictures/Snapchat-123456789.jpg" to "folder:Snapchat",
    "Pictures/Snapchat/x.jpg" to "folder:Snapchat",
    // Any other folder by its name; loose in a shared folder with nothing to say, Other.
    "Movies/Petcube/x.mp4" to "folder:Petcube",
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
        assertEquals("folder:WhatsApp", MediaSource.WHATSAPP.id)
        assertEquals("WhatsApp", MediaSource.WHATSAPP.folder)
        assertEquals(MediaSource.WHATSAPP, MediaSource.parse("whatsapp"))
        assertEquals(MediaSource.WHATSAPP, MediaSource.parse("WHATSAPP"))
        assertEquals(MediaSource.WHATSAPP, MediaSource.parse("folder:whatsapp"))
        assertEquals(MediaSource.WHATSAPP, MediaSource.folder("Whatsapp"))
        assertEquals(MediaSource.imported(MediaSource.WHATSAPP), MediaSource.parse("import:whatsapp"))
        assertEquals("import:folder:WhatsApp", MediaSource.parse("import:whatsapp")?.id)
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
        assertEquals("folder:Received", MediaSource.folder("Received").id)
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
}
