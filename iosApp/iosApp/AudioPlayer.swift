import AVFoundation
import SwiftUI
import SharedLogic

// Sound recordings play here: Moments and a recording's page. See docs/screens/moments.md.

/// What plays: one sound recording at a time, at `positionMillis`, and whether it is playing or paused there.
struct AudioPlayback: Equatable {
    let sha256: String
    let positionMillis: Int64
    let playing: Bool
    var speed: Float = 1
}

/// Plays the server's sound recordings: one at a time, never by itself, only while the app is in front (the app pauses
/// it when it leaves the screen: no background audio, no lock-screen controls). The original streams from
/// `/media/{sha256}` with Range through `ServerVideoLoader`, so a 3-hour recording starts at once and seeks anywhere.
/// Short ones (calls, voice messages) are kept in the video cache once played; long ones aren't, as one could push out
/// every kept video. A feed video playing aloud falls silent while it plays (`FeedPlayback`). Positions are remembered
/// while the app runs. As Android's AudioPlayer.kt.
@MainActor
final class AudioPlayer: ObservableObject {
    static let shared = AudioPlayer()

    @Published private(set) var playback: AudioPlayback?
    /// Set when a recording couldn't be played (AVPlayer reads neither Ogg nor Opus); the screen says so once.
    @Published var failed = false

    /// Sent before a recording starts, so a feed video playing aloud mutes itself.
    nonisolated static let willPlay = Notification.Name("AudioPlayer.willPlay")

    private var player: AVPlayer?
    private var current: SoundRecording?
    private var ticker: Any?
    private var observations: [NSKeyValueObservation] = []
    private var ended: NSObjectProtocol?
    private var positions: [String: Int64] = [:]
    private var speed: Float = 1

    /// Whether a recording is loaded (playing or paused): the audio session is its while it is.
    var isActive: Bool { player != nil }

    /// Where `recording` is, while it is the one playing or paused.
    func of(_ recording: SoundRecording) -> AudioPlayback? {
        playback?.sha256 == recording.sha256 ? playback : nil
    }

    func toggle(_ recording: SoundRecording) {
        if of(recording)?.playing == true { pause() } else { play(recording) }
    }

    /// Plays `recording`, from `fromMillis` or where it was left; the one playing before stops.
    func play(_ recording: SoundRecording, from fromMillis: Int64? = nil) {
        if let player, current?.sha256 == recording.sha256 {
            if let fromMillis { player.seek(to: time(fromMillis), toleranceBefore: .zero, toleranceAfter: .zero) }
            start(player)
            return
        }
        stop()
        let keep = recording.durationMillis > 0 && recording.durationMillis <= cachedMillis
        guard let asset = ServerVideos.asset(sha256: recording.sha256, fileName: nil, keep: keep) else {
            failed = true
            return
        }
        current = recording
        let start = fromMillis ?? positions[recording.sha256] ?? 0
        let item = AVPlayerItem(asset: asset)
        // Speech at 1.5× and 2× keeps its pitch.
        item.audioTimePitchAlgorithm = .timeDomain
        let player = AVPlayer(playerItem: item)
        self.player = player
        observations = [
            item.observe(\.status) { [weak self] item, _ in
                guard item.status == .failed else { return }
                Task { @MainActor in
                    self?.failed = true
                    self?.stop()
                }
            },
            player.observe(\.timeControlStatus) { [weak self] _, _ in
                Task { @MainActor in self?.publish() }
            },
        ]
        let sha = recording.sha256
        ended = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, let player = self.player else { return }
                self.positions[sha] = nil
                player.pause()
                player.seek(to: .zero)
                self.publish()
            }
        }
        ticker = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 4), queue: .main) { [weak self] _ in
            Task { @MainActor in self?.publish() }
        }
        if start > 0 { player.seek(to: time(start), toleranceBefore: .zero, toleranceAfter: .zero) }
        playback = AudioPlayback(sha256: recording.sha256, positionMillis: start, playing: true, speed: speed)
        self.start(player)
    }

    func pause() {
        player?.pause()
        publish()
    }

    /// Goes to `millis` into `recording`: playing it from there if it isn't the one playing.
    func seek(_ recording: SoundRecording, to millis: Int64) {
        let length = recording.durationMillis > 0 ? recording.durationMillis : Int64.max
        let at = min(max(millis, 0), length)
        guard let player, current?.sha256 == recording.sha256 else { return play(recording, from: at) }
        player.seek(to: time(at), toleranceBefore: .zero, toleranceAfter: .zero)
        publish(at: at)
    }

    func setSpeed(_ speed: Float) {
        self.speed = speed
        if let player, player.timeControlStatus != .paused { player.rate = speed }
        publish()
    }

    /// Stops playing and lets go of the player; where it was is remembered.
    func stop() {
        if let player {
            if let current { positions[current.sha256] = millis(player.currentTime()) }
            player.pause()
            ticker.map(player.removeTimeObserver)
        }
        ticker = nil
        observations = []
        ended.map(NotificationCenter.default.removeObserver)
        ended = nil
        player = nil
        current = nil
        playback = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }

    private func start(_ player: AVPlayer) {
        NotificationCenter.default.post(name: Self.willPlay, object: nil)
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .spokenAudio)
        try? session.setActive(true)
        player.playImmediately(atRate: speed)
        publish()
    }

    private func publish(at millis: Int64? = nil) {
        guard let current, let player else { return }
        let position = millis ?? self.millis(player.currentTime())
        let playing = player.timeControlStatus != .paused
        let next = AudioPlayback(sha256: current.sha256, positionMillis: position, playing: playing, speed: speed)
        if next != playback { playback = next }
    }

    private func time(_ millis: Int64) -> CMTime { CMTime(value: millis, timescale: 1000) }

    private func millis(_ time: CMTime) -> Int64 { time.isNumeric ? Int64(time.seconds * 1000) : 0 }
}

/// Recordings up to this long are kept in the video cache once played.
private let cachedMillis: Int64 = 10 * 60_000
