package com.chencohensolution.journal.ui.library

import android.graphics.RectF
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.stories.ExploreScope
import com.chencohensolution.journal.stories.GeoArea
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.stories.MapOverview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

// The Explore map: every located photo as a pin, pins near each other as one circle with their count, and the years
// to show (see docs/screens/explore.md). Map tiles come from OpenFreeMap: it sees which areas are looked at, never
// the photos, which are drawn on top here.

internal class MapActions(
    val back: () -> Unit = {},
    /** The viewer on the photos [scope] takes in, starting at [itemId] (or the newest of them). */
    val openPhotos: (scope: ExploreScope, itemId: String?) -> Unit = { _, _ -> },
    /** A switch of the "Showing" sheet: the setting changes everywhere. */
    val toggleSource: (MediaSource) -> Unit = {},
)

internal const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
internal const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val SOURCE = "photos"
private const val CLUSTERS = "photo-clusters"
private const val COUNTS = "photo-counts"
private const val PINS = "photo-pins"

/** A feature's number of photos: a square's own, or the sum of a circle's squares. */
private const val PHOTOS = "photos"

/** How close the map opens on a photo's place. */
private const val PLACE_ZOOM = 15.0

/** Past this zoom pins aren't joined any more; a circle left is photos taken in one spot. */
private const val CLUSTER_MAX_ZOOM = 16

/** How far from a tap, in pixels, a pin still counts as tapped. */
private const val TAP_SLOP = 24f

