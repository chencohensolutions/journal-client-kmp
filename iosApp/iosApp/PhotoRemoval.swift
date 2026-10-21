import Photos
import SharedLogic

/// The one place the app may take a photo out of the phone's library (allowed by the owner, as on Android's
/// PhotoRemoval.kt): "Delete from this phone" in a photo's details. It never deletes anything without asking: iOS shows
/// its own prompt, which the user confirms, and the photo goes to Recently Deleted (kept there 30 days). The caller must
/// first have the server confirm it holds the photo (`IosServerFiles.prepareRemoval`, `ServerLibrary.confirms`) and keep
/// its preview. See PhotoSafetyTest.
enum PhotoRemoval {
    /// Asks iOS to delete `item` from the library; true when the user agreed and it is gone.
    static func moveToRecentlyDeleted(_ item: MediaItem) async -> Bool {
        guard item.isOnDevice, item.backup.isBackedUp, item.sha256 != nil,
              let asset = PhotoLibraryImages.shared.asset(item.id) else { return false }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetChangeRequest.deleteAssets([asset] as NSArray)
            }
            return true
        } catch {
            // The user said no, or the photo was gone already.
            return false
        }
    }
}
