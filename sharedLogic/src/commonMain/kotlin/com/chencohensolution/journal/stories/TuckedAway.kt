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

/** A tucked-away tag revealed on one day ([TuckedSession.dayTags]); [tag] in lower case. */
data class DayTag(val day: LocalDay, val tag: String)

/** What this session reveals of what is tucked away, and whether the phone's lock was passed for it. */
data class TuckedSession(
    /** The phone's lock was passed: revealing, Bring back and Unmark private don't ask again until the session ends. */
    val unlocked: Boolean = false,
    /** The days whose tucked photos (tucked as files) show, in place, with a small mark. */
    val days: Set<LocalDay> = emptySet(),
    /** A tucked tag's photos shown on one day: its day pill was tapped (Nov 19, 2026). */
    val dayTags: Set<DayTag> = emptySet(),
    /** Tucked tags shown on every day: Show everywhere for now, on the tag's page (lower case). */
    val tags: Set<String> = emptySet(),
) {
    fun shows(day: LocalDay): Boolean = day in days

    /** Whether the photos of the tucked tag [tag] show on [day]. */
    fun showsTag(day: LocalDay, tag: String): Boolean = tag.lowercase().let { it in tags || DayTag(day, it) in dayTags }

    /** Whether the tucked tag [tag] shows everywhere (Show everywhere for now). */
    fun showsEverywhere(tag: String): Boolean = tag.lowercase() in tags

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

    /**
     * A tucked tag's day pill ("3 Dog") was tapped: hides that tag's photos of [days] when any of them shows, else
     * reveals them (asks nothing, as [show]).
     */
    fun toggleTag(days: Collection<LocalDay>, tag: String) {
        val pairs = days.map { DayTag(it, tag.lowercase()) }.toSet()
        _state.update { session ->
            if (pairs.any { it in session.dayTags }) session.copy(dayTags = session.dayTags - pairs) else session.copy(dayTags = session.dayTags + pairs)
        }
    }

    /** Show everywhere for now: the tucked tag [tag]'s photos show on every day until the session ends. */
    fun showEverywhere(tag: String) = _state.update { it.copy(tags = it.tags + tag.lowercase()) }

    /** Hide again: what [showEverywhere] showed of [tag], and its days revealed one by one, are hidden again. */
    fun hideEverywhere(tag: String) {
        val lower = tag.lowercase()
        _state.update { session -> session.copy(tags = session.tags - lower, dayTags = session.dayTags.filterTo(HashSet()) { it.tag != lower }) }
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
 * storyline photos are tucked away as files ([tucked], revealed or not), and whether they show ([revealed]); and a pill
 * per tucked tag with photos that day ([tags]).
 */
data class StorylineDay(
    val day: LocalDay,
    val items: List<MediaItem>,
    val tucked: Int,
    val revealed: Boolean,
    val tags: List<TuckedTagCount> = emptyList(),
)

/**
 * The storyline ([all], newest first, tucked ones too) by day, as shown with [session] and the tucked tags [tagged]: a
 * day whose photos are all tucked away is still a day, with its counts and no photos.
 */
fun storylineDays(all: List<MediaItem>, session: TuckedSession, tagged: TagTucking = TagTucking.NONE): List<StorylineDay> =
    all.groupBy { it.day }.map { (day, items) ->
        val tucked = items.count { it.isTucked }
        val revealed = tucked > 0 && session.shows(day)
        StorylineDay(
            day,
            items.filter { (!it.isTucked || revealed) && !tagged.hides(it) },
            tucked,
            revealed,
            tagged.countsOf(items),
        )
    }

/** A tucked tag's pill on a day or a Moments row: "3 Dog"; [shown] while its photos there are revealed. */
data class TuckedTagCount(val tag: String, val count: Int, val shown: Boolean)

/**
 * Tucked-away tags (Nov 19, 2026): a photo whose note carries a tucked tag leaves Moments, the storyline, Explore and
 * search as a tucked file does, unless it is highlighted ([PhotoNote.highlight]: a highlight shows through). A file
 * tucked away itself stays so whatever its tags; it counts under the generic pill, never a tag's.
 */
data class TagTucking(
    /**
     * Photo id to the tucked tag that hides it, as written on its note: the first of its tucked tags alphabetically,
     * so a photo counts once. Highlighted photos aren't in it.
     */
    val hiddenBy: Map<String, String> = emptyMap(),
    /** What this session reveals ([TuckedSession.dayTags], [TuckedSession.tags]). */
    val session: TuckedSession = TuckedSession.NONE,
) {
    /** The tucked tag that takes [item] out of the feeds, whether revealed or not; null for one tucked as a file. */
    fun tagOf(item: MediaItem): String? = if (item.isTucked) null else hiddenBy[item.id]

    /** Whether a tucked tag keeps [item] out of sight now (not revealed this session). */
    fun hides(item: MediaItem): Boolean = tagOf(item)?.let { !session.showsTag(item.day, it) } ?: false

    /** The pills of [items] (a day's, a row's): each tucked tag with photos among them, by name. */
    fun countsOf(items: List<MediaItem>): List<TuckedTagCount> {
        if (hiddenBy.isEmpty()) return emptyList()
        return items.mapNotNull { item -> tagOf(item)?.let { it to item } }
            .groupBy { it.first.lowercase() }
            .map { (_, uses) -> TuckedTagCount(uses.first().first, uses.size, uses.any { (tag, item) -> session.showsTag(item.day, tag) }) }
            .sortedBy { it.tag.lowercase() }
    }

    companion object {
        val NONE = TagTucking()

        /** From [notes] and the tags tucked away ([tucked], compared ignoring case). */
        fun of(notes: Map<String, PhotoNote>, tucked: Collection<String>, session: TuckedSession = TuckedSession.NONE): TagTucking {
            if (tucked.isEmpty()) return TagTucking(session = session)
            val lower = tucked.mapTo(HashSet()) { it.lowercase() }
            val hiddenBy = HashMap<String, String>()
            notes.values.forEach { note ->
                if (note.highlight != null) return@forEach
                note.tags.filter { it.lowercase() in lower }.minByOrNull { it.lowercase() }?.let { hiddenBy[note.deviceId] = it }
            }
            return TagTucking(hiddenBy, session)
        }
    }
}

/** Whether every one of [items] (photos picked) is tucked away: then the picking bar offers Bring back. */
fun allTucked(items: Collection<MediaItem>): Boolean = items.isNotEmpty() && items.all { it.isTucked }
