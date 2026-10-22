#if DEBUG

import UIKit
import SharedLogic

/// The UI lab's pictures instead of the photo library's: a flat landscape (sun and hill) in a color picked by the
/// item's id, at the item's aspect ratio. The same as Android's PlaceholderThumbnails, colors included.
enum LabPictures {
    private static let tones: [UInt32] = [
        0x8FB3A6, 0xE3CDB4, 0xC9D6CF, 0xB9A58E, 0x6F8F9B, 0xD8B7A6,
        0xA7B98F, 0x9AA7C4, 0x5E8A7C, 0xE8D9B8, 0x7E9C8C, 0xCDB8A0,
    ]
    private static let cache = NSCache<NSString, UIImage>()

    static func image(_ item: MediaItem) -> UIImage {
        if let image = cache.object(forKey: item.id as NSString) { return image }
        let w = 320
        let h = item.width > 0 && item.height > 0 ? min(max(w * Int(item.height) / Int(item.width), 80), 960) : w
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format).image { context in
            let (w, h) = (CGFloat(w), CGFloat(h))
            color(tones[javaHash(item.id).floorMod(tones.count)]).setFill()
            context.fill(CGRect(x: 0, y: 0, width: w, height: h))
            UIColor.white.withAlphaComponent(0.45).setFill()
            let r = min(w, h) * 0.11
            context.cgContext.fillEllipse(in: CGRect(x: w * 0.75 - r, y: h * 0.27 - r, width: 2 * r, height: 2 * r))
            UIColor.black.withAlphaComponent(0.12).setFill()
            context.cgContext.fillEllipse(in: CGRect(x: -0.2 * w, y: 0.62 * h, width: 1.4 * w, height: 0.63 * h))
        }
        cache.setObject(image, forKey: item.id as NSString)
        return image
    }

    private static func color(_ rgb: UInt32) -> UIColor {
        UIColor(red: CGFloat(rgb >> 16 & 0xFF) / 255, green: CGFloat(rgb >> 8 & 0xFF) / 255, blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }

    /// Kotlin's String.hashCode, so a photo has the same color as in Android's lab.
    private static func javaHash(_ s: String) -> Int32 {
        s.utf16.reduce(Int32(0)) { $0 &* 31 &+ Int32($1) }
    }
}

private extension Int32 {
    func floorMod(_ n: Int) -> Int { ((Int(self) % n) + n) % n }
}

#endif
