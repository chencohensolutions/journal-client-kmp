package com.chencohensolution.journal.ui.library

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.server.CopyProblem
import com.chencohensolution.journal.swap.CopyKeyKind
import com.chencohensolution.journal.swap.KeptSwap
import com.chencohensolution.journal.swap.MySwap
import com.chencohensolution.journal.swap.Passphrase
import com.chencohensolution.journal.swap.ShownInvite
import com.chencohensolution.journal.swap.SuggestedPassphrase
import com.chencohensolution.journal.swap.SwapLink
import com.chencohensolution.journal.swap.SwapOffer
import com.chencohensolution.journal.swap.SwapOutcome
import com.chencohensolution.journal.swap.SwapQuota
import com.chencohensolution.journal.swap.SwapRefusal
import com.chencohensolution.journal.swap.SwapState
import com.chencohensolution.journal.swap.parseSwapLink
import java.text.NumberFormat

// Settings › Backup › Swap with a friend (docs/screens/swap.md; docs/plans/swap-from-phones.md). The page lists the
// user's swaps both ways and the offers; "Keep a friend's copy here" makes an invite (a QR code, Share, the countdown);
// "Keep my copy at a friend's" joins one (scan or paste, the name, the passphrase or plain, an offer back).

internal class SwapActions(
    val openHere: () -> Unit = {},
    val openThere: () -> Unit = {},
    /** Opens the passphrase step for an offer back. */
    val accept: (SwapOffer) -> Unit = {},
    val decline: (SwapOffer) -> Unit = {},
    val remove: (MySwap) -> Unit = {},
    val revoke: (KeptSwap, deleteFiles: Boolean) -> Unit = { _, _ -> },
    val showPassphrase: () -> Unit = {},
)

/** Settings › Backup › Swap with a friend: the two ways in, the offers, then each swap with its space and Remove/Revoke. */
@Composable
internal fun SwapScreen(state: SwapState, nowMillis: Long, passphraseKept: Boolean, actions: SwapActions, back: () -> Unit) {
    var removing by remember { mutableStateOf<MySwap?>(null) }
    var revoking by remember { mutableStateOf<KeptSwap?>(null) }
    SwapPage(stringResource(R.string.swap_title), back) {
        Text(stringResource(R.string.swap_body), color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 32.dp))
        if (state.unsupported) {
            Note(stringResource(R.string.swap_unsupported))
            return@SwapPage
        }
        SettingsCard {
            PageRow(stringResource(R.string.swap_there_title), stringResource(R.string.swap_there_row), actions.openThere)
            if (state.canKeepFriends) {
                RowDivider()
                val free = state.info?.freeSpace?.let { stringResource(R.string.swap_here_row, sizeText(it)) } ?: ""
                PageRow(stringResource(R.string.swap_here_title), free, actions.openHere)
            }
        }
        state.offers.forEach { offer -> OfferCard(offer, nowMillis, state.acting, { actions.accept(offer) }, { actions.decline(offer) }) }

        if (state.mine.isNotEmpty()) {
            SectionTitle(stringResource(R.string.swap_mine_title))
            SettingsCard {
                state.mine.forEachIndexed { i, swap ->
                    if (i > 0) RowDivider()
                    MySwapRow(swap, state.acting) { removing = swap }
                }
            }
        }
        if (state.kept.isNotEmpty()) {
            SectionTitle(stringResource(R.string.swap_kept_title))
            SettingsCard {
                state.kept.forEachIndexed { i, swap ->
                    if (i > 0) RowDivider()
                    KeptSwapRow(swap, state.acting) { revoking = swap }
                }
            }
        }
        if (state.info?.copyKey == CopyKeyKind.PASSPHRASE || passphraseKept) {
            SectionTitle(stringResource(R.string.swap_passphrase_section))
            SettingsCard {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp)) {
                    PageRow(stringResource(R.string.swap_show_passphrase), stringResource(R.string.swap_show_passphrase_body), actions.showPassphrase)
                }
            }
        }
        state.error?.let { Note(stringResource(R.string.swap_offline)) }
    }
    removing?.let { swap ->
        ConfirmDialog(
            title = stringResource(R.string.swap_remove_title, swap.name),
            body = stringResource(R.string.swap_remove_body, swap.name),
            confirm = stringResource(R.string.swap_remove),
            onConfirm = { actions.remove(swap); removing = null },
            onDismiss = { removing = null },
        )
    }
    revoking?.let { swap -> RevokeDialog(swap, { delete -> actions.revoke(swap, delete); revoking = null }, { revoking = null }) }
}

