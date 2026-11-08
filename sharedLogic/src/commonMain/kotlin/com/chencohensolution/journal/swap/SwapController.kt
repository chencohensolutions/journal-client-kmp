package com.chencohensolution.journal.swap

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.server.DataCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// Settings › Backup › Swap with a friend (docs/plans/swap-from-phones.md; journal-server-go docs/peers.md, "Swaps from
// the phones"). Each user sets up their own: "Keep a friend's copy here" makes an invite (a QR code and a link, 10
// minutes, once); "Keep my copy at a friend's" joins one, encrypted with a passphrase unless the user trusts the friend,
// and may offer a copy back, which the friend's phone accepts. The phone sees only its own user's swaps.

/** What joinSwap needs from the user, from their copy key on the server. */
enum class CopyKeyKind {
    /** None yet: a new passphrase makes one (the phone suggests six words). */
    NONE,

    /** Made from a passphrase: the same passphrase again (the saved one). */
    PASSPHRASE,

    /** A key file made at the server: no passphrase. */
    KEY_FILE,
}

/** What this server offers for swaps. */
data class SwapInfo(
    /** It keeps friends' copies (its admin set peers_folder): "Keep a friend's copy here" works. */
    val available: Boolean,
    /** The most a friend may be offered, in bytes; null when not [available]. */
    val freeSpace: Long?,
    val copyKey: CopyKeyKind,
    /** This server's id ("MFZWI3D-…"), for the recovery sheet; null when discovery is off. */
    val serverId: String?,
)

/** The user's copy at a friend's server. */
data class MySwap(
    /** As the user named the friend; names the copy in Copies, Remove and the recovery sheet. */
    val name: String,
    val quota: Long,
    val addedAtMillis: Long,
    val encrypted: Boolean,
    /** How the copy is doing; null when the server can't open it. (Not `copy`: NSObject's copy() in Swift.) */
    val dataCopy: DataCopy?,
)

/** A friend's copy kept on this server. */
data class KeptSwap(
    val name: String,
    val quota: Long,
    /** Bytes the friend's files take here now. */
    val used: Long,
    val addedAtMillis: Long,
    /** When Revoke refused the friend's server; its files stay until deleted. Null while kept. */
    val revokedAtMillis: Long?,
) {
    val revoked: Boolean get() = revokedAtMillis != null

    /** How full the quota is, 0 to 1. */
    val fraction: Float get() = if (quota > 0) (used.toFloat() / quota).coerceIn(0f, 1f) else 0f
}

/** A friend who joined one of the user's invites offers to keep the user's copy too, for 10 minutes. */
data class SwapOffer(
    val id: String,
    /** The friend's user on their server. */
    val from: String,
    /** The friend as the user named them in the invite: the copy's name once accepted. */
    val name: String,
    val quota: Long,
    val expiresAtMillis: Long,
)

/** An invite showing as a QR code. */
data class ShownInvite(
    val name: String,
    val quota: Long,
    val link: String,
    val code: String,
    val expiresAtMillis: Long,
    /** The friend's server joined with it: the friend's copy is now kept here. */
    val joined: Boolean = false,
) {
    /** Seconds left before the code stops working, 0 once expired. */
    fun secondsLeft(nowMillis: Long): Long = ((expiresAtMillis - nowMillis + 999) / 1000).coerceAtLeast(0)

    fun expired(nowMillis: Long): Boolean = !joined && nowMillis >= expiresAtMillis
}

/** Why the server refused to join a swap or accept an offer. */
enum class SwapRefusal {
    /** Not a swap link. */
    INVALID_LINK,

    /** The friend's server refused the code: wrong, used or expired (they make a new one), or too many tries. */
    CODE_REFUSED,

    /** The friend's server couldn't be reached at any address, nor found in the DHT. */
    UNREACHABLE,

    /** The link is this user's own server's. */
    OWN_SERVER,

    /** The user has a friend or a copy of that name already. */
    NAME_TAKEN,

    /** An encrypted copy needs the passphrase. */
    NEEDS_PASSPHRASE,

    /** Too weak for a new copy key: 24 characters, or 4 words and 20. */
    WEAK_PASSPHRASE,

    /** Not the passphrase of the user's copy key. */
    WRONG_PASSPHRASE,

    /** The copy offered back is more than this server's free space, or it keeps no friends' copies. */
    NO_SPACE,

    /** The offer isn't there any more (expired, or answered). */
    OFFER_GONE,

    /** The user keeps their copy at that friend already, under another name (journal-server 1.1.1): once per friend. */
    ALREADY_SWAPPED,

