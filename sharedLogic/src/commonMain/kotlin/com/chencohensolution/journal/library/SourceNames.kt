package com.chencohensolution.journal.library

/**
 * The names and folders that tell where a file came from: the table [sourceOfFolder] and [sourceOfAsset] read, a copy
 * of the server's (media.SourceOf in journal-server-go), so a phone and the server label a file alike before it is
 * uploaded. Both are tested over the same cases (SourceNamesTest here, the rule's table test there); a change to one is
 * made to the other.
 */
internal object SourceNames {
    /**
     * How Android names screenshots, without the extension: Screenshot_2015-07-18-03-54-57, Screenshot_20220917_160207,
     * and since Samsung's One UI with the app on screen after the time: Screenshot_20240301_030715_Bumble,
     * Screenshot_20220603-144846_Bumble(1), Screenshot_20221115-220726_Bumble~20221117-191459 (the ~ and (1) are copies
     * the gallery made).
     */
    private val SCREENSHOT = Regex(
        """^screenshot_(?:\d{8}[-_]\d{6}\d*|\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2})(?:_(.*?))?(?:~[\d-]+)?\s*(?:\(\d+\))?$""",
        RegexOption.IGNORE_CASE,
    )

    /** How Android names screen recordings: Screen_Recording_20240301-030715_Bumble.mp4, Screen_recording_20240301_030715.mp4. */
    private val SCREEN_RECORDING = Regex("""^screen[_ ]?recording_\d{8}[-_]\d{6}""", RegexOption.IGNORE_CASE)

    /** Words in a folder's name that make it a screenshots folder ("Screenshots", "Screen recordings", "ScreenRecorder"). */
    private val SCREENSHOT_FOLDERS = listOf("screenshot", "screenrecord", "screen record")

    /**
     * How WhatsApp names the files it saves: IMG-20230115-WA0012.jpg, VID-20230115-WA0003.mp4, and AUD-, PTT-, STK- and
     * DOC- for voice notes, stickers and documents. Copies keep the name after they leave its folders.
     */
    private val WHATSAPP = Regex("""^(img|vid|aud|ptt|stk|doc)-\d{8}-wa\d+""", RegexOption.IGNORE_CASE)

    /** A messaging app as its folder is named ([app], also how it is spelled), and the names it gives what it saves. */
    class App(val app: String, val names: Regex)

    /** The messaging apps whose names and folders say so, besides WhatsApp; each is a folder source by its name. */
    val MESSAGING: List<App> = listOf(
        App("Telegram", Regex("""^(photo|video)_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}""", RegexOption.IGNORE_CASE)),
        App("Signal", Regex("""^signal-\d{4}-\d{2}-\d{2}""", RegexOption.IGNORE_CASE)),
        App("Facebook", Regex("""^FB_IMG_\d+""", RegexOption.IGNORE_CASE)),
        App("Messenger", Regex("""^received_\d+""", RegexOption.IGNORE_CASE)),
        App("Snapchat", Regex("""^Snapchat-\d+""", RegexOption.IGNORE_CASE)),
    )

    /**
     * The names cameras give: DSC_000385.JPG (Nikon, DSC0… Sony, DSCF… Fujifilm), Samsung's 20250928_211937_016.jpg,
     * IMG_20240101_120000.jpg, Pixel's PXL_20240101_120000000.jpg, VID_20240101_120000.mp4, MOV_0012.mp4, CAM00012.jpg.
     */
    val CAMERA_NAMES: List<Regex> = listOf(
        Regex("""^DSC[F_]?\d{3,}""", RegexOption.IGNORE_CASE),
        Regex("""^\d{8}_\d{6}"""),
        Regex("""^IMG_\d{8}""", RegexOption.IGNORE_CASE),
        Regex("""^PXL_\d{8}""", RegexOption.IGNORE_CASE),
        Regex("""^VID_\d{8}""", RegexOption.IGNORE_CASE),
        Regex("""^MOV_\d+""", RegexOption.IGNORE_CASE),
        Regex("""^CAM_?\d+""", RegexOption.IGNORE_CASE),
    )

    /** The folder cameras save into ("DCIM/Camera"). */
    private const val CAMERA_FOLDER = "camera"

    /** The apps the table knows, as they are spelled: a folder of one of them is named so whatever its case. */
    private val APPS: List<String> = listOf("WhatsApp") + MESSAGING.map { it.app }

    fun isScreenshotName(fileName: String): Boolean =
        SCREENSHOT.matches(fileName.substringBeforeLast('.')) || SCREEN_RECORDING.containsMatchIn(fileName)

    fun isScreenshotFolder(folder: String): Boolean = folder.lowercase().let { name -> SCREENSHOT_FOLDERS.any { it in name } }

    fun isWhatsAppName(fileName: String): Boolean = WHATSAPP.containsMatchIn(fileName)

    /** Any folder with WhatsApp in its name ("WhatsApp Images", "com.whatsapp"). */
    fun isWhatsAppFolder(folder: String): Boolean = "whatsapp" in folder.lowercase()

    /**
     * The messaging app [fileName] or one of [folders] names: by the name first, then a folder named as the app is, or
     * starting with its name and a space ("Telegram Images").
     */
    fun messagingApp(fileName: String, folders: List<String>): String? =
        MESSAGING.firstOrNull { it.names.containsMatchIn(fileName) }?.app
            ?: MESSAGING.firstOrNull { app ->
                folders.any { it.trim().equals(app.app, ignoreCase = true) || it.trim().startsWith(app.app + " ", ignoreCase = true) }
            }?.app

    fun isCameraFolder(folder: String): Boolean = folder.trim().equals(CAMERA_FOLDER, ignoreCase = true)

    fun isCameraName(fileName: String): Boolean = CAMERA_NAMES.any { it.containsMatchIn(fileName) }

    /** How the table spells the app [name] is, ignoring case ("whatsapp" is "WhatsApp"); null for an app it doesn't know. */
    fun appSpelling(name: String): String? = APPS.firstOrNull { it.equals(name, ignoreCase = true) }
}