/** Where the map looks once it settled: what is in view, and at which zoom. */
private class MapLook(val area: GeoArea, val zoom: Double)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapScreen(library: LibraryState, stories: StoriesState, actions: MapActions, at: GeoPoint? = null) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val insets = WindowInsets.systemBars.asPaddingValues()
    val density = LocalDensity.current
    // The located photos' years and boxes, never the photos themselves: those around what is in view are read as the
    // map moves (docs/plans/library-on-disk.md, step 6c).
    val reads = LocalExploreReads.current
    val overview by produceState<MapOverview?>(null, library, stories.places, stories.shownSources) {
        value = reads.mapOverview(library, stories)
    }
    // The whole library's photos per source, shown or not, for the "Showing" sheet.
    val counts = overview?.counts.orEmpty()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val shownSources = stories.shownSources.shownAmong(counts)
    if (sheetOpen) ShowingSheet(shownSources, counts, actions.toggleSource, onDismiss = { sheetOpen = false })
    val years = overview?.years
    // The years shown and where the map looks, kept while the viewer is open above it.
    var fromYear by rememberSaveable { mutableStateOf<Int?>(null) }
    var toYear by rememberSaveable { mutableStateOf<Int?>(null) }
    // Opened from a photo's details: on its place, close up.
    var camera by rememberSaveable { mutableStateOf(at?.let { doubleArrayOf(it.latitude, it.longitude, PLACE_ZOOM) }) }
    if (years == null) {
        MapEmpty(loaded = overview != null, actions) {
            ShowingLine(shownSources, presentSources(counts, shownSources), onChange = { sheetOpen = true })
        }
        return
    }
    val from = (fromYear ?: years.first).coerceIn(years)
    val to = (toYear ?: years.last).coerceIn(years)
    var inView by remember { mutableIntStateOf(0) }
    var look by remember { mutableStateOf<MapLook?>(null) }

    val map = remember {
        MapLibre.getInstance(context)
        PhotoMap(MapView(context).apply { onCreate(null) })
    }
    MapLifecycle(map.view)
    val currentActions by rememberUpdatedState(actions)
    val scopeOf = { visible: GeoArea -> ExploreScope(fromYear = from, toYear = to, area = visible) }
    val currentScopeOf by rememberUpdatedState(scopeOf)
    val pinColors = Triple(LibraryColors.Inverse.toArgb(), LibraryColors.OnInverse.toArgb(), LibraryColors.Background.toArgb())
    LaunchedEffect(map, dark) {
        val topPx = with(density) { (insets.calculateTopPadding() + 12.dp).roundToPx() }
        val sidePx = with(density) { 12.dp.roundToPx() }
        map.load(
            style = if (dark) DARK_STYLE else LIGHT_STYLE,
            circle = pinColors.first,
            text = pinColors.second,
            ring = pinColors.third,
        ) { mapLibre ->
            mapLibre.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                logoGravity = Gravity.TOP or Gravity.END
                attributionGravity = Gravity.TOP or Gravity.END
                setLogoMargins(0, topPx, sidePx * 4, 0)
                setAttributionMargins(0, topPx, sidePx, 0)
            }
            camera?.let { (lat, lng, zoom) -> mapLibre.cameraPosition = CameraPosition.Builder().target(LatLng(lat, lng)).zoom(zoom).build() }
            mapLibre.addOnCameraIdleListener {
                look = MapLook(mapLibre.visibleArea(), mapLibre.cameraPosition.zoom)
                mapLibre.cameraPosition.target?.let { camera = doubleArrayOf(it.latitude, it.longitude, mapLibre.cameraPosition.zoom) }
            }
            mapLibre.addOnMapClickListener { latLng ->
                val tap = mapLibre.projection.toScreenLocation(latLng)
                val box = RectF(tap.x - TAP_SLOP, tap.y - TAP_SLOP, tap.x + TAP_SLOP, tap.y + TAP_SLOP)
                val cluster = mapLibre.queryRenderedFeatures(box, CLUSTERS).firstOrNull()
                val pin = if (cluster == null) mapLibre.queryRenderedFeatures(box, PINS).firstOrNull() else null
                when {
                    cluster != null && mapLibre.cameraPosition.zoom < CLUSTER_MAX_ZOOM -> {
                        val point = cluster.geometry() as Point
                        // A circle the map joined knows how far to zoom in to part it; a square of photos is nearer in.
                        val zoom = map.source?.takeIf { cluster.hasProperty("point_count") }?.getClusterExpansionZoom(cluster)
                            ?: (mapLibre.cameraPosition.zoom.toInt() + 2)
                        mapLibre.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude(), point.longitude()), zoom.toDouble()))
                    }
                    // Photos taken in one spot, all of them in view: the viewer on what is in view.
                    cluster != null -> currentActions.openPhotos(currentScopeOf(mapLibre.visibleArea()), null)
                    pin != null -> currentActions.openPhotos(currentScopeOf(mapLibre.visibleArea()), pin.getStringProperty("id"))
                    else -> return@addOnMapClickListener false
                }
                true
            }
        }
    }
    // The first time (with no place saved) the map fits the photos of the years chosen. Then the photos around what is in
    // view, counted per square, follow where the map settles, the years chosen and the library.
    val loaded = overview
    LaunchedEffect(map.ready, loaded, from, to, look) {
        if (!map.ready || loaded == null) return@LaunchedEffect
        val mapLibre = map.mapLibre ?: return@LaunchedEffect
        if (camera == null) {
            loaded.bounds(from, to)?.let { mapLibre.moveCamera(fitting(it, with(density) { 64.dp.roundToPx() })) }
            return@LaunchedEffect
        }
        val now = look ?: return@LaunchedEffect
        val drawn = reads.mapArea(stories, loaded, now.area, now.zoom, from, to)
        inView = drawn.inView
        val collection = withContext(Dispatchers.Default) {
            FeatureCollection.fromFeatures(
                drawn.cells.map { cell ->
                    Feature.fromGeometry(Point.fromLngLat(cell.point.longitude, cell.point.latitude)).apply {
                        addNumberProperty(PHOTOS, cell.count)
                        cell.id?.let { addStringProperty("id", it) }
                    }
                },
            )
        }
        map.source?.setGeoJson(collection)
    }

    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        AndroidView(factory = { map.view }, modifier = Modifier.fillMaxSize())
        IconButton(
            onClick = actions.back,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 8.dp, top = 8.dp)
                .background(LibraryColors.PillScrim, CircleShape),
        ) {
            Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = LibraryColors.PhotoText)
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(LibraryColors.Background, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            ShowingLine(shownSources, presentSources(counts, shownSources), onChange = { sheetOpen = true }, Modifier.padding(end = 8.dp, bottom = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (from == years.first && to == years.last) stringResource(R.string.explore_all_years) else if (from == to) "$from" else stringResource(R.string.explore_year_range, from, to),
                        color = LibraryColors.OnBackground,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(pluralStringResource(R.plurals.map_photos_here, inView, inView), color = LibraryColors.Muted, fontSize = 13.sp)
                }
                TextButton(enabled = inView > 0, onClick = { look?.let { actions.openPhotos(scopeOf(it.area), null) } }) {
                    Text(stringResource(R.string.map_show), color = if (inView > 0) LibraryColors.OnBackground else LibraryColors.Muted, fontWeight = FontWeight.SemiBold)
                }
            }
            if (years.first < years.last) {
                RangeSlider(
                    value = from.toFloat()..to.toFloat(),
                    onValueChange = { range ->
                        fromYear = Math.round(range.start)
                        toYear = Math.round(range.endInclusive)
                    },
                    valueRange = years.first.toFloat()..years.last.toFloat(),
                    steps = (years.last - years.first - 1).coerceAtLeast(0),
                    colors = SliderDefaults.colors(
                        thumbColor = LibraryColors.Inverse,
                        activeTrackColor = LibraryColors.Inverse,
                        inactiveTrackColor = LibraryColors.Raised,
                        activeTickColor = LibraryColors.OnInverseMuted,
                        inactiveTickColor = LibraryColors.Muted,
                    ),
                    modifier = Modifier.padding(end = 8.dp),
                )
                Row(Modifier.fillMaxWidth().padding(end = 8.dp)) {
                    Text("${years.first}", color = LibraryColors.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${years.last}", color = LibraryColors.Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

/** No located photo yet (or still working them out): the message, and a way back. */
@Composable
private fun MapEmpty(loaded: Boolean, actions: MapActions, line: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        if (loaded) {
            Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                Text(stringResource(R.string.map_no_photos), color = LibraryColors.Muted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 16.dp))
                // Their photos may all be of sources hidden.
                line()
            }
        }
        IconButton(onClick = actions.back, modifier = Modifier.statusBarsPadding().padding(start = 8.dp, top = 8.dp)) {
            Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = LibraryColors.OnBackground)
        }
    }
}