    /** A reason this app doesn't know yet (a newer server): its [SwapOutcome.Refused.message] says it. */
    OTHER,
}

/** How the last join or accept ended. */
sealed interface SwapOutcome {
    /** The copy goes to the friend's now; [offered] when a copy here was offered back. */
    data class Joined(val swap: MySwap, val offered: Boolean) : SwapOutcome

    /** [message] is the server's, in English; the apps show their own words for [reason]. */
    data class Refused(val reason: SwapRefusal, val message: String) : SwapOutcome
}

data class SwapState(
    /** Null until the server answered serverInfo.swap, or when it has no swaps (older than 1.1.0). */
    val info: SwapInfo? = null,
    val mine: List<MySwap> = emptyList(),
    val kept: List<KeptSwap> = emptyList(),
    val offers: List<SwapOffer> = emptyList(),
    /** The swaps were answered at least once. */
    val loaded: Boolean = false,
    /** The server has no swaps: it is older than journal-server 1.1.0. */
    val unsupported: Boolean = false,
    /** The invite showing as a QR code, until closed. */
    val invite: ShownInvite? = null,
    /** How the last join or accept ended, until [SwapController.clearOutcome]. */
    val outcome: SwapOutcome? = null,
    /** A call is out: the buttons wait. */
    val acting: Boolean = false,
    /** The last call failed (the server can't be reached, or answered an error). */
    val error: String? = null,
) {
    /** The offer for the invite showing, once its friend joined with an offer back. */
    val inviteOffer: SwapOffer? get() = invite?.let { inv -> offers.firstOrNull { it.name == inv.name } }

    /** "Keep a friend's copy here" shows. */
    val canKeepFriends: Boolean get() = info?.available == true

    /** Names the user already gave friends, both ways: a new one must differ ([SwapRefusal.NAME_TAKEN]). */
    fun nameTaken(name: String): Boolean {
        val n = name.trim()
        return mine.any { it.name.equals(n, ignoreCase = true) } || kept.any { it.name.equals(n, ignoreCase = true) }
    }

    companion object {
        /** Before the server answered; for Swift, which has no default arguments. */
        val NONE = SwapState()
    }
}

internal data class SwapsAnswer(val mine: List<MySwap>, val kept: List<KeptSwap>, val offers: List<SwapOffer>)

/** The swap calls (Swaps.graphql); [HttpSwapApi] over the server, a fake in tests. */
internal interface SwapApi {
    /** Null when the server has no swaps (older than 1.1.0). */
    suspend fun info(): SwapInfo?
    suspend fun swaps(): SwapsAnswer
    suspend fun invite(name: String, quota: Long): ShownInvite
    suspend fun join(link: String, name: String, passphrase: String?, plain: Boolean, offerQuota: Long?): SwapOutcome
    suspend fun accept(id: String, passphrase: String?, plain: Boolean): SwapOutcome
    suspend fun decline(id: String): Boolean
    suspend fun remove(name: String): Boolean
    suspend fun revoke(name: String, deleteFiles: Boolean): Boolean
}

/**
 * The user's swaps, for Settings › Backup › Swap with a friend. [refresh] asks once; while the page shows, [watch]
 * asks every [inviteEveryMillis] while an invite shows (so the friend's join and offer back appear at once), every
 * [idleEveryMillis] otherwise.
 */
