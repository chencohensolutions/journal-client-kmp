import Photos
import UIKit
import SharedLogic

/// The viewer's Share: the original file itself goes to the system share sheet, byte for byte (EXIF, HEIC and the
/// video as recorded), never a re-encoded image. A library item's original resource is read from PhotoKit (read only);
/// one only on the server is its kept or cached original, downloaded first the way the viewer does when it isn't here.
/// The file is copied under its real name into a temp folder that keeps only the latest share. With "Share JPEG XL as
/// JPEG" (Settings, on by default) a JPEG XL the server packed from a JPEG goes as that JPEG, byte for byte, as few apps
/// open JPEG XL; the server makes it again (no libjxl here). One that wasn't packed from a JPEG goes as it is.
enum ShareOriginal {
    /// The file to share, or nil when it couldn't be had.
    static func file(for item: MediaItem) async -> URL? {
        guard let folder = freshFolder() else { return nil }
        if IosJournal.shared.shareJpegXlAsJpeg && item.isJpegXl,
           let path = await awaiting({ done in ServerImages.shared.files.fetchJpegOf(item: item, onResult: done) }) {
            let name = safeName(((item.fileName ?? "photo") as NSString).deletingPathExtension + ".jpg")
            return await copy(URL(fileURLWithPath: path), to: folder.appendingPathComponent(name))
        }
        if item.isOnDevice { return await libraryOriginal(item, into: folder) }
        guard let path = await awaiting({ done in ServerImages.shared.files.fetchOriginal(item: item, onResult: done) }) else { return nil }
        let source = URL(fileURLWithPath: path)
        // The cache calls it "original.<ext>": it goes out under the photo's own name.
        var name = item.fileName.map(safeName) ?? source.lastPathComponent
        if (name as NSString).pathExtension.isEmpty, !source.pathExtension.isEmpty { name += "." + source.pathExtension }
        return await copy(source, to: folder.appendingPathComponent(name))
    }

    private static func copy(_ source: URL, to target: URL) async -> URL? {
        await Task.detached(priority: .userInitiated) {
            (try? FileManager.default.copyItem(at: source, to: target)) != nil ? target : nil
        }.value
    }

    /// Opens the share sheet for `url` over whatever is on screen. Saving to the photo library isn't offered: the app
    /// never writes there.
    @MainActor
    static func present(_ url: URL) {
        let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        sheet.excludedActivityTypes = [.saveToCameraRoll]
        guard var top = UIApplication.shared.connectedScenes
            .compactMap({ ($0 as? UIWindowScene)?.keyWindow })
            .first?.rootViewController else { return }
        while let presented = top.presentedViewController { top = presented }
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = top.view
            popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.maxY - 60, width: 1, height: 1)
        }
        top.present(sheet, animated: true)
    }

    /// The library item's original resource (the photo or video as taken, not an edit), written out by PhotoKit; an
    /// iCloud-only one is downloaded by it.
    private static func libraryOriginal(_ item: MediaItem, into folder: URL) async -> URL? {
        guard let asset = PhotoLibraryImages.shared.asset(item.id) else { return nil }
        let resources = PHAssetResource.assetResources(for: asset)
        let wanted: [PHAssetResourceType] = item.isVideo ? [.video, .fullSizeVideo] : [.photo, .fullSizePhoto]
        guard let resource = wanted.lazy.compactMap({ type in resources.first { $0.type == type } }).first ?? resources.first
        else { return nil }
        let target = folder.appendingPathComponent(safeName(resource.originalFilename))
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true
        return await withCheckedContinuation { continuation in
            PHAssetResourceManager.default().writeData(for: resource, toFile: target, options: options) { error in
                continuation.resume(returning: error == nil ? target : nil)
            }
        }
    }

    /// `tmp/Share`, emptied: only the latest share is kept.
    private static func freshFolder() -> URL? {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("Share", isDirectory: true)
        try? FileManager.default.removeItem(at: folder)
        return (try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)) != nil ? folder : nil
    }

    /// A file name without path parts.
    private static func safeName(_ name: String) -> String {
        let cleaned = name.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_")
        return cleaned.isEmpty || cleaned == "." || cleaned == ".." ? "original" : cleaned
    }
}

private extension MediaItem {
    /// A JPEG XL photo, by its type or name.
    var isJpegXl: Bool {
        !isVideo && (mimeType?.lowercased() == "image/jxl" || fileName?.lowercased().hasSuffix(".jxl") == true)
    }
}
