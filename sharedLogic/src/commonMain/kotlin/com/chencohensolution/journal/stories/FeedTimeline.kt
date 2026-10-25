package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.LocalDay

// The months and days of a Moments feed, for going to a date in it: the month pill above the tabs, the dial of months
// and the strip of days (docs/screens/moments.md, "Going to a date"). Worked out from the feed, never saved.

/** A day with shown photos, and the feed row it is in: the newest one when the day is split over more than one. */
data class TimelineDay(val day: LocalDay, val count: Int, val sectionKey: String)

/** A month with shown photos; [days] newest first. */
data class TimelineMonth(val year: Int, val month: Int, val days: List<TimelineDay>) {
    val count: Int = days.sumOf { it.count }

    /** How many days the month has: 28 to 31. */
    val length: Int = (firstDay.plusDays(32).let { LocalDay(it.year, it.month, 1) }.toEpochDay() - firstDay.toEpochDay()).toInt()

    private val firstDay: LocalDay get() = LocalDay(year, month, 1)
    private val byDayOfMonth: Map<Int, TimelineDay> = days.associateBy { it.day.day }

    /** The day [dayOfMonth] (1-based) if it has photos. */
    fun dayOn(dayOfMonth: Int): TimelineDay? = byDayOfMonth[dayOfMonth]

    /**
     * Blank cells before the 1st in a calendar whose weeks start on [firstWeekday], ISO numbered (1 Monday … 7
     * Sunday).
     */
    fun leadingBlanks(firstWeekday: Int): Int = (firstDay.isoWeekday - firstWeekday + 7) % 7
}

/** [months] newest first, as the feed. Indexes into it are what the pill, the dial and the strip pass around. */
class FeedTimeline(val months: List<TimelineMonth>) {
    /** Newest first. */
    val years: List<Int> = months.map { it.year }.distinct()

    /** The month with the most photos: the dial's bars are sized against it. */
    val peak: Int = months.maxOfOrNull { it.count } ?: 0

    private val monthOfSection: Map<String, Int> = buildMap {
        months.forEachIndexed { i, month -> month.days.forEach { if (it.sectionKey !in this) put(it.sectionKey, i) } }
    }

    val isEmpty: Boolean get() = months.isEmpty()

    /** The month a feed row is in: its newest day's. */
    fun monthOf(sectionKey: String): Int? = monthOfSection[sectionKey]

    /** [by] months with photos from [from], positive being newer; it stops at the newest and the oldest. */
    fun step(from: Int, by: Int): Int = (from - by).coerceIn(0, (months.size - 1).coerceAtLeast(0))

    fun isNewest(month: Int): Boolean = month <= 0
    fun isOldest(month: Int): Boolean = month >= months.size - 1

    /** Where going to [month] lands: its newest day, the top of the month in a feed read newest first. */
    fun landing(month: Int): TimelineDay = months[month].days.first()

    /** The day with photos nearest to [dayOfMonth] in [month]; the later one on a tie. */
    fun nearestDay(month: Int, dayOfMonth: Int): TimelineDay? =
        months[month].days.minWithOrNull(compareBy<TimelineDay> { kotlin.math.abs(it.day.day - dayOfMonth) }.thenByDescending { it.day.day })

    /** The month with photos nearest to [year]-[month]; the newer one on a tie. */
    fun nearestMonth(year: Int, month: Int): Int? {
        val wanted = year * 12 + month
        return months.indices.minWithOrNull(compareBy<Int> { kotlin.math.abs(months[it].year * 12 + months[it].month - wanted) }.thenBy { it })
    }

    /** Whether going from one month to another changes the year: a firmer tick. */
    fun crossesYear(from: Int, to: Int): Boolean = months[from].year != months[to].year

    companion object {
        val NONE = FeedTimeline(emptyList())

        fun of(feed: MomentsFeed): FeedTimeline {
            val days = LinkedHashMap<LocalDay, TimelineDay>()
            feed.entries.forEach { entry ->
                val section = (entry as? FeedEntry.Section)?.section ?: return@forEach
                section.items.groupingBy { it.day }.eachCount().forEach { (day, count) ->
                    val known = days[day]
                    days[day] = known?.copy(count = known.count + count) ?: TimelineDay(day, count, section.key)
                }
            }
            return of(days.values)
        }

        /** The timeline of [days], each with the row it is in (the database's rows: FeedTable.timeline). */
        internal fun of(days: Collection<TimelineDay>): FeedTimeline {
            val months = days.sortedByDescending { it.day }.groupBy { it.day.year to it.day.month }
                .map { (yearMonth, days) -> TimelineMonth(yearMonth.first, yearMonth.second, days) }
            return FeedTimeline(months)
        }
    }
}

/** 1 Monday … 7 Sunday. 1970-01-01 was a Thursday. */
internal val LocalDay.isoWeekday: Int get() = (toEpochDay() + 3).mod(7L).toInt() + 1
