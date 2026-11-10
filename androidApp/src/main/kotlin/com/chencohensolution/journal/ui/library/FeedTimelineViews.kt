package com.chencohensolution.journal.ui.library

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.stories.FeedTimeline
import com.chencohensolution.journal.stories.TimelineDay
import com.chencohensolution.journal.stories.TimelineMonth
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Going to a date in Moments: the month pill above the tabs and, tapped, the ruler it grows into (the month's days and
// a dial of months). The months and days are the shared FeedTimeline; these draw them and pass the finger on. As iOS's
// FeedTimelineViews.swift. See docs/screens/moments.md, "Going to a date".

/** "October 2026". */
internal val TimelineMonth.title: String
    get() = "${Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()).replaceFirstChar { it.titlecase() }} $year"

/** A light tick per day or month, a firmer one per year. */
private fun View.tick(timeline: FeedTimeline, from: Int, to: Int) {
    performHapticFeedback(if (timeline.crossesYear(from, to)) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.CLOCK_TICK)
}

/**
 * Above the tabs: the pill, or the ruler while [open]. [current] is the month at the top of the feed; [go] jumps the
 * feed to a day. Arrows and swipes step from the month they last went to, shown until the feed has settled there.
 *
 * The pill and the ruler are one card, laid out here on one timer ([MORPH_MILLIS]): its size goes from the pill's to
 * the ruler's with its bottom where it is, and the ruler rides at its top, uncovered downward as it grows. The month's
 * name is one text placed and scaled on the same timer, from the middle of the pill to the ruler's title, which is always
 * inside the card. Both are always composed: opening only starts the timer (building the ruler on the tap stalled
 * frames).
 */
@Composable
internal fun TimelineBar(
    timeline: FeedTimeline,
    current: Int?,
    /** The day of the row at the top of the feed: the pill says it with its month ("24 October 2026"). */
    day: LocalDay?,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    go: (TimelineDay) -> Unit,
) {
    // The month last gone to, shown until the feed is there: a month further down is read first, which can take more
    // than a moment, and the ruler showing the feed's old month meanwhile sent the dial back and forth (Oct 26, 2026).
    var sent by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(sent, current) {
        if (sent == null) return@LaunchedEffect
        if (current == sent) {
            sent = null
            return@LaunchedEffect
        }
        // In case the feed never gets there (the row went, or the feed was scrolled away meanwhile).
        delay(SENT_GIVE_UP_MILLIS)
        sent = null
    }
    val shown = (sent ?: current ?: 0).coerceIn(0, timeline.months.size - 1)
    val goToMonth = { month: Int ->
        sent = month
        go(timeline.landing(month))
    }
    val progress = remember { Animatable(if (open) 1f else 0f) }
    LaunchedEffect(open) { progress.animateTo(if (open) 1f else 0f, tween(MORPH_MILLIS, easing = FastOutSlowInEasing)) }
    val raised = LibraryColors.Raised
    // The day with its month while the pill is closed and the feed has settled; the month alone in the ruler.
    val month = timeline.months[shown]
    val name = day?.takeIf { sent == null && !open && it.year == month.year && it.month == month.month }
        ?.let { formatDate(it.toLocalDate(), "dMMMMy") } ?: month.title
    Layout(
        contents = listOf(
            // The card: its corners go from the pill's to the ruler's; drawn, so a frame doesn't recompose it.
            {
                Spacer(
                    Modifier.graphicsLayer {
                        val radius = lerp(PILL_RADIUS.toPx(), RULER_RADIUS.toPx(), progress.value)
                        shape = RoundedCornerShape(radius)
                        shadowElevation = 14.dp.toPx()
                        clip = true
                    }.drawBehind { drawRect(raised) },
                )
            },
            { TimelinePill(timeline, shown, name, Modifier.graphicsLayer { alpha = (1f - progress.value / 0.3f).coerceIn(0f, 1f) }, step = goToMonth, open = { onOpen(true) }) },
            {
                TimelinePanel(timeline, shown, onDay = { day ->
                    sent = shown
                    go(day)
                }, onMonth = goToMonth, onClose = { onOpen(false) })
            },
            { Text(name, color = LibraryColors.OnBackground, fontSize = RULER_TITLE_SP.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) { (cardContent, pillContent, panelContent, nameContent), constraints ->
        val p = progress.value
        val width = constraints.maxWidth
        val gutter = 8.dp.roundToPx()
        val pill = pillContent.first().measure(Constraints())
        val panelWidth = width - 2 * gutter
        val panel = panelContent.first().measure(Constraints.fixedWidth(panelWidth))
        val cardWidth = lerp(pill.width, panelWidth, p)
        val cardHeight = lerp(pill.height, panel.height, p)
        val card = cardContent.first().measure(Constraints.fixed(cardWidth, cardHeight))
        val title = nameContent.first().measure(Constraints())
        layout(width, cardHeight) {
            val cardX = (width - cardWidth) / 2
            card.place(cardX, 0)
            // The pill sits at the card's bottom, which stays put; the ruler rides at the card's top.
            val pillX = (width - pill.width) / 2
            val pillY = cardHeight - pill.height
            if (p < 1f) pill.place(pillX, pillY)
            val panelX = gutter
            val panelY = 0
            if (p > 0f) {
                val visible = androidx.compose.ui.geometry.Rect(
                    (cardX - panelX).toFloat(), 0f, (cardX - panelX + cardWidth).toFloat(), cardHeight.toFloat(),
                )
                val radius = lerp(PILL_RADIUS.toPx(), RULER_RADIUS.toPx(), p)
                panel.placeWithLayer(panelX, panelY) {
                    // In once the name is up in its row.
                    alpha = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    shape = WindowShape(visible, radius)
                    clip = true
                }
            }
            // The name: from the middle of the pill at its size there to the ruler's title, scaled from its top-left,
            // in a straight line on the screen. The card's bottom doesn't move, so the line is worked out from it:
            // where the name starts and ends above the bottom, all on the card's own progress. On that line it stays
            // inside the card, above the ruler's lines that the card uncovers under it.
            val small = PILL_TITLE_SP / RULER_TITLE_SP
            val scale = lerp(small, 1f, p)
            val padding = 16.dp.toPx()
            val fromX = pillX + pill.width / 2f - title.width * small / 2
            val toX = panelX + if (layoutDirection == LayoutDirection.Rtl) panelWidth - padding - title.width else padding
            val fromAboveBottom = pill.height / 2f + title.height * small / 2
            val toAboveBottom = panel.height - padding
            val aboveBottom = lerp(fromAboveBottom, toAboveBottom, p)
            title.placeWithLayer(lerp(fromX, toX, p).roundToInt(), (cardHeight - aboveBottom).roundToInt()) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

private const val MORPH_MILLIS = 360

/** How long the month gone to is shown when the feed doesn't get there. */
private const val SENT_GIVE_UP_MILLIS = 10_000L
private const val PILL_TITLE_SP = 15f
private const val RULER_TITLE_SP = 20f
private val PILL_RADIUS = 20.dp
private val RULER_RADIUS = 28.dp

/** The part of the ruler the card shows while it grows: [visible] in the ruler's coordinates, with the card's corners. */
private class WindowShape(private val visible: androidx.compose.ui.geometry.Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(visible, CornerRadius(radius)))
}

/**
 * "‹ October 2026 ›". The arrows go a month older or newer (held, they keep going); a swipe along the month's name
 * steps a month every 22 dp, toward the newer end being newer; a tap on it opens the ruler.
 */
@Composable
private fun TimelinePill(timeline: FeedTimeline, month: Int, name: String, modifier: Modifier, step: (Int) -> Unit, open: () -> Unit) {
    val view = LocalView.current
    val currentMonth by rememberUpdatedState(month)
    // The swipe outlives a composition (and the timeline, which is made again as rows are read): it calls the latest,
    // and isn't cut off when the timeline changes under the finger (Nov 10, 2026).
    val currentStep by rememberUpdatedState(step)
    val currentTimeline by rememberUpdatedState(timeline)
    val go = { to: Int ->
        if (to != currentMonth) {
            view.tick(currentTimeline, currentMonth, to)
            currentStep(to)
        }
    }
    val swipeStep = with(LocalDensity.current) { 22.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var swiping by remember { mutableStateOf(false) }
    Row(
        modifier
            .width(248.dp)
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimelineArrow(LibraryIcons.ChevronRight, stringResource(R.string.timeline_older), pointsBack = true, enabled = !timeline.isOldest(month)) {
            go(timeline.step(currentMonth, -1))
        }
        var start by remember { mutableStateOf(0) }
        var dragged by remember { mutableStateOf(0f) }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .alpha(if (swiping) 0.8f else 1f)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            swiping = true
                            start = currentMonth
                            dragged = 0f
                        },
                        onDragEnd = { swiping = false },
                        onDragCancel = { swiping = false },
                    ) { change, amount ->
                        change.consume()
                        dragged += if (rtl) -amount else amount
                        go(currentTimeline.step(start, (dragged / swipeStep).roundToInt()))
                    }
                }
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.timeline_open), onClick = open),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name,
                color = LibraryColors.OnBackground,
                fontSize = PILL_TITLE_SP.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                // Its place only: the bar draws the name, which moves between here and the ruler's title.
                modifier = Modifier.alpha(0f),
            )
        }
        TimelineArrow(LibraryIcons.ChevronRight, stringResource(R.string.timeline_newer), pointsBack = false, enabled = !timeline.isNewest(month)) {
            go(timeline.step(currentMonth, 1))
        }
    }
}

