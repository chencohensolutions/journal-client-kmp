package com.chencohensolution.journal.stories

/** A story's grid is this many square cells wide; tile sizes are whole cells. */
const val GRID_COLUMNS = 6

/** The sizes a tile can have on a story's grid, in cells. [fileName] is how story.json writes it. */
enum class TileSize(val columns: Int, val rows: Int, val fileName: String) {
    SMALL(2, 2, "small"),
    WIDE(4, 2, "wide"),
    TALL(2, 4, "tall"),
    HALF(3, 3, "half"),
    LARGE(4, 4, "large"),
    FULL(6, 4, "full"),
    ;

    companion object {
        fun parse(name: String?): TileSize? = entries.firstOrNull { it.fileName == name }
    }
}

/** The point of a photo that stays in view when its tile crops it: 0..1 from the left and from the top. */
data class FocusPoint(val x: Float, val y: Float) {
    companion object {
        val CENTER = FocusPoint(0.5f, 0.5f)

        fun of(x: Float, y: Float) = FocusPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }
}

/** How the owner arranged one tile of a story; whatever is null is automatic. */
data class TileLayout(val size: TileSize? = null, val focus: FocusPoint? = null) {
    val isEmpty: Boolean get() = size == null && focus == null
}

/** The key of a group's tile in [Story.layout] and [Story.tileOrder]; a photo's key is its device id. */
fun groupTileKey(groupId: String): String = "group:$groupId"

/** A tile on its band's grid, in cells from the top start corner. */
data class PlacedTile(
    val tile: StoryTile,
    val column: Int,
    val row: Int,
    val size: TileSize,
    val focus: FocusPoint,
    /** Its size was chosen by the owner rather than automatic. */
    val sized: Boolean,
)

/**
 * Rows of a day's grid that no tile crosses into or out of. Screens show a band as one piece, so a long day can
 * still be scrolled lazily.
 */
data class StoryBand(val rows: Int, val tiles: List<PlacedTile>) {
    val key: String get() = tiles.first().tile.key
}

/**
 * The automatic size: groups and the first tile of a day with three or more tiles are large, the rest small. This
 * is how stories looked before they could be arranged.
 */
internal fun autoSize(tile: StoryTile, index: Int, count: Int): TileSize =
    if (tile is StoryTile.Group || (index == 0 && count >= 3)) TileSize.LARGE else TileSize.SMALL

/**
 * Places a day's tiles, in order, on a [GRID_COLUMNS]-wide grid: each goes to the first spot from the top where it
 * fits, so a later small tile fills a hole a large one left (dense packing). Cut into [StoryBand]s.
 */
internal fun packDay(tiles: List<StoryTile>, layout: Map<String, TileLayout>): List<StoryBand> {
    val taken = mutableListOf<BooleanArray>()
    fun free(row: Int, column: Int) = row >= taken.size || !taken[row][column]
    fun fits(row: Int, column: Int, size: TileSize) =
        (row until row + size.rows).all { r -> (column until column + size.columns).all { c -> free(r, c) } }

    var firstOpen = 0
    val placed = tiles.mapIndexed { index, tile ->
        val arranged = layout[tile.key]
        val size = arranged?.size ?: autoSize(tile, index, tiles.size)
        while (firstOpen < taken.size && taken[firstOpen].all { it }) firstOpen++
        var row = firstOpen
        var column = -1
        while (column < 0) {
            column = (0..GRID_COLUMNS - size.columns).firstOrNull { fits(row, it, size) } ?: -1
            if (column < 0) row++
        }
        while (taken.size < row + size.rows) taken += BooleanArray(GRID_COLUMNS)
        for (r in row until row + size.rows) for (c in column until column + size.columns) taken[r][c] = true
        PlacedTile(tile, column, row, size, arranged?.focus ?: FocusPoint.CENTER, sized = arranged?.size != null)
    }

    // A band ends at every row boundary no tile spans.
    val crossed = BooleanArray(taken.size + 1)
    placed.forEach { tile -> for (boundary in tile.row + 1 until tile.row + tile.size.rows) crossed[boundary] = true }
    val ends = (1 until taken.size).filter { !crossed[it] } + taken.size
    var start = 0
    return ends.mapNotNull { end ->
        val band = placed.filter { it.row in start until end }.map { it.copy(row = it.row - start) }
        val rows = end - start
        start = end
        band.takeIf { it.isNotEmpty() }?.let { StoryBand(rows, it) }
    }
}

/**
 * [tiles] in the story's order: those named in [order] first, as listed; each other tile (new since the story was
 * arranged) right after the last one taken no later than it.
 */
internal fun <T : Any> arrangeTiles(tiles: List<T>, order: List<String>, key: (T) -> String, time: (T) -> Long): List<T> {
    val byKey = tiles.associateBy(key)
    val result = order.distinct().mapNotNull { byKey[it] }.toMutableList()
    val placed = result.map(key).toSet()
    tiles.sortedBy(time).filter { key(it) !in placed }.forEach { tile ->
        val at = time(tile)
        result.add(result.indexOfLast { time(it) <= at } + 1, tile)
    }
    return result
}