/** The map view and what was set up on it once its style loaded. */
private class PhotoMap(val view: MapView) {
    var mapLibre: MapLibreMap? = null
    var source: GeoJsonSource? = null
    var ready by mutableStateOf(false)

    /** Loads [style] with an empty photo source and its layers, then calls [onReady]; again when the theme changes. */
    fun load(style: String, circle: Int, text: Int, ring: Int, onReady: (MapLibreMap) -> Unit) {
        ready = false
        view.getMapAsync { map ->
            val first = mapLibre == null
            mapLibre = map
            map.setStyle(Style.Builder().fromUri(style)) { loaded ->
                // Each feature is a square of photos (PHOTOS of them, see mapAreaOf): the circles the map joins add
                // them up, and a square of more than one is a circle too.
                val photos = GeoJsonSource(
                    SOURCE,
                    FeatureCollection.fromFeatures(emptyList()),
                    GeoJsonOptions().withCluster(true).withClusterMaxZoom(CLUSTER_MAX_ZOOM).withClusterRadius(48)
                        .withClusterProperty(PHOTOS, Expression.sum(Expression.accumulated(), Expression.get(PHOTOS)), Expression.get(PHOTOS)),
                )
                loaded.addSource(photos)
                source = photos
                val clustered = Expression.any(Expression.has("point_count"), Expression.gt(Expression.get(PHOTOS), 1))
                val count = Expression.get(PHOTOS)
                loaded.addLayer(
                    CircleLayer(CLUSTERS, SOURCE).withFilter(clustered).withProperties(
                        PropertyFactory.circleColor(circle),
                        PropertyFactory.circleRadius(
                            Expression.step(
                                count,
                                Expression.literal(16),
                                Expression.stop(10, 20),
                                Expression.stop(100, 25),
                                Expression.stop(1000, 30),
                            ),
                        ),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor(ring),
                    ),
                )
                loaded.addLayer(
                    SymbolLayer(COUNTS, SOURCE).withFilter(clustered).withProperties(
                        PropertyFactory.textField(abbreviated(count)),
                        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textColor(text),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(true),
                    ),
                )
                loaded.addLayer(
                    CircleLayer(PINS, SOURCE).withFilter(Expression.not(clustered)).withProperties(
                        PropertyFactory.circleColor(circle),
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor(ring),
                    ),
                )
                // Listeners are added once; a new style (the theme changed) keeps them.
                if (first) onReady(map)
                ready = true
            }
        }
    }
}

/** [count] written as the map wrote its own counts: 1,500 as "1.5k", 12,000 as "12k". */
private fun abbreviated(count: Expression): Expression = Expression.switchCase(
    Expression.gte(count, 10_000),
    Expression.concat(Expression.toString(Expression.round(Expression.division(count, Expression.literal(1_000)))), Expression.literal("k")),
    Expression.gte(count, 1_000),
    Expression.concat(
        Expression.toString(Expression.division(Expression.round(Expression.division(count, Expression.literal(100))), Expression.literal(10))),
        Expression.literal("k"),
    ),
    Expression.toString(count),
)

/** The part of the world [this] shows. */
private fun MapLibreMap.visibleArea(): GeoArea = projection.visibleRegion.latLngBounds.let {
    GeoArea(south = it.latitudeSouth, west = it.longitudeWest, north = it.latitudeNorth, east = it.longitudeEast)
}

/**
 * The camera that shows all of the photos in [box] (the box around them, MapOverview.bounds), [padding] pixels in from
 * the edges; close up on their place when they are all in one.
 */
private fun fitting(box: GeoArea, padding: Int) = if (box.south == box.north && box.west == box.east) {
    CameraUpdateFactory.newLatLngZoom(LatLng(box.south, box.west), 13.0)
} else {
    CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(listOf(LatLng(box.south, box.west), LatLng(box.north, box.east))).build(), padding)
}

/** MapView needs the screen's lifecycle passed on, and destroying when it leaves. */
@Composable
private fun MapLifecycle(view: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, view) {
        // Added observers are brought up to the current state, so a map opened on a resumed screen starts at once.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            view.onDestroy()
        }
    }
}
