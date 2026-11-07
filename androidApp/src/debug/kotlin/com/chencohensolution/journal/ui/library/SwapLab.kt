package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.swap.CopyKeyKind
import com.chencohensolution.journal.swap.KeptSwap
import com.chencohensolution.journal.swap.MySwap
import com.chencohensolution.journal.swap.ShownInvite
import com.chencohensolution.journal.swap.SuggestedPassphrase
import com.chencohensolution.journal.swap.SwapInfo
import com.chencohensolution.journal.swap.SwapOffer
import com.chencohensolution.journal.swap.SwapOutcome
import com.chencohensolution.journal.swap.SwapRefusal
import com.chencohensolution.journal.swap.SwapState

// Made-up swaps for the Swap with a friend previews (SwapScreenshots.kt).

private const val GB = 1_000_000_000L

/** The clock the made-up invites and offers count down from. */
internal const val labSwapNow = 1_800_000_000_000L

internal val labSwapLink = "journal://swap?address=https%3A%2F%2F203.0.113.5%3A56398&code=K7QM-4XPD&from=dana&id=" +
    "MFZWI3D-BONSGYC-MFZWI3D-BONSGYC-MFZWI3D-BONSGYC-MFZWI3D-BONSGYC&key=" + "ab".repeat(32)

internal val labSwapInfo = SwapInfo(available = true, freeSpace = 1_840 * GB, copyKey = CopyKeyKind.NONE, serverId = "MFZWI3D-BONSGYC-MFZWI3D-BONSGYC")

internal val labSwaps = SwapState(
    info = labSwapInfo.copy(copyKey = CopyKeyKind.PASSPHRASE),
    mine = listOf(
        MySwap("Dana", 1_000 * GB, labSwapNow, encrypted = true, dataCopy = labCopies.copies.firstOrNull { it.onPeer } ?: labCopies.copies.first()),
        MySwap("Noa", 250 * GB, labSwapNow, encrypted = false, dataCopy = null),
    ),
    kept = listOf(
        KeptSwap("Dana", 500 * GB, 212 * GB, labSwapNow, null),
        KeptSwap("Avi", 100 * GB, 96 * GB, labSwapNow, revokedAtMillis = labSwapNow),
    ),
    offers = listOf(SwapOffer("o1", "noa", "Noa", 300 * GB, labSwapNow + 7 * 60_000)),
    loaded = true,
)

internal val labSwapsEmpty = SwapState(info = labSwapInfo, loaded = true)

internal val labInvite = ShownInvite("Dana", 500 * GB, labSwapLink, "K7QM-4XPD", labSwapNow + 9 * 60_000 + 41_000)

internal val labWords = SuggestedPassphrase(listOf("velvet", "outlast", "gravy", "unicorn", "sprinkle", "humble"), listOf(1, 4))

internal fun labDraft(step: JoinStep, offer: SwapOffer? = null) = JoinDraft(offer).apply {
    this.step = step
    linkText = labSwapLink
    name = "Dana"
    suggested = labWords
    offerBack = true
    offerQuota = 500 * GB
    check1 = "outlast"
}

internal val labJoined = SwapOutcome.Joined(MySwap("Dana", 500 * GB, labSwapNow, encrypted = true, dataCopy = null), offered = true)
internal val labRefused = SwapOutcome.Refused(SwapRefusal.CODE_REFUSED, "the code was used")

