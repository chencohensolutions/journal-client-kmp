import AVFoundation
import ImageIO
import UIKit
import SharedLogic

/// Pictures of photos that are only on the server, or whose original the app downloaded: the shared
/// `IosServerFiles` picks what to show (an exact-size preview, the original), these decode it with ImageIO, which
/// reads every format the server makes (WebP, HEIF, AVIF, and JPEG XL from iOS 17). Decoded images are kept in memory.
final class ServerImages: NSObject, ServerImageDecoding {
    static let shared = ServerImages()

    let files = IosJournal.shared.serverFiles()
    private let cache = NSCache<NSString, UIImage>()
    /// The largest image decoded for each item, by key in `cache`: what the viewer shows at once.
    private var largest: [String: (pixels: Int, key: NSString)] = [:]
    private let lock = NSLock()

    private override init() {
        super.init()
        cache.totalCostLimit = 256 << 20
        files.decoding = self
        // Streamed videos count toward the same limit, and Settings' Clear.
        ServerVideoCache.shared.attach()
    }

    /// `item` to cover `pixels` (a tile), from memory or as `IosServerFiles.tile` finds it.
    func tile(_ item: MediaItem, pixels: CGSize) async -> UIImage? {
        let width = Int32(pixels.width.rounded()), height = Int32(pixels.height.rounded())
        guard width > 0, height > 0 else { return nil }
        let key = "\(item.id)@\(width)x\(height)" as NSString
        if let image = cache.object(forKey: key) { return image }
        let image = await awaiting { done in self.files.tile(item: item, width: width, height: height, onResult: done) }
        if let image { remember(image, for: item, key: key) }
        return image
    }

    /// The best image of `item` already on this phone, asking the server for nothing: the largest decoded in memory,
    /// else the largest preview kept on disk.
    func cached(_ item: MediaItem) async -> UIImage? {
        if let image = inMemory(item) { return image }
        return await awaiting { done in self.files.largestPreview(item: item, onResult: done) }
    }

    /// The largest image of `item` decoded in memory, at once (nil if none).
    func inMemory(_ item: MediaItem) -> UIImage? {
        lock.lock()
        defer { lock.unlock() }
        return largest[item.id].flatMap { cache.object(forKey: $0.key) }
    }

    /// A server-only photo opened on its own, fitted in `maxSide` pixels: its original, downloaded and kept.
    func viewer(_ item: MediaItem, maxSide: Int) async -> UIImage? {
        let image = await awaiting { done in self.files.viewer(item: item, maxSide: Int32(maxSide), onResult: done) }
        if let image { remember(image, for: item, key: "\(item.id)@viewer" as NSString) }
        return image
    }

    private func remember(_ image: UIImage, for item: MediaItem, key: NSString) {
        let pixels = Int(image.size.width * image.scale * image.size.height * image.scale)
        cache.setObject(image, forKey: key, cost: pixels * 4)
        lock.lock()
        if pixels > largest[item.id]?.pixels ?? 0 || largest[item.id].map({ cache.object(forKey: $0.key) == nil }) == true {
            largest[item.id] = (pixels, key)
        }
        lock.unlock()
    }

    // MARK: ServerImageDecoding (called off the main thread)

    func decodeFile(path: String, isVideo: Bool, maxSide: Int32) -> UIImage? {
        let url = URL(fileURLWithPath: path)
        if isVideo { return Self.videoFrame(url: url, maxSide: CGFloat(maxSide)) }
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary).map { UIImage(cgImage: $0) }
    }

    func decodeData(data: Data) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
        else { return nil }
        return UIImage(cgImage: image)
    }

    /// A frame of the video at `url`, its longer side at most `maxSide` pixels; blocks until it is read.
    static func videoFrame(url: URL, maxSide: CGFloat) -> UIImage? {
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: maxSide, height: maxSide)
        let done = DispatchSemaphore(value: 0)
        var frame: CGImage?
        generator.generateCGImageAsynchronously(for: CMTime(seconds: 0.5, preferredTimescale: 600)) { image, _, _ in
            frame = image
            done.signal()
        }
        done.wait()
        return frame.map { UIImage(cgImage: $0) }
    }
}