/** An arrow at an end of the pill: tapped it steps once; held, it steps after 0.4 s, then every 0.25 s, then faster. */
@Composable
private fun TimelineArrow(icon: ImageVector, label: String, pointsBack: Boolean, enabled: Boolean, onStep: () -> Unit) {
    val scope = rememberCoroutineScope()
    val currentStep by rememberUpdatedState(onStep)
    Box(
        Modifier
            .size(44.dp, 40.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .semantics { contentDescription = label }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    var repeated = false
                    val repeating: Job = scope.launch {
                        delay(400)
                        var interval = 250L
                        while (true) {
                            repeated = true
                            currentStep()
                            delay(interval)
                            interval = (interval - 30).coerceAtLeast(90)
                        }
                    }
                    val released = tryAwaitRelease()
                    repeating.cancel()
                    if (released && !repeated) currentStep()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(18.dp).rotate(if (pointsBack) 180f else 0f))
    }
}

/** What the pill grows into: the month and its counts, its days, and the dial of months (the bar draws its card). */
@Composable
private fun TimelinePanel(timeline: FeedTimeline, month: Int, onDay: (TimelineDay) -> Unit, onMonth: (Int) -> Unit, onClose: () -> Unit) {
    val shown = timeline.months[month]
    // Every touch on the ruler is the ruler's: one between its controls (the title, the day numbers, the gaps) used to
    // fall through to the scrim under it and fold it (Nov 10, 2026: "it collapses unexpectedly").
    Box(Modifier.fillMaxWidth().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Room at the end for the close button, pinned in the corner over it.
            Row(Modifier.padding(end = CLOSE_SIZE + CLOSE_INSET - 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        shown.title,
                        color = LibraryColors.OnBackground,
                        fontSize = RULER_TITLE_SP.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        // Its place only: the bar draws the name.
                        modifier = Modifier.alpha(0f),
                    )
                    Text(stringResource(R.string.timeline_counts, shown.count, shown.days.size), color = LibraryColors.Muted, fontSize = 13.sp)
                }
            }
            DayStrip(timeline, month, onDay)
            MonthDial(timeline, month, onMonth)
        }
        // In the top corner, as far from the top as from the side.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(CLOSE_INSET)
                .size(CLOSE_SIZE)
                .background(LibraryColors.OnBackground.copy(alpha = 0.1f), CircleShape)
                .clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(LibraryIcons.ChevronDown, stringResource(R.string.timeline_close), tint = LibraryColors.OnBackground, modifier = Modifier.size(18.dp))
        }
    }
}

