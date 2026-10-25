import MapLibre
import SwiftUI
import SharedLogic

// The Explore map: every located photo as a pin, pins near each other as one circle with their count, and the years
// to show (see docs/screens/explore.md). Map tiles come from OpenFreeMap: it sees which areas are looked at, never
// the photos, which are drawn on top here.

/// Where the map looks once it settled: what is in view, and at which zoom.
struct MapLook: Equatable {
    let area: GeoArea
    let zoom: Double
}

/// What the photos in view are read again for: where the map looks, the years, the overview and the library.
private struct MapAreaInput: Equatable {
    let look: MapLook
    let from: Int
    let to: Int
    let overview: ObjectIdentifier
    let library: ObjectIdentifier
}

/// A place to open the map on, close up (a photo's location from the viewer's details).
struct MapCenter: Hashable, Identifiable {
    let latitude: Double
    let longitude: Double
    var id: String { "\(latitude),\(longitude)" }
}

struct ExploreMapView: View {
    let library: LibraryState
    let stories: StoriesState
    /// Opens close up on this place (zoom 15) instead of fitting every photo.
    var at: MapCenter? = nil
    /// A source on or off everywhere, from the "Showing" sheet.
    let toggleSource: (MediaSource) -> Void
    /// The viewer on the photos the scope takes in, starting at a photo (or the newest of them).
    let openPhotos: (ExploreScope, _ itemId: String?) -> Void

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @Environment(\.exploreReads) private var reads
    /// The located photos' years and boxes, never the photos themselves: those around what is in view are read as the
    /// map moves (docs/plans/library-on-disk.md, step 6c).
    @State private var overview: MapOverview?
    @State private var loaded = false
    /// The years shown; nil for the first or last year with photos.
    @State private var fromYear: Int?
    @State private var toYear: Int?
    /// Where the map looks, once it has settled.
    @State private var look: MapLook?
    /// The photos around what is in view, counted per square, and how many are in view.
    @State private var drawn: MapArea?
    @State private var choosing = false

    private var counts: [MediaSource: KotlinInt] { overview?.counts ?? [:] }

    var body: some View {
        ZStack {
            LibraryColors.background.ignoresSafeArea()
            if let overview, let range = overview.years {
                let years = Int(range.first)...Int(range.last)
                let from = min(max(fromYear ?? years.lowerBound, years.lowerBound), years.upperBound)
                let to = min(max(toYear ?? years.upperBound, from), years.upperBound)
                let inView = Int(drawn?.inView ?? 0)
                PhotoMap(
                    cells: drawn?.cells ?? [],
                    bounds: overview.bounds(from: Int32(from), to: Int32(to)),
                    at: at,
                    dark: colorScheme == .dark,
                    onLook: { look = $0 },
                    onOpen: { area, itemId in openPhotos(.of(fromYear: from, toYear: to, area: area), itemId) }
                )
                .ignoresSafeArea()
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    MapSheet(years: years, from: from, to: to, inView: inView, onRange: { fromYear = $0; toYear = $1 }) {
                        if let look { openPhotos(.of(fromYear: from, toYear: to, area: look.area), nil) }
                    }
                }
                .task(id: look.map { MapAreaInput(look: $0, from: from, to: to, overview: ObjectIdentifier(overview), library: ObjectIdentifier(library)) }) {
                    guard let look else { return }
                    if let area = await reads.mapArea(stories, overview, visible: look.area, zoom: look.zoom, from: from, to: to) { drawn = area }
                }
            } else if loaded {
                Text("No photos with a location yet.")
                    .font(.system(size: 15))
                    .foregroundStyle(LibraryColors.muted)
                    .padding(24)
            }
        }
        .overlay(alignment: .topLeading) {
            Button { dismiss() } label: {
                Image(systemName: "chevron.backward")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(LibraryColors.photoText)
                    .frame(width: 44, height: 44)
                    .background(LibraryColors.pillScrim, in: Circle())
            }
            .accessibilityLabel("Back")
            .padding(.leading, 12)
            .padding(.top, 4)
        }
        // Beside the back button: the map's logo and attribution are at the top right.
        .overlay(alignment: .topLeading) { showingButton }
        .sheet(isPresented: $choosing) {
            ShowingSheet(shown: stories.shownSources, counts: counts, onToggle: toggleSource)
        }
        .toolbar(.hidden, for: .navigationBar)
        .task(id: ExploreInput(library, stories)) {
            if let read = await reads.mapOverview(library, stories) { overview = read }
            loaded = true
        }
    }

    /// The sources shown ("Camera · Other"), opening the "Showing" sheet.
    @ViewBuilder
    private var showingButton: some View {
        let present = sourceCountList(counts).map(\.0)
        if !present.isEmpty {
            let on = present.filter { stories.shownSources.isShown(source: $0) }.map(\.title)
            Button { choosing = true } label: {
                HStack(spacing: 6) {
                    Image(systemName: "line.3.horizontal.decrease").font(.system(size: 13, weight: .semibold))
                    Text(verbatim: on.isEmpty ? String(localized: "Nothing shown") : on.joined(separator: " · "))
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(1)
                }
                .foregroundStyle(LibraryColors.photoText)
                .padding(.horizontal, 14)
                .frame(height: 44)
                .background(LibraryColors.pillScrim, in: Capsule())
            }
            .accessibilityLabel("Showing")
            .padding(.leading, 64)
            .padding(.top, 4)
        }
    }

}

