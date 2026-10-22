package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.journal.R
import com.chencohensolution.journal.server.HiddenCollections
import com.chencohensolution.journal.server.HiddenCollectionsController
import com.chencohensolution.journal.server.HiddenCollectionsState
import com.chencohensolution.journal.server.ServerLibrary
import java.text.NumberFormat

/**
 * Settings → Private media → Hidden collections: all of WhatsApp, and screenshots by app, hidden on every device by the
 * server (see [HiddenCollections]). Behind the phone's lock. Each switch is saved on the server at once; nothing is kept
 * on this phone. The logic is [HiddenCollectionsController]. See docs/screens/settings.md.
 */
@Composable
internal fun HiddenScreen(library: ServerLibrary, back: () -> Unit) {
    val title = stringResource(R.string.settings_hidden_title)
    PhoneLocked(title, back) {
        val controller = remember(library) { HiddenCollectionsController(library) }
        DisposableEffect(controller) { onDispose { controller.close() } }
        LaunchedEffect(controller) { controller.load() }
        val state by controller.state.collectAsStateWithLifecycle()
        val actions = remember(controller) {
            HiddenActions(retry = controller::retry, set = controller::set, setQuery = controller::setQuery)
        }
        HiddenContent(title, state, actions, back)
    }
}

/** What the Hidden collections screen can ask for; see [HiddenCollectionsController]. */
internal class HiddenActions(
    val retry: () -> Unit,
    val set: (HiddenCollections.Collection, Boolean) -> Unit,
    val setQuery: (String) -> Unit,
)

/** Draws [state]; once unlocked. */
@Composable
internal fun HiddenContent(title: String, state: HiddenCollectionsState, actions: HiddenActions, back: () -> Unit) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            Text(
                stringResource(R.string.hidden_intro),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
            when (state) {
                is HiddenCollectionsState.Ready -> {
                    if (state.notChanged) {
                        Text(
                            stringResource(R.string.hidden_not_changed),
                            color = LibraryColors.Warning,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        )
                    }
                    val shown = state.collections
                    val set = actions.set
                    SectionTitle(stringResource(R.string.hidden_whatsapp_title))
                    SettingsCard {
                        CollectionRow(stringResource(R.string.hidden_whatsapp), shown.whatsapp, R.plurals.hidden_files, state.saving, set)
                    }
                    SectionTitle(stringResource(R.string.hidden_screenshots_title))
                    SettingsCard {
                        CollectionRow(stringResource(R.string.hidden_without_app), shown.screenshotsWithoutApp, R.plurals.hidden_screenshots, state.saving, set)
                        HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                        if (shown.screenshotApps.isEmpty()) {
                            Text(stringResource(R.string.hidden_no_apps), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                        } else {
                            OutlinedTextField(
                                value = state.query,
                                onValueChange = actions.setQuery,
                                placeholder = { Text(stringResource(R.string.hidden_find_app)) },
                                leadingIcon = { Icon(LibraryIcons.Search, contentDescription = null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp)) },
                                singleLine = true,
                                textStyle = TextStyle(color = LibraryColors.OnBackground, fontSize = 16.sp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                            )
                            state.shownApps.forEach { app -> CollectionRow(app.name, app, R.plurals.hidden_screenshots, state.saving, set) }
                        }
                    }
                }
                HiddenCollectionsState.Unreachable -> {
                    Column(Modifier.padding(horizontal = 32.dp, vertical = 16.dp)) {
                        Text(stringResource(R.string.hidden_unreachable), color = LibraryColors.OnBackground, fontSize = 16.sp)
                        TextButton(onClick = actions.retry) { Text(stringResource(R.string.hidden_retry), color = LibraryColors.OnBackground) }
                    }
                }
                HiddenCollectionsState.Loading -> Text(
                    stringResource(R.string.hidden_loading),
                    color = LibraryColors.Muted,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp).align(Alignment.CenterHorizontally),
                )
            }
        }
        TitleBar(title, back, Modifier.align(Alignment.TopCenter))
    }
}

/** A collection's switch, with how many files it has; on means hidden. Disabled while a change is being saved. */
@Composable
private fun CollectionRow(
    title: String,
    collection: HiddenCollections.Collection,
    countText: Int,
    saving: Boolean,
    set: (HiddenCollections.Collection, Boolean) -> Unit,
) {
    val count = NumberFormat.getIntegerInstance().format(collection.files)
    SwitchRow(
        title = title,
        body = pluralStringResource(countText, collection.files, count),
        checked = collection.hidden,
        onChange = { if (!saving) set(collection, it) },
    )
}
