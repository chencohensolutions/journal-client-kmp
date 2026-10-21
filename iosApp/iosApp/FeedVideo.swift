import AVFoundation
import SwiftUI
import SharedLogic

// Videos in the Moments feed play by themselves, muted, while they are the one most in view. See docs/screens/moments.md.

/// Which video of the feed plays, and its sound: one setting for the whole feed, so unmuting carries to the next. Also
/// the photo each swipe set shows, so a set that scrolls away and back opens where it was.
final class FeedPlayback: ObservableObject {
    @Published private(set) var playingId: String?
    @Published private(set) var muted = true

    /// Off while another tab, the viewer or another screen shows, or the app is in the background.
    var enabled = false {
        didSet { if enabled != oldValue { choose() } }
    }

    /// The part of the screen not covered by the top bar and the bottom controls, in global coordinates.
    var viewport: ClosedRange<CGFloat> = 0...0

    /// The blocks on screen that are or can turn to a video, by their key: the video shown (nil for a photo of a
    /// set) and where the block is.
    private var frames: [String: (itemId: String?, frame: CGRect)] = [:]
    private var candidate: String?
    private var pending: DispatchWorkItem?
    /// The photo each swipe set shows, by the set's key.
    private(set) var pages: [String: String] = [:]
    private var interruption: NSObjectProtocol?

    init() {
        // With sound, a video gives way when another app takes the audio back.
        interruption = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            if type == .began, self?.muted == false { self?.toggleMute() }
        }
    }

    deinit {
        interruption.map(NotificationCenter.default.removeObserver)
    }

    func toggleMute() {
        muted.toggle()
        updateAudioSession()
    }

    /// A block is at `frame` on screen, showing the video `itemId` or a photo (nil).
    func report(block key: String, itemId: String?, frame: CGRect) {
        frames[key] = (itemId, frame)
        choose()
    }

    /// A set turned to `itemId`: it plays when it is a video (`isVideo`).
    func showPage(_ itemId: String, isVideo: Bool, of key: String) {
        pages[key] = itemId
        guard let frame = frames[key]?.frame else { return }
        report(block: key, itemId: isVideo ? itemId : nil, frame: frame)
    }

    func remove(block key: String) {
        guard frames.removeValue(forKey: key) != nil else { return }
        choose()
    }

    /// Of the videos at least 60% in view (of their height, or of the viewport for one taller than it), the one
    /// closest to its middle. It stops at once and starts only once the scroll has settled on it.
    private func choose() {
        let next = enabled ? mostVisible() : nil
        guard next != candidate else { return }
        candidate = next
        pending?.cancel()
        pending = nil
        guard let next else {
            setPlaying(nil)
            return
        }
        let work = DispatchWorkItem { [weak self] in self?.setPlaying(next) }
        pending = work
        DispatchQueue.main.asyncAfter(deadline: .now() + videoSettleSeconds, execute: work)
    }

    private func mostVisible() -> String? {
        let top = viewport.lowerBound, bottom = viewport.upperBound
        let middle = (top + bottom) / 2
        return frames.values
            .filter { entry in
                guard entry.itemId != nil else { return false }
                let inView = min(entry.frame.maxY, bottom) - max(entry.frame.minY, top)
                return inView >= minShown * min(entry.frame.height, bottom - top)
            }
            .min { abs($0.frame.midY - middle) < abs($1.frame.midY - middle) }?
            .itemId
    }

    private func setPlaying(_ id: String?) {
        guard id != playingId else { return }
        playingId = id
        updateAudioSession()
    }

    /// Muted, the feed mixes with music from other apps; with sound it takes the audio while a video plays.
    private func updateAudioSession() {
        let session = AVAudioSession.sharedInstance()
        if playingId != nil && !muted {
            try? session.setCategory(.playback, mode: .moviePlayback)
            try? session.setActive(true)
        } else if playingId != nil {
            try? session.setCategory(.ambient, options: [.mixWithOthers])
        } else {
            try? session.setCategory(.soloAmbient)
            try? session.setActive(false, options: [.notifyOthersOnDeactivation])
        }
    }
}

private let minShown: CGFloat = 0.6

/// A video starts once it has been the one to play this long, so a fling past several doesn't open each of them.
private let videoSettleSeconds = 0.15

/// `item` playing in a loop, muted or not. Its caller keeps the poster on top until `onFirstFrame`, and for good after
/// `onFailed`.
struct InlineVideo: UIViewRepresentable {
    let item: MediaItem
    let muted: Bool
    let onFirstFrame: () -> Void
    let onFailed: () -> Void

    func makeUIView(context: Context) -> PlayerView {
        let view = PlayerView()
        view.playerLayer.videoGravity = .resizeAspectFill
        context.coordinator.start(item: item, in: view, muted: muted, onFirstFrame: onFirstFrame, onFailed: onFailed)
        return view
    }

    func updateUIView(_ view: PlayerView, context: Context) {
        view.playerLayer.player?.isMuted = muted
    }

    static func dismantleUIView(_ view: PlayerView, coordinator: Coordinator) {
        coordinator.stop()
        view.playerLayer.player = nil
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        private var player: AVQueuePlayer?
        private var looper: AVPlayerLooper?
        private var observations: [NSKeyValueObservation] = []
        private var task: Task<Void, Never>?

        func start(item: MediaItem, in view: PlayerView, muted: Bool, onFirstFrame: @escaping () -> Void, onFailed: @escaping () -> Void) {
            task = Task { @MainActor [weak self, weak view] in
                guard let asset = await PhotoLibraryImages.shared.videoAsset(item: item) else {
                    onFailed()
                    return
                }
                guard let self, let view, !Task.isCancelled else { return }
                let player = AVQueuePlayer()
                player.isMuted = muted
                let looper = AVPlayerLooper(player: player, templateItem: AVPlayerItem(asset: asset))
                observations = [
                    view.playerLayer.observe(\.isReadyForDisplay, options: [.new]) { layer, _ in
                        if layer.isReadyForDisplay { DispatchQueue.main.async(execute: onFirstFrame) }
                    },
                    looper.observe(\.status, options: [.new]) { looper, _ in
                        if looper.status == .failed { DispatchQueue.main.async(execute: onFailed) }
                    },
                ]
                view.playerLayer.player = player
                self.player = player
                self.looper = looper
                player.play()
            }
        }

        func stop() {
            task?.cancel()
            observations.forEach { $0.invalidate() }
            observations = []
            looper?.disableLooping()
            player?.pause()
            player = nil
            looper = nil
        }
    }
}

final class PlayerView: UIView {
    override static var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

/// The sound button over a playing video.
struct MuteButton: View {
    let muted: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            Image(systemName: muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(LibraryColors.photoText)
                .frame(width: 32, height: 32)
                .background(LibraryColors.badgeScrim, in: Circle())
                .frame(width: 44, height: 44)
        }
        .accessibilityLabel(muted ? "Turn sound on" : "Turn sound off")
    }
}
