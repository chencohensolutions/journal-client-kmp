package com.chencohensolution.journal.ui.library

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The library screens are always dark, like a photo app, whatever the system theme. */
internal object LibraryColors {
    val Background = Color(0xFF0F1413)
    val Raised = Color(0xFF1E2725)
    val Muted = Color(0xFFA9B4B1)
    val Accent = Color(0xFF1F5F5B)
    val Light = Color(0xFFF5F6F4)
    val Ink = Color(0xFF17201F)
    val InkMuted = Color(0xFF5E6866)
    val Warning = Color(0xFFF2B84B)
    val Error = Color(0xFFC2410C)
    val BadgeScrim = Color(0x990F1413)
    val PillScrim = Color(0xB30F1413)

    /** Behind the floating top bar: dark at the edge, fading into the content. */
    val TopFade = Brush.verticalGradient(listOf(Background.copy(alpha = 0.95f), Background.copy(alpha = 0.75f), Color.Transparent))
    val BottomFade = Brush.verticalGradient(listOf(Color.Transparent, Background.copy(alpha = 0.9f)))
    val LabelFade = Brush.verticalGradient(listOf(Background.copy(alpha = 0.55f), Color.Transparent))
}

/** Line icons (Material icons are not a dependency of this app). */
internal object LibraryIcons {
    private fun icon(name: String, filled: Boolean, vararg paths: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
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
    val Play = icon("Play", true, "M7 4v16l13-8z")
    val More = icon("More", true, "M12 3a2 2 0 1 0 0.01 0z", "M12 10a2 2 0 1 0 0.01 0z", "M12 17a2 2 0 1 0 0.01 0z")
    val Back = icon("Back", false, "M19 12H5", "M12 19l-7-7 7-7")
    val Info = icon("Info", false, "M12 3a9 9 0 1 0 0.01 0", "M12 11v5", "M12 8h0.01")
    val Grid = icon("Grid", false, "M4 3h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M14 3h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M4 13h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z", "M14 13h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z")
    val Calendar = icon("Calendar", false, "M5 4h14a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M3 9h18", "M8 2v4", "M16 2v4")
    val Lock = icon("Lock", false, "M5 11h14a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4")
    val Plus = icon("Plus", false, "M12 5v14", "M5 12h14")
    val Edit = icon("Edit", false, "M12 20h9", "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z")
    val Trash = icon("Trash", false, "M3 6h18", "M8 6V4h8v2", "M19 6l-1 14H6L5 6")
    val Minus = icon("Minus", false, "M5 12h14")
    val Search = icon("Search", false, "M11 4a7 7 0 1 0 0.01 0", "M20 20l-3.5-3.5")
    val Tag = icon("Tag", false, "M3 12V4a1 1 0 0 1 1-1h8l9 9-9 9z", "M7.5 7.5h0.01")
    val Close = icon("Close", false, "M6 6l12 12", "M18 6L6 18")
    val Stack = icon("Stack", false, "M9 7h11a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z", "M4 16V5a1 1 0 0 1 1-1h11")
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
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
}

/** "0:42", "12:05", "1:02:09". */
internal fun formatDuration(millis: Long): String {
    val total = millis / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
