package com.chencohensolution.journal.ui.library

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Keys of the elements shared between screens: a photo and the viewer. The same keys as the iOS zoom transitions. */
internal object SharedKey {
    fun item(id: String) = "item|$id"
}

/** Set by the library around its screens; null in previews and screenshot tests, where nothing is shared. */
internal val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The same element on two screens, such as a photo tile and the viewer's photo: it moves from one to the other. */
@Composable
internal fun Modifier.shared(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val screen = LocalNavAnimatedContentScope.current
    return with(shared) { this@shared.sharedElement(rememberSharedContentState(key), screen) }
}

/**
 * Screens that open out of a tile (the viewer) fade around the shared element instead of sliding,
 * when opened and closed, including while a back gesture scrubs it.
 */
internal val FadeAroundSharedElements: Map<String, Any> =
    NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() } +
        NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() } +
        NavDisplay.predictivePopTransitionSpec { fadeIn() togetherWith fadeOut() }

/**
 * Scrolls the row holding [itemId] into view when it changes, unless it is already in view: the screen under the viewer
 * does this so the photo the viewer closes on has its tile to land on. Every screen lists its photo rows last, after a
 * header that varies, so [rowOf] counts them among the last [rows] items; it returns -1 for a photo the screen lacks.
 * A row that comes only later (Moments reading down to an old photo's row) is waited for, up to [ROW_WAIT_MILLIS].
 */
@Composable
internal fun LazyListState.LandOn(itemId: String?, rows: Int, rowOf: (String) -> Int) = LandOn(
    itemId, rows, rowOf,
    total = { layoutInfo.totalItemsCount },
    inView = { index -> layoutInfo.visibleItemsInfo.any { it.index == index } },
    scrollTo = { scrollToItem(it) },
)

/** [LazyListState.LandOn] for a grid. */
@Composable
internal fun LazyGridState.LandOn(itemId: String?, rows: Int, rowOf: (String) -> Int) = LandOn(
    itemId, rows, rowOf,
    total = { layoutInfo.totalItemsCount },
    inView = { index -> layoutInfo.visibleItemsInfo.any { it.index == index } },
    scrollTo = { scrollToItem(it) },
)

@Composable
private fun LandOn(
    itemId: String?,
    rows: Int,
    rowOf: (String) -> Int,
    total: () -> Int,
    inView: (Int) -> Boolean,
    scrollTo: suspend (Int) -> Unit,
) {
    // The latest rows: a photo's row may come only after it is asked for (Moments reads down to it, step 6f).
    val currentRows = rememberUpdatedState(rows)
    val currentRowOf = rememberUpdatedState(rowOf)
    LaunchedEffect(itemId) {
        val id = itemId ?: return@LaunchedEffect
        val row = withTimeoutOrNull(ROW_WAIT_MILLIS) {
            snapshotFlow { currentRows.value to currentRowOf.value(id) }.first { it.second >= 0 }
        } ?: return@LaunchedEffect
        // A screen coming back is laid out a frame after it is composed.
        snapshotFlow(total).first { it >= row.first }
        val index = total() - row.first + row.second
        if (!inView(index)) scrollTo(index)
    }
}

/**
 * How long a screen waits for the row of the photo to land on to come (Moments reads down to an old photo's row first):
 * long enough for a read on a slow phone, short enough that a photo the screen never gets doesn't scroll it later.
 */
private const val ROW_WAIT_MILLIS = 15_000L