@OptIn(ExperimentalTime::class)
class SwapController internal constructor(
    private val api: SwapApi,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val inviteEveryMillis: Long = 3_000,
    private val idleEveryMillis: Long = 30_000,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow(SwapState())
    val state: StateFlow<SwapState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift. */
    val currentState: SwapState get() = _state.value

    /** The clock the countdown uses, for the apps. */
    fun nowMillis(): Long = now()

    /** Asks the server for its swap info and the swaps once. */
    fun refresh() {
        scope.launch { ask(withInfo = true) }
    }

    /** Asks until the handle is cancelled: while the Swap page shows. */
    fun watch(): ObservationHandle {
        val job = scope.launch {
            var withInfo = true
            while (true) {
                ask(withInfo)
                withInfo = false
                val invite = _state.value.invite
                val quick = invite != null && !invite.expired(now()) && (!invite.joined || _state.value.inviteOffer == null)
                withTimeoutOrNull(if (quick) inviteEveryMillis else idleEveryMillis) { wake.receive() }
            }
        }
        return ObservationHandle { job.cancel() }
    }

    /** Makes an invite for a friend named [name] to keep their copy here, up to [quota] bytes, and shows it. */
    fun invite(name: String, quota: Long) = act { api.invite(name.trim(), quota).also { inv -> _state.update { it.copy(invite = inv) } } }

    /** Closes the QR code (the code still works until it expires). */
    fun closeInvite() {
        _state.update { it.copy(invite = null) }
    }

    /**
     * Keeps the user's copy at the server that made [link], named [name] here: encrypted with [passphrase] unless
     * [plain]; with [offerQuota] (bytes, or null), offers the friend a copy here in return.
     */
    fun join(link: String, name: String, passphrase: String?, plain: Boolean, offerQuota: Long?) = act {
        val outcome = api.join(link.trim(), name.trim(), passphrase.takeUnless { plain }, plain, offerQuota)
        _state.update { it.copy(outcome = outcome) }
        if (outcome is SwapOutcome.Joined) ask(withInfo = true)
    }

    /** For Swift: [join] with no offer back (Kotlin's nullable Long arrives as KotlinLong there). */
    fun joinWithoutOffer(link: String, name: String, passphrase: String?, plain: Boolean) = join(link, name, passphrase, plain, null)

    /** Accepts [offer]: the user's copy goes to that friend's server too, encrypted with [passphrase] unless [plain]. */
    fun accept(offer: SwapOffer, passphrase: String?, plain: Boolean) = act {
        val outcome = api.accept(offer.id, passphrase.takeUnless { plain }, plain)
        _state.update { it.copy(outcome = outcome) }
        if (outcome is SwapOutcome.Joined || (outcome as? SwapOutcome.Refused)?.reason == SwapRefusal.OFFER_GONE) {
            ask(withInfo = true)
        }
    }

    /** Declines [offer]: the copy kept here for that friend stays; the user's copy doesn't go there. */
    fun decline(offer: SwapOffer) = act {
        api.decline(offer.id)
        _state.update { s -> s.copy(offers = s.offers.filter { it.id != offer.id }) }
        ask(withInfo = false)
    }

    /** Stops keeping the user's copy at the friend [name]; the files there stay until the friend removes them. */
    fun remove(name: String) = act {
        api.remove(name)
        ask(withInfo = false)
    }

    /** Stops keeping friend [name]'s copy here; with [deleteFiles], deletes its files to free the space. */
    fun revoke(name: String, deleteFiles: Boolean) = act {
        api.revoke(name, deleteFiles)
        ask(withInfo = true)
    }

    /** Forgets how the last join or accept ended (the screen showed it). */
    fun clearOutcome() {
        _state.update { it.copy(outcome = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun act(block: suspend () -> Unit) {
        if (_state.value.acting) return
        _state.update { it.copy(acting = true, error = null) }
        scope.launch {
            try {
                block()
                _state.update { it.copy(acting = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(acting = false, error = e.message ?: "The server can't be reached") }
            }
            wake.trySend(Unit)
        }
    }

    private suspend fun ask(withInfo: Boolean) {
        try {
            if (withInfo || _state.value.info == null) {
                val info = api.info()
                if (info == null) {
                    _state.update { it.copy(unsupported = true, loaded = true) }
                    return
                }
                _state.update { it.copy(info = info, unsupported = false) }
            }
            val answer = api.swaps()
            _state.update { s ->
                val invite = s.invite?.let { inv ->
                    if (!inv.joined && answer.kept.any { it.name == inv.name && !it.revoked }) inv.copy(joined = true) else inv
                }
                s.copy(mine = answer.mine, kept = answer.kept, offers = answer.offers, invite = invite, loaded = true, error = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "The server can't be reached") }
        }
    }

    fun observe(onChange: (SwapState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() = scope.cancel()
}

/** Quotas to offer a friend, up to the free space. */
object SwapQuota {
    private const val GB = 1_000_000_000L

    /** 50 GB, 100 GB, 250 GB, 500 GB, 1 TB, 2 TB… up to [freeSpace], and [freeSpace] itself last. */
    fun choices(freeSpace: Long): List<Long> {
        if (freeSpace <= 0) return emptyList()
        val steps = listOf(50L, 100L, 250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L).map { it * GB }
        return (steps.filter { it < freeSpace } + freeSpace).distinct()
    }

    /** The default choice: 500 GB, or at most half the free space when that is less (the smallest choice at least). */
    fun suggested(freeSpace: Long): Long {
        val all = choices(freeSpace)
        return all.lastOrNull { it <= minOf(500 * GB, freeSpace / 2) } ?: all.firstOrNull() ?: 0
    }
}
