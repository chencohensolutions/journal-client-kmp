package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.MediaItem

/** A story's grid is this many square cells wide; tile sizes are whole cells. */
const val GRID_COLUMNS = 6

/**
 * The sizes a tile can have on a story's grid, in cells. [fileName] is how story.json writes it. [ORIGINAL] is the
 * full width with the photo's own aspect ratio, so it isn't cropped; its [rows] are only nominal.
 */
enum class TileSize(val columns: Int, val rows: Int, val fileName: String) {
    SMALL(2, 2, "small"),
    WIDE(4, 2, "wide"),
    TALL(2, 4, "tall"),
    HALF(3, 3, "half"),
    LARGE(4, 4, "large"),
    FULL(6, 4, "full"),
    ORIGINAL(6, 4, "original"),
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

/** How far a tile may zoom into its photo, around its focus point. */
const val MAX_ZOOM = 4f

/** How the owner arranged one tile of a story; whatever is null is automatic. */
data class TileLayout(val size: TileSize? = null, val focus: FocusPoint? = null, val zoom: Float? = null) {
    val isEmpty: Boolean get() = size == null && focus == null && zoom == null
}

/** 1 (the whole tile filled, nothing more) to [MAX_ZOOM]; 1 is stored as no zoom. */
internal fun cleanZoom(zoom: Float?): Float? = zoom?.coerceIn(1f, MAX_ZOOM)?.takeIf { it > 1.001f }

/** The key of a group's tile in [Story.layout] and [Story.tileOrder]; a photo's key is its device id. */
fun groupTileKey(groupId: String): String = "group:$groupId"

/** The photo a tile shows: the photo itself, or a group's cover. */
val StoryTile.photo: MediaItem
    get() = when (this) {
        is StoryTile.Media -> item
        is StoryTile.Group -> group.cover
    }

/** Width over height of the photo as it is shown (1.5 when the library doesn't know its size). */
val MediaItem.aspectRatio: Float
    get() = if (width > 0 && height > 0) width.toFloat() / height else 1.5f

/** A tile on its band's grid, in cells from the top start corner. */
data class PlacedTile(
    val tile: StoryTile,
    val column: Int,
    val row: Int,
    val size: TileSize,
    val focus: FocusPoint,
    /** 1 or more: how much closer than filling the tile the photo is shown, around [focus]. */
    val zoom: Float,
    /** Its size was chosen by the owner rather than automatic. */
    val sized: Boolean,
) {
    /** Width over height of the tile (gaps aside): its cells, or for [TileSize.ORIGINAL] the photo's own. */
    val aspectRatio: Float
        get() = if (size == TileSize.ORIGINAL) tile.photo.aspectRatio else size.columns.toFloat() / size.rows
}

/**
 * Rows of a day's grid that no tile crosses into or out of. Screens show a band as one piece, so a long day can
 * still be scrolled lazily. A band with [aspectRatio] holds one [TileSize.ORIGINAL] tile, full width and that
 * ratio (width over height) instead of whole cells.
 */
data class StoryBand(val rows: Int, val tiles: List<PlacedTile>, val aspectRatio: Float? = null) {
    val key: String get() = tiles.first().tile.key
}

/** The size of a tile the owner didn't size: full width at its photo's own ratio, so nothing is cropped. */
val AUTO_SIZE = TileSize.ORIGINAL

/**
 * Places a day's tiles, in order, on a [GRID_COLUMNS]-wide grid: each goes to the first spot from the top where it
 * fits, so a later small tile fills a hole a large one left (dense packing). Cut into [StoryBand]s. An
 * [TileSize.ORIGINAL] tile is its own band below everything before it; tiles after it start a new grid under it.
 */
internal fun packDay(tiles: List<StoryTile>, layout: Map<String, TileLayout>): List<StoryBand> {
    val placed = tiles.map { tile ->
        val arranged = layout[tile.key]
        PlacedTile(
            tile = tile,
            column = 0,
            row = 0,
            size = arranged?.size ?: AUTO_SIZE,
            focus = arranged?.focus ?: FocusPoint.CENTER,
            zoom = arranged?.zoom ?: 1f,
            sized = arranged?.size != null,
        )
    }
    val bands = mutableListOf<StoryBand>()
    var run = mutableListOf<PlacedTile>()
    placed.forEach { tile ->
        if (tile.size == TileSize.ORIGINAL) {
            bands += packCells(run)
            run = mutableListOf()
            bands += StoryBand(GRID_COLUMNS, listOf(tile), aspectRatio = tile.tile.photo.aspectRatio)
        } else {
            run += tile
        }
    }
    return bands + packCells(run)
}

/** Dense packing of tiles sized in whole cells, cut into bands at every row boundary no tile spans. */
private fun packCells(tiles: List<PlacedTile>): List<StoryBand> {
    val taken = mutableListOf<BooleanArray>()
    fun free(row: Int, column: Int) = row >= taken.size || !taken[row][column]
    fun fits(row: Int, column: Int, size: TileSize) =
        (row until row + size.rows).all { r -> (column until column + size.columns).all { c -> free(r, c) } }

    var firstOpen = 0
    val placed = tiles.map { tile ->
        val size = tile.size
        while (firstOpen < taken.size && taken[firstOpen].all { it }) firstOpen++
        var row = firstOpen
        var column = -1
        while (column < 0) {
            column = (0..GRID_COLUMNS - size.columns).firstOrNull { fits(row, it, size) } ?: -1
            if (column < 0) row++
        }
        while (taken.size < row + size.rows) taken += BooleanArray(GRID_COLUMNS)
        for (r in row until row + size.rows) for (c in column until column + size.columns) taken[r][c] = true
        tile.copy(column = column, row = row)
    }

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