/** "hen offers to keep your copy too (1 TB)", with its minutes left, Accept and Decline. */
@Composable
private fun OfferCard(offer: SwapOffer, nowMillis: Long, acting: Boolean, accept: () -> Unit, decline: () -> Unit) {
    val minutes = ((offer.expiresAtMillis - nowMillis) / 60_000).coerceAtLeast(0).toInt() + 1
    SettingsCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.swap_offer_title, offer.name, sizeText(offer.quota)), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.swap_offer_body, offer.name, minutes), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(stringResource(R.string.swap_offer_accept), enabled = !acting, onClick = accept)
                TextButton(onClick = decline, enabled = !acting) { Text(stringResource(R.string.swap_offer_decline), color = LibraryColors.OnBackground) }
            }
        }
    }
}

/** My copy at a friend's: the name with a lock when encrypted, how the copy is doing, the quota, and Remove. */
@Composable
private fun MySwapRow(swap: MySwap, acting: Boolean, remove: () -> Unit) {
    val format = NumberFormat.getIntegerInstance()
    val copy = swap.dataCopy
    val status = when {
        copy == null -> stringResource(R.string.swap_copy_unknown)
        copy.problem == CopyProblem.PEER_UNREACHABLE -> stringResource(R.string.swap_copy_unreachable)
        copy.problem == CopyProblem.QUOTA_FULL -> stringResource(R.string.copies_quota_full)
        copy.running != null -> stringResource(R.string.swap_copy_running)
        copy.checkedAtMillis == null -> stringResource(R.string.copies_checking)
        else -> stringResource(R.string.swap_copy_files, format.format(copy.heldFiles), format.format(copy.originals))
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(swap.name, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (swap.encrypted) {
                    Icon(LibraryIcons.Lock, stringResource(R.string.copies_encrypted), tint = LibraryColors.Muted, modifier = Modifier.padding(start = 6.dp).size(14.dp))
                }
            }
            Text(status, color = if (copy?.problem != null) LibraryColors.Error else LibraryColors.Muted, fontSize = 13.sp)
            Text(
                stringResource(if (swap.encrypted) R.string.swap_mine_quota_encrypted else R.string.swap_mine_quota_plain, sizeText(swap.quota)),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
            )
        }
        TextButton(onClick = remove, enabled = !acting) { Text(stringResource(R.string.swap_remove), color = LibraryColors.OnBackground) }
    }
}

/** A friend's copy here: the space used of the quota as a bar, and Revoke (or "Refused since" once revoked). */
@Composable
private fun KeptSwapRow(swap: KeptSwap, acting: Boolean, revoke: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(swap.name, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.swap_kept_used, sizeText(swap.used), sizeText(swap.quota)), color = LibraryColors.Muted, fontSize = 13.sp)
            LinearProgressIndicator(
                progress = { swap.fraction },
                color = LibraryColors.OnBackground,
                trackColor = LibraryColors.Muted.copy(alpha = 0.2f),
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 12.dp),
            )
            if (swap.revoked) Text(stringResource(R.string.swap_kept_revoked), color = LibraryColors.Warning, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        TextButton(onClick = revoke, enabled = !acting) {
            Text(stringResource(if (swap.revoked) R.string.swap_delete_files else R.string.swap_revoke), color = LibraryColors.OnBackground)
        }
    }
}

@Composable
private fun RevokeDialog(swap: KeptSwap, revoke: (Boolean) -> Unit, dismiss: () -> Unit) {
    var delete by remember { mutableStateOf(swap.revoked) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.swap_revoke_title, swap.name)) },
        text = {
            Column {
                Text(stringResource(R.string.swap_revoke_body, swap.name))
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(delete, { delete = it }, colors = CheckboxDefaults.colors(checkedColor = LibraryColors.OnBackground))
                    Text(stringResource(R.string.swap_revoke_delete, sizeText(swap.used)))
                }
            }
        },
        confirmButton = { TextButton(onClick = { revoke(delete) }) { Text(stringResource(R.string.swap_revoke)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.swap_cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.swap_cancel)) } },
    )
}

