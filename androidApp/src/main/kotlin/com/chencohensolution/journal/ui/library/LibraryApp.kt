package com.chencohensolution.journal.ui.library

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.chencohensolution.journal.backup.BackupActivity
import com.chencohensolution.journal.backup.BackupController
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryFilter
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.server.KeptOriginals
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.server.ServerPreviews
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.stories.CropKind
import com.chencohensolution.journal.stories.PhotoCrops
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.storage.FreeUpController
import com.chencohensolution.journal.storage.PhoneStorage

/**
 * The server's copies: its list of photos, their previews kept here, originals downloaded and kept, and what is kept
 * on this phone in all (previews, originals and videos under one limit).
 */
class ServerFiles(val library: ServerLibrary, val previews: ServerPreviews, val originals: KeptOriginals, val storage: PhoneStorage)

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

/** The routing table: each [Route] on the back stack rendered as its screen, with the actions wired to the controllers. */
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
    val actions = libraryActions(library, libraryState, backup, server, freeUp, freeUpState, settings)

    if (libraryState.access == PhotoAccess.NOT_DETERMINED) {
        PhotoAccessScreen(onAllow = actions.requestAccess, onNotNow = library::skipAccess)
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
    actions.openFreeUp = { push(Route.FreeUp) }
    var previewBytes by actions.previewBytes
    // A function, not a value: each screen on the back stack keeps the content it was first given, so it has to read
    // the state when it draws.
    fun storageUi() = StorageUi(previewBytes, settingsState.previewCacheGigabytes, freeUpState, actions.canTrashWithoutAsking, settingsState.previewOptions)

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
                                        allowAccess = actions.requestAccess,
                                        changeSelection = actions.changeSelection,
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
                                        explore = ExploreActions(
                                            openPlace = { push(Route.Place(it)) },
                                            openYear = { year, month -> push(Route.Year(year, month)) },
                                            openMap = { push(Route.Map) },
                                        ),
                                    ),
                                    landing = landingHere,
                                    backupRunning = backupRunning,
                                )
                                is Route.Place -> {
                                    var year by rememberSaveable { mutableStateOf<Int?>(null) }
                                    PlaceScreen(
                                        town = route.town,
                                        library = libraryState,
                                        stories = storiesState,
                                        year = year,
                                        onYear = { year = it },
                                        onOpenItem = { scope, section, item -> push(Route.Viewer(null, null, section.key, LibraryFilter.ALL, item.id, scope = scope)) },
                                        onBack = pop,
                                        landing = landingHere,
                                    )
                                }
                                is Route.Year -> {
                                    var month by rememberSaveable { mutableStateOf(route.month) }
                                    YearScreen(
                                        year = route.year,
                                        library = libraryState,
                                        stories = storiesState,
                                        month = month,
                                        onMonth = { month = it },
                                        onOpenItem = { scope, section, item -> push(Route.Viewer(null, null, section.key, LibraryFilter.ALL, item.id, scope = scope)) },
                                        onBack = pop,
                                        landing = landingHere,
                                    )
                                }
                                Route.Map -> MapScreen(
                                    library = libraryState,
                                    stories = storiesState,
                                    actions = MapActions(
                                        back = pop,
                                        openPhotos = { scope, itemId ->
                                            val first = itemId ?: scopeItems(libraryState, storiesState, scope).firstOrNull()?.id
                                            if (first != null) push(Route.Viewer(null, null, null, LibraryFilter.ALL, first, scope = scope))
                                        },
                                    ),
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
                                            setShowPrivateMedia = settings::setShowPrivateMedia,
                                            setPrivateThresholdPercent = settings::setPrivateThresholdPercent,
                                            back = pop,
                                        ),
                                        backup = BackupUi(backupState, libraryState.notBackedUpCount, actions.backgroundLocation, serverUsage),
                                        backupActions = actions.backup,
                                        storage = storageUi(),
                                        storageActions = actions.storage,
                                    )
                                }
                                Route.FreeUp -> {
                                    LaunchedEffect(Unit) { freeUp.refreshPlan() }
                                    FreeUpScreen(ui = storageUi(), actions = actions.storage, back = pop)
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
                                        downloadOriginal = actions.downloadOriginal,
                                        deleteFromPhone = actions.deleteFromPhone,
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

/** Material's shared X axis: the screen opened slides in from the side it lies on while the two crossfade. */
private fun sharedAxis(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    val slide = tween<IntOffset>(300, easing = FastOutSlowInEasing)
    return (slideInHorizontally(slide) { direction * it / 4 } + fadeIn(tween(210, delayMillis = 90))) togetherWith
        (slideOutHorizontally(slide) { -direction * it / 4 } + fadeOut(tween(90)))
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
