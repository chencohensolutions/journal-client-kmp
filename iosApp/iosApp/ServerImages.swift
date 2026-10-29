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

    /// What `item` shows first, before its sharp tile (Settings › Storage on this phone › Quick image while scrolling),
    /// from memory or as `IosServerFiles.firstImage` finds it; nil when it is off.
    func first(_ item: MediaItem, windowPixels: Int) async -> UIImage? {
        let key = "\(item.id)@first-\(windowPixels)" as NSString
        if let image = cache.object(forKey: key) { return image }
        let image = await awaiting { done in self.files.firstImage(item: item, windowWidth: Int32(windowPixels), onResult: done) }
        if let image { cache.setObject(image, forKey: key, cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4)) }
        return image
    }

    /// `face` as a round avatar `pixels` across: a crop of its photo's preview from the server, kept in memory.
    func face(_ face: Face, pixels: Int) async -> UIImage? {
        let key = "face:\(face.id)@\(pixels)" as NSString
        if let image = cache.object(forKey: key) { return image }
        let image = await awaiting { done in self.files.face(face: face, side: Int32(pixels), onResult: done) }
        if let image { cache.setObject(image, forKey: key, cost: pixels * pixels * 4) }
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

    /// The original of a server-only photo (or one downloaded and kept) at `maxSide` pixels, for zooming into: the same
    /// file as `viewer`'s, decoded larger, and not kept in memory (a 48 MP image would push everything else out).
    func fullOriginal(_ item: MediaItem, maxSide: Int) async -> UIImage? {
        await awaiting { done in self.files.viewer(item: item, maxSide: Int32(maxSide), onResult: done) }
    }

    /// Whether `item`'s original is on this phone already: showing it downloads nothing.
    func hasOriginal(_ item: MediaItem) async -> Bool {
        await awaiting { done in self.files.hasOriginal(item: item) { done($0.boolValue) } }
    }

    /// How far `item`'s original is downloading now (0..1), or nil when it isn't; called on the main thread.
    func observeOriginalProgress(_ item: MediaItem, onChange: @escaping (Double?) -> Void) -> ObservationHandle {
        files.observeOriginalProgress(item: item) { onChange($0.map { Double($0.floatValue) }) }
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

// MARK: The viewer's original details and Mark private

extension ServerImages {
    /// The file of `item`'s original on this phone (kept, or downloaded into the cache by the viewer); nil while it
    /// isn't here.
    func originalPath(_ item: MediaItem) async -> String? {
        await awaiting { done in self.files.originalPath(item: item, onResult: done) }
    }

    /// Marks `item` private on the server, or no longer private; the library follows once the server's list is synced.
    func setPrivate(_ item: MediaItem, isPrivate: Bool) async -> Bool {
        await awaiting { done in
            self.files.setPrivate(item: item, isPrivate: isPrivate, onDone: { done($0.boolValue) })
        }
    }

    /// Marks many photos private at once (search's picked photos); false when the server couldn't be reached.
    func markPrivate(_ items: [MediaItem]) async -> Bool {
        await awaiting { done in
            self.files.markPrivate(items: items, onDone: { done($0.boolValue) })
        }
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
