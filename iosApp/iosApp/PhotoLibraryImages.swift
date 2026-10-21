import AVFoundation
import Photos
import UIKit

/// Loads thumbnails, full images and videos from PhotoKit by `MediaItem.id` (the PHAsset local identifier).
final class PhotoLibraryImages {
    static let shared = PhotoLibraryImages()

    private let manager = PHCachingImageManager()
    private var assets: [String: PHAsset] = [:]
    private let lock = NSLock()

    func asset(_ id: String) -> PHAsset? {
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

    /// Starts loading images that will be asked for soon at this size (a swipe set's neighbors), so they show at once.
    func prefetch(ids: [String], pointSize: CGSize) {
        let assets = ids.compactMap(asset)
        guard !assets.isEmpty else { return }
        manager.startCachingImages(for: assets, targetSize: Self.pixels(pointSize), contentMode: .aspectFill, options: Self.options(.highQualityFormat))
    }

    private static func pixels(_ pointSize: CGSize) -> CGSize {
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

    /// The file name the photo had when it was taken or imported.
    func originalFileName(id: String) -> String? {
        guard let asset = asset(id) else { return nil }
        return PHAssetResource.assetResources(for: asset).first?.originalFilename
    }
}
