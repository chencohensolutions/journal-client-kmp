package com.chencohensolution.journal.ui.library

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.journal.R
import com.chencohensolution.journal.swap.CopyKeyKind
import com.chencohensolution.journal.swap.SuggestedPassphrase
import com.chencohensolution.journal.swap.SwapController
import com.chencohensolution.journal.swap.SwapOutcome
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The Swap with a friend routes wired to the SwapController and the phone (scanner, Credential Manager, the share
// sheet, the recovery sheet). The screens themselves are in SwapScreen.kt.

/** What the Swap routes keep while they show: the draft of a join, and a swap link opened from outside the app. */
internal class SwapRouteMemory {
    var draft by mutableStateOf<JoinDraft?>(null)
}

@Composable
internal fun SwapRoute(route: Route, swaps: SwapController, memory: SwapRouteMemory, push: (Route) -> Unit, pop: () -> Unit) {
    val state by swaps.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val keeper = remember { PassphraseKeeper(context) }
    // While a Swap page shows: asked every 3 s while an invite shows, else every 30 s.
    DisposableEffect(Unit) {
        val watch = swaps.watch()
        onDispose { watch.cancel() }
    }
    var now by remember { mutableLongStateOf(swaps.nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = swaps.nowMillis()
            delay(1_000)
        }
    }
    val copied = stringResource(R.string.swap_copied)

    fun sheet(words: List<String>, friend: String) =
        shareRecoverySheet(context, RecoverySheet(words, friend, state.info?.serverId, user = null))

    when (route) {
        Route.Swap -> SwapScreen(
            state, now, keeper.kept,
            SwapActions(
                openHere = { swaps.closeInvite(); push(Route.SwapHere) },
                openThere = { memory.draft = JoinDraft(); swaps.clearOutcome(); push(Route.SwapThere()) },
                accept = { offer -> openAccept(offer, swaps, memory, push) },
                decline = swaps::decline,
                remove = { swaps.remove(it.name) },
                revoke = { swap, delete -> swaps.revoke(swap.name, delete) },
                showPassphrase = { push(Route.CopyPassphrase) },
            ),
            back = pop,
        )
        Route.SwapHere -> SwapHereScreen(
            state, now,
            SwapHereActions(
                invite = swaps::invite,
                share = { shareText(context, context.getString(R.string.swap_share_invite, it.link)) },
                copy = { copyText(context, it.link, sensitive = false); Toast.makeText(context, copied, Toast.LENGTH_SHORT).show() },
                again = swaps::closeInvite,
                accept = { offer -> openAccept(offer, swaps, memory, push) },
                decline = swaps::decline,
                done = { swaps.closeInvite(); pop() },
            ),
            back = { swaps.closeInvite(); pop() },
        )
        is Route.SwapThere -> {
            val offer = route.offerId?.let { id -> state.offers.firstOrNull { it.id == id } }
            val draft = memory.draft ?: JoinDraft(offer).also { memory.draft = it }
            // A new copy key from the suggested words: kept on this phone at once when the copy started, and offered to
            // Google Password Manager if the user didn't save it there yet.
            LaunchedEffect(state.outcome) {
                val joined = state.outcome as? SwapOutcome.Joined ?: return@LaunchedEffect
                val passphrase = draft.passphrase(state.info?.copyKey.takeIf { draft.suggested == null } ?: CopyKeyKind.NONE)
                if (joined.swap.encrypted && passphrase != null) {
                    keeper.save(passphrase)
                    if (!draft.savedToManager && activity != null) draft.savedToManager = saveToPasswordManager(activity, state.info?.serverId, passphrase)
                }
            }
            SwapThereScreen(
                state, draft,
                SwapThereActions(
                    scan = {
                        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                        GmsBarcodeScanning.getClient(context, options).startScan()
                            .addOnSuccessListener { barcode ->
                                barcode.rawValue?.let { raw ->
                                    draft.linkText = raw
                                    com.chencohensolution.journal.swap.parseSwapLink(raw)?.from?.let { if (draft.name.isBlank()) draft.name = it }
                                }
                            }
                            .addOnFailureListener { Toast.makeText(context, R.string.swap_scan_failed, Toast.LENGTH_LONG).show() }
                    },
                    fill = {
                        keeper.read()?.let { draft.typed = it } ?: activity?.let { a ->
                            scope.launch { passphraseFromPasswordManager(a)?.let { draft.typed = it } }
                        }
                    },
                    saveToManager = { words: SuggestedPassphrase ->
                        activity?.let { a -> scope.launch { draft.savedToManager = saveToPasswordManager(a, state.info?.serverId, words.text) } }
                    },
                    shareWords = { shareText(context, context.getString(R.string.swap_share_words, it.text)) },
                    copyWords = { copyText(context, it.text, sensitive = true); Toast.makeText(context, copied, Toast.LENGTH_SHORT).show() },
                    recoverySheet = { sheet(it.words, draft.name) },
                    join = {
                        swaps.clearOutcome()
                        val kind = state.info?.copyKey
                        val passphrase = draft.passphrase(kind)
                        draft.step = JoinStep.RESULT
                        val accepting = draft.offer
                        if (accepting != null) {
                            swaps.accept(accepting, passphrase, draft.plain)
                        } else {
                            swaps.join(draft.linkText, draft.name, passphrase, draft.plain, draft.offerQuota.takeIf { draft.offerBack && it > 0 })
                        }
                    },
                    done = { memory.draft = null; swaps.clearOutcome(); pop() },
                ),
                back = { memory.draft = null; swaps.clearOutcome(); pop() },
            )
        }
        Route.CopyPassphrase -> PhoneLocked(stringResource(R.string.swap_show_passphrase), pop) {
            var passphrase by remember { mutableStateOf(keeper.read()) }
            CopyPassphraseScreen(
                passphrase,
                copy = { passphrase?.let { copyText(context, it, sensitive = true); Toast.makeText(context, copied, Toast.LENGTH_SHORT).show() } },
                share = { passphrase?.let { shareText(context, context.getString(R.string.swap_share_words, it)) } },
                sheet = { passphrase?.let { p -> sheet(p.split(" "), state.mine.firstOrNull { it.encrypted }?.name ?: "") } },
                fill = {
                    activity?.let { a ->
                        scope.launch { passphraseFromPasswordManager(a)?.let { keeper.save(it); passphrase = it } }
                    }
                },
                back = pop,
            )
        }
        else -> Unit
    }
}

/** Accepting an offer back: with a key file nothing is asked; otherwise the passphrase step first. */
private fun openAccept(offer: com.chencohensolution.journal.swap.SwapOffer, swaps: SwapController, memory: SwapRouteMemory, push: (Route) -> Unit) {
    swaps.clearOutcome()
    memory.draft = JoinDraft(offer)
    push(Route.SwapThere(offer.id))
}
