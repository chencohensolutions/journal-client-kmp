import CoreImage.CIFilterBuiltins
import Security
import SwiftUI
import UIKit

// Swap with a friend, the iOS pieces, as on Android's SwapPlatform.kt: the QR code, the passphrase in iCloud Keychain,
// the recovery sheet (docs/plans/swap-from-phones.md, "Keeping the passphrase"). Never on the journal server or the
// friend's: a copy guards against losing those.

/// `text` as a QR code, black on white with a quiet zone, sharp at any size.
func qrImage(_ text: String) -> UIImage? {
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(text.utf8)
    filter.correctionLevel = "M"
    guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 12, y: 12)),
          let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
    return UIImage(cgImage: cg)
}

/// The copy passphrase as a synchronizable Keychain item: iCloud Keychain brings it to a new iPhone on the same Apple
/// Account. Shown again only behind Face ID (`PhoneLocked`).
enum PassphraseKeychain {
    private static let service = "com.chencohensolution.journal.copy-passphrase"
    private static let account = "copy passphrase"

    private static var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: kCFBooleanTrue!,
        ]
    }

    /// Saves (or replaces) the passphrase; true when the Keychain took it.
    @discardableResult
    static func save(_ passphrase: String, serverId: String?) -> Bool {
        SecItemDelete(query as CFDictionary)
        var item = query
        item[kSecValueData as String] = Data(passphrase.utf8)
        // Synchronizable items can't be ThisDeviceOnly; after the first unlock, as iCloud Keychain needs.
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        item[kSecAttrLabel as String] = String(localized: "Journal copy passphrase (\(String((serverId ?? "journal").prefix(7))))")
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }

    static func read() -> String? {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static var kept: Bool { read() != nil }
}

/// What the recovery sheet says: the words, at which friend's, from which server.
struct RecoverySheet {
    let words: [String]
    let friend: String
    let serverId: String?

    /// One A4 page: the words numbered and as a QR code, the server id, the friend's name and how to restore. Written to
    /// the temporary folder, for the share sheet (to print, or keep in Files).
    func write() -> URL? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(String(localized: "Journal recovery sheet") + ".pdf")
        let page = CGRect(x: 0, y: 0, width: 595, height: 842)
        let margin: CGFloat = 56
        let renderer = UIGraphicsPDFRenderer(bounds: page)
        let rtl = Locale.current.language.characterDirection == .rightToLeft
        let style = NSMutableParagraphStyle()
        style.alignment = rtl ? .right : .left
        func attrs(_ font: UIFont) -> [NSAttributedString.Key: Any] {
            [.font: font, .foregroundColor: UIColor.black, .paragraphStyle: style]
        }
        do {
            try renderer.writePDF(to: url) { context in
                context.beginPage()
                var y = margin
                func paragraph(_ text: String, _ font: UIFont, after: CGFloat = 10, x: CGFloat = 56, w: CGFloat = 483) {
                    let s = NSAttributedString(string: text, attributes: attrs(font))
                    let rect = s.boundingRect(with: CGSize(width: w, height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin], context: nil)
                    s.draw(with: CGRect(x: x, y: y, width: w, height: ceil(rect.height)), options: [.usesLineFragmentOrigin], context: nil)
                    y += ceil(rect.height) + after
                }
                paragraph(String(localized: "Journal: recovery sheet"), .boldSystemFont(ofSize: 22), after: 6)
                paragraph(String(localized: "Made on \(Date.now.formatted(date: .long, time: .omitted))"), .systemFont(ofSize: 11), after: 18)
                paragraph(String(localized: "These six words open the encrypted copy of your journal kept on \(friend)’s server. Nobody can open it without them, not even you. Keep this page somewhere safe, away from your PC."), .systemFont(ofSize: 11), after: 18)
                // The words (English, left to right), with their QR code beside them.
                let top = y
                let qrSize: CGFloat = 150
                let wordStyle = NSMutableParagraphStyle()
                wordStyle.alignment = .left
                for (i, word) in words.enumerated() {
                    let s = NSAttributedString(string: "\(i + 1).  \(word)", attributes: [.font: UIFont.monospacedSystemFont(ofSize: 18, weight: .regular), .paragraphStyle: wordStyle])
                    s.draw(at: CGPoint(x: rtl ? margin + qrSize + 24 : margin, y: y))
                    y += 30
                }
                qrImage(words.joined(separator: " "))?.draw(in: CGRect(x: rtl ? margin : page.width - margin - qrSize, y: top, width: qrSize, height: qrSize))
                y = max(y, top + qrSize) + 18
                paragraph(String(localized: "Kept at"), .boldSystemFont(ofSize: 11), after: 2)
                paragraph(friend, .systemFont(ofSize: 11))
                paragraph(String(localized: "Your server’s ID"), .boldSystemFont(ofSize: 11), after: 2)
                paragraph(serverId ?? String(localized: "Not known"), .systemFont(ofSize: 11), after: 18)
                paragraph(String(localized: "To restore"), .boldSystemFont(ofSize: 11), after: 4)
                paragraph(String(localized: "1. Install journal-server on a new PC.\n2. Ask \(friend) to let your new server take over the copy, then join it.\n3. Run: journal-server restore <user> --from \(friend) --to <an empty folder> --passphrase\n   and type the six words."), .systemFont(ofSize: 11), after: 18)
                paragraph(String(localized: "Anyone with these words and the copy can open your photos."), .boldSystemFont(ofSize: 11))
            }
            return url
        } catch {
            return nil
        }
    }
}

/// Copies `text`; a passphrase stays out of other devices' Universal Clipboard and expires after a few minutes.
func copyToPasteboard(_ text: String, sensitive: Bool) {
    if sensitive {
        UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: text]], options: [.localOnly: true, .expirationDate: Date.now.addingTimeInterval(180)])
    } else {
        UIPasteboard.general.string = text
    }
}

/// The share sheet, presented from the frontmost view controller.
func presentShareSheet(_ items: [Any]) {
    guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }),
          var top = scene.keyWindow?.rootViewController else { return }
    while let presented = top.presentedViewController { top = presented }
    let sheet = UIActivityViewController(activityItems: items, applicationActivities: nil)
    sheet.popoverPresentationController?.sourceView = top.view
    top.present(sheet, animated: true)
}
