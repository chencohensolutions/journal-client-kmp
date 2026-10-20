package com.chencohensolution.journal.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.provider.Settings
import java.net.URLDecoder
import java.net.URLEncoder
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.compose.ui.unit.IntOffset
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.chencohensolution.journal.R
import com.chencohensolution.journal.backup.BackupActivity
import com.chencohensolution.journal.backup.BackupController
import androidx.activity.result.IntentSenderRequest
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.server.KeptOriginals
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.server.ServerPreviews
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.storage.FreeUpActivity
import com.chencohensolution.journal.storage.FreeUpController
import com.chencohensolution.journal.storage.PhoneStorage
import com.chencohensolution.journal.work.FreeUpWorker
import com.chencohensolution.journal.work.currentWifiName
import com.chencohensolution.journal.library.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.chencohensolution.journal.stories.CropKind
import com.chencohensolution.journal.stories.PhotoCrops
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryFilter
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.settings.SettingsController

private val PHOTO_PERMISSIONS = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    // Read-only: without it MediaStore hides where photos were taken, which the Moments tab groups by.
    Manifest.permission.ACCESS_MEDIA_LOCATION,
)

/** Library screens, as a back stack. Each is encoded as a string so the stack survives rotation. */
private sealed interface Route {
    data object Journal : Route
    data object AllPhotos : Route
    data class Story(val id: String) : Route
    data class Group(val storyId: String, val groupId: String) : Route
    data object Tags : Route
    data class Tag(val name: String) : Route
    data object Settings : Route
    data object FreeUp : Route

    /** The crop screen for one photo, opened on [kind]. */
    data class Crop(val itemId: String, val kind: CropKind) : Route

    /**
     * Opened from a group ([storyId] and [groupId]), a story ([storyId]), a tag ([tag]), one row of the Moments tab
     * ([moment], its [com.chencohensolution.journal.stories.FeedSection.key]), or All photos with [filter].
     */
    data class Viewer(
        val storyId: String?,
        val groupId: String?,
        val moment: String?,
        val filter: LibraryFilter,
        val itemId: String,
        val tag: String? = null,
    ) : Route

    // Tags are free text and moment keys are item ids, so they are URL-encoded to keep "|" out.
    fun encode(): String = when (this) {
        Journal -> "journal"
        AllPhotos -> "all"
        is Story -> "story|$id"
        is Group -> "group|$storyId|$groupId"
        Tags -> "tags"
        is Tag -> "tag|${URLEncoder.encode(name, "UTF-8")}"
        Settings -> "settings"
        FreeUp -> "free-up"
        is Crop -> "crop|$kind|$itemId"
        is Viewer -> "viewer|${storyId.orEmpty()}|${groupId.orEmpty()}|${moment?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()}|$filter|${tag?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()}|$itemId"
    }

    companion object {
        fun decode(value: String): Route {
            val parts = value.split("|")
            return when (parts[0]) {
                "all" -> AllPhotos
                "story" -> Story(parts[1])
                "group" -> Group(parts[1], parts[2])
                "tags" -> Tags
                "tag" -> Tag(URLDecoder.decode(parts[1], "UTF-8"))
                "settings" -> Settings
                "free-up" -> FreeUp
                "crop" -> Crop(parts.drop(2).joinToString("|"), CropKind.valueOf(parts[1]))
                "viewer" -> Viewer(
                    storyId = parts[1].ifEmpty { null },
                    groupId = parts[2].ifEmpty { null },
                    moment = parts[3].ifEmpty { null }?.let { URLDecoder.decode(it, "UTF-8") },
                    filter = LibraryFilter.valueOf(parts[4]),
                    tag = parts[5].ifEmpty { null }?.let { URLDecoder.decode(it, "UTF-8") },
                    itemId = parts.drop(6).joinToString("|"),
                )
                else -> Journal
            }
        }
    }
}

private val BackStackSaver = listSaver<List<Route>, String>(
    save = { stack -> stack.map(Route::encode) },
    restore = { saved -> saved.map(Route::decode) },
)