/// At the bottom: the years shown, how many photos are in view with Show, and a slider over the years.
private struct MapSheet: View {
    let years: ClosedRange<Int>
    let from: Int
    let to: Int
    let inView: Int
    let onRange: (Int, Int) -> Void
    let onShow: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Group {
                        if from == years.lowerBound && to == years.upperBound {
                            Text("All years")
                        } else {
                            Text(verbatim: yearRange(from, to))
                        }
                    }
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(LibraryColors.onBackground)
                    Text("\(inView) photos here")
                        .font(.system(size: 13))
                        .foregroundStyle(LibraryColors.muted)
                }
                Spacer()
                Button("Show", action: onShow)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(inView > 0 ? LibraryColors.onBackground : LibraryColors.muted)
                    .padding(.horizontal, 12)
                    .frame(height: 40)
                    .disabled(inView == 0)
            }
            if years.lowerBound < years.upperBound {
                YearRangeSlider(years: years, from: from, to: to, onChange: onRange)
                    .padding(.top, 4)
                HStack {
                    Text(verbatim: String(years.lowerBound))
                    Spacer()
                    Text(verbatim: String(years.upperBound))
                }
                .font(.system(size: 12))
                .foregroundStyle(LibraryColors.muted)
            }
        }
        .padding(.leading, 20)
        .padding(.trailing, 12)
        .padding(.top, 12)
        .padding(.bottom, 4)
        .background(
            UnevenRoundedRectangle(topLeadingRadius: 20, topTrailingRadius: 20)
                .fill(LibraryColors.background)
                .ignoresSafeArea(edges: .bottom)
        )
    }
}

/// Two thumbs over the years, stopping at each year.
private struct YearRangeSlider: View {
    let years: ClosedRange<Int>
    let from: Int
    let to: Int
    let onChange: (Int, Int) -> Void

    private let thumb: CGFloat = 24