private val CLOSE_SIZE = 36.dp
private val CLOSE_INSET = 12.dp

/** The days of a month in a line, those with photos lit. Tap one or slide along: the feed goes to the lit day nearest the finger. */
@Composable
private fun DayStrip(timeline: FeedTimeline, month: Int, onDay: (TimelineDay) -> Unit) {
    val view = LocalView.current
    val shown = timeline.months[month]
    var picked by remember(month) { mutableStateOf<Int?>(null) }
    val currentMonth by rememberUpdatedState(month)
    // The touch handler outlives a composition: it calls the latest (the feed's rows change as more are read, and an old
    // go() looked for the day in rows from before: sometimes nothing happened, Nov 10, 2026).
    val currentOnDay by rememberUpdatedState(onDay)
    val currentTimeline by rememberUpdatedState(timeline)
    val accent = LibraryColors.Accent
    val dim = LibraryColors.Muted.copy(alpha = 0.25f)
    val ring = LibraryColors.OnBackground
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val count = shown.length
    // A touch anywhere on the strip, its day numbers too (only the 14 dp line of dots took it, so a tap on a number did
    // nothing; Nov 10, 2026). The touch going down always goes, even to the day picked last (the feed may have moved
    // since); sliding goes on each new day.
    val pick = { x: Float, width: Float, always: Boolean ->
        val fromStart = if (rtl) width - x else x
        val day = (fromStart / (width / count)).toInt().coerceIn(0, count - 1) + 1
        currentTimeline.nearestDay(currentMonth, day)?.let { nearest ->
            if (always || nearest.day.day != picked) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                picked = nearest.day.day
                currentOnDay(nearest)
            }
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(32.dp).pointerInput(month) {
            awaitEachGesture {
                val down = awaitFirstDown()
                pick(down.position.x, size.width.toFloat(), true)
                down.consume()
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { change ->
                        if (change.pressed) pick(change.position.x, size.width.toFloat(), false)
                        change.consume()
                    }
                } while (event.changes.any { it.pressed })
            }
        },
    ) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val w = size.width / count
            for (day in 1..count) {
                val x = (if (rtl) count - day else day - 1) * w + w / 2
                val lit = shown.dayOn(day) != null
                val on = day == picked
                drawCircle(if (lit) accent else dim, radius = (if (on) 4.5f else 3f) * density, center = Offset(x, size.height / 2))
                if (on) drawCircle(ring, radius = 7.5f * density, center = Offset(x, size.height / 2), style = Stroke(1.5f * density))
            }
        }
        Row(Modifier.fillMaxWidth().align(Alignment.BottomStart)) {
            for (day in 1..count) {
                Text(
                    if (day == 1 || day % 5 == 0) "$day" else "",
                    color = LibraryColors.Muted,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The months oldest to newest along the reading direction, a bar each as tall as its photos; the one at the needle
 * is [month]. Dragged like a dial: it follows the finger, a flick keeps it turning, and it settles on a month.
 */
@Composable
private fun MonthDial(timeline: FeedTimeline, month: Int, onMonth: (Int) -> Unit) {
    val view = LocalView.current
    val count = timeline.months.size
    val scope = rememberCoroutineScope()
    // Where the dial is, in months from the oldest: the timeline's months are newest first.
    val at = remember { Animatable((count - 1 - month).toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val currentMonth by rememberUpdatedState(month)
    // As the day strip: the drag outlives a composition and calls the latest.
    val currentOnMonth by rememberUpdatedState(onMonth)
    LaunchedEffect(month) {
        if (!dragging && !at.isRunning) at.snapTo((count - 1 - month).toFloat())
    }
    val stepPx = with(LocalDensity.current) { 16.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val pick = { position: Float ->
        val to = count - 1 - position.roundToInt().coerceIn(0, count - 1)
        if (to != currentMonth) {
            view.tick(timeline, currentMonth, to)
            currentOnMonth(to)
        }
    }
    val peak = timeline.peak.coerceAtLeast(1).toFloat()
    val picked = LibraryColors.OnBackground
    val other = LibraryColors.Muted.copy(alpha = 0.6f)
    val needle = LibraryColors.Accent
    val labels = LibraryColors.Muted
    Box(Modifier.fillMaxWidth().height(66.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .pointerInput(count) {
                    val tracker = VelocityTracker()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        dragging = true
                        tracker.resetTracking()
                        val startAt = at.value
                        val startX = down.position.x
                        scope.launch { at.stop() }
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val moved = (change.position.x - startX) * if (rtl) -1 else 1
                            val position = (startAt - moved / stepPx).coerceIn(0f, (count - 1).toFloat())
                            scope.launch { at.snapTo(position) }
                            pick(position)
                            change.consume()
                        } while (event.changes.any { it.pressed })
                        // A flick goes on as far as it would have slid, then settles on a month.
                        val velocity = tracker.calculateVelocity().x * if (rtl) -1 else 1
                        val target = (at.value - velocity * 0.25f / stepPx).roundToInt().coerceIn(0, count - 1)
                        dragging = false
                        scope.launch { at.animateTo(target.toFloat(), tween(450)) }
                        pick(target.toFloat())
                    }
                },
        ) {
            val mid = size.width / 2
            val current = at.value
            val reach = (mid / stepPx).toInt() + 2
            val lo = (current.toInt() - reach).coerceAtLeast(0)
            val hi = (current.toInt() + reach).coerceAtMost(count - 1)
            for (i in lo..hi) {
                val shown = timeline.months[count - 1 - i]
                val isPicked = count - 1 - i == month
                val offset = (i - current) * stepPx
                val x = mid + if (rtl) -offset else offset
                val h = (6 + 40 * sqrt(shown.count / peak)) * density
                val w = (if (isPicked) 8f else 6f) * density
                // Fades toward the ends, as the dial turns away.
                val fade = (1f - abs(x - mid) / mid).coerceIn(0f, 1f)
                drawRoundRect(
                    (if (isPicked) picked else other).copy(alpha = (if (isPicked) 1f else 0.6f) * (0.25f + 0.75f * fade)),
                    topLeft = Offset(x - w / 2, size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w / 2),
                )
            }
            drawRect(needle, topLeft = Offset(mid - 1 * density, 0f), size = Size(2 * density, size.height))
        }
        // Years under January, as the dial has turned.
        BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp).align(Alignment.BottomStart)) {
            val density = LocalDensity.current.density
            val mid = constraints.maxWidth / 2f
            timeline.months.forEachIndexed { index, shown ->
                if (shown.month != 1) return@forEachIndexed
                val i = count - 1 - index
                val offset = (i - at.value) * stepPx
                val x = mid + if (rtl) -offset else offset
                if (abs(x - mid) < mid) {
                    Text(
                        "${shown.year}",
                        color = labels,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.absoluteOffset { IntOffset((x - 14 * density).roundToInt(), 0) }.alpha(1f - abs(x - mid) / mid),
                    )
                }
            }
        }
    }
}

/** Over the feed while the ruler is open: any touch folds it and goes no further, so no photo opens by accident. */
@Composable
internal fun TimelineScrim(onClose: () -> Unit) {
    val currentClose by rememberUpdatedState(onClose)
    Spacer(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    currentClose()
                }
            },
    )
}

