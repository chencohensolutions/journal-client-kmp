package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chencohensolution.journal.R

// The pieces every settings-style page shares (docs/screens/style.md): the page itself under its title bar, the muted
// note under a title or a card, and the empty, loading and unreachable states.

/**
 * A settings-style page: [content] scrolls under the floating [TitleBar] ([title], back), with room for the bars.
 * [imePadding] keeps the content above the keyboard (pages with a text field).
 */
@Composable
internal fun SettingsScaffold(
    title: String,
    back: () -> Unit,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    imePadding: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val insets = barsRoom()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .then(if (imePadding) Modifier.imePadding() else Modifier)
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
            verticalArrangement = verticalArrangement,
            content = content,
        )
        TitleBar(title, back, Modifier.align(Alignment.TopCenter))
    }
}

/** An intro or a note on a settings-style page: Caption, muted, 32 dp from the sides. */
@Composable
internal fun PageNote(text: String, modifier: Modifier = Modifier, top: Int = 0, bottom: Int = 0) {
    Text(
        text,
        color = LibraryColors.Muted,
        style = LibraryType.Caption,
        modifier = modifier.padding(start = 32.dp, end = 32.dp, top = top.dp, bottom = bottom.dp),
    )
}

/** Nothing to show: a muted line, centered, and an optional ink button under it ("Go to Moments"). */
@Composable
internal fun EmptyState(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = LibraryColors.Muted, style = LibraryType.Body, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            SecondaryButton(actionLabel, onAction, Modifier.padding(top = 16.dp))
        }
    }
}

/** Still reading: a small spinner with "Loading…" under it, centered. */
@Composable
internal fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = LibraryColors.Muted, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        Text(stringResource(R.string.library_loading), color = LibraryColors.Muted, style = LibraryType.Caption, modifier = Modifier.padding(top = 10.dp))
    }
}

/** The server can't be reached: what that means, and Try again. */
@Composable
internal fun UnreachableState(onRetry: (() -> Unit)?, modifier: Modifier = Modifier, text: String = stringResource(R.string.hidden_unreachable)) {
    EmptyState(text, modifier, actionLabel = onRetry?.let { stringResource(R.string.hidden_retry) }, onAction = onRetry)
}