    var body: some View {
        GeometryReader { geometry in
            let track = geometry.size.width - thumb
            let span = CGFloat(years.upperBound - years.lowerBound)
            let x = { (year: Int) in CGFloat(year - years.lowerBound) / span * track }
            let year = { (position: CGFloat) in years.lowerBound + Int((min(max(position, 0), track) / track * span).rounded()) }
            ZStack(alignment: .leading) {
                Capsule().fill(LibraryColors.muted.opacity(0.3)).frame(height: 4).padding(.horizontal, thumb / 2)
                Capsule().fill(LibraryColors.inverse)
                    .frame(width: x(to) - x(from), height: 4)
                    .offset(x: x(from) + thumb / 2)
                ForEach(years.lowerBound...years.upperBound, id: \.self) { tick in
                    Circle()
                        .fill((from...to).contains(tick) ? LibraryColors.onInverseMuted : LibraryColors.muted)
                        .frame(width: 3, height: 3)
                        .offset(x: x(tick) + thumb / 2 - 1.5)
                }
                handle(at: x(from)) { onChange(min(year($0), to), to) }
                    .accessibilityLabel(Text(verbatim: String(from)))
                    .accessibilityAdjustableAction { direction in
                        onChange(direction == .increment ? min(from + 1, to) : max(from - 1, years.lowerBound), to)
                    }
                handle(at: x(to)) { onChange(from, max(year($0), from)) }
                    .accessibilityLabel(Text(verbatim: String(to)))
                    .accessibilityAdjustableAction { direction in
                        onChange(from, direction == .increment ? min(to + 1, years.upperBound) : max(to - 1, from))
                    }
            }
            .frame(maxHeight: .infinity)
            .coordinateSpace(.named("slider"))
            // Years run left to right, also in Hebrew, like the labels under the slider.
            .environment(\.layoutDirection, .leftToRight)
        }
        .frame(height: thumb + 8)
    }

    private func handle(at x: CGFloat, onDrag: @escaping (CGFloat) -> Void) -> some View {
        Circle()
            .fill(LibraryColors.inverse)
            .frame(width: thumb, height: thumb)
            .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
            .offset(x: x)
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .named("slider"))
                    .onChanged { onDrag($0.location.x - thumb / 2) }
            )
    }
}

/// The maps' styles (OpenFreeMap), for Explore's map and the viewer's location card.
enum MapStyles {
    static let light = URL(string: "https://tiles.openfreemap.org/styles/liberty")!
    static let dark = URL(string: "https://tiles.openfreemap.org/styles/dark")!

    static func url(dark: Bool) -> URL { dark ? Self.dark : light }
}
private let sourceId = "photos"
private let clustersId = "photo-clusters"
private let countsId = "photo-counts"
private let pinsId = "photo-pins"
/// Each feature is a square of photos, this many of them (see `mapAreaOf`); the circles the map joins add them up.
private let photosKey = "photos"

/// Past this zoom pins aren't joined any more; a circle left is photos taken in one spot.
private let clusterMaxZoom = 16.0

/// How far from a tap, in points, a pin still counts as tapped.
private let tapSlop: CGFloat = 24