/** Each feed row's section key (a moment's label is in the section above it), for the month at the top of the feed. */
internal fun List<FeedRow>.sectionKeys(): List<String?> {
    var section: String? = null
    return map { row ->
        when (row) {
            is FeedRow.Heading -> section = row.section.key
            is FeedRow.Photo -> section = row.section.key
            is FeedRow.BandTop -> section = row.section.key
            is FeedRow.BandPhotos -> section = row.section.key
            is FeedRow.BandEnd -> section = row.section.key
            is FeedRow.RecordingBar -> section = row.section.key
            is FeedRow.Sound -> section = row.section.key
            else -> Unit
        }
        section
    }
}

/** The newest day a row shows (a heading its row's newest day), or null for rows that show no photos of their own. */
private fun FeedRow.newestDay(): LocalDay? = when (this) {
    is FeedRow.Heading -> section.span.newestDay
    is FeedRow.Label -> moment.items.maxByOrNull { it.takenAtMillis }?.day
    is FeedRow.RecordingBar -> moment.items.maxByOrNull { it.takenAtMillis }?.day
    is FeedRow.Photo -> block.items.maxByOrNull { it.takenAtMillis }?.day
    else -> null
}

/** Each feed row's day: its own newest, or the one above it for rows without photos. The pill's day at the top. */
internal fun List<FeedRow>.rowDays(): List<LocalDay?> {
    var day: LocalDay? = null
    return map { row -> row.newestDay()?.let { day = it }; day }
}

/**
 * Where going to [day] lands in the row [sectionKey]: a row can hold several days, so the first moment label or photo
 * of that day in it (newest first, as the feed reads), or the row's heading when the day is its newest or not found.
 * -1 when the row is not in the list.
 */
internal fun List<FeedRow>.rowOfDay(sectionKey: String, day: LocalDay): Int {
    val heading = indexOfFirst { it.key == "heading-$sectionKey" }
    if (heading < 0) return -1
    if ((this[heading] as FeedRow.Heading).section.span.newestDay == day) return heading
    for (i in heading + 1 until size) {
        when (val row = this[i]) {
            is FeedRow.Heading -> break
            is FeedRow.Label -> if (row.newestDay() == day) return i
            is FeedRow.RecordingBar -> if (row.newestDay() == day) return i
            is FeedRow.Photo -> if (row.block.items.any { it.day == day }) return i
            else -> Unit
        }
    }
    return heading
}
