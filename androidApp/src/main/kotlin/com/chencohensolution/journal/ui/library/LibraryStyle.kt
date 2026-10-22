package com.chencohensolution.journal.ui.library

import android.text.format.DateFormat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Library colors, light or dark with the system theme (the viewer is always dark). Things drawn over photos (the
 * Photo* colors, scrims and LabelFade) are the same in both: white on a dark scrim reads on any photo.
 */
@Immutable
internal class LibraryPalette(
    val Background: Color,
    val Raised: Color,
    /** Text and icons on Background and Raised. */
    val OnBackground: Color,
    val Muted: Color,
    val Accent: Color,
    /** A surface that stands out from the page: light on dark, dark on light (cards, the selected tab, Undo). */
    val Inverse: Color,
    val OnInverse: Color,
    val OnInverseMuted: Color,
    val Warning: Color,
    val Error: Color,
) {
    val BadgeScrim = Color(0x990F1413)
    val PillScrim = Color(0xB30F1413)
    val PhotoText = Color.White
    val PhotoTextMuted = Color(0xFFE3E8E6)

    /** Light chips over photos (the calendar block, "Cover"). */
    val Paper = Color(0xFFF5F6F4)
    val PaperInk = Color(0xFF17201F)

    /** Behind the floating top bar: the page color at the edge, fading into the content. */
    val TopFade = Brush.verticalGradient(listOf(Background.copy(alpha = 0.95f), Background.copy(alpha = 0.75f), Color.Transparent))
    val BottomFade = Brush.verticalGradient(listOf(Color.Transparent, Background.copy(alpha = 0.9f)))

    /** Under text written over the top or bottom of a photo. */
    val LabelFade = Brush.verticalGradient(listOf(Color(0x8C0F1413), Color.Transparent))
    val PhotoFade = Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60F1413)))
}

internal val DarkLibraryPalette = LibraryPalette(
    Background = Color(0xFF0F1413),
    Raised = Color(0xFF1E2725),
    OnBackground = Color.White,
    Muted = Color(0xFFA9B4B1),
    Accent = Color(0xFF1F5F5B),
    Inverse = Color(0xFFF5F6F4),
    OnInverse = Color(0xFF17201F),
    OnInverseMuted = Color(0xFF5E6866),
    Warning = Color(0xFFF2B84B),
    Error = Color(0xFFC2410C),
)

internal val LightLibraryPalette = LibraryPalette(
    Background = Color(0xFFF4F5F3),
    Raised = Color.White,
    OnBackground = Color(0xFF17201F),
    Muted = Color(0xFF5E6866),
    Accent = Color(0xFF1F5F5B),
    Inverse = Color(0xFF17201F),
    OnInverse = Color(0xFFF5F6F4),
    OnInverseMuted = Color(0xFFA9B4B1),
    Warning = Color(0xFFB45309),
    Error = Color(0xFFC2410C),
)

private val LocalLibraryPalette = staticCompositionLocalOf { DarkLibraryPalette }

internal val LibraryColors: LibraryPalette
    @Composable @ReadOnlyComposable get() = LocalLibraryPalette.current

/** The library palette for [content]: the system theme's, or dark when [dark] says so (the viewer). */
@Composable
internal fun LibraryTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLibraryPalette provides if (dark) DarkLibraryPalette else LightLibraryPalette, content = content)
}

