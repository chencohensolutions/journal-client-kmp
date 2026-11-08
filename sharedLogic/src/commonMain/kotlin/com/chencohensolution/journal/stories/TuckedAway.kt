package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/*
 * Tucked away (Nov 8, 2026; "מוצנע"): a casual private level. A tucked photo leaves Moments, the storyline and Explore
 * and is never in search, but each day that has some says so ("3 tucked away"); tapping that reveals them in place for
 * the session. Nothing about revealing is saved.
 */

/** What this session reveals of what is tucked away, and whether the phone's lock was passed for it. */
data class TuckedSession(
    /** The phone's lock was passed: revealing, Bring back and Unmark private don't ask again until the session ends. */
    val unlocked: Boolean = false,
    /** The days whose tucked photos show, in place, with a small mark. */
    val days: Set<LocalDay> = emptySet(),
) {
    fun shows(day: LocalDay): Boolean = day in days

    /** Whether [item] shows: not tucked, or tucked on a day revealed. */
    fun shows(item: MediaItem): Boolean = !item.isTucked || item.day in days

    companion object {
        val NONE = TuckedSession()
    }
}

/**
 * The session of [TuckedSession]: revealed days and the unlock last until the next app load, a process start or the
 * app coming back after more than [SESSION_GAP_MILLIS] in the background. [nowMillis] is the clock (tests give theirs).
 */
@OptIn(ExperimentalTime::class)
class TuckedReveal(private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val _state = MutableStateFlow(TuckedSession.NONE)
    val state: StateFlow<TuckedSession> = _state.asStateFlow()

    /** Same as `state.value`, for Swift. */
    val current: TuckedSession get() = _state.value

    private var backgroundSince: Long? = null

    /** Whether revealing (or Bring back, Unmark private) must ask for the phone's lock first. */
    val needsLock: Boolean get() = !_state.value.unlocked

    /** The phone's lock was passed. */
    fun unlocked() = _state.update { it.copy(unlocked = true) }

    /** Reveals the tucked photos of [days]; asks nothing (the screen asked for the lock when [needsLock]). */
    fun show(days: Collection<LocalDay>) = _state.update { it.copy(days = it.days + days) }

    /** Hides them again. */
    fun hide(days: Collection<LocalDay>) = _state.update { it.copy(days = it.days - days.toSet()) }

    /** A day's pill was tapped: hides its days when any of them shows, else reveals them. */
    fun toggle(days: Collection<LocalDay>) {
        if (days.any { it in _state.value.days }) hide(days) else show(days)
    }

    /** The app went to the background. */
    fun wentToBackground() {
        if (backgroundSince == null) backgroundSince = nowMillis()
    }

    /** The app is in front again: after more than [SESSION_GAP_MILLIS] away, the session starts over. */
    fun cameBack() {
        val since = backgroundSince ?: return
        backgroundSince = null
        if (nowMillis() - since > SESSION_GAP_MILLIS) _state.value = TuckedSession.NONE
    }

    companion object {
        /** Away longer than this, the app comes back as a new session: nothing revealed, locked again. */
        const val SESSION_GAP_MILLIS = 5 * 60_000L
    }
}

/**
 * The storyline's day as the screen draws it: its photos shown ([items], newest first), how many of the day's
 * storyline photos are tucked away ([tucked], revealed or not), and whether they show ([revealed]).
 */
data class StorylineDay(val day: LocalDay, val items: List<MediaItem>, val tucked: Int, val revealed: Boolean)

/**
 * The storyline ([all], newest first, tucked ones too) by day, as shown with [session]: a day whose photos are all
 * tucked away is still a day, with its count and no photos.
 */
fun storylineDays(all: List<MediaItem>, session: TuckedSession): List<StorylineDay> =
    all.groupBy { it.day }.map { (day, items) ->
        val tucked = items.count { it.isTucked }
        val revealed = tucked > 0 && session.shows(day)
        StorylineDay(day, if (revealed) items else items.filterNot { it.isTucked }, tucked, revealed)
    }

/** Whether every one of [items] (photos picked) is tucked away: then the picking bar offers Bring back. */
fun allTucked(items: Collection<MediaItem>): Boolean = items.isNotEmpty() && items.all { it.isTucked }
