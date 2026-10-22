import AVFoundation
import ImageIO
import Photos
import UIKit
import SharedLogic

/// Loads thumbnails, full images and videos from PhotoKit by `MediaItem.id` (the PHAsset local identifier).
final class PhotoLibraryImages {
    static let shared = PhotoLibraryImages()

    private let manager = PHCachingImageManager()
    private var assets: [String: PHAsset] = [:]
    private let lock = NSLock()

    func asset(_ id: String) -> PHAsset? {
        #if DEBUG
        if UiLab.isOn { return nil }
        #endif
        lock.lock()
        defer { lock.unlock() }
        if let asset = assets[id] { return asset }
        let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject
        assets[id] = asset
        return asset
    }

    /// An image at least `pointSize` big on screen; nil if the asset is gone.
    func image(id: String, pointSize: CGSize, quality: PHImageRequestOptionsDeliveryMode = .highQualityFormat) async -> UIImage? {
        guard let asset = asset(id) else { return nil }
        return await withCheckedContinuation { continuation in
            manager.requestImage(
                for: asset,
                targetSize: Self.pixels(pointSize),
                contentMode: .aspectFill,
                options: Self.options(quality)
            ) { image, _ in
                continuation.resume(returning: image)
            }
        }
    }

    /// `item` at least `pointSize` big: from PhotoKit, or, for a photo not in the library, its downloaded original or the
    /// server's preview at exactly those pixels (`ServerImages`). A quick (`.fastFormat`) server image is only what is
    /// already in memory, so a second size isn't asked of the server.
    func image(item: MediaItem, pointSize: CGSize, quality: PHImageRequestOptionsDeliveryMode = .highQualityFormat) async -> UIImage? {
        #if DEBUG
        if UiLab.isOn { return LabPictures.image(item) }
        #endif
        if item.isOnDevice { return await image(id: item.id, pointSize: pointSize, quality: quality) }
        if quality == .fastFormat { return ServerImages.shared.inMemory(item) }
        return await ServerImages.shared.tile(item, pixels: Self.pixels(pointSize))
    }

    /// `item`'s original, upright, its longer side `maxSide` pixels (its own when smaller): what the viewer zooms into.
    /// From PhotoKit's original data for a library photo (downloaded from iCloud if need be), else its downloaded
    /// original or the server's (`ServerImages.fullOriginal`). Not kept in memory once the viewer lets go of it.
    func original(item: MediaItem, maxSide: Int) async -> UIImage? {
        #if DEBUG
        if UiLab.isOn { return LabPictures.image(item) }
        #endif
        guard item.isOnDevice else { return await ServerImages.shared.fullOriginal(item, maxSide: maxSide) }
        guard let asset = asset(item.id) else { return nil }
        let options = PHImageRequestOptions()
        options.isNetworkAccessAllowed = true
        options.deliveryMode = .highQualityFormat
        options.version = .current
        let data: Data? = await withCheckedContinuation { continuation in
            manager.requestImageDataAndOrientation(for: asset, options: options) { data, _, _, _ in
                continuation.resume(returning: data)
            }
        }
        guard let data else { return nil }
        return await Task.detached(priority: .userInitiated) { Self.decode(data, maxSide: maxSide) }.value
    }

    /// `data` decoded upright with its longer side at most `maxSide` pixels.
    private static func decode(_ data: Data, maxSide: Int) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary).map { UIImage(cgImage: $0) }
    }

    /// What a player opens: the library's video, the original the app downloaded, or one only on the server streamed
    /// (`ServerVideos`); nil when that can't be (this device isn't registered).
    @MainActor
    func playerItem(item: MediaItem) async -> AVPlayerItem? {
        if item.isOnDevice { return await playerItem(id: item.id) }
        return await videoAsset(item: item).map { AVPlayerItem(asset: $0) }
    }

    /// The video itself (see `videoAsset(id:)`), its downloaded original, or one only on the server streamed.
    @MainActor
    func videoAsset(item: MediaItem) async -> AVAsset? {
        if item.isOnDevice { return await videoAsset(id: item.id) }
        if let kept = item.keptOriginal { return AVURLAsset(url: URL(fileURLWithPath: kept)) }
        return ServerVideos.asset(item)
    }

    /// Starts loading images that will be asked for soon at this size (a swipe set's neighbors), so they show at once.
    func prefetch(ids: [String], pointSize: CGSize) {
        let assets = ids.compactMap(asset)
        guard !assets.isEmpty else { return }
        manager.startCachingImages(for: assets, targetSize: Self.pixels(pointSize), contentMode: .aspectFill, options: Self.options(.highQualityFormat))
    }

    static func pixels(_ pointSize: CGSize) -> CGSize {
        let scale = UITraitCollection.current.displayScale
        return CGSize(width: pointSize.width * scale, height: pointSize.height * scale)
    }

    private static func options(_ quality: PHImageRequestOptionsDeliveryMode) -> PHImageRequestOptions {
        let options = PHImageRequestOptions()
        options.deliveryMode = quality
        options.resizeMode = .fast
        // Photos only in iCloud are downloaded; the viewer needs them.
        options.isNetworkAccessAllowed = true
        return options
    }

    func playerItem(id: String) async -> AVPlayerItem? {
        guard let asset = asset(id) else { return nil }
        let options = PHVideoRequestOptions()
        options.isNetworkAccessAllowed = true
        return await withCheckedContinuation { continuation in
            manager.requestPlayerItem(forVideo: asset, options: options) { item, _ in
                continuation.resume(returning: item)
            }
        }
    }

    /// The video itself, for playing it in a loop (an AVPlayerLooper copies items made from it).
    func videoAsset(id: String) async -> AVAsset? {
        guard let asset = asset(id) else { return nil }
        let options = PHVideoRequestOptions()
        options.isNetworkAccessAllowed = true
        return await withCheckedContinuation { continuation in
            manager.requestAVAsset(forVideo: asset, options: options) { video, _, _ in
                continuation.resume(returning: video)
            }
        }
    }
}