/// MapLibre's map with the squares of photos as a clustered shape source and three layers, as on Android.
private struct PhotoMap: UIViewRepresentable {
    let cells: [MapCell]
    /// The box around the photos of the years shown: the map fits it the first time (unless it opened on a place).
    let bounds: GeoArea?
    /// Where it opens, close up; nil: it fits `bounds`.
    let at: MapCenter?
    let dark: Bool
    let onLook: (MapLook) -> Void
    let onOpen: (GeoArea, _ itemId: String?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero, styleURL: MapStyles.url(dark: dark))
        map.delegate = context.coordinator
        map.isRotateEnabled = false
        map.isPitchEnabled = false
        map.compassView.compassVisibility = .hidden
        map.logoViewPosition = .topRight
        map.attributionButtonPosition = .topRight
        map.logoViewMargins = CGPoint(x: 48, y: 12)
        map.attributionButtonMargins = CGPoint(x: 12, y: 12)
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        // The map's own double tap zooms; a single tap waits for it to fail.
        for recognizer in map.gestureRecognizers ?? [] {
            if let double = recognizer as? UITapGestureRecognizer, double.numberOfTapsRequired == 2 { tap.require(toFail: double) }
        }
        map.addGestureRecognizer(tap)
        context.coordinator.parent = self
        if let at {
            map.setCenter(CLLocationCoordinate2D(latitude: at.latitude, longitude: at.longitude), zoomLevel: 15, animated: false)
            context.coordinator.placed = true
        }
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self
        let style = MapStyles.url(dark: dark)
        if map.styleURL != style {
            // The new style comes without the photos; they are added again once it loads.
            coordinator.source = nil
            map.styleURL = style
        } else {
            coordinator.update(map)
        }
    }

    final class Coordinator: NSObject, MLNMapViewDelegate {
        var parent: PhotoMap?
        var source: MLNShapeSource?
        private var shownCells: [MapCell]?
        /// The map fits the photos once (unless it opened on a place); after that it stays where it was put.
        var placed = false

        func mapView(_ map: MLNMapView, didFinishLoading style: MLNStyle) {
            guard let parent else { return }
            let colors = Colors(dark: parent.dark)
            let photos = MLNShapeSource(identifier: sourceId, shape: nil, options: [
                .clustered: true,
                .clusterRadius: 48,
                .maximumZoomLevelForClustering: clusterMaxZoom,
                .clusterProperties: [photosKey: [NSExpression(format: "sum:({$featureAccumulated, \(photosKey)})"), NSExpression(forKeyPath: photosKey)]],
            ])
            style.addSource(photos)
            source = photos
            shownCells = nil

            // A circle the map joined, or a square of more than one photo.
            let clustered = NSPredicate(mglJSONObject: ["any", ["has", "point_count"], [">", ["get", photosKey], 1]])
            let count: [Any] = ["get", photosKey]

            let clusters = MLNCircleStyleLayer(identifier: clustersId, source: photos)
            clusters.predicate = clustered
            clusters.circleColor = NSExpression(forConstantValue: colors.circle)
            clusters.circleRadius = NSExpression(mglJSONObject: ["step", count, 16, 10, 20, 100, 25, 1000, 30])
            clusters.circleStrokeWidth = NSExpression(forConstantValue: 2)
            clusters.circleStrokeColor = NSExpression(forConstantValue: colors.ring)
            style.addLayer(clusters)

            let counts = MLNSymbolStyleLayer(identifier: countsId, source: photos)
            counts.predicate = clustered
            // As the map wrote its own counts: 1,500 as "1.5k", 12,000 as "12k".
            counts.text = NSExpression(mglJSONObject: [
                "case",
                [">=", count, 10000], ["concat", ["to-string", ["round", ["/", count, 1000]]], "k"],
                [">=", count, 1000], ["concat", ["to-string", ["/", ["round", ["/", count, 100]], 10]], "k"],
                ["to-string", count],
            ])
            counts.textFontNames = NSExpression(forConstantValue: ["Noto Sans Regular"])
            counts.textFontSize = NSExpression(forConstantValue: 12)
            counts.textColor = NSExpression(forConstantValue: colors.text)
            counts.textAllowsOverlap = NSExpression(forConstantValue: true)
            counts.textIgnoresPlacement = NSExpression(forConstantValue: true)
            style.addLayer(counts)

            let pins = MLNCircleStyleLayer(identifier: pinsId, source: photos)
            pins.predicate = NSCompoundPredicate(notPredicateWithSubpredicate: clustered)
            pins.circleColor = NSExpression(forConstantValue: colors.circle)
            pins.circleRadius = NSExpression(forConstantValue: 7)
            pins.circleStrokeWidth = NSExpression(forConstantValue: 2)
            pins.circleStrokeColor = NSExpression(forConstantValue: colors.ring)
            style.addLayer(pins)

            update(map)
        }

        /// The first time, fits the photos; then puts the squares on the map when they changed.
        func update(_ map: MLNMapView) {
            guard let source, let parent else { return }
            if !placed, let bounds = parent.bounds {
                placed = true
                fit(bounds, on: map)
                return
            }
            if !placed { return }
            let cells = parent.cells
            if let shownCells, shownCells.count == cells.count, zip(shownCells, cells).allSatisfy({ $0 === $1 }) { return }
            shownCells = cells
            source.shape = MLNShapeCollectionFeature(shapes: cells.map { cell in
                let feature = MLNPointFeature()
                feature.coordinate = CLLocationCoordinate2D(latitude: cell.point.latitude, longitude: cell.point.longitude)
                var attributes: [String: Any] = [photosKey: Int(cell.count)]
                if let id = cell.id { attributes["id"] = id }
                feature.attributes = attributes
                return feature
            })
        }

        private func fit(_ box: GeoArea, on map: MLNMapView) {
            // The padding needs the map's size: wait until it is laid out.
            guard map.bounds.width > 200 else {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self, weak map] in
                    if let map { self?.fit(box, on: map) }
                }
                return
            }
            if box.south == box.north && box.west == box.east {
                map.setCenter(CLLocationCoordinate2D(latitude: box.south, longitude: box.west), zoomLevel: 13, animated: false)
            } else {
                let southWest = CLLocationCoordinate2D(latitude: box.south, longitude: box.west)
                let northEast = CLLocationCoordinate2D(latitude: box.north, longitude: box.east)
                // Clear of the sheet at the bottom.
                let padding = UIEdgeInsets(top: 96, left: 64, bottom: 200, right: 64)
                map.setCamera(map.cameraThatFitsCoordinateBounds(MLNCoordinateBounds(sw: southWest, ne: northEast), edgePadding: padding), animated: false)
            }
            report(map)
        }

        func mapView(_ map: MLNMapView, regionDidChangeAnimated animated: Bool) { report(map) }

        func mapViewDidFinishLoadingMap(_ map: MLNMapView) { if placed { report(map) } }

        private func report(_ map: MLNMapView) {
            guard map.bounds.width > 0 else { return }
            parent?.onLook(MapLook(area: area(of: map), zoom: map.zoomLevel))
        }

        private func area(of map: MLNMapView) -> GeoArea {
            let bounds = map.visibleCoordinateBounds
            return GeoArea(south: bounds.sw.latitude, west: bounds.sw.longitude, north: bounds.ne.latitude, east: bounds.ne.longitude)
        }

        @objc func tapped(_ gesture: UITapGestureRecognizer) {
            guard let map = gesture.view as? MLNMapView, let parent else { return }
            let point = gesture.location(in: map)
            let box = CGRect(x: point.x - tapSlop, y: point.y - tapSlop, width: tapSlop * 2, height: tapSlop * 2)
            let cluster = map.visibleFeatures(in: box, styleLayerIdentifiers: [clustersId]).first
            if let cluster, map.zoomLevel < clusterMaxZoom {
                // A circle the map joined knows how far to zoom in to part it; a square of photos is nearer in.
                let zoom = (cluster as? MLNPointFeatureCluster).flatMap { source?.zoomLevel(forExpanding: $0) } ?? floor(map.zoomLevel) + 2
                map.setCenter(cluster.coordinate, zoomLevel: zoom, animated: true)
            } else if cluster != nil {
                // Photos taken in one spot, all of them in view: the viewer on what is in view.
                parent.onOpen(area(of: map), nil)
            } else if let pin = map.visibleFeatures(in: box, styleLayerIdentifiers: [pinsId]).first,
                      let id = pin.attribute(forKey: "id") as? String {
                parent.onOpen(area(of: map), id)
            }
        }
    }

    /// The library colors as the map needs them: fixed, for the scheme it shows.
    private struct Colors {
        let circle: UIColor
        let text: UIColor
        let ring: UIColor

        init(dark: Bool) {
            let traits = UITraitCollection(userInterfaceStyle: dark ? .dark : .light)
            circle = UIColor(LibraryColors.inverse).resolvedColor(with: traits)
            text = UIColor(LibraryColors.onInverse).resolvedColor(with: traits)
            ring = UIColor(LibraryColors.background).resolvedColor(with: traits)
        }
    }
}