/** Line icons (Material icons are not a dependency of this app). */
internal object LibraryIcons {
    /** [mirrored] icons point along the reading direction, so they flip in right-to-left layouts (Hebrew). */
    private fun icon(name: String, filled: Boolean, vararg paths: String, mirrored: Boolean = false) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirrored).apply {
            paths.forEach { data ->
                val nodes = PathParser().parsePathString(data).toNodes()
                if (filled) {
                    addPath(pathData = nodes, fill = SolidColor(Color.Black))
                } else {
                    addPath(
                        pathData = nodes,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 2f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
        }.build()

    val CloudOff = icon("CloudOff", false, "M17.5 19H7a5 5 0 0 1-2.7-9.2", "M8.6 5.6A6 6 0 0 1 19.5 11a4 4 0 0 1 1.6 6.3", "M2 2l20 20")
    val CloudUp = icon("CloudUp", false, "M17.5 19H7a5 5 0 1 1 1.1-9.9A6 6 0 0 1 19.5 11a4 4 0 0 1-2 8z", "M12 16v-5", "M9.5 13.5L12 11l2.5 2.5")
    val Check = icon("Check", false, "M5 12l5 5 9-10")
    val Phone = icon("Phone", false, "M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M11 18h2")
    val Play = icon("Play", true, "M7 4v16l13-8z")
    val More = icon("More", true, "M12 3a2 2 0 1 0 0.01 0z", "M12 10a2 2 0 1 0 0.01 0z", "M12 17a2 2 0 1 0 0.01 0z")
    val Back = icon("Back", false, "M19 12H5", "M12 19l-7-7 7-7", mirrored = true)
    val Info = icon("Info", false, "M12 3a9 9 0 1 0 0.01 0", "M12 11v5", "M12 8h0.01")
    val Grid = icon("Grid", false, "M4 3h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M14 3h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M4 13h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z", "M14 13h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z")
    val Calendar = icon("Calendar", false, "M5 4h14a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M3 9h18", "M8 2v4", "M16 2v4")
    val Lock = icon("Lock", false, "M5 11h14a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4")
    val Plus = icon("Plus", false, "M12 5v14", "M5 12h14")
    val Edit = icon("Edit", false, "M12 20h9", "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z")
    val Minus = icon("Minus", false, "M5 12h14")
    val Search = icon("Search", false, "M11 4a7 7 0 1 0 0.01 0", "M20 20l-3.5-3.5")
    val Tag = icon("Tag", false, "M3 12V4a1 1 0 0 1 1-1h8l9 9-9 9z", "M7.5 7.5h0.01")
    val Close = icon("Close", false, "M6 6l12 12", "M18 6L6 18")
    val Stack = icon("Stack", false, "M9 7h11a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z", "M4 16V5a1 1 0 0 1 1-1h11")
    val Settings = icon("Settings", false, "M4 7h9", "M17 7h3", "M15 5v4", "M4 17h3", "M11 17h9", "M9 15v4")
    val Eye = icon("Eye", false, "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", "M12 9a3 3 0 1 0 0.01 0")
    val Clock = icon("Clock", false, "M12 3a9 9 0 1 0 0.01 0", "M12 7v5l3 2")
    val Crop = icon("Crop", false, "M6 2v14a2 2 0 0 0 2 2h14", "M18 22V8a2 2 0 0 0-2-2H2")
    val ChevronRight = icon("ChevronRight", false, "M9 6l6 6-6 6", mirrored = true)
    val ChevronDown = icon("ChevronDown", false, "M6 9l6 6 6-6")
    val Share = icon(
        "Share", false, "M18 2a3 3 0 1 0 0.01 0", "M6 9a3 3 0 1 0 0.01 0", "M18 16a3 3 0 1 0 0.01 0", "M8.6 13.5l6.8 4", "M15.4 6.5l-6.8 4",
    )
    val Map = icon("Map", false, "M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2z", "M9 4v14", "M15 6v14")
    val Photo = icon("Photo", false, "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M8.5 10a1.5 1.5 0 1 0 0.01 0", "M21 15l-5-5L5 21")
}

internal fun LocalDay.toLocalDate(): LocalDate = LocalDate.of(year, month, day)

/** "Today", "Yesterday", "Tue, Oct 7", or "Tue, Oct 7, 2025" for other years. */
@Composable
internal fun dayLabel(day: LocalDay): String {
    val date = day.toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.library_today)
        today.minusDays(1) -> stringResource(R.string.library_yesterday)
        else -> formatDate(date, if (date.year == today.year) "EEEMMMd" else "EEEMMMdyyyy")
    }
}

/** [skeleton] is a CLDR skeleton; the locale decides order and punctuation. */
internal fun formatDate(date: java.time.temporal.TemporalAccessor, skeleton: String): String {
    val locale = Locale.getDefault()
    return formatters.getOrPut(locale to skeleton) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }.format(date)
}

/**
 * Formatters by locale and skeleton: looking up the pattern and parsing it took longer than formatting, and the feed
 * formats several dates per item as it scrolls. Formatters are immutable, so any thread may share them.
 */
private val formatters = ConcurrentHashMap<Pair<Locale, String>, DateTimeFormatter>()

/** "0:42", "12:05", "1:02:09". */
internal fun formatDuration(millis: Long): String {
    val total = millis / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** "2:09 PM", or "2:09 – 2:10 PM" when the photos span minutes. */
internal fun timeRange(items: List<MediaItem>): String {
    val zone = java.time.ZoneId.systemDefault()
    val first = java.time.Instant.ofEpochMilli(items.minOf { it.takenAtMillis }).atZone(zone)
    val last = java.time.Instant.ofEpochMilli(items.maxOf { it.takenAtMillis }).atZone(zone)
    val start = formatDate(first, "jmm")
    val end = formatDate(last, "jmm")
    return if (start == end) start else "$start – $end"
}
