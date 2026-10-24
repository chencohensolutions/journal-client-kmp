package com.chencohensolution.journal.ui.library

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.storage.FreeUpActivity
import com.chencohensolution.journal.storage.FreeUpPlan
import com.chencohensolution.journal.storage.FreeUpRule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Free up space, opened from Settings: one button that frees everything this phone backed up (from the chosen
 * sources), with "Older than" and "Keep the newest" under More options. Each file is checked on the phone and on the
 * server before it goes to the phone's trash. See docs/screens/settings.md.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FreeUpScreen(ui: StorageUi, actions: StorageActions, back: () -> Unit) {
    val context = LocalContext.current
    val insets = WindowInsets.systemBars.asPaddingValues()
    val freeUp = ui.freeUp
    val rule = freeUp.rule
    val plan = freeUp.plan
    val running = freeUp.activity is FreeUpActivity.Running
    var askAccess by rememberSaveable { mutableStateOf(false) }
    var moreOptions by rememberSaveable { mutableStateOf(rule != FreeUpRule.Everything) }

    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(
                        when {
                            plan == null -> stringResource(R.string.settings_storage_counting)
                            plan.groups.isEmpty() -> stringResource(R.string.free_up_nothing)
                            else -> Formatter.formatShortFileSize(context, plan.bytes)
                        },
                        color = LibraryColors.OnBackground,
                        fontSize = if (plan == null || plan.groups.isEmpty()) 20.sp else 34.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(ruleSummary(rule), color = LibraryColors.OnBackground, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                    if (plan != null && plan.groups.isNotEmpty()) {
                        Text(planDetails(plan), color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                    } else if (plan != null) {
                        Text(stringResource(R.string.settings_free_up_plan_none), color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    Button(
                        onClick = { if (ui.canTrashWithoutAsking) actions.freeUp() else askAccess = true },
                        enabled = !running && plan != null && plan.groups.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = LibraryColors.Inverse, contentColor = LibraryColors.OnInverse),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp),
                    ) {
                        Text(
                            if (plan != null && plan.groups.isNotEmpty()) {
                                stringResource(R.string.free_up_button_amount, Formatter.formatShortFileSize(context, plan.bytes))
                            } else {
                                stringResource(R.string.settings_free_up_button)
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(stringResource(R.string.settings_free_up_body), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    freeUpStatus(freeUp)?.let { Text(it, color = LibraryColors.OnBackground, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
                    if (!running && freeUp.waitingCount > 0) {
                        TextButton(onClick = actions.moveWaitingToTrash, modifier = Modifier.padding(top = 4.dp)) {
                            Text(stringResource(R.string.settings_free_up_move_waiting), color = LibraryColors.OnBackground)
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.free_up_sources))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaSource.entries.forEach { source ->
                    Chip(sourceName(source), source in freeUp.sources) { actions.toggleSource(source) }
                }
            }

            Row(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clickable { moreOptions = !moreOptions }
                    .padding(start = 32.dp, end = 28.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.free_up_more), color = LibraryColors.OnBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(
                    LibraryIcons.ChevronDown,
                    contentDescription = null,
                    tint = LibraryColors.Muted,
                    modifier = Modifier.size(20.dp).rotate(if (moreOptions) 180f else 0f),
                )
            }
            if (moreOptions) {
                SettingsCard {
                    Column(Modifier.fillMaxWidth().selectableGroup()) {
                        ModeOption(
                            title = stringResource(R.string.free_up_rule_everything),
                            body = stringResource(R.string.free_up_rule_everything_body),
                            selected = rule == FreeUpRule.Everything,
                            onSelect = { actions.setRule(FreeUpRule.Everything) },
                        )
                        ModeOption(
                            title = stringResource(R.string.settings_free_up_older),
                            body = stringResource(R.string.settings_free_up_older_body),
                            selected = rule is FreeUpRule.OlderThan,
                            onSelect = { if (rule !is FreeUpRule.OlderThan) actions.setRule(FreeUpRule.OlderThan(DEFAULT_OLDER_THAN_DAYS)) },
                        )
                        // Each choice's values sit under it, shown while it is the one chosen.
                        if (rule is FreeUpRule.OlderThan) {
                            OptionChips {
                                FreeUpRule.DAYS_OPTIONS.forEach { days ->
                                    Chip(ageText(days), rule.days == days) { actions.setRule(FreeUpRule.OlderThan(days)) }
                                }
                            }
                        }
                        ModeOption(
                            title = stringResource(R.string.settings_free_up_keep),
                            body = stringResource(R.string.settings_free_up_keep_body),
                            selected = rule is FreeUpRule.KeepNewest,
                            onSelect = { if (rule !is FreeUpRule.KeepNewest) actions.setRule(FreeUpRule.KeepNewest(DEFAULT_KEEP_GIGABYTES)) },
                        )
                        if (rule is FreeUpRule.KeepNewest) {
                            OptionChips {
                                FreeUpRule.GIGABYTES_OPTIONS.forEach { gigabytes ->
                                    Chip(stringResource(R.string.settings_storage_gigabytes, gigabytes), rule.gigabytes == gigabytes) {
                                        actions.setRule(FreeUpRule.KeepNewest(gigabytes))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        TitleBar(stringResource(R.string.settings_free_up_title), back, Modifier.align(Alignment.TopCenter))
    }

    if (askAccess) {
        AlertDialog(
            onDismissRequest = { askAccess = false },
            title = { Text(stringResource(R.string.settings_free_up_access_title)) },
            text = { Text(stringResource(R.string.settings_free_up_access_body)) },
            confirmButton = {
                TextButton(onClick = {
                    askAccess = false
                    actions.allowTrashWithoutAsking()
                }) { Text(stringResource(R.string.settings_free_up_access_allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askAccess = false
                    actions.freeUp()
                }) { Text(stringResource(R.string.settings_free_up_access_later)) }
            },
        )
    }
}

private const val DEFAULT_OLDER_THAN_DAYS = 365

/** A choice's values, indented under its title like its description. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OptionChips(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.padding(start = 52.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

/** What the rule takes: "Everything backed up", "Taken more than 1 year ago", "All but the newest 10 GB". */
@Composable
private fun ruleSummary(rule: FreeUpRule): String = when (rule) {
    FreeUpRule.Everything -> stringResource(R.string.free_up_summary_everything)
    is FreeUpRule.OlderThan -> stringResource(R.string.free_up_summary_older, ageText(rule.days))
    is FreeUpRule.KeepNewest -> stringResource(R.string.free_up_summary_keep, stringResource(R.string.settings_storage_gigabytes, rule.gigabytes))
}

/** "1,240 photos, 38 videos · Mar 2019 – Oct 2025". */
@Composable
private fun planDetails(plan: FreeUpPlan): String {
    val counts = photosAndVideos(plan.photos, plan.videos)
    val (oldest, newest) = plan.takenRange ?: return counts
    val month = DateTimeFormatter.ofPattern("MMM yyyy")
    fun format(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(month)
    val from = format(oldest)
    val to = format(newest)
    return if (from == to) "$counts · $from" else "$counts · $from – $to"
}
