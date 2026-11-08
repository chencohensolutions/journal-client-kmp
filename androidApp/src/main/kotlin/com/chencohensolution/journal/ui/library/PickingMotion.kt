package com.chencohensolution.journal.ui.library

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

// Motion for Tucked away and picking (Nov 8, 2026): photos leaving and coming back, the picking bar, the check marks.
// One length and easing for all of it (Material's standard), so the pieces move together. Compose itself honours the
// system's "Remove animations" (animator duration scale 0 ends these at once).

/** How long the picking motion takes. */
internal const val MOTION_MS = 250

internal fun <T> motion(): FiniteAnimationSpec<T> = tween(MOTION_MS, easing = FastOutSlowInEasing)

// Photos leaving and coming back go one step at a time (asked Nov 8, 2026): leaving, the photo fades out in its place
// first, then the space closes; coming back, the space opens first, then the photo fades in.

/** How long a photo takes to fade out or in. */
internal const val FADE_MS = 200

/** How long its space takes to close or open. */
internal const val SPACE_MS = 250

private val spaceEasing = FastOutSlowInEasing

/**
 * Whether the latest change of a list's [keys] only took rows away (hiding, tucking away): then the others close the
 * space after the leaving rows faded; otherwise (rows added: revealing, bringing back) they open it at once.
 */
@Composable
internal fun rowsHiding(keys: List<String>): Boolean {
    val last = remember { arrayOf<Set<String>?>(null) }
    val hiding = remember { booleanArrayOf(false) }
    val now = remember(keys) { keys.toHashSet() }
    val before = last[0]
    if (before != null && before != now) hiding[0] = now.all { it in before }
    last[0] = now
    return hiding[0]
}

/**
 * A list item that comes and goes in two steps: leaving, it fades out in its place, then the rows around it close the
 * space; coming in, the rows make space first, then it fades in. [hiding]: the list's latest change only took rows
 * away ([rowsHiding]), so the others wait for the fade before closing the space.
 */
internal fun LazyItemScope.animatedRow(hiding: Boolean): Modifier =
    Modifier.animateItem(
        fadeInSpec = tween(FADE_MS, delayMillis = SPACE_MS, easing = LinearOutSlowInEasing),
        placementSpec = tween(SPACE_MS, delayMillis = if (hiding) FADE_MS else 0, easing = spaceEasing),
        fadeOutSpec = tween(FADE_MS, easing = FastOutLinearInEasing),
    )

/** A bar coming up from the bottom: slides up a little and fades in. */
internal val barIn: EnterTransition = slideInVertically(motion()) { it / 2 } + fadeIn(motion())
internal val barOut: ExitTransition = slideOutVertically(motion()) { it / 2 } + fadeOut(motion())

/** The bottom tabs and the picking bar trading places: the old one slides down and fades as the new one comes up. */
internal fun <S> AnimatedContentTransitionScope<S>.barSwap(): ContentTransform =
    (barIn togetherWith barOut).using(SizeTransform(clip = false) { _, _ -> tween<IntSize>(MOTION_MS, easing = FastOutSlowInEasing) })

/** Something in a row appearing or leaving (a pill, the bar's Write · Crop · Tag): grows open and fades. */
// One step at a time, as photos do: the space opens, then it fades in; it fades out, then the space closes.
internal val growIn: EnterTransition =
    expandVertically(tween(SPACE_MS, easing = spaceEasing)) + fadeIn(tween(FADE_MS, delayMillis = SPACE_MS, easing = LinearOutSlowInEasing))
internal val shrinkOut: ExitTransition =
    fadeOut(tween(FADE_MS, easing = FastOutLinearInEasing)) + shrinkVertically(tween(SPACE_MS, delayMillis = FADE_MS, easing = spaceEasing))

/** A check mark popping in and out: a soft spring, so it lands with a little bounce. */
private val checkSpring = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
internal val checkIn: EnterTransition = scaleIn(checkSpring, initialScale = 0.4f) + fadeIn(tween(MOTION_MS / 2))
internal val checkOut: ExitTransition = scaleOut(tween(MOTION_MS / 2), targetScale = 0.4f) + fadeOut(tween(MOTION_MS / 2))

/**
 * A picked photo draws in a little from its edges with rounded corners, and springs back out when unpicked; [selected]
 * is null when not picking.
 */
@Composable
internal fun Modifier.pickedInset(selected: Boolean?): Modifier {
    val on = selected == true
    val scale by animateFloatAsState(if (on) 0.92f else 1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow), label = "picked scale")
    val corner by animateDpAsState(if (on) 14.dp else 0.dp, motion(), label = "picked corner")
    // Untouched when not picked (nothing clipped, no layer), as before picking.
    if (scale == 1f && corner == 0.dp) return this
    val shape = RoundedCornerShape(corner)
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clip(shape)
}

/**
 * The check mark over a photo while picking ([selected] null when not): it scales in as picking starts and out as it
 * ends, keeping its last look while it leaves.
 */
@Composable
internal fun PickMark(selected: Boolean?, modifier: Modifier = Modifier, mark: @Composable (Boolean) -> Unit = { SelectMark(it) }) {
    val last = remember { booleanArrayOf(false) }
    if (selected != null) last[0] = selected
    AnimatedVisibility(selected != null, modifier, enter = checkIn, exit = checkOut, label = "pick mark") {
        mark(selected ?: last[0])
    }
}

/** A round check: filled with the accent and a check when [selected] (colour fading, check springing), else a ring. */
@Composable
internal fun CheckCircle(selected: Boolean, size: Dp, iconSize: Dp, unselected: Color, modifier: Modifier = Modifier) {
    val fill by animateColorAsState(if (selected) LibraryColors.Accent else unselected, motion(), label = "check fill")
    Box(
        modifier.size(size).background(fill, CircleShape).border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(selected, enter = checkIn, exit = checkOut, label = "check") {
            Icon(LibraryIcons.Check, null, tint = Color.White, modifier = Modifier.size(iconSize))
        }
    }
}

/** A count changing: the new number slides in from below when it grows, from above when it shrinks. */
internal fun <S> AnimatedContentTransitionScope<S>.countChange(up: Boolean): ContentTransform =
    ((slideInVertically(motion()) { if (up) it else -it } + fadeIn(motion())) togetherWith
        (slideOutVertically(motion()) { if (up) -it else it } + fadeOut(motion())))
        .using(SizeTransform(clip = false) { _, _ -> motion() })
