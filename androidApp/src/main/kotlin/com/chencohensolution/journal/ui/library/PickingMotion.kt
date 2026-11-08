package com.chencohensolution.journal.ui.library

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
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

/** A row of the feed or the storyline coming into the list: it fades in while the others make room. */
internal val itemIn: FiniteAnimationSpec<Float> = tween(MOTION_MS, easing = FastOutSlowInEasing)
internal val itemOut: FiniteAnimationSpec<Float> = tween(MOTION_MS - 50, easing = FastOutSlowInEasing)
internal val itemMove: FiniteAnimationSpec<IntOffset> = tween(MOTION_MS, easing = FastOutSlowInEasing)

/** A list item that fades in and out and slides into place as rows around it come and go. */
internal fun LazyItemScope.animatedRow(): Modifier = Modifier.animateItem(fadeInSpec = itemIn, placementSpec = itemMove, fadeOutSpec = itemOut)

/** A bar coming up from the bottom: slides up a little and fades in. */
internal val barIn: EnterTransition = slideInVertically(motion()) { it / 2 } + fadeIn(motion())
internal val barOut: ExitTransition = slideOutVertically(motion()) { it / 2 } + fadeOut(motion())

/** The bottom tabs and the picking bar trading places: the old one slides down and fades as the new one comes up. */
internal fun <S> AnimatedContentTransitionScope<S>.barSwap(): ContentTransform =
    (barIn togetherWith barOut).using(SizeTransform(clip = false) { _, _ -> tween<IntSize>(MOTION_MS, easing = FastOutSlowInEasing) })

/** Something in a row appearing or leaving (a pill, the bar's Write · Crop · Tag): grows open and fades. */
internal val growIn: EnterTransition = expandVertically(motion()) + fadeIn(motion())
internal val shrinkOut: ExitTransition = shrinkVertically(motion()) + fadeOut(motion())

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
