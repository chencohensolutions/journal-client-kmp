import MapLibre
import SwiftUI
import SharedLogic

// The viewer's location card: where the photo was taken on a small still map, the place and the coordinates under it.
// Like Android's LocationCard (InfoPanelSuggestions.kt).

/// The map with the photo's pin, "Bahá'í Gardens, Haifa, Israel" and "32.81431, 34.98712" under it.
struct LocationCard: View {
    let point: GeoPoint
    let name: PlaceName?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            LocationMapView(latitude: point.latitude, longitude: point.longitude)
                .frame(height: 170)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 2) {
                if !place.isEmpty {
                    Text(place).font(.system(size: 15, weight: .semibold))
                }
                Text(String(format: "%.5f, %.5f", locale: Locale(identifier: "en_US_POSIX"), point.latitude, point.longitude))
                    .font(.system(size: 13))
                    .foregroundStyle(LibraryColors.onInverseMuted)
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 6)
        }
        .foregroundStyle(LibraryColors.onInverse)
        .padding(6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LibraryColors.panelCard, in: RoundedRectangle(cornerRadius: 16))
    }

    /// "label, city, country", the city left out when it is the label.
    private var place: String {
        let label = name?.label
        let city = name?.city.flatMap { $0 == label ? nil : $0 }
        return [label, city, name?.country].compactMap { $0 }.joined(separator: ", ")
    }
}

/// A still picture of the map around a point at zoom 14 (`MLNMapSnapshotter`), with the pin drawn on top at the
/// centre: no live map in the panel, so it scrolls and clips cleanly (a live GL map in the viewer froze the screen on
/// Android). Its style follows the panel's surface (light on the always-dark viewer's light panel).
struct LocationMapView: View {
    let latitude: Double
    let longitude: Double

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.displayScale) private var displayScale
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            ZStack {
                LibraryColors.panelCard
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: size.width, height: size.height)
                        .clipped()
                        .transition(.opacity)
                }
                Circle()
                    .fill(LibraryColors.accent)
                    .frame(width: 16, height: 16)
                    .padding(3)
                    .background(Circle().fill(.white))
            }
            .frame(width: size.width, height: size.height)
            .task(id: "\(latitude),\(longitude),\(Int(size.width))x\(Int(size.height)),\(colorScheme == .light)") {
                guard size.width > 0, size.height > 0 else { return }
                let shot = await MapSnapshots.image(
                    latitude: latitude, longitude: longitude, size: size, scale: displayScale, dark: colorScheme == .light
                )
                withAnimation(.easeInOut(duration: 0.2)) { image = shot }
            }
        }
        .accessibilityHidden(true)
    }
}

/// Map pictures made with `MLNMapSnapshotter`, kept in memory while the app runs (swiping back to a photo shows its
/// map at once).
@MainActor
enum MapSnapshots {
    private static let cache = NSCache<NSString, UIImage>()

    static func image(latitude: Double, longitude: Double, size: CGSize, scale: CGFloat, dark: Bool) async -> UIImage? {
        let key = "\(latitude),\(longitude),\(size.width)x\(size.height)@\(scale),\(dark)" as NSString
        if let image = cache.object(forKey: key) { return image }
        let center = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        let camera = MLNMapCamera(lookingAtCenter: center, altitude: 1000, pitch: 0, heading: 0)
        let options = MLNMapSnapshotOptions(styleURL: MapStyles.url(dark: dark), camera: camera, size: size)
        options.zoomLevel = 14
        options.scale = scale
        options.showsLogo = false
        let snapshotter = MLNMapSnapshotter(options: options)
        let image: UIImage? = await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                snapshotter.start { snapshot, _ in continuation.resume(returning: snapshot?.image) }
            }
        } onCancel: {
            Task { @MainActor in snapshotter.cancel() }
        }
        if let image { cache.setObject(image, forKey: key) }
        return image
    }
}