// --- Keep a friend's copy here ---------------------------------------------------------------------------------------

internal class SwapHereActions(
    val invite: (name: String, quota: Long) -> Unit = { _, _ -> },
    val share: (ShownInvite) -> Unit = {},
    val copy: (ShownInvite) -> Unit = {},
    /** Closes the QR code, for a new invite. */
    val again: () -> Unit = {},
    val accept: (SwapOffer) -> Unit = {},
    val decline: (SwapOffer) -> Unit = {},
    val done: () -> Unit = {},
)

/**
 * Keep a friend's copy here: the friend's name and how much to keep for them (up to the free space), then the QR code
 * with the code, the countdown, Share and Copy; once the friend's server joined, that, and their offer back to accept.
 */
@Composable
internal fun SwapHereScreen(state: SwapState, nowMillis: Long, actions: SwapHereActions, back: () -> Unit) {
    val invite = state.invite
    SwapPage(stringResource(R.string.swap_here_title), back) {
        if (invite == null) {
            val free = state.info?.freeSpace ?: 0
            var name by rememberSaveable { mutableStateOf("") }
            var quota by rememberSaveable(free) { mutableStateOf(SwapQuota.suggested(free)) }
            Text(stringResource(R.string.swap_here_body), color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 32.dp))
            SettingsCard {
                Column(Modifier.padding(16.dp)) {
                    NameField(name, { name = it }, taken = state.nameTaken(name))
                    Text(stringResource(R.string.swap_here_quota), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                    Text(stringResource(R.string.swap_here_free, sizeText(free)), color = LibraryColors.Muted, fontSize = 13.sp)
                    QuotaChips(free, quota) { quota = it }
                }
            }
            Box(Modifier.padding(horizontal = 16.dp)) {
                PrimaryButton(
                    stringResource(R.string.swap_here_make),
                    enabled = name.isNotBlank() && !state.nameTaken(name) && quota > 0 && !state.acting,
                    busy = state.acting,
                ) { actions.invite(name, quota) }
            }
            state.error?.let { Note(it) }
            return@SwapPage
        }
        val expired = invite.expired(nowMillis)
        SettingsCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    invite.joined -> {
                        Icon(LibraryIcons.Check, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(40.dp))
                        Text(stringResource(R.string.swap_here_joined, invite.name), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                        Text(stringResource(R.string.swap_here_joined_body, invite.name, sizeText(invite.quota)), color = LibraryColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                    expired -> {
                        Text(stringResource(R.string.swap_here_expired), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        TextButton(onClick = actions.again) { Text(stringResource(R.string.swap_here_again), color = LibraryColors.OnBackground) }
                    }
                    else -> {
                        Text(stringResource(R.string.swap_here_scan, invite.name), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        val qr = remember(invite.link) { qrBitmap(invite.link, 720).asImageBitmap() }
                        Image(qr, stringResource(R.string.swap_here_qr), Modifier.padding(vertical = 12.dp).size(240.dp).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(8.dp)))
                        Text(invite.code, color = LibraryColors.OnBackground, fontSize = 20.sp, fontFamily = FontFamily.Monospace)
                        val left = invite.secondsLeft(nowMillis)
                        Text(stringResource(R.string.swap_here_countdown, left / 60, left % 60), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton(stringResource(R.string.swap_share)) { actions.share(invite) }
                            OutlinedButton(onClick = { actions.copy(invite) }) { Text(stringResource(R.string.swap_copy_link), color = LibraryColors.OnBackground) }
                        }
                        Text(stringResource(R.string.swap_here_waiting, invite.name), color = LibraryColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
        state.inviteOffer?.let { offer -> OfferCard(offer, nowMillis, state.acting, { actions.accept(offer) }, { actions.decline(offer) }) }
        if (invite.joined) Box(Modifier.padding(horizontal = 16.dp)) { PrimaryButton(stringResource(R.string.swap_done), onClick = actions.done) }
    }
}

// --- Keep my copy at a friend's (and accepting an offer back) --------------------------------------------------------

internal enum class JoinStep { LINK, PROTECT, WORDS, CHECK, RESULT }

/** What the user has entered so far in "Keep my copy at a friend's"; kept while the screen shows, never saved. */
internal class JoinDraft(offer: SwapOffer? = null) {
    /** Accepting an offer back: no link step, the name is the offer's. */
    val offer: SwapOffer? = offer
    var step by mutableStateOf(if (offer != null) JoinStep.PROTECT else JoinStep.LINK)
    var linkText by mutableStateOf("")
    var name by mutableStateOf(offer?.name ?: "")
    var plain by mutableStateOf(false)
    /** The passphrase typed (copy key PASSPHRASE), or filled from the phone or Password Manager. */
    var typed by mutableStateOf("")
    var suggested by mutableStateOf<SuggestedPassphrase?>(null)
    var check1 by mutableStateOf("")
    var check2 by mutableStateOf("")
    var offerBack by mutableStateOf(false)
    var offerQuota by mutableStateOf(0L)
    var savedToManager by mutableStateOf(false)

    val link: SwapLink? get() = parseSwapLink(linkText)

    /** The passphrase joinSwap gets: the suggested words for a new copy key, else what was typed; null for plain. */
    fun passphrase(kind: CopyKeyKind?): String? = when {
        plain || kind == CopyKeyKind.KEY_FILE -> null
        kind == CopyKeyKind.NONE -> suggested?.text
        else -> typed.trim().ifEmpty { null }
    }
}

internal class SwapThereActions(
    val scan: () -> Unit = {},
    /** Fills the passphrase from the phone or Google Password Manager. */
    val fill: () -> Unit = {},
    val saveToManager: (SuggestedPassphrase) -> Unit = {},
    val shareWords: (SuggestedPassphrase) -> Unit = {},
    val copyWords: (SuggestedPassphrase) -> Unit = {},
    val recoverySheet: (SuggestedPassphrase) -> Unit = {},
    val join: () -> Unit = {},
    val done: () -> Unit = {},
)

/**
 * Keep my copy at a friend's: the link (scan or paste) and the friend's name; encrypted or plain, with an optional
 * offer back; for a new copy key, the six words shown once with Save, the recovery sheet, Share and Copy; two of
 * them typed back; then how it went. Accepting an offer back starts at the second step.
 */
@Composable
internal fun SwapThereScreen(state: SwapState, draft: JoinDraft, actions: SwapThereActions, back: () -> Unit) {
    val kind = state.info?.copyKey
    val title = if (draft.offer != null) stringResource(R.string.swap_accept_title, draft.offer.name) else stringResource(R.string.swap_there_title)
    SwapPage(title, back) {
        when (draft.step) {
            JoinStep.LINK -> LinkStep(state, draft, actions)
            JoinStep.PROTECT -> ProtectStep(state, draft, kind, actions)
            JoinStep.WORDS -> WordsStep(draft, actions)
            JoinStep.CHECK -> CheckStep(state, draft, actions)
            JoinStep.RESULT -> ResultStep(state, draft, actions)
        }
    }
}

@Composable
private fun LinkStep(state: SwapState, draft: JoinDraft, actions: SwapThereActions) {
    Text(stringResource(R.string.swap_there_body), color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 32.dp))
    SettingsCard {
        Column(Modifier.padding(16.dp)) {
            PrimaryButton(stringResource(R.string.swap_there_scan), onClick = actions.scan)
            val context = LocalContext.current
            val setLink: (String) -> Unit = {
                draft.linkText = it
                parseSwapLink(it)?.from?.let { from -> if (draft.name.isBlank()) draft.name = from }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.swap_there_or_paste), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                // The link alone, when it came in a message with words around it.
                TextButton(onClick = { pastedText(context).let { setLink(SWAP_LINK_IN_TEXT.find(it)?.value ?: it.trim()) } }) {
                    Text(stringResource(R.string.swap_there_paste))
                }
            }
            val link = draft.link
            OutlinedTextField(
                value = draft.linkText,
                onValueChange = setLink,
                placeholder = { Text("journal://swap?…") },
                isError = draft.linkText.isNotBlank() && link == null,
                supportingText = if (draft.linkText.isNotBlank() && link == null) {
                    { Text(stringResource(R.string.swap_there_bad_link)) }
                } else null,
                colors = fieldColors(),
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            NameField(draft.name, { draft.name = it }, taken = state.nameTaken(draft.name))
        }
    }
    Box(Modifier.padding(horizontal = 16.dp)) {
        PrimaryButton(stringResource(R.string.swap_next), enabled = draft.link != null && draft.name.isNotBlank() && !state.nameTaken(draft.name)) {
            draft.step = JoinStep.PROTECT
        }
    }
}

@Composable
private fun ProtectStep(state: SwapState, draft: JoinDraft, kind: CopyKeyKind?, actions: SwapThereActions) {
    SectionTitle(stringResource(R.string.swap_protect_title))
    SettingsCard {
        ModeOption(stringResource(R.string.swap_protect_encrypted), stringResource(R.string.swap_protect_encrypted_body, draft.name), !draft.plain) { draft.plain = false }
        RowDivider()
        ModeOption(stringResource(R.string.swap_protect_plain), stringResource(R.string.swap_protect_plain_body, draft.name), draft.plain) { draft.plain = true }
    }
    if (!draft.plain) {
        when (kind) {
            CopyKeyKind.KEY_FILE -> Note(stringResource(R.string.swap_protect_key_file))
            CopyKeyKind.PASSPHRASE -> SettingsCard {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.swap_protect_same), color = LibraryColors.Muted, fontSize = 13.sp)
                    OutlinedTextField(
                        value = draft.typed,
                        onValueChange = { draft.typed = it },
                        label = { Text(stringResource(R.string.swap_passphrase)) },
                        colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    TextButton(onClick = actions.fill) { Text(stringResource(R.string.swap_protect_fill), color = LibraryColors.OnBackground) }
                }
            }
            else -> Note(stringResource(R.string.swap_protect_new))
        }
    }
    // The other way: offer the friend a copy here, which their phone accepts (one scan, both ways).
    if (draft.offer == null && state.canKeepFriends) {
        val free = state.info?.freeSpace ?: 0
        SettingsCard {
            SwitchRow(stringResource(R.string.swap_offer_back, draft.name), stringResource(R.string.swap_offer_back_body, draft.name), draft.offerBack) {
                draft.offerBack = it
                if (it && draft.offerQuota == 0L) draft.offerQuota = SwapQuota.suggested(free)
            }
            if (draft.offerBack) Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { QuotaChips(free, draft.offerQuota) { draft.offerQuota = it } }
        }
    }
    val needsWords = !draft.plain && kind == CopyKeyKind.NONE
    val ready = draft.plain || kind == CopyKeyKind.KEY_FILE || kind == CopyKeyKind.NONE || draft.typed.isNotBlank()
    Box(Modifier.padding(horizontal = 16.dp)) {
        if (needsWords) {
            PrimaryButton(stringResource(R.string.swap_next)) {
                if (draft.suggested == null) draft.suggested = Passphrase.suggest()
                draft.step = JoinStep.WORDS
            }
        } else {
            PrimaryButton(stringResource(R.string.swap_start), enabled = ready && !state.acting, busy = state.acting, onClick = actions.join)
        }
    }
}

/** The six words, shown once: large and numbered, with Save, Recovery sheet, Share and Copy. */
@Composable
private fun WordsStep(draft: JoinDraft, actions: SwapThereActions) {
    val words = draft.suggested ?: return
    Text(stringResource(R.string.swap_words_body), color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 32.dp))
    // The words are English: numbered left to right in Hebrew too.
    SettingsCard {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            words.words.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    pair.forEachIndexed { col, word ->
                        Text(
                            "${row * 2 + col + 1}. $word",
                            color = LibraryColors.OnBackground,
                            fontSize = 22.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        }
    }
    SettingsCard {
        PageRow(
            stringResource(if (draft.savedToManager) R.string.swap_words_saved else R.string.swap_words_save),
            stringResource(R.string.swap_words_save_body),
        ) { actions.saveToManager(words) }
        RowDivider()
        PageRow(stringResource(R.string.swap_words_sheet), stringResource(R.string.swap_words_sheet_body)) { actions.recoverySheet(words) }
        RowDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(onClick = { actions.shareWords(words) }) { Text(stringResource(R.string.swap_share), color = LibraryColors.OnBackground) }
            TextButton(onClick = { actions.copyWords(words) }) { Text(stringResource(R.string.swap_copy), color = LibraryColors.OnBackground) }
        }
    }
    Note(stringResource(R.string.swap_words_lost))
    Box(Modifier.padding(horizontal = 16.dp)) { PrimaryButton(stringResource(R.string.swap_words_kept)) { draft.step = JoinStep.CHECK } }
}

/** Two of the words typed back, so they were surely kept. */
@Composable
private fun CheckStep(state: SwapState, draft: JoinDraft, actions: SwapThereActions) {
    val words = draft.suggested ?: return
    val right = words.typedBack(listOf(draft.check1, draft.check2))
    Text(stringResource(R.string.swap_check_body), color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 32.dp))
    SettingsCard {
        Column(Modifier.padding(16.dp)) {
            CheckField(words.check[0] + 1, draft.check1) { draft.check1 = it }
            CheckField(words.check[1] + 1, draft.check2) { draft.check2 = it }
            if (draft.check1.isNotBlank() && draft.check2.isNotBlank() && !right) {
                Text(stringResource(R.string.swap_check_wrong), color = LibraryColors.Error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(stringResource(R.string.swap_start), enabled = right && !state.acting, busy = state.acting, onClick = actions.join)
        TextButton(onClick = { draft.step = JoinStep.WORDS }) { Text(stringResource(R.string.swap_check_show_again), color = LibraryColors.OnBackground) }
    }
}

@Composable
private fun CheckField(number: Int, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.swap_check_word, number)) },
        singleLine = true,
        colors = fieldColors(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/** How it went: the copy going to the friend's (and the offer back sent), or why not with a way back. */
@Composable
private fun ResultStep(state: SwapState, draft: JoinDraft, actions: SwapThereActions) {
    val outcome = state.outcome
    if (outcome == null) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LibraryColors.OnBackground) }
        return
    }
    SettingsCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (outcome) {
                is SwapOutcome.Joined -> {
                    Icon(LibraryIcons.Check, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(40.dp))
                    Text(stringResource(R.string.swap_joined, outcome.swap.name), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        stringResource(if (outcome.swap.encrypted) R.string.swap_joined_encrypted else R.string.swap_joined_plain, outcome.swap.name, sizeText(outcome.swap.quota)),
                        color = LibraryColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    )
                    if (outcome.offered) Text(stringResource(R.string.swap_joined_offered, outcome.swap.name), color = LibraryColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    if (draft.savedToManager) Text(stringResource(R.string.swap_joined_saved), color = LibraryColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                }
                is SwapOutcome.Refused -> {
                    Text(refusalText(outcome.reason, draft.name), color = LibraryColors.OnBackground, fontSize = 16.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
    Box(Modifier.padding(horizontal = 16.dp)) {
        when (outcome) {
            is SwapOutcome.Joined -> PrimaryButton(stringResource(R.string.swap_done), onClick = actions.done)
            is SwapOutcome.Refused -> PrimaryButton(stringResource(R.string.swap_back)) { draft.step = refusalStep(outcome.reason, draft.offer != null) }
        }
    }
}

/** The step a refusal sends the user back to. */
internal fun refusalStep(reason: SwapRefusal, accepting: Boolean): JoinStep = when (reason) {
    SwapRefusal.INVALID_LINK, SwapRefusal.CODE_REFUSED, SwapRefusal.UNREACHABLE, SwapRefusal.OWN_SERVER, SwapRefusal.NAME_TAKEN,
    SwapRefusal.ALREADY_SWAPPED ->
        if (accepting) JoinStep.PROTECT else JoinStep.LINK
    else -> JoinStep.PROTECT
}

@Composable
internal fun refusalText(reason: SwapRefusal, friend: String): String = when (reason) {
    SwapRefusal.INVALID_LINK -> stringResource(R.string.swap_refused_invalid_link)
    SwapRefusal.CODE_REFUSED -> stringResource(R.string.swap_refused_code, friend)
    SwapRefusal.UNREACHABLE -> stringResource(R.string.swap_refused_unreachable, friend)
    SwapRefusal.OWN_SERVER -> stringResource(R.string.swap_refused_own)
    SwapRefusal.NAME_TAKEN -> stringResource(R.string.swap_refused_name, friend)
    SwapRefusal.NEEDS_PASSPHRASE -> stringResource(R.string.swap_refused_needs_passphrase)
    SwapRefusal.WEAK_PASSPHRASE -> stringResource(R.string.swap_refused_weak)
    SwapRefusal.WRONG_PASSPHRASE -> stringResource(R.string.swap_refused_wrong)
    SwapRefusal.NO_SPACE -> stringResource(R.string.swap_refused_no_space)
    SwapRefusal.OFFER_GONE -> stringResource(R.string.swap_refused_offer_gone, friend)
    SwapRefusal.ALREADY_SWAPPED -> stringResource(R.string.swap_refused_already, friend)
    SwapRefusal.OTHER -> stringResource(R.string.swap_refused_other)
}

// --- Show my copy passphrase ---------------------------------------------------------------------------------------

/** The passphrase, behind the phone's lock: its words large, with Copy, Share and the recovery sheet. */
@Composable
internal fun CopyPassphraseScreen(passphrase: String?, copy: () -> Unit, share: () -> Unit, sheet: () -> Unit, fill: () -> Unit, back: () -> Unit) {
    SwapPage(stringResource(R.string.swap_show_passphrase), back) {
        if (passphrase == null) {
            Note(stringResource(R.string.swap_passphrase_none))
            Box(Modifier.padding(horizontal = 16.dp)) { PrimaryButton(stringResource(R.string.swap_protect_fill), onClick = fill) }
            return@SwapPage
        }
        SettingsCard {
            Text(
                "\u200E" + passphrase,
                color = LibraryColors.OnBackground,
                fontSize = 22.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 34.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
        Note(stringResource(R.string.swap_words_lost))
        SettingsCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                TextButton(onClick = copy) { Text(stringResource(R.string.swap_copy), color = LibraryColors.OnBackground) }
                TextButton(onClick = share) { Text(stringResource(R.string.swap_share), color = LibraryColors.OnBackground) }
                TextButton(onClick = sheet) { Text(stringResource(R.string.swap_words_sheet), color = LibraryColors.OnBackground) }
            }
        }
    }
}

// --- Pieces --------------------------------------------------------------------------------------------------------

@Composable
private fun SwapPage(title: String, back: () -> Unit, content: @Composable () -> Unit) {
    val insets = barsRoom()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
        TitleBar(title, back, Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp))
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = LibraryColors.Inverse,
            contentColor = LibraryColors.OnInverse,
            disabledContainerColor = LibraryColors.Muted.copy(alpha = 0.25f),
            disabledContentColor = LibraryColors.Muted,
        ),
    ) {
        if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), color = LibraryColors.OnInverse, strokeWidth = 2.dp)
        Text(text)
    }
}

