package com.chencohensolution.journal.ui.library

import android.Manifest
import android.content.Intent
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.compose.ui.unit.IntOffset
import com.chencohensolution.journal.connection.JournalController
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

    /**
     * Opened from a group ([storyId] and [groupId]), a story ([storyId]), a tag ([tag]), one moment of the journal
     * ([moment], its key), or All photos with [filter].
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
    onRegisterAgain: () -> Unit,
) {
    val journalState by journal.state.collectAsStateWithLifecycle()
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

    if (libraryState.access == PhotoAccess.NOT_DETERMINED) {
        PhotoAccessScreen(onAllow = requestAccess, onNotNow = library::skipAccess)
        return
    }

    var stack by rememberSaveable(stateSaver = BackStackSaver) { mutableStateOf(listOf<Route>(Route.Journal)) }
    val push: (Route) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { stack = stack.dropLast(1) }

    // The library follows the system theme; the viewer is always dark, like a photo app. Each screen gets its own
    // theme so the viewer turns dark with its transition, not the screen it opens from.
    val systemDark = isSystemInDarkTheme()
    SystemBars(activity as? ComponentActivity, stack.last() is Route.Viewer || systemDark)
    // Each entry keeps its saveable state (scroll positions among it) while it is under the top of the stack.
    NavDisplay(
        backStack = stack.mapIndexed(::StackEntry),
        onBack = pop,
        transitionSpec = { sharedAxis(forward = true) },
        popTransitionSpec = { sharedAxis(forward = false) },
        predictivePopTransitionSpec = { sharedAxis(forward = false) },
        entryProvider = { entry ->
            NavEntry(entry, contentKey = entry.contentKey) {
                val route = entry.route
                LibraryTheme(dark = route is Route.Viewer || systemDark) {
                    when (route) {
                        Route.Journal -> JournalHomeScreen(
                            library = libraryState,
                            stories = storiesState,
                            health = journalState.health,
                            actions = HomeActions(
                                openItem = { moment, item -> push(Route.Viewer(null, null, moment.key, LibraryFilter.ALL, item.id)) },
                                openTags = { push(Route.Tags) },
                                openStory = { push(Route.Story(it)) },
                                createStory = { title, key -> stories.createStoryFromMoment(title, key)?.let { push(Route.Story(it)) } },
                                openAllPhotos = { push(Route.AllPhotos) },
                                openSettings = { push(Route.Settings) },
                                allowAccess = requestAccess,
                                changeSelection = { permissionLauncher.launch(PHOTO_PERMISSIONS) },
                                registerAgain = onRegisterAgain,
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
                        )
                        Route.Settings -> SettingsScreen(
                            settings = settingsState,
                            actions = SettingsActions(
                                setGroupingMode = settings::setGroupingMode,
                                setGapHours = settings::setGapHours,
                                setSimilarityPercent = settings::setSimilarityPercent,
                                resetSimilarity = settings::resetSimilarity,
                                back = pop,
                            ),
                        )
                        Route.Tags -> TagsScreen(storiesState, onOpenTag = { push(Route.Tag(it)) }, onBack = pop)
                        is Route.Tag -> TagScreen(
                            tag = route.name,
                            stories = storiesState,
                            onOpenItem = { push(Route.Viewer(null, null, null, LibraryFilter.ALL, it.id, tag = route.name)) },
                            onBack = pop,
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
                            ),
                        )
                    }
                }
            }
        },
    )
}

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
 * the moment of the journal (only its unsorted photos, as shown there), or everything in the All photos filter.
 */
private fun viewerItems(library: LibraryState, stories: StoriesState, route: Route.Viewer): List<MediaItem> = when {
    route.tag != null -> stories.tagged(route.tag).flatMap { it.items }
    route.storyId != null && route.groupId != null -> stories.story(route.storyId)?.group(route.groupId)?.viewerItems.orEmpty()
    route.storyId != null -> stories.story(route.storyId)?.viewerItems.orEmpty()
    route.moment != null -> stories.moment(route.moment)?.items.orEmpty()
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