// MARK: The viewer's Download original, Delete from this phone and Mark private

extension ServerImages {
    /// Downloads `item`'s original and keeps it in Application Support; `progress` gets each percent.
    func downloadOriginal(_ item: MediaItem, progress: @escaping (Int) -> Void) async -> Bool {
        await awaiting { done in
            self.files.downloadOriginal(item: item, onProgress: { progress($0.intValue) }, onDone: { done($0.boolValue) })
        }
    }

    /// Marks `item` private on the server, or no longer private; the library follows once the server's list is synced.
    func setPrivate(_ item: MediaItem, isPrivate: Bool) async -> Bool {
        await awaiting { done in
            self.files.setPrivate(item: item, isPrivate: isPrivate, onDone: { done($0.boolValue) })
        }
    }

    /// "Delete from this phone": only once the server confirms it has the file and a preview is kept here
    /// (`IosServerFiles.prepareRemoval`); a library photo then goes through iOS's own prompt (`PhotoRemoval`), after
    /// which `refresh` re-reads the library.
    func deleteFromPhone(_ item: MediaItem, refresh: @escaping () -> Void) async -> RemovalOutcome {
        // A video keeps a frame of it, made here (the server makes posters too, once it can).
        var poster: (data: Data, width: Int, height: Int)?
        if item.isVideo, let frame = await Self.posterFrame(item), let data = frame.jpegData(compressionQuality: 0.9) {
            poster = (data, Int(frame.size.width * frame.scale), Int(frame.size.height * frame.scale))
        }
        let check = await awaiting { done in
            self.files.prepareRemoval(
                item: item, poster: poster?.data, posterWidth: Int32(poster?.width ?? 0), posterHeight: Int32(poster?.height ?? 0), onDone: done
            )
        }
        switch check {
        case .deleted: return .deleted
        case .ask:
            let deleted = await PhotoRemoval.moveToRecentlyDeleted(item)
            refresh()
            return deleted ? .deleted : .declined
        case .notOnServer: return .notOnServer
        case .noPreview: return .noPreview
        default: return .unreachable
        }
    }

    /// The longer side of the frame kept for a video that leaves the phone (Android's VIDEO_POSTER_SIDE).
    private static let posterSide: CGFloat = 1920

    private static func posterFrame(_ item: MediaItem) async -> UIImage? {
        if let kept = item.keptOriginal {
            return await Task.detached { videoFrame(url: URL(fileURLWithPath: kept), maxSide: posterSide) }.value
        }
        let scale = UITraitCollection.current.displayScale
        return await PhotoLibraryImages.shared.image(item: item, pointSize: CGSize(width: posterSide / scale, height: posterSide / scale))
    }
}

/// Awaits a shared-logic call that answers through a callback (exactly once, nil when cancelled) and returns a handle
/// to cancel it; cancelling the task cancels the call.
func awaiting<T>(_ start: @escaping (@escaping (T) -> Void) -> ObservationHandle) async -> T {
    let box = HandleBox()
    return await withTaskCancellationHandler {
        await withCheckedContinuation { continuation in
            box.set(start { continuation.resume(returning: $0) })
        }
    } onCancel: {
        box.cancel()
    }
}

/// The handle of a call that may be cancelled before it is known.
private final class HandleBox: @unchecked Sendable {
    private let lock = NSLock()
    private var handle: ObservationHandle?
    private var cancelled = false

    func set(_ handle: ObservationHandle) {
        lock.lock()
        self.handle = handle
        let cancel = cancelled
        lock.unlock()
        if cancel { handle.cancel() }
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let handle = handle
        lock.unlock()
        handle?.cancel()
    }
}
