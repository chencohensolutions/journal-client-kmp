import Photos
import SharedLogic

/// The one place the app may take a photo out of the phone's library (allowed by the owner, as on Android's
/// PhotoRemoval.kt): "Free up space" in Settings. It never deletes anything without asking: iOS shows its own prompt,
/// which the user confirms, and the photos go to Recently Deleted (kept there 30 days). The caller must first have the server confirm it holds each photo by sha256 ("Free up space"
/// hashes both copies again). See PhotoSafetyTest.
enum PhotoRemoval {
    /// "Free up space": asks iOS to delete all of `items` in one request (one prompt); true when the user agreed.
    /// `items` must come from `FreeUpController.waitingItems`: each was hashed on this phone and on the server against
    /// the sha256 backup uploaded, and is unchanged since (size and date; a pair leaves together or not at all).
    static func moveToRecentlyDeleted(checked items: [MediaItem]) async -> Bool {
        guard !items.isEmpty, items.allSatisfy(\.isOnDevice) else { return false }
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: items.map(\.id), options: nil)
        // One gone already: its pair would be split, so nothing goes; the next run checks them again.
        guard assets.count == items.count else { return false }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetChangeRequest.deleteAssets(assets)
            }
            return true
        } catch {
            // The user said no.
            return false
        }
    }
}