/** Everything after enrollment: photo access, then the journal and its screens. */
@Composable
fun LibraryApp(
    journal: JournalController,
    library: LibraryController,
    stories: StoriesController,
    settings: SettingsController,
    backup: BackupController,
    server: ServerFiles,
    freeUp: FreeUpController,
    onRegisterAgain: () -> Unit,
) = CompositionLocalProvider(LocalServerPreviews provides server.previews, LocalPhoneStorage provides server.storage) {
    LibraryScreens(journal, library, stories, settings, backup, server, freeUp, onRegisterAgain)
}

/** The longer side of the frame kept for a video that leaves the phone. */
private const val VIDEO_POSTER_SIDE = 1920

/**
 * The server's copies: its list of photos, their previews kept here, originals downloaded and kept, and what is kept
 * on this phone in all (previews, originals and videos under one limit).
 */
class ServerFiles(val library: ServerLibrary, val previews: ServerPreviews, val originals: KeptOriginals, val storage: PhoneStorage)

@Composable
private fun LibraryScreens(
    journal: JournalController,
    library: LibraryController,
    stories: StoriesController,
    settings: SettingsController,
    backup: BackupController,
    server: ServerFiles,
    freeUp: FreeUpController,
    onRegisterAgain: () -> Unit,
) {
    val journalState by journal.state.collectAsStateWithLifecycle()
    val freeUpState by freeUp.state.collectAsStateWithLifecycle()
    val backupState by backup.state.collectAsStateWithLifecycle()
    val serverUsage by server.library.usage.collectAsStateWithLifecycle()
    val backupRunning = (backupState.activity as? BackupActivity.Running)?.progress
    val settingsState by settings.state.collectAsStateWithLifecycle()
    val libraryState by library.state.collectAsStateWithLifecycle()
    val storiesState by stories.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    LaunchedEffect(Unit) { journal.connect() }
    // Picks up photos taken and permission changes made while the app was in the background.
    LifecycleResumeEffect(Unit) {
        library.refresh()
        onPauseOrDispose {}
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        library.onAccessRequested()
    }
    // Photo access given before photo locations were asked for with it: ask for them once.
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { library.refresh() }
    var locationAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(libraryState.access) {
        val canRead = libraryState.access == PhotoAccess.FULL || libraryState.access == PhotoAccess.LIMITED
        val granted = activity?.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (canRead && !granted && !locationAsked) {
            locationAsked = true
            locationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }
    val requestAccess: () -> Unit = {
        // After two denials Android stops showing the prompt; only the app's settings page can grant access.
        val blocked = library.accessRequested &&
            libraryState.access == PhotoAccess.DENIED &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES) == false
        if (blocked && activity != null) {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
            )
        } else {
            permissionLauncher.launch(PHOTO_PERMISSIONS)
        }
    }

    // Backup: notifications for its progress, and location for "only on these Wi-Fi networks".
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    var backgroundLocation by remember { mutableStateOf(granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }
    LifecycleResumeEffect(Unit) {
        backgroundLocation = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        onPauseOrDispose {}
    }
    val addCurrentNetwork: () -> Unit = {
        scope.launch {
            val name = currentWifiName(context)
            if (name != null) backup.addWifiNetwork(name) else Toast.makeText(context, R.string.settings_backup_no_wifi, Toast.LENGTH_SHORT).show()
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val networkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) addCurrentNetwork()
    }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { backgroundLocation = it }

    // "Delete from this phone": Android's own prompt moves a library photo to the gallery's trash.
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { library.refresh() }
    val downloadOriginal: suspend (MediaItem, (Float) -> Unit) -> Boolean = { item, onProgress ->
        val sha = item.sha256
        sha != null && server.originals.download(sha, item.fileName ?: sha, onProgress)
    }
    val deleteFromPhone: suspend (MediaItem) -> RemovalOutcome = remove@{ item ->
        val sha = item.sha256
        if (sha == null || item.backup != BackupStatus.BackedUp) return@remove RemovalOutcome.NOT_ON_SERVER
        // Never without the server confirming it has these bytes, and a preview kept to show instead.
        when (server.library.confirms(sha)) {
            ServerLibrary.Confirmation.UNREACHABLE -> return@remove RemovalOutcome.UNREACHABLE
            ServerLibrary.Confirmation.MISSING -> {
                backup.uploadAgain(item)
                return@remove RemovalOutcome.NOT_ON_SERVER
            }
            ServerLibrary.Confirmation.HAS_IT -> Unit
        }
        val previewKept = if (item.type == MediaType.VIDEO) {
            // Keep a frame of it, made here from the video itself (the server makes posters too, once online).
            withContext(Dispatchers.IO) { videoPoster(context.contentResolver, item, VIDEO_POSTER_SIDE) }
                ?.let { (bytes, width, height) -> server.previews.keepPoster(sha, bytes, width, height); true } ?: false
        } else {
            server.previews.keepForViewing(sha)
        }
        if (!previewKept) return@remove RemovalOutcome.NO_PREVIEW
        if (!item.isOnDevice) {
            server.originals.delete(sha)
            RemovalOutcome.DELETED
        } else {
            trashLauncher.launch(IntentSenderRequest.Builder(moveToTrashRequest(context, item)).build())
            RemovalOutcome.ASKED
        }
    }
    // "Free up space": checked files that wait go to the trash with one system prompt, shown when a run has ended (the
    // notification at its end opens the app), or from Settings.
    val freeUpTrashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        freeUp.confirmationDone()
        FreeUpWorker.dismissConfirmation(context)
        library.refresh()
    }
    val moveWaitingToTrash: () -> Unit = {
        scope.launch {
            val items = freeUp.waitingItems()
            if (items.isEmpty()) {
                freeUp.confirmationDone()
            } else {
                freeUpTrashLauncher.launch(IntentSenderRequest.Builder(moveToTrashRequest(context, items)).build())
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        freeUp.refreshPlan()
        onPauseOrDispose {}
    }
    LaunchedEffect(freeUpState.needsConfirmation) { if (freeUpState.needsConfirmation) moveWaitingToTrash() }
    val photoTrash = remember { AndroidPhotoTrash(context) }
    var canTrashWithoutAsking by remember { mutableStateOf(photoTrash.canTrashWithoutAsking()) }
    LifecycleResumeEffect(Unit) {
        canTrashWithoutAsking = photoTrash.canTrashWithoutAsking()
        onPauseOrDispose {}
    }
    val startFreeUp: () -> Unit = {
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        freeUp.start()
    }
    // Back from the system page for "Media management" access, given or not: the run starts either way.
    val manageMediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canTrashWithoutAsking = photoTrash.canTrashWithoutAsking()
        startFreeUp()
    }
    var previewBytes by remember { mutableStateOf<Long?>(null) }
    // Set once navigation exists, below.
    var openFreeUp: () -> Unit = {}
    val storageActions = StorageActions(
        setPreviewsEnabled = settings::setPreviewsEnabled,
        setPreviewFormat = settings::setPreviewFormat,
        setPreviewQuality = settings::setPreviewQuality,
        setPreviewLimit = settings::setPreviewCacheGigabytes,
        clearPreviews = {
            scope.launch {
                server.storage.clear()
                previewBytes = server.storage.usedBytes()
            }
        },
        setRule = freeUp::setRule,
        freeUp = startFreeUp,
        allowTrashWithoutAsking = { manageMediaLauncher.launch(manageMediaSettingsIntent(context)) },
        moveWaitingToTrash = moveWaitingToTrash,
        toggleSource = freeUp::toggleSource,
        openFreeUp = { openFreeUp() },
    )
    val backupActions = BackupActions(
        setEnabled = { on ->
            if (on && !granted(Manifest.permission.POST_NOTIFICATIONS)) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            backup.setEnabled(on)
        },
        toggleSource = backup::toggleSource,
        setWifiOnly = backup::setWifiOnly,
        setChargingOnly = backup::setChargingOnly,
        setOrder = backup::setOrder,
        addCurrentNetwork = {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                addCurrentNetwork()
            } else {
                networkLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        },
        removeNetwork = backup::removeWifiNetwork,
        allowBackgroundLocation = { backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
        backUpNow = backup::backUpNow,
    )

    if (libraryState.access == PhotoAccess.NOT_DETERMINED) {
        PhotoAccessScreen(onAllow = requestAccess, onNotNow = library::skipAccess)
        return
    }

    var stack by rememberSaveable(stateSaver = BackStackSaver) { mutableStateOf(listOf<Route>(Route.Journal)) }
    // The photo the viewer shows, for the screen under it: that screen scrolls its tile into view to land on.
    var landing by remember { mutableStateOf<Landing?>(null) }
    val push: (Route) -> Unit = {
        landing = null
        stack = stack + it
    }
    val pop: () -> Unit = { stack = stack.dropLast(1) }
    openFreeUp = { push(Route.FreeUp) }

    // The library follows the system theme; the viewer is always dark, like a photo app. Each screen gets its own
    // theme so the viewer turns dark with its transition, not the screen it opens from.
    val systemDark = isSystemInDarkTheme()
    SystemBars(activity as? ComponentActivity, stack.last().let { it is Route.Viewer || it is Route.Crop } || systemDark)
    // Each entry keeps its saveable state (scroll positions among it) while it is under the top of the stack.
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavDisplay(
                backStack = stack.mapIndexed(::StackEntry),
                onBack = pop,
                sharedTransitionScope = this,
                transitionSpec = { sharedAxis(forward = true) },
                popTransitionSpec = { sharedAxis(forward = false) },
                predictivePopTransitionSpec = { sharedAxis(forward = false) },
                entryProvider = { entry ->
                    val opensFromTile = entry.route is Route.Viewer || entry.route is Route.Story || entry.route is Route.Group
                    NavEntry(entry, contentKey = entry.contentKey, metadata = if (opensFromTile) FadeAroundSharedElements else emptyMap()) {
                        val route = entry.route
                        val landingHere = landing?.takeIf { it.depth == entry.depth }?.itemId
                        LibraryTheme(dark = route is Route.Viewer || route is Route.Crop || systemDark) {
                            when (route) {
                                Route.Journal -> JournalHomeScreen(
                                    library = libraryState,
                                    stories = storiesState,
                                    health = journalState.health,
                                    actions = HomeActions(
                                        openItem = { section, item -> push(Route.Viewer(null, null, section.key, LibraryFilter.ALL, item.id)) },
                                        openTags = { push(Route.Tags) },
                                        openStory = { push(Route.Story(it)) },
                                        createStory = { title, key -> stories.createStoryFromFeed(title, key)?.let { push(Route.Story(it)) } },
                                        toggleSource = settings::toggleFeedSource,
                                        dismissTrip = settings::dismissTrip,
                                        openAllPhotos = { push(Route.AllPhotos) },
                                        openSettings = { push(Route.Settings) },
                                        allowAccess = requestAccess,
                                        changeSelection = { permissionLauncher.launch(PHOTO_PERMISSIONS) },
                                        registerAgain = onRegisterAgain,
                                        setCaption = { itemId, text -> stories.setCaption(itemId, text) },
                                        crop = { push(Route.Crop(it, CropKind.MOMENTS)) },
                                        merge = stories::mergeInFeed,
                                        takeOutOfSet = stories::takeOutOfSet,
                                        turnSet = stories::turnSet,
                                        toggleStory = { storyId, itemId, add ->
                                            if (add) stories.addItems(storyId, listOf(itemId)) else stories.removeItems(storyId, listOf(itemId))
                                        },
                                        createStoryWith = { title, itemId -> stories.createStory(title, listOf(itemId)) },
                                        toggleTag = { itemId, tag, add ->
                                            if (add) stories.addTag(listOf(itemId), tag) else stories.removeTag(listOf(itemId), tag)
                                        },
                                    ),
                                    landing = landingHere,
                                    backupRunning = backupRunning,
                                )
                                Route.AllPhotos -> {
                                    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
                                    AllPhotosScreen(
                                        library = libraryState,
                                        health = journalState.health,
                                        filter = filter,
                                        onFilter = { filter = it },
                                        onOpenItem = { push(Route.Viewer(null, null, null, filter, it.id)) },
                                        onBack = pop,
                                        landing = landingHere,
                                        backupRunning = backupRunning,
                                    )
                                }
                                is Route.Story -> StoryScreen(
                                    story = storiesState.story(route.id),
                                    neighborDays = storiesState.neighborDays(route.id),
                                    health = journalState.health,
                                    actions = StoryActions(
                                        openItem = { push(Route.Viewer(route.id, null, null, LibraryFilter.ALL, it.id)) },
                                        openGroup = { push(Route.Group(route.id, it.id)) },
                                        addDay = { stories.addDay(route.id, it) },
                                        rename = { stories.rename(route.id, it) },
                                        delete = {
                                            stories.delete(route.id)
                                            pop()
                                        },
                                        setIntro = { stories.setIntro(route.id, it) },
                                        acceptSuggestion = { stories.acceptSuggestion(route.id, it) },
                                        groupTiles = { stories.groupTiles(route.id, it) },
                                        mergeTiles = { stories.mergeTiles(route.id, it) },
                                        restore = stories::restore,
                                        dismissSuggestion = { stories.dismissSuggestion(route.id, it) },
                                        ungroup = { stories.ungroup(route.id, it) },
                                        removeItems = { stories.removeItems(route.id, it) },
                                        setTileSize = { key, size -> stories.setTileSize(route.id, key, size) },
                                        setTileFocus = { key, focus, zoom -> stories.setTileFocus(route.id, key, focus.x, focus.y, zoom) },
                                        moveTile = { key, offset -> stories.moveTile(route.id, key, offset) },
                                        resetLayout = { stories.resetLayout(route.id) },
                                        resetGroups = { stories.resetGroups(route.id) },
                                        back = pop,
                                    ),
                                    landing = landingHere,
                                )
                                is Route.Group -> GroupScreen(
                                    story = storiesState.story(route.storyId),
                                    group = storiesState.story(route.storyId)?.group(route.groupId),
                                    actions = GroupActions(
                                        openItem = { push(Route.Viewer(route.storyId, route.groupId, null, LibraryFilter.ALL, it.id)) },
                                        openGroup = { push(Route.Group(route.storyId, it.id)) },
                                        rename = { stories.renameGroup(route.storyId, route.groupId, it) },
                                        setText = { stories.setGroupText(route.storyId, route.groupId, it) },
                                        ungroup = { stories.ungroup(route.storyId, route.groupId) },
                                        groupTiles = { stories.groupTiles(route.storyId, it, parentId = route.groupId) },
                                        mergeTiles = { stories.mergeTiles(route.storyId, it, parentId = route.groupId) },
                                        restore = stories::restore,
                                        takeOut = { stories.removeFromGroup(route.storyId, route.groupId, it) },
                                        acceptSuggestion = { stories.acceptGroupSuggestion(route.storyId, route.groupId, it) },
                                        dismissSuggestion = { stories.dismissSuggestion(route.storyId, it) },
                                        back = pop,
                                    ),
                                    landing = landingHere,
                                )
                                Route.Settings -> {
                                    // Asked on open, and again when a backup run ends, so it follows the uploads.
                                    LaunchedEffect(backupState.activity is BackupActivity.Running) {
                                        if (backupState.activity !is BackupActivity.Running) server.library.refreshUsage()
                                    }
                                    // Counted on open, and again (after trimming) when the limit changes.
                                    LaunchedEffect(settingsState.previewCacheGigabytes) {
                                        server.storage.trimToLimit()
                                        previewBytes = server.storage.usedBytes()
                                    }
                                    SettingsScreen(
                                        settings = settingsState,
                                        actions = SettingsActions(
                                            setGroupingMode = settings::setGroupingMode,
                                            setGapHours = settings::setGapHours,
                                            setSimilarityPercent = settings::setSimilarityPercent,
                                            resetSimilarity = settings::resetSimilarity,
                                            back = pop,
                                        ),
                                        backup = BackupUi(backupState, libraryState.notBackedUpCount, backgroundLocation, serverUsage),
                                        backupActions = backupActions,
                                        storage = StorageUi(previewBytes, settingsState.previewCacheGigabytes, freeUpState, canTrashWithoutAsking, settingsState.previewOptions),
                                        storageActions = storageActions,
                                    )
                                }
                                Route.FreeUp -> {
                                    LaunchedEffect(Unit) { freeUp.refreshPlan() }
                                    FreeUpScreen(
                                        ui = StorageUi(previewBytes, settingsState.previewCacheGigabytes, freeUpState, canTrashWithoutAsking, settingsState.previewOptions),
                                        actions = storageActions,
                                        back = pop,
                                    )
                                }
                                Route.Tags -> TagsScreen(storiesState, onOpenTag = { push(Route.Tag(it)) }, onBack = pop)
                                is Route.Tag -> TagScreen(
                                    tag = route.name,
                                    stories = storiesState,
                                    onOpenItem = { push(Route.Viewer(null, null, null, LibraryFilter.ALL, it.id, tag = route.name)) },
                                    onBack = pop,
                                    landing = landingHere,
                                )
                                is Route.Viewer -> ViewerScreen(
                                    // Fixed when opened: tagging or grouping a photo must not pull it out from under the viewer.
                                    items = rememberSaveable(route.encode()) { viewerItems(libraryState, storiesState, route).map { it.id } }
                                        .mapNotNull { storiesState.itemsById[it] },
                                    startId = route.itemId,
                                    stories = storiesState,
                                    openedFrom = route.storyId?.let(storiesState::story),
                                    group = route.groupId?.let { route.storyId?.let(storiesState::story)?.group(it) },
                                    actions = ViewerActions(
                                        back = pop,
                                        openGroup = { groupId -> route.storyId?.let { push(Route.Group(it, groupId)) } },
                                        setCover = { itemId -> if (route.storyId != null && route.groupId != null) stories.setCover(route.storyId, route.groupId, itemId) },
                                        removeFromGroup = { itemId ->
                                            if (route.storyId != null && route.groupId != null) stories.removeFromGroup(route.storyId, route.groupId, listOf(itemId))
                                        },
                                        toggleStory = { storyId, itemId, add ->
                                            if (add) stories.addItems(storyId, listOf(itemId)) else stories.removeItems(storyId, listOf(itemId))
                                        },
                                        createStory = { title, itemId -> stories.createStory(title, listOf(itemId)) },
                                        setCaption = { itemId, text -> stories.setCaption(itemId, text) },
                                        toggleTag = { itemId, tag, add ->
                                            if (add) stories.addTag(listOf(itemId), tag) else stories.removeTag(listOf(itemId), tag)
                                        },
                                        showing = { landing = Landing(entry.depth - 1, it) },
                                        crop = { itemId, kind -> push(Route.Crop(itemId, kind)) },
                                        downloadOriginal = downloadOriginal,
                                        deleteFromPhone = deleteFromPhone,
                                    ),
                                )
                                is Route.Crop -> {
                                    val item = storiesState.itemsById[route.itemId]
                                    if (item == null) {
                                        // Deleted from the device while open.
                                        LaunchedEffect(Unit) { pop() }
                                    } else {
                                        CropEditor(
                                            item = item,
                                            saved = storiesState.note(item.id)?.crops ?: PhotoCrops(),
                                            startKind = route.kind,
                                            onDone = { crops ->
                                                stories.setCrops(item.id, crops)
                                                pop()
                                            },
                                            onClose = pop,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

/** The photo the viewer shows, for the entry at [depth] under it. */
private data class Landing(val depth: Int, val itemId: String)

/** A back stack entry; its depth tells apart a group opened twice, from its parent and from the viewer. */
private data class StackEntry(val depth: Int, val route: Route) {
    val contentKey get() = "$depth|${route.encode()}"
}

/** Material's shared X axis: the screen opened slides in from the side it lies on while the two crossfade. */
private fun sharedAxis(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    val slide = tween<IntOffset>(300, easing = FastOutSlowInEasing)
    return (slideInHorizontally(slide) { direction * it / 4 } + fadeIn(tween(210, delayMillis = 90))) togetherWith
        (slideOutHorizontally(slide) { -direction * it / 4 } + fadeOut(tween(90)))
}

/**
 * The items the viewer swipes through: the group or story it was opened from (where a group is one stop, its cover),
 * the row of the Moments tab (as shown there), or everything in the All photos filter.
 */
private fun viewerItems(library: LibraryState, stories: StoriesState, route: Route.Viewer): List<MediaItem> = when {
    route.tag != null -> stories.tagged(route.tag).flatMap { it.items }
    route.storyId != null && route.groupId != null -> stories.story(route.storyId)?.group(route.groupId)?.viewerItems.orEmpty()
    route.storyId != null -> stories.story(route.storyId)?.viewerItems.orEmpty()
    route.moment != null -> stories.feed.section(route.moment)?.items.orEmpty()
    else -> library.days(route.filter).flatMap { it.items }
}

/** Status and navigation bar icons that read on the library: light over dark screens, dark over light ones. */
@Composable
private fun SystemBars(activity: ComponentActivity?, dark: Boolean) {
    DisposableEffect(activity, dark) {
        val style = if (dark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { activity?.enableEdgeToEdge() }
    }
}
