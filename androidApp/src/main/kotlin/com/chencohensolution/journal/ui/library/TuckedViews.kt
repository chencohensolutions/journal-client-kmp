package com.chencohensolution.journal.ui.library

import android.app.KeyguardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.stories.allTucked
import kotlinx.coroutines.launch

// Tucked away (Nov 8, 2026; "מוצנע"): the day's pill, the mark on a revealed photo, the phone's lock before revealing,
// and the picking bar shared by Moments, the storyline, Explore's pages and search. See docs/screens/moments.md.

/**
 * What the screens can do with what is tucked away, provided once by LibraryApp ([LocalTucked]); the defaults do
 * nothing (previews).
 */
@Stable
internal class TuckedActions(
    /** The server can tuck files away (serverInfo.tuckedAway): Tuck away is offered. */
    val canTuck: Boolean = false,
    /** Whether revealing, Bring back or Unmark private must pass the phone's lock first (not yet this session). */
    val needsLock: () -> Boolean = { false },
    /** The phone's lock was passed: the session doesn't ask again. */
    val unlocked: () -> Unit = {},
    /** A day's pill: reveals the tucked photos of these days, or hides them again. */
    val toggleDays: (Collection<LocalDay>) -> Unit = {},
    /** Tucks away (true) or brings back; false when the server couldn't be reached. */
    val setTucked: suspend (List<MediaItem>, Boolean) -> Boolean = { _, _ -> false },
    /** Marks private; false when the server couldn't be reached. */
    val markPrivate: suspend (List<MediaItem>) -> Boolean = { false },
)

internal val LocalTucked = staticCompositionLocalOf { TuckedActions() }

/**
 * Runs what it is given once the phone's lock is passed, when the session needs it ([TuckedActions.needsLock]); right
 * away otherwise, in previews, or on a phone with no screen lock (nothing to ask). A cancelled prompt runs nothing.
 */
@Composable
internal fun rememberTuckedLock(): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    val tucked by rememberUpdatedState(LocalTucked.current)
    val preview = LocalInspectionMode.current
    val title = stringResource(R.string.tucked_away)
    return remember(context, preview, title) {
        { then ->
            val secure = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
            if (preview || !secure || !tucked.needsLock()) {
                then()
            } else {
                askForPhoneLock(context, title) {
                    tucked.unlocked()
                    then()
                }
            }
        }
    }
}

