package com.chencohensolution.journal.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chencohensolution.journal.R
import kotlinx.coroutines.delay

// Messages instead of toasts (docs/screens/style.md): one MessageBar, hosted once by LibraryApp (and once by
// enrollment), above the bottom controls. A screen posts to LocalMessages; code outside composition (casting) posts to
// AppMessages. A plain note goes after 6 s; one with an action ("Try again") stays until dismissed or acted on.

/** A message: its text and an optional action. */
internal class PostedMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** Where messages are posted; the host shows the last one. */
internal class Messages {
    var current by mutableStateOf<PostedMessage?>(null)
        private set

    fun post(message: PostedMessage) {
        current = message
    }

    fun post(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) = post(PostedMessage(text, actionLabel, action))

    fun dismiss(message: PostedMessage? = current) {
        if (current === message) current = null
    }
}

/** The app's messages, for code outside composition (the cast session). */
internal val AppMessages = Messages()

internal val LocalMessages = staticCompositionLocalOf { AppMessages }

/** Posts a string resource to [LocalMessages]: `val say = rememberSay(); say(R.string.x)`. */
@Composable
internal fun rememberSay(): (Int) -> Unit {
    val messages = LocalMessages.current
    val context = LocalContext.current
    return { id: Int -> messages.post(context.getString(id)) }
}

/** How long a plain note stays. */
private const val NOTE_MILLIS = 6_000L

/** The bar that shows [messages]' current message, to be placed above the bottom controls. */
@Composable
internal fun MessageHost(messages: Messages, modifier: Modifier = Modifier) {
    val message = messages.current
    LaunchedEffect(message) {
        if (message != null && message.action == null) {
            delay(NOTE_MILLIS)
            messages.dismiss(message)
        }
    }
    // The last message stays drawn while the bar slides away.
    var shown by androidx.compose.runtime.remember { mutableStateOf<PostedMessage?>(null) }
    if (message != null) shown = message
    AnimatedVisibility(
        message != null,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        shown?.let { MessageBar(it, onDismiss = { messages.dismiss(it) }) }
    }
}

/** A message in an ink bar: its text, its action, and a close button. */
@Composable
internal fun MessageBar(message: PostedMessage, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(horizontal = 16.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(16.dp))
            .background(LibraryColors.Inverse, LibraryShapes.Card)
            .padding(start = 16.dp, end = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message.text,
            color = LibraryColors.OnInverse,
            style = LibraryType.Body,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
        )
        if (message.actionLabel != null && message.action != null) {
            TextAction(
                message.actionLabel,
                {
                    onDismiss()
                    message.action.invoke()
                },
                color = LibraryColors.OnInverse,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(LibraryIcons.Close, stringResource(R.string.message_dismiss), tint = LibraryColors.OnInverseMuted, modifier = Modifier.size(18.dp))
        }
    }
}