@Composable
private fun NameField(name: String, onChange: (String) -> Unit, taken: Boolean) {
    OutlinedTextField(
        value = name,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.swap_friend_name)) },
        singleLine = true,
        isError = taken,
        supportingText = if (taken) {
            { Text(stringResource(R.string.swap_name_taken)) }
        } else null,
        colors = fieldColors(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun QuotaChips(free: Long, selected: Long, onSelect: (Long) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SwapQuota.choices(free).forEach { q ->
            val label = if (q == free) stringResource(R.string.swap_quota_all, sizeText(q)) else sizeText(q)
            Chip(label, q == selected) { onSelect(q) }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = LibraryColors.OnBackground,
    unfocusedTextColor = LibraryColors.OnBackground,
    focusedBorderColor = LibraryColors.OnBackground,
    unfocusedBorderColor = LibraryColors.Muted,
    focusedLabelColor = LibraryColors.OnBackground,
    unfocusedLabelColor = LibraryColors.Muted,
    cursorColor = LibraryColors.OnBackground,
)

@Composable
private fun sizeText(bytes: Long) = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** Backup's "Swap with a friend" row: how many friends, or what it is. */
@Composable
internal fun SwapRow(state: SwapState, open: () -> Unit) {
    val friends = (state.mine.map { it.name } + state.kept.filter { !it.revoked }.map { it.name }).distinct().size
    HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
    val body = when {
        state.offers.isNotEmpty() -> stringResource(R.string.swap_row_offer, state.offers.first().name)
        friends > 0 -> androidx.compose.ui.res.pluralStringResource(R.plurals.swap_row_friends, friends, friends)
        else -> stringResource(R.string.swap_row_none)
    }
    PageRow(stringResource(R.string.swap_title), body, open)
}

private val SWAP_LINK_IN_TEXT = Regex("""journal://swap\?[^\s<>"']+""")