/** In a day's divider or heading: "3 tucked away", outlined; filled while they show. */
@Composable
internal fun TuckedPill(count: Int, revealed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (revealed) LibraryColors.OnInverse else LibraryColors.Muted
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (revealed) {
                    Modifier.background(LibraryColors.Inverse, RoundedCornerShape(50))
                } else {
                    Modifier.border(1.dp, LibraryColors.Muted.copy(alpha = 0.5f), RoundedCornerShape(50))
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(if (revealed) LibraryIcons.Eye else LibraryIcons.EyeOff, null, tint = color, modifier = Modifier.size(14.dp))
        Text(pluralStringResource(R.plurals.tucked_pill, count, count), color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** On a revealed tucked photo's corner: a small eye with a line through it. */
@Composable
internal fun TuckedMark(modifier: Modifier = Modifier) {
    Box(
        modifier.size(26.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(LibraryIcons.EyeOff, stringResource(R.string.tucked_away), tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

/** What the picking bar offers with one photo picked, or with a few in Moments; null leaves it out. */
internal class PickingExtras(
    val write: (() -> Unit)? = null,
    /** Edit (true) rather than Write: the photo has writing. */
    val written: Boolean = false,
    val crop: (() -> Unit)? = null,
    val tag: (() -> Unit)? = null,
    /** Moments, two or more picked: one set of them. */
    val merge: (() -> Unit)? = null,
    /** Moments, one set picked: the photo it is turned to leaves it. */
    val takeOut: (() -> Unit)? = null,
)

/**
 * While picking, in place of the tabs (or search's field): Cancel, "N selected" (photos, a set each of its own), Mark
 * private and Tuck away, or Bring back when everything picked is tucked away (behind the phone's lock, unless the
 * session passed it). Above it, [extras]: Write · Crop · Tag with one photo picked, Merge with more in Moments. Mark
 * private asks first; Tuck away doesn't. Both keep the picks while the server can't be reached, and say so.
 */
@Composable
internal fun PickingBar(
    picked: List<MediaItem>,
    onCancel: () -> Unit,
    /** Done on the server: picking stops ([onDone] gets what was changed, and whether it was tucked away). */
    onDone: (items: List<MediaItem>, tucked: Boolean?) -> Unit,
    modifier: Modifier = Modifier,
    extras: PickingExtras = PickingExtras(),
    /** Search never shows tucked photos, so it never offers Bring back. */
    canBringBack: Boolean = true,
) {
    val tucked = LocalTucked.current
    val lock = rememberTuckedLock()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmingPrivate by remember { mutableStateOf(false) }
    // What the server is asked until it answers: "private", "tuck", "back"; null when nothing is.
    var busy by remember { mutableStateOf<String?>(null) }
    val bringBack = canBringBack && allTucked(picked)
    fun send(what: String, call: suspend () -> Boolean, tuckedNow: Boolean?) {
        busy = what
        val items = picked
        scope.launch {
            if (call()) onDone(items, tuckedNow) else Toast.makeText(context, R.string.library_private_unreachable, Toast.LENGTH_LONG).show()
            busy = null
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val single = picked.size == 1
        val extraButtons = buildList {
            if (single) {
                extras.write?.let { add((if (extras.written) R.string.picking_edit else R.string.picking_write) to it) }
                extras.crop?.let { add(R.string.feed_menu_crop to it) }
                extras.tag?.let { add(R.string.library_tag to it) }
            }
            extras.merge?.let { add(R.string.feed_merge to it) }
            extras.takeOut?.let { add(R.string.picking_take_out to it) }
        }
        if (extraButtons.isNotEmpty()) {
            Row(
                Modifier.background(LibraryColors.Inverse, RoundedCornerShape(50)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                extraButtons.forEach { (label, action) ->
                    TextButton(onClick = action, enabled = busy == null) {
                        Text(stringResource(label), color = LibraryColors.OnInverse, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Row(
            Modifier.background(LibraryColors.Inverse, RoundedCornerShape(50)).padding(6.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.library_cancel), color = LibraryColors.OnInverse) }
            Text(
                pluralStringResource(R.plurals.feed_selected, picked.size, picked.size),
                color = LibraryColors.OnInverseMuted,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            BarButton(stringResource(R.string.library_private_mark), busy == "private", enabled = picked.isNotEmpty() && busy == null, accent = false) {
                confirmingPrivate = true
            }
            if (bringBack) {
                BarButton(stringResource(R.string.tucked_bring_back), busy == "back", enabled = busy == null) {
                    lock { send("back", { tucked.setTucked(picked, false) }, false) }
                }
            } else if (tucked.canTuck) {
                BarButton(stringResource(R.string.tucked_tuck_away), busy == "tuck", enabled = picked.isNotEmpty() && busy == null) {
                    send("tuck", { tucked.setTucked(picked, true) }, true)
                }
            }
        }
    }
    if (confirmingPrivate) {
        AlertDialog(
            onDismissRequest = { confirmingPrivate = false },
            title = { Text(pluralStringResource(R.plurals.search_private_title, picked.size, picked.size)) },
            text = { Text(stringResource(R.string.search_private_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingPrivate = false
                    send("private", { tucked.markPrivate(picked) }, null)
                }) { Text(stringResource(R.string.library_private_mark_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmingPrivate = false }) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
}

/** A filled button of the picking bar; it spins while [busy]. */
@Composable
private fun BarButton(label: String, busy: Boolean, enabled: Boolean, accent: Boolean = true, onClick: () -> Unit) {
    val background = when {
        !enabled && !busy -> LibraryColors.OnInverseMuted.copy(alpha = 0.3f)
        accent -> LibraryColors.Accent
        else -> LibraryColors.OnInverseMuted.copy(alpha = 0.35f)
    }
    Box(
        Modifier
            .height(40.dp)
            .background(background, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
