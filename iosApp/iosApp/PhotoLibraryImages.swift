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
        let scale = UITraitCollection.current.displayScale
        let options = PHImageRequestOptions()
        options.deliveryMode = quality
        options.resizeMode = .fast
        // Photos only in iCloud are downloaded; the viewer needs them.
        options.isNetworkAccessAllowed = true
        return await withCheckedContinuation { continuation in
            manager.requestImage(
                for: asset,
                targetSize: CGSize(width: pointSize.width * scale, height: pointSize.height * scale),
                contentMode: .aspectFill,
                options: options
            ) { image, _ in
                continuation.resume(returning: image)
            }
        }
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

    /// The file name the photo had when it was taken or imported.
    func originalFileName(id: String) -> String? {
        guard let asset = asset(id) else { return nil }
        return PHAssetResource.assetResources(for: asset).first?.originalFilename
    }
}
